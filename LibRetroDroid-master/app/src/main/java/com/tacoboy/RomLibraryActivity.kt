package com.tacoboy

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.libretrodroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ROM picker / library hub. Each GameSystem remembers its own SAF folder
 * (TacoBoyPrefs), so switching systems via the tab row is instant instead
 * of re-picking a folder — the whole point being that this screen can
 * stand in as the app's "home" without needing a separate launcher menu.
 * Returns the picked ROM's Uri as an activity result rather than loading
 * it directly — TacoBoyActivity still owns the single GLRetroView.
 */
class RomLibraryActivity : AppCompatActivity() {

    enum class ViewMode { LIST, GRID }

    /** Persisted via TacoBoyPrefs.getLibrarySortMode/setLibrarySortMode, same pattern as ViewMode. */
    enum class SortMode { NAME, RECENTLY_PLAYED, MOST_PLAYED }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var viewModeToggle: TextView
    private lateinit var sortButton: TextView
    private lateinit var searchButton: View
    private lateinit var searchRow: View
    private lateinit var searchInput: EditText
    private lateinit var systemPickerButton: TextView
    private lateinit var biosButton: TextView

    private lateinit var currentSystem: GameSystem
    private var folderUri: Uri? = null
    private var roms: List<RomLibrary.RomEntry> = emptyList()
    private var viewMode = ViewMode.LIST
    private var sortMode = SortMode.NAME
    private var searchQuery: String = ""
    private var pendingArtTarget: RomLibrary.RomEntry? = null
    /** Non-null only while the BIOS dialog is showing. Held so an import launched from inside
     *  that dialog can repopulate the list in place when it returns, instead of the user
     *  landing back on a stale list that doesn't show the file they just added. */
    private var biosListContainer: LinearLayout? = null

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                TacoBoyPrefs.setRomFolderUri(this, currentSystem, uri.toString())
                folderUri = uri
                loadRoms(forceRescan = true)
            }
        }

    private val artPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val rom = pendingArtTarget
            pendingArtTarget = null
            if (uri == null || rom == null) return@registerForActivityResult

            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { BoxArtCache.setCustomArt(this@RomLibraryActivity, rom.displayName, uri) }
                if (ok) {
                    recyclerView.adapter?.notifyDataSetChanged()
                } else {
                    Toast.makeText(this@RomLibraryActivity, R.string.game_menu_custom_art_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val biosPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val fileName = DocumentFile.fromSingleUri(this, uri)?.name ?: "bios.bin"

            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { BiosManager.importBios(this@RomLibraryActivity, uri, fileName) }
                Toast.makeText(
                    this@RomLibraryActivity,
                    if (ok) R.string.library_bios_imported else R.string.library_bios_import_failed,
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) refreshBiosUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_rom_library)

        viewMode = TacoBoyPrefs.getLibraryViewMode(this)
        sortMode = TacoBoyPrefs.getLibrarySortMode(this)

        recyclerView = findViewById(R.id.rom_recycler_view)
        emptyState = findViewById(R.id.empty_state)
        loadingIndicator = findViewById(R.id.loading_indicator)
        viewModeToggle = findViewById(R.id.view_mode_toggle)
        sortButton = findViewById(R.id.sort_button)
        searchButton = findViewById(R.id.search_button)
        searchRow = findViewById(R.id.search_row)
        searchInput = findViewById(R.id.search_input)
        systemPickerButton = findViewById(R.id.system_picker_button)
        systemPickerButton.setOnClickListener { showSystemPicker() }
        biosButton = findViewById(R.id.import_bios_button)

        BoundaryController(
            this,
            findViewById(R.id.boundary_guideline),
            findViewById(R.id.occlusion_zone),
            findViewById(R.id.boundary_handle),
            findViewById(R.id.boundary_hint)
        )

        val refreshButton = findViewById<View>(R.id.refresh_button)
        val changeFolderButton = findViewById<View>(R.id.change_folder_button)
        val downloadBoxArtButton = findViewById<View>(R.id.download_boxart_button)
        val settingsButton = findViewById<View>(R.id.settings_button)
        val resumeGameButton = findViewById<View>(R.id.resume_game_button)

        viewModeToggle.setOnClickListener { toggleViewMode() }
        sortButton.setOnClickListener { cycleSortMode() }
        searchButton.setOnClickListener { toggleSearch() }
        findViewById<View>(R.id.search_clear).setOnClickListener { closeSearch() }
        refreshButton.setOnClickListener { loadRoms(forceRescan = true) }
        changeFolderButton.setOnClickListener { folderPickerLauncher.launch(null) }
        downloadBoxArtButton.setOnClickListener { onDownloadBoxArtClicked() }
        biosButton.setOnClickListener { showBiosDialog() }
        settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // No result set — same as pressing back, which TacoBoyActivity's
        // libraryLauncher already treats as "nothing picked, leave the running
        // game alone" (see EXTRA_RESULT_ROM_URI being absent from the result).
        val runningGameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)
        if (runningGameTitle != null) {
            resumeGameButton.visibility = View.VISIBLE
            resumeGameButton.setOnClickListener { finish() }
            TooltipCompat.setTooltipText(resumeGameButton, getString(R.string.library_tooltip_resume_game, runningGameTitle))
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                renderRoms()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        TooltipCompat.setTooltipText(refreshButton, getString(R.string.library_tooltip_refresh))
        TooltipCompat.setTooltipText(changeFolderButton, getString(R.string.library_tooltip_change_folder))
        TooltipCompat.setTooltipText(downloadBoxArtButton, getString(R.string.library_tooltip_get_art))
        TooltipCompat.setTooltipText(biosButton, getString(R.string.library_tooltip_import_bios))
        TooltipCompat.setTooltipText(settingsButton, getString(R.string.library_tooltip_settings))
        TooltipCompat.setTooltipText(searchButton, getString(R.string.library_tooltip_search))

        updateViewModeLabel()
        updateSortTooltip()
        selectSystem(TacoBoyPrefs.getLastSystem(this) ?: GameSystem.GBA)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Without this, a Pocket Taco B press (see PocketTacoDetector.isSpuriousBack)
     *  would silently back out of the library — not just a game-screen problem. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PocketTacoDetector.isSpuriousBack(event.keyCode, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun selectSystem(system: GameSystem) {
        // A search on the previous tab wouldn't mean anything against this system's titles.
        closeSearch()

        currentSystem = system
        TacoBoyPrefs.setLastSystem(this, system)
        systemPickerButton.text = getString(R.string.system_picker_button, getString(system.nameRes))
        biosButton.visibility = if (system.needsBios) View.VISIBLE else View.GONE
        if (system.needsBios) updateBiosButtonLabel()

        folderUri = TacoBoyPrefs.getRomFolderUri(this, system)?.let(Uri::parse)
        val folder = folderUri
        if (folder == null) {
            roms = emptyList()
            loadingIndicator.visibility = View.GONE
            emptyState.text = getString(R.string.rom_picker_no_folder, getString(system.nameRes))
            renderRoms()
        } else {
            loadRoms(forceRescan = false)
        }
    }

    /**
     * The BIOS list lives in a dialog rather than stacked under the system tabs. Inline, it was
     * pure vertical cost on exactly one system: PS1 lost several rows of its ROM grid to it, on
     * a screen a clamp-on Pocket Taco has already shortened (see BoundaryController), and it
     * grew with every BIOS imported. The toolbar button keeps the at-a-glance part -- its label
     * carries the active BIOS's region flag -- so nothing has to be opened just to check which
     * region is live.
     *
     * Tapping a row makes that file active (TacoBoyPrefs.setActiveBiosFileName); the actual
     * staging into BiosManager.activeBiosDirectory still happens lazily at the next game load
     * (see TacoBoyActivity.setupRetroView), so this only persists the choice and re-renders.
     */
    private fun showBiosDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        biosListContainer = container
        populateBiosList()

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.library_bios_dialog_title, currentSystem.shortLabel))
            .setView(ScrollView(this).apply { addView(container) })
            .setNeutralButton(R.string.library_bios_import_action, null)
            .setPositiveButton(R.string.library_bios_close, null)
            .setOnDismissListener { biosListContainer = null }
            .show()
        // Same reasoning as every other dialog here: keep it above the clamp boundary.
        dialog.window?.setGravity(Gravity.TOP)
        // Wired after show() rather than through the Builder so importing doesn't dismiss the
        // dialog -- the picked file lands back in a list that's still open, making "import,
        // then pick which one is active" one uninterrupted flow.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            biosPickerLauncher.launch("*/*")
        }
    }

    /** Both halves of the BIOS UI at once: the toolbar button's status label (always) and the
     *  dialog's list (only while it's open). Called after anything that changes what's on disk
     *  or which file is chosen. */
    private fun refreshBiosUi() {
        if (currentSystem.needsBios) updateBiosButtonLabel()
        populateBiosList()
    }

    /** The icon is whatever this system's detection had to say about the active file -- a
     *  region flag for PS1, a plain tick for Lynx's single valid boot ROM. Read back out of
     *  the detected list rather than re-derived, since detection is now per-system. */
    private fun updateBiosButtonLabel() {
        val activeFileNames = BiosManager.activeFileNames(this, currentSystem)
        // Sega CD can have one BIOS per region in use at once, so every active flag is shown.
        val icon = BiosManager.listDetectedBios(this, currentSystem)
            .filter { it.fileName in activeFileNames }
            .joinToString("") { it.icon }
            .ifEmpty { null }
        biosButton.text = if (icon != null) {
            getString(R.string.library_bios_label_active, icon)
        } else {
            getString(
                if (currentSystem.biosOptional) R.string.library_bios_label_none_optional
                else R.string.library_bios_label_none
            )
        }
    }

    private fun populateBiosList() {
        val container = biosListContainer ?: return
        container.removeAllViews()

        val detected = BiosManager.listDetectedBios(this, currentSystem)
        if (detected.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(
                    if (currentSystem.biosOptional) R.string.library_bios_status_none_optional
                    else R.string.library_bios_status_none
                )
                textSize = 14f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        val activeFileNames = BiosManager.activeFileNames(this, currentSystem)
        detected.forEach { bios ->
            container.addView(biosRow(bios, bios.fileName in activeFileNames))
        }
        if (detected.size > 1 && !currentSystem.biosOptional) {
            container.addView(TextView(this).apply {
                text = getString(
                    if (currentSystem == GameSystem.SEGA_CD) R.string.library_bios_multiple_hint_sega_cd
                    else R.string.library_bios_multiple_hint
                )
                textSize = 12f
                alpha = 0.6f
                setPadding(0, dp(8), 0, dp(4))
            })
        }
    }

    /** Name + region on the left (tap to activate), a delete button on the right. Selecting is
     *  the common action and deleting the rare destructive one, so they're separate targets
     *  rather than one row plus a long-press, and only delete is confirmed. */
    private fun biosRow(bios: BiosManager.DetectedBios, active: Boolean): View {
        val fileName = bios.fileName
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val selectArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (!active) {
                isClickable = true
                isFocusable = true
                setBackgroundResource(selectableItemBackgroundResId())
                setOnClickListener {
                    TacoBoyPrefs.setActiveBiosFileName(this@RomLibraryActivity, currentSystem, fileName)
                    refreshBiosUi()
                }
            }
        }

        selectArea.addView(TextView(this).apply {
            text = "$fileName ${bios.icon}"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        selectArea.addView(TextView(this).apply {
            text = getString(if (active) R.string.library_bios_active else R.string.library_bios_select)
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            if (active) setBackgroundResource(R.drawable.tab_selected_bg) else alpha = 0.6f
        })

        row.addView(selectArea)

        val deleteButton = TextView(this).apply {
            text = getString(R.string.library_bios_icon_delete)
            textSize = 16f
            alpha = 0.7f
            setPadding(dp(12), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableItemBackgroundResId())
            setOnClickListener { confirmDeleteBios(fileName) }
        }
        TooltipCompat.setTooltipText(deleteButton, getString(R.string.library_bios_tooltip_delete))
        row.addView(deleteButton)

        return row
    }

    /** Confirmed rather than immediate: a BIOS dump can't be re-fetched from inside the app the
     *  way box art can, so a mis-tap next to "Select" would cost a file the user may have no
     *  other copy of. Deleting the active one is allowed -- BiosManager.deleteBios clears the
     *  saved choice and resolveActiveFileName falls back to whatever is left. */
    private fun confirmDeleteBios(fileName: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.library_bios_delete_title)
            .setMessage(getString(R.string.library_bios_delete_message, fileName))
            .setPositiveButton(R.string.library_bios_delete_confirm) { _, _ ->
                val deleted = BiosManager.deleteBios(this, fileName)
                Toast.makeText(
                    this,
                    if (deleted) R.string.library_bios_deleted else R.string.library_bios_delete_failed,
                    Toast.LENGTH_SHORT
                ).show()
                refreshBiosUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.setGravity(Gravity.TOP)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Same TypedValue-resolution pattern SettingsActivity uses for its own icon-only
     *  buttons, duplicated here rather than shared since these rows are the only place in
     *  this Activity that builds views in code with a ripple background. */
    private fun selectableItemBackgroundResId(): Int {
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    /**
     * Every system, in GameSystem.PICKER_ORDER, with how many games each has so the list says
     * where the games are: the count visible in the library (hidden games excluded), "No folder"
     * for a system never set up, or "Not scanned yet" for one whose folder has no scan cached.
     * Counts come from RomLibraryCache, not a rescan, so opening the list never walks a folder.
     */
    private fun showSystemPicker() {
        lifecycleScope.launch {
            val hidden = TacoBoyPrefs.getHiddenRoms(this@RomLibraryActivity)
            val details = withContext(Dispatchers.IO) {
                GameSystem.PICKER_ORDER.map { system ->
                    val folder = TacoBoyPrefs.getRomFolderUri(this@RomLibraryActivity, system)?.let(Uri::parse)
                    when {
                        folder == null -> getString(R.string.system_picker_no_folder)
                        else -> RomLibraryCache.load(this@RomLibraryActivity, system, folder)
                            ?.count { !hidden.contains(it.uri.toString()) }
                            ?.let { resources.getQuantityString(R.plurals.system_picker_games, it, it) }
                            ?: getString(R.string.system_picker_not_scanned)
                    }
                }
            }
            val items = GameSystem.PICKER_ORDER.mapIndexed { i, system ->
                android.text.SpannableStringBuilder(getString(system.nameRes)).apply {
                    append("   ")
                    val start = length
                    append(details[i])
                    setSpan(android.text.style.ForegroundColorSpan(0x88FFFFFF.toInt()), start, length, 0)
                    setSpan(android.text.style.RelativeSizeSpan(0.85f), start, length, 0)
                }
            }.toTypedArray<CharSequence>()

            val dialog = AlertDialog.Builder(this@RomLibraryActivity)
                .setTitle(R.string.system_picker_title)
                .setSingleChoiceItems(items, GameSystem.PICKER_ORDER.indexOf(currentSystem)) { dialog, which ->
                    dialog.dismiss()
                    val picked = GameSystem.PICKER_ORDER[which]
                    if (picked != currentSystem) selectSystem(picked)
                }
                .show()
            // Same as every other dialog here: keep it above the clamp boundary.
            dialog.window?.setGravity(Gravity.TOP)
        }
    }

    private fun toggleViewMode() {
        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        TacoBoyPrefs.setLibraryViewMode(this, viewMode)
        updateViewModeLabel()
        renderRoms()
    }

    private fun updateViewModeLabel() {
        val switchingToGrid = viewMode == ViewMode.LIST
        viewModeToggle.text = getString(if (switchingToGrid) R.string.library_icon_grid else R.string.library_icon_list)
        TooltipCompat.setTooltipText(
            viewModeToggle,
            getString(if (switchingToGrid) R.string.library_tooltip_switch_to_grid else R.string.library_tooltip_switch_to_list)
        )
    }

    private fun loadRoms(forceRescan: Boolean) {
        val folder = folderUri ?: return
        val system = currentSystem

        val cached = if (forceRescan) null else RomLibraryCache.load(this, system, folder)
        if (cached != null) {
            roms = cached
            renderRoms()
            return
        }

        loadingIndicator.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE

        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                RomLibrary.scanRoms(this@RomLibraryActivity, folder)
                    .filter { system in GameSystem.candidatesForFileName(it.displayName) }
            }
            // The system may have changed while this scan was in flight — don't clobber a newer selection.
            if (currentSystem != system) return@launch
            RomLibraryCache.save(this@RomLibraryActivity, system, folder, scanned)
            roms = scanned
            loadingIndicator.visibility = View.GONE
            emptyState.text = getString(R.string.rom_picker_empty)
            renderRoms()
        }
    }

    private fun renderRoms() {
        val hidden = TacoBoyPrefs.getHiddenRoms(this)
        val afterHidden = roms.filterNot { hidden.contains(it.uri.toString()) }

        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            afterHidden
        } else {
            afterHidden.filter { displayTitle(it).contains(query, ignoreCase = true) }
        }
        val visible = sortedRoms(filtered)

        if (visible.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            // Only overrides the message for "search matched nothing" — a genuinely
            // empty library (no folder set, or a folder with nothing in it) keeps
            // whatever text selectSystem()/loadRoms() already put in emptyState.
            if (query.isNotEmpty() && afterHidden.isNotEmpty()) {
                emptyState.text = getString(R.string.rom_picker_search_empty)
            }
            return
        }

        emptyState.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE

        when (viewMode) {
            ViewMode.LIST -> {
                recyclerView.layoutManager = LinearLayoutManager(this)
                recyclerView.adapter = RomListAdapter(visible, ::onRomSelected, ::onRomLongPressed)
            }
            ViewMode.GRID -> {
                recyclerView.layoutManager = GridLayoutManager(this, GRID_SPAN_COUNT)
                recyclerView.adapter = RomGridAdapter(visible, ::onRomSelected, ::onRomLongPressed)
            }
        }
    }

    private fun displayTitle(rom: RomLibrary.RomEntry): String {
        return TacoBoyPrefs.getCustomTitle(this, rom.uri.toString()) ?: rom.displayName
    }

    private fun sortedRoms(source: List<RomLibrary.RomEntry>): List<RomLibrary.RomEntry> {
        return when (sortMode) {
            SortMode.NAME -> source.sortedBy { displayTitle(it).lowercase() }
            SortMode.RECENTLY_PLAYED -> source.sortedByDescending { TacoBoyPrefs.getLastPlayed(this, it.uri.toString()) }
            SortMode.MOST_PLAYED -> source.sortedByDescending { TacoBoyPrefs.getPlayCount(this, it.uri.toString()) }
        }
    }

    private fun cycleSortMode() {
        val modes = SortMode.entries
        sortMode = modes[(modes.indexOf(sortMode) + 1) % modes.size]
        TacoBoyPrefs.setLibrarySortMode(this, sortMode)
        updateSortTooltip()
        renderRoms()
        Toast.makeText(this, getString(R.string.library_sorted_toast, sortModeLabel()), Toast.LENGTH_SHORT).show()
    }

    private fun updateSortTooltip() {
        TooltipCompat.setTooltipText(sortButton, getString(R.string.library_tooltip_sort, sortModeLabel()))
    }

    private fun sortModeLabel(): String = getString(
        when (sortMode) {
            SortMode.NAME -> R.string.sort_mode_name
            SortMode.RECENTLY_PLAYED -> R.string.sort_mode_recently_played
            SortMode.MOST_PLAYED -> R.string.sort_mode_most_played
        }
    )

    private fun toggleSearch() {
        if (searchRow.visibility == View.VISIBLE) {
            closeSearch()
            return
        }
        searchRow.visibility = View.VISIBLE
        searchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        if (searchRow.visibility != View.VISIBLE && searchQuery.isEmpty()) return
        searchInput.text.clear() // TextWatcher clears searchQuery and re-renders.
        searchRow.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /** Long-press per-game menu: custom box art, reset art, hide, rename. Alternate-core switching was
     *  considered but deliberately left out — there's only one core binary bundled per system, so a
     *  "switch core" option would have nothing to switch to until a second one is sourced. */
    private fun onRomLongPressed(rom: RomLibrary.RomEntry) {
        val displayTitle = TacoBoyPrefs.getCustomTitle(this, rom.uri.toString()) ?: rom.displayName
        val options = arrayOf(
            getString(R.string.game_menu_custom_art),
            getString(R.string.game_menu_reset_art),
            getString(R.string.game_menu_hide),
            getString(R.string.game_menu_rename),
            getString(R.string.game_menu_check_achievements),
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(displayTitle)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pendingArtTarget = rom
                        artPickerLauncher.launch("image/*")
                    }
                    1 -> resetArt(rom)
                    2 -> hideRom(rom)
                    3 -> renameRom(rom, displayTitle)
                    4 -> checkAchievements(rom)
                }
            }
            .show()
        // Keeps the menu within the game region above the clamp boundary,
        // same reasoning as everything else on this screen.
        dialog.window?.setGravity(Gravity.TOP)
    }

    private fun resetArt(rom: RomLibrary.RomEntry) {
        BoxArtCache.clear(this, rom.displayName)
        Toast.makeText(this, R.string.game_menu_art_resetting, Toast.LENGTH_SHORT).show()
        recyclerView.adapter?.notifyDataSetChanged()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { BoxArtCache.ensureDownloaded(this@RomLibraryActivity, rom.displayName, currentSystem) }
            recyclerView.adapter?.notifyDataSetChanged()
        }
    }

    /**
     * Identifies the ROM, one at a time on demand rather than something run
     * automatically across the whole library — RomHasher reads the full ROM file
     * to hash it, and doing that for every game on every scan would be a real
     * cost most of the time nobody's about to look at achievements at all. A
     * successful match hands off to AchievementsActivity for the actual list;
     * every other outcome (not logged in, unsupported system, no match, request
     * failure) has nothing to show a screen for, so it's just a toast.
     *
     * A match is also saved for offline play (AchievementCache) when live tracking is logged
     * in, so a game can be readied before going offline without having to start it. That runs
     * behind the list rather than before it, so checking is no slower than it was; it needs
     * the live-tracking session because the definitions do.
     */
    private fun checkAchievements(rom: RomLibrary.RomEntry) {
        // Taken now: the hash depends on the system (a .chd is PS1 or Sega CD), and the user
        // can switch system before the lookup finishes.
        val system = currentSystem
        val username = TacoBoyPrefs.getRetroAchievementsUsername(this)
        val apiKey = TacoBoyPrefs.getRetroAchievementsApiKey(this)
        if (username == null || apiKey == null) {
            Toast.makeText(this, R.string.achievements_check_not_logged_in, Toast.LENGTH_LONG).show()
            return
        }

        val displayTitle = TacoBoyPrefs.getCustomTitle(this, rom.uri.toString()) ?: rom.displayName

        lifecycleScope.launch {
            var hash: String? = null
            val gameId = withContext(Dispatchers.IO) {
                hash = RomHasher.raHash(this@RomLibraryActivity, rom, system) ?: return@withContext null
                RetroAchievementsClient.identifyGameId(username, apiKey, hash!!)
            }
            when {
                gameId != null && gameId > 0 -> {
                    AchievementsActivity.start(this@RomLibraryActivity, gameId, displayTitle)
                    hash?.let { saveForOffline(it, gameId, username, apiKey) }
                }
                gameId == null -> Toast.makeText(this@RomLibraryActivity, R.string.achievements_check_failed, Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this@RomLibraryActivity, R.string.achievements_check_not_recognized, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveForOffline(hash: String, gameId: Int, username: String, apiKey: String) {
        val sessionUsername = TacoBoyPrefs.getRetroAchievementsSessionUsername(this) ?: return
        val sessionToken = TacoBoyPrefs.getRetroAchievementsSessionToken(this) ?: return
        val appContext = applicationContext
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                AchievementCache.refresh(appContext, hash, gameId, username, apiKey, sessionUsername, sessionToken)
            }
            // Application context: the achievement list is in front by now.
            if (saved != null) Toast.makeText(appContext, R.string.achievements_saved_for_offline_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideRom(rom: RomLibrary.RomEntry) {
        TacoBoyPrefs.setHidden(this, rom.uri.toString(), true)
        Toast.makeText(this, R.string.game_menu_hidden, Toast.LENGTH_LONG).show()
        renderRoms()
    }

    private fun renameRom(rom: RomLibrary.RomEntry, currentTitle: String) {
        val input = EditText(this).apply {
            setText(currentTitle)
            hint = getString(R.string.game_menu_rename_hint)
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.game_menu_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                TacoBoyPrefs.setCustomTitle(this, rom.uri.toString(), input.text.toString())
                recyclerView.adapter?.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.setGravity(Gravity.TOP)
    }

    /**
     * Downloads box art for every currently-listed title missing it,
     * 4-at-a-time to avoid hammering libretro's thumbnail server. Many
     * titles (aftermarket/homebrew/patched ROMs especially) simply have no
     * matching art upstream — that's an expected miss, not a failure.
     */
    private fun onDownloadBoxArtClicked() {
        if (roms.isEmpty()) return

        val missing = roms.filter { BoxArtCache.getCachedOrNull(this, it.displayName) == null }
        if (missing.isEmpty()) {
            Toast.makeText(this, R.string.library_boxart_up_to_date, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, getString(R.string.library_boxart_downloading, missing.size), Toast.LENGTH_SHORT).show()

        // The art folder is the system's, and switching system mid-download must not change it.
        val system = currentSystem
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                missing.chunked(4).forEach { chunk ->
                    chunk.map { rom -> async { BoxArtCache.ensureDownloaded(this@RomLibraryActivity, rom.displayName, system) } }
                        .awaitAll()
                }
            }
            recyclerView.adapter?.notifyDataSetChanged()
            Toast.makeText(this@RomLibraryActivity, R.string.library_boxart_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onRomSelected(rom: RomLibrary.RomEntry) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_ROM_URI, rom.uri.toString()))
        finish()
    }

    companion object {
        const val EXTRA_RESULT_ROM_URI = "rom_uri"
        // Set by TacoBoyActivity.openLibrary() only when a game is currently loaded —
        // its presence (not just a boolean) doubles as what resume_game_button's
        // tooltip displays, so there's no separate "is a game running" flag to keep in sync.
        const val EXTRA_GAME_TITLE = "game_title"
        private const val GRID_SPAN_COUNT = 3
    }
}
