package com.tacoboy

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.android.libretrodroid.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** One tap of a resize handle. Ten percent is small enough that overshooting costs one tap
 *  back, and large enough to be visible immediately -- a step you can't see looks broken. */
private const val SCALE_STEP = 0.1f

/**
 * The on-screen gamepad, drawn into the black zone below the boundary handle.
 *
 * Exists so someone without the Pocket Taco attached can keep playing in TacoBoy rather than
 * moving their saves to another emulator and back — that, not feature parity with other
 * emulators, is the reason it's here. Off by default; the top-row button toggles it.
 *
 * One custom View that paints every control and tracks every pointer itself, rather than a
 * ViewGroup of child Views. A gamepad is inherently multi-touch — a stick and a face button at
 * the same time is the normal case, not an edge case — and Android delivers a touch stream to
 * whichever child claimed the first pointer, so child Views would need the same pointer
 * bookkeeping anyway, on top of a layout pass. Doing it here also makes the hit areas
 * independent of the drawn size, which is what lets the touch targets stay usable when the
 * zone is dragged small.
 *
 * Sends targets straight through [onKey] rather than through ControllerBindings: bindings
 * remap *physical* buttons onto targets, and these are already targets.
 */
class TouchControls @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Which stick moved. Kept as this enum rather than a libretro motion-source int so the
     *  view doesn't have to know anything about GLRetroView. */
    enum class Stick { LEFT, RIGHT }

    /**
     * PlayStation's face-button shapes, with the colours the real pad prints them in.
     *
     * `scale` is an optical correction, not a bounding box: shapes of equal geometric size
     * do not read as equal. A circle looks largest at a given radius, a triangle smallest,
     * so the circle is drawn slightly under the nominal size and the triangle slightly over
     * to make the four look like a matched set.
     */
    enum class Glyph(val colour: Int, val scale: Float) {
        // Tuned against the drawn widths rather than by eye alone. An equilateral triangle is
        // 1.73x its circumradius wide against a circle's 2x, so an unscaled triangle both
        // measures and looks wider; a cross drawn on the diagonals measures narrowest. These
        // land the four within a few percent of each other.
        TRIANGLE(0xFF5BC8A0.toInt(), 1.00f),
        CIRCLE(0xFFE8546B.toInt(), 0.87f),
        CROSS(0xFF6A87E0.toInt(), 0.89f),
        SQUARE(0xFFE27BB8.toInt(), 0.82f),
    }

    /** (action, keyCode) — action is KeyEvent.ACTION_DOWN/ACTION_UP, keyCode a Target's. */
    var onKey: ((Int, Int) -> Unit)? = null

    /** Stick position, each axis -1..1, Android's convention (up is negative). */
    var onAnalog: ((Stick, Float, Float) -> Unit)? = null

    private var system: GameSystem? = null
    private var withSticks = false

    /** A short tick on each press, so a glass button gives back something a physical one
     *  would. Fires on a press or a new D-pad direction only -- never while a stick is
     *  moving, which would buzz continuously for as long as a thumb rested on it. */
    var hapticStrength: HapticStrength = HapticStrength.DEFAULT

    /** Resolved once: the lookup is cheap but this is called on every button press, and on
     *  API 31+ it goes through VibratorManager rather than the deprecated direct service. */
    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            TacoBoyLog.e("TacoBoy.TouchControls", "No vibrator available", e)
            null
        }
    }

    /** While true the pad moves instead of playing: dragging a control repositions it, tapping
     *  one selects it for resizing, and nothing reaches the core. */
    var editMode = false
        set(value) {
            if (field == value) return
            // Leaving a button held and then switching modes would strand the press, since the
            // release goes to the other mode's handler.
            releaseEverything()
            field = value
            selectedId = null
            invalidate()
        }

    /**
     * Base size of every control, from TacoBoyPrefs.getTouchControlScale.
     *
     * Applied by scaling the layout's `unit` rather than each radius at the end, so that the
     * gaps *within* a cluster grow with the buttons -- the face diamond's spread and the
     * D-pad's arms are already expressed in units. Anchor points stay fractions of the zone,
     * so clusters grow in place instead of drifting across the screen.
     */
    var globalScale: Float = 1f
        set(value) {
            val clamped = value.coerceIn(
                TacoBoyPrefs.TOUCH_CONTROL_SCALE_MIN,
                TacoBoyPrefs.TOUCH_CONTROL_SCALE_MAX
            )
            if (field == clamped) return
            field = clamped
            if (width > 0 && height > 0) layoutControls(width.toFloat(), height.toFloat())
            invalidate()
        }

    /** Which control the resize handles are currently attached to, in edit mode only. */
    private var selectedId: String? = null

    /** Reports the full set of user-positioned controls after a drag, as fractions of the
     *  zone. Empty means "everything is at its default". */
    var onLayoutChanged: ((Map<String, TouchLayouts.Position>) -> Unit)? = null

    private var overrides: MutableMap<String, TouchLayouts.Position> = mutableMapOf()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDDDDD.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPath = Path()

    private val idleFill = 0xFF262626.toInt()
    private val pressedFill = 0xFF5A5A5A.toInt()
    private val knobFill = 0xFF3A3A3A.toInt()
    private val outline = 0xFF4D4D4D.toInt()
    private val editOutline = 0xFF5E8DB5.toInt()
    private val selectedOutline = 0xFF9AD1FF.toInt()

    private val activeOutline get() = if (editMode) editOutline else outline

    /** The selected control is outlined brighter than the rest, so it is obvious which one the
     *  resize handles are about to act on. */
    private fun outlineFor(id: String) =
        if (editMode && id == selectedId) selectedOutline else activeOutline

    private class Button(
        /** Stable across releases and independent of position — this is what a saved custom
         *  layout keys off, so it must not be the label (which differs per system) or the
         *  index (which shifts when a system's control set changes). */
        val id: String,
        val keyCode: Int,
        val label: String,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var radius: Float = 0f,
        /** Start/Select read better as pills than as circles the size of a face button. */
        var pill: Boolean = false,
        /** Drawn instead of [label] when set. PlayStation's shapes are drawn rather than
         *  typed because the Unicode characters are designed at noticeably different sizes
         *  (U+2715 is a heavy multiplication sign, U+25CB a light geometric circle), so as
         *  text the cross came out visibly bigger than the circle and triangle. Drawing them
         *  also allows the DualShock colours. */
        var glyph: Glyph? = null,
        var pressed: Boolean = false,
    )

    /** One analog stick's geometry and live position. Two of these rather than two sets of
     *  loose fields, so the pointer map can just hold whichever one a finger grabbed. */
    private class StickState(val side: Stick) {
        var cx = 0f
        var cy = 0f
        var radius = 0f
        var knobX = 0f
        var knobY = 0f
        var active = false

        fun centreKnob() {
            knobX = cx
            knobY = cy
            active = false
        }
    }

    private val buttons = mutableListOf<Button>()
    private val leftStick = StickState(Stick.LEFT)
    private val rightStick = StickState(Stick.RIGHT)

    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadRadius = 0f
    private val dpadPressed = mutableSetOf<Int>()

    /** pointerId -> what that finger is holding. */
    private val pointerTargets = mutableMapOf<Int, Any>()
    private object DpadHandle

    /**
     * The Lynx's two Option buttons and Pause arrive as RetroPad L/R/Start, so the generic
     * labels would be actively misleading on the one system where the printed names differ
     * from the RetroPad ones.
     */
    private fun labelFor(system: GameSystem, target: ControllerBindings.Target): String =
        when (system) {
            GameSystem.LYNX -> when (target) {
                ControllerBindings.Target.L1 -> "OPT 1"
                ControllerBindings.Target.R1 -> "OPT 2"
                ControllerBindings.Target.START -> "PAUSE"
                else -> target.label
            }
            // Genesis is the extreme case: the RetroPad name and the printed name disagree on
            // every single button, so showing the raw targets would mislabel the whole pad.
            GameSystem.GENESIS -> when (target) {
                ControllerBindings.Target.Y -> "A"
                ControllerBindings.Target.B -> "B"
                ControllerBindings.Target.A -> "C"
                ControllerBindings.Target.L1 -> "X"
                ControllerBindings.Target.X -> "Y"
                ControllerBindings.Target.R1 -> "Z"
                ControllerBindings.Target.SELECT -> "MODE"
                else -> target.label
            }
            // Sega's 8-bit pads print 1 and 2, and the core wires them to RetroPad B and A.
            // Master System's pause is on the console itself; the SG-1000 has neither.
            GameSystem.MASTER_SYSTEM, GameSystem.GAME_GEAR, GameSystem.SG_1000 -> when (target) {
                ControllerBindings.Target.B -> "1"
                ControllerBindings.Target.A -> "2"
                ControllerBindings.Target.START ->
                    if (system == GameSystem.MASTER_SYSTEM) "PAUSE" else "Start"
                else -> target.label
            }
            else -> target.label
        }

    /**
     * The PlayStation prints shapes, and every PS1 game's on-screen prompts use them, so the
     * RetroPad letters would force a translation step on every prompt. The diamond already
     * sits in DualShock positions (X top, Y left, A right, B bottom) and the standard
     * RetroPad mapping is B=Cross, A=Circle, Y=Square, X=Triangle — so the shapes land
     * exactly where the real pad has them without the layout moving at all.
     */
    private fun glyphFor(system: GameSystem, target: ControllerBindings.Target): Glyph? {
        if (system != GameSystem.PS1) return null
        return when (target) {
            ControllerBindings.Target.X -> Glyph.TRIANGLE
            ControllerBindings.Target.Y -> Glyph.SQUARE
            ControllerBindings.Target.A -> Glyph.CIRCLE
            ControllerBindings.Target.B -> Glyph.CROSS
            else -> null
        }
    }

    fun configure(
        system: GameSystem,
        withSticks: Boolean,
        savedLayout: Map<String, TouchLayouts.Position> = emptyMap(),
        globalScale: Float = 1f,
    ) {
        this.system = system
        this.withSticks = withSticks
        this.overrides = savedLayout.toMutableMap()
        this.selectedId = null
        // Set before buildControls() so the layout pass below is the only one that runs with
        // the new control set; the setter's own relayout, if this changed the value, lands on
        // the outgoing one and is immediately superseded.
        this.globalScale = globalScale
        buildControls()
        if (width > 0 && height > 0) layoutControls(width.toFloat(), height.toFloat())
        invalidate()
    }

    private fun buildControls() {
        buttons.clear()
        val system = system ?: return
        val relevant = system.relevantControllerTargets

        // L3/R3 get their own small buttons beside the sticks rather than being a click of the
        // stick itself: on a touchscreen a press and a nudge are the same gesture, so a
        // clickable stick would fire L3 every time you moved it. Everything else this system
        // actually has is offered, so the pad shrinks to two buttons on Game Boy and fills out
        // on PS1.
        val order = listOf(
            ControllerBindings.Target.L2, ControllerBindings.Target.L1,
            ControllerBindings.Target.R1, ControllerBindings.Target.R2,
            ControllerBindings.Target.SELECT, ControllerBindings.Target.START,
            ControllerBindings.Target.X, ControllerBindings.Target.Y,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
            ControllerBindings.Target.L3, ControllerBindings.Target.R3,
        )
        order.filter { it in relevant }.forEach { target ->
            buttons += Button(
                id = target.name,
                keyCode = target.keyCode,
                label = labelFor(system, target),
                pill = target == ControllerBindings.Target.START ||
                    target == ControllerBindings.Target.SELECT,
                glyph = glyphFor(system, target),
            )
        }
    }

    private fun byKeyCode(target: ControllerBindings.Target): Button? =
        buttons.firstOrNull { it.keyCode == target.keyCode }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls(w.toFloat(), h.toFloat())
    }

    /**
     * Thumb-reach arrangement: the primary controls sit low, in the bottom corners where
     * thumbs rest holding a phone in portrait, Start/Select are lifted up out of that travel
     * path so they can't be caught by accident mid-game, and shoulders run along the top edge
     * furthest from both.
     *
     * With sticks (PS1) the pad becomes symmetric: D-pad and face buttons share a row, with a
     * stick directly below each. That mirrors a DualShock, and it means the thumbs' resting
     * position is the sticks — right for the camera-and-aiming games the sticks exist for —
     * while the D-pad and buttons stay one short reach up.
     *
     * Genesis is laid out as the two rows of three its real six-button pad had, rather than a
     * diamond plus shoulders. On the Pocket Taco X and Z genuinely *are* the shoulder buttons,
     * because that is the only place six actions fit on that hardware — but on screen there is
     * no such constraint, and pretending otherwise would put two face buttons in the wrong
     * place on the one system where all six are printed together.
     *
     * Everything is a fraction of the zone rather than a dp size, because the zone's height is
     * whatever the user has dragged the boundary handle to. The lowest row deliberately stops
     * short of the bottom edge: controls sitting flush against it collide with the system's
     * back/home gestures, which is a much worse failure than a little wasted space.
     */
    private fun layoutControls(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return

        // Chrome that has to stay finger-sized whatever the controls are doing (the edit-mode
        // resize handles) measures against the unscaled zone instead.
        baseUnit = min(w, h)
        val unit = baseUnit * globalScale
        val shoulderR = unit * 0.070f
        // The face diamond is taller than the D-pad cross for the same nominal size (half a
        // spread plus a button radius, against one arm), so on PS1 -- where a stick sits
        // directly beneath it -- it has to be tighter or B lands on the stick.
        val faceR = unit * if (withSticks) 0.060f else 0.068f
        val faceSpread = unit * if (withSticks) 0.122f else 0.135f

        val sixButtonFace = system == GameSystem.GENESIS

        // Shoulders, outermost first so L1/R1 land under the thumbs' natural reach and
        // L2/R2 (PS1 only) tuck inside them. Skipped entirely on Genesis, where L1/R1 carry
        // X and Z and belong in the face cluster below.
        val shoulderY = h * 0.085f
        // Outer pair pinned near the edges, inner pair stepped in by their own diameter rather
        // than by another fraction of the width. At the default size the two are the same
        // place; as the controls grow, a fixed fraction would leave L1 and L2 overlapping.
        val outerShoulderX = (w * 0.11f).coerceAtLeast(shoulderR)
        val shoulderStep = shoulderR * 2.3f
        if (!sixButtonFace) {
            byKeyCode(ControllerBindings.Target.L1)?.apply {
                cx = outerShoulderX; cy = shoulderY; radius = shoulderR
            }
            byKeyCode(ControllerBindings.Target.R1)?.apply {
                cx = w - outerShoulderX; cy = shoulderY; radius = shoulderR
            }
        }
        // With no L1/R1 outboard of them (Genesis), L2/R2 take the outer position themselves.
        val innerShoulderX =
            if (sixButtonFace) outerShoulderX else outerShoulderX + shoulderStep
        byKeyCode(ControllerBindings.Target.L2)?.apply {
            cx = innerShoulderX; cy = shoulderY; radius = shoulderR
        }
        byKeyCode(ControllerBindings.Target.R2)?.apply {
            cx = w - innerShoulderX; cy = shoulderY; radius = shoulderR
        }

        val menuY = h * 0.21f
        val menuR = unit * 0.055f
        val select = byKeyCode(ControllerBindings.Target.SELECT)
        val start = byKeyCode(ControllerBindings.Target.START)
        if (select != null && start != null) {
            // Split either side of centre by the pill's own half-width plus a gap, not by a
            // fixed fraction: a pill is nearly 2x its radius wide, so at larger sizes fixed
            // anchors ran Select and Start straight through each other.
            val menuOffset = (menuR * 2.2f).coerceAtLeast(w * 0.12f)
            select.apply { cx = w * 0.5f - menuOffset; cy = menuY; radius = menuR }
            start.apply { cx = w * 0.5f + menuOffset; cy = menuY; radius = menuR }
        } else {
            // Lynx has only Pause; centre it rather than leaving a lopsided gap.
            (start ?: select)?.apply { cx = w * 0.5f; cy = menuY; radius = menuR }
        }

        // How close any cluster is allowed to get to the edge of the zone. Measured against
        // the unscaled zone so it stays a constant margin rather than growing with the pad.
        val edge = baseUnit * 0.015f

        val faceCy: Float
        if (withSticks) {
            dpadCx = w * 0.19f
            dpadCy = h * 0.47f
            dpadRadius = unit * 0.115f
            faceCy = dpadCy

            val stickR = unit * 0.105f
            // The face diamond grows downward from a row anchored to a fraction of the zone,
            // so the taller it gets the closer its bottom button comes to the stick beneath
            // it -- and a button overlapping a stick is not merely untidy: buttons win
            // hit-test ties, so the overlap would press Cross instead of moving the stick.
            // Nudge the sticks down by however much is needed to stay clear, and no further;
            // the cap keeps the row from backing into the system's gesture area at the bottom
            // edge, which is why it sits above it in the first place.
            val stickY = (h * 0.81f)
                .coerceAtLeast(
                    (faceCy + faceSpread + faceR + edge + stickR).coerceAtMost(h * 0.84f)
                )
            leftStick.apply { cx = w * 0.23f; cy = stickY; radius = stickR; centreKnob() }
            rightStick.apply { cx = w * 0.77f; cy = stickY; radius = stickR; centreKnob() }

            // Inboard of each stick, level with it, in the empty band between the two. Small,
            // because they are rarely used. The gap matters: buttons win hit-test ties, so if
            // one of these sat close enough for its touch area to reach inside the stick's
            // drawn circle, aiming at the stick's inner edge would press L3 instead of moving
            // it. Placed so the button's reach stops short of the stick's own outline.
            val clickR = unit * 0.052f
            // Measured out from the stick's own edge rather than from a fraction of the width,
            // so the gap this comment depends on survives the controls being scaled up -- at a
            // fixed fraction a grown stick swallows it, and with it the ability to aim at the
            // stick's inner edge at all.
            // 1.55 rather than the 1.35 the touch reach itself uses, so the reach ends a
            // little short of the stick's outline instead of exactly on it -- the slack the
            // original fixed anchor happened to have, now kept at every size.
            val clickOffset = stickR + clickR * 1.55f
            byKeyCode(ControllerBindings.Target.L3)?.apply {
                cx = leftStick.cx + clickOffset; cy = stickY; radius = clickR
            }
            byKeyCode(ControllerBindings.Target.R3)?.apply {
                cx = rightStick.cx - clickOffset; cy = stickY; radius = clickR
            }
        } else {
            dpadCx = w * 0.21f
            dpadCy = h * 0.68f
            dpadRadius = unit * 0.155f
            faceCy = dpadCy

            leftStick.radius = 0f
            rightStick.radius = 0f
        }
        // Whole-cluster clamp. The per-control clamp at the end of this method is a backstop
        // that moves one control at a time, which on a cluster means pulling a single button
        // out of formation and leaving the diamond visibly lopsided. Nudging the cluster's
        // centre instead keeps its shape at any size.
        dpadCx = dpadCx.coerceAtLeast(dpadRadius + edge)

        val face = listOf(
            ControllerBindings.Target.X, ControllerBindings.Target.Y,
            ControllerBindings.Target.A, ControllerBindings.Target.B,
        ).mapNotNull { byKeyCode(it) }

        // Half the width the face cluster is about to occupy, which differs per arrangement --
        // needed before anything is placed so the cluster's centre can be pulled in off the
        // edge as a unit.
        val faceHalf = when {
            sixButtonFace -> unit * 0.115f + unit * 0.058f
            face.size <= 2 -> unit * 0.115f + unit * 0.095f
            else -> faceSpread + faceR
        }
        val faceCx = (w * 0.79f)
            .coerceAtMost(w - faceHalf - edge)
            .coerceAtLeast(faceHalf + edge)

        if (sixButtonFace) {
            // Two rows of three, as printed: X Y Z above A B C.
            val colGap = unit * 0.115f
            val rowGap = unit * 0.062f
            val sixR = unit * 0.058f
            val top = listOf(
                ControllerBindings.Target.L1, ControllerBindings.Target.X,
                ControllerBindings.Target.R1,
            )
            val bottom = listOf(
                ControllerBindings.Target.Y, ControllerBindings.Target.B,
                ControllerBindings.Target.A,
            )
            top.forEachIndexed { i, target ->
                byKeyCode(target)?.apply {
                    cx = faceCx + (i - 1) * colGap; cy = faceCy - rowGap; radius = sixR
                }
            }
            bottom.forEachIndexed { i, target ->
                byKeyCode(target)?.apply {
                    cx = faceCx + (i - 1) * colGap; cy = faceCy + rowGap; radius = sixR
                }
            }
        } else if (face.size <= 2) {
            // Game Boy / Lynx: B left and low, A right and high, the diagonal both consoles
            // actually used.
            val spread = unit * 0.115f
            val bigR = unit * 0.095f
            byKeyCode(ControllerBindings.Target.B)?.apply {
                cx = faceCx - spread; cy = faceCy + spread * 0.55f; radius = bigR
            }
            byKeyCode(ControllerBindings.Target.A)?.apply {
                cx = faceCx + spread; cy = faceCy - spread * 0.55f; radius = bigR
            }
        } else {
            // SNES/PS1 diamond, in the positions those consoles print them.
            val spread = faceSpread
            byKeyCode(ControllerBindings.Target.X)?.apply {
                cx = faceCx; cy = faceCy - spread; radius = faceR
            }
            byKeyCode(ControllerBindings.Target.Y)?.apply {
                cx = faceCx - spread; cy = faceCy; radius = faceR
            }
            byKeyCode(ControllerBindings.Target.A)?.apply {
                cx = faceCx + spread; cy = faceCy; radius = faceR
            }
            byKeyCode(ControllerBindings.Target.B)?.apply {
                cx = faceCx; cy = faceCy + spread; radius = faceR
            }
        }

        strokePaint.strokeWidth = unit * 0.006f
        textPaint.textSize = unit * 0.055f

        // Anything the user has customised wins over the computed default. Applied as a final
        // pass rather than woven into the branches above so that a control they never touched
        // still picks up any later change to the defaults.
        //
        // Size first, then position, then the bounds clamp -- each pass reads the sizes the
        // previous one settled on, and a control's own size decides how far into the zone its
        // centre has to sit.
        movables().forEach { movable ->
            overrides[movable.id]?.scale?.let { scale ->
                if (scale != 1f) movable.setRadius(movable.radius * scale)
            }
        }
        movables().forEach { movable ->
            overrides[movable.id]?.let { movable.moveTo(it.x * w, it.y * h) }
        }
        // Growing a control -- globally or on its own -- can push it past an edge it fitted
        // inside at its old size, where part of it would be undraggable and unpressable. Both
        // scales are user-facing, so this has to hold for the defaults too, not just for
        // positions that were dragged there.
        movables().forEach { movable ->
            val x = movable.cx.coerceIn(movable.extentX, (w - movable.extentX).coerceAtLeast(movable.extentX))
            val y = movable.cy.coerceIn(movable.extentY, (h - movable.extentY).coerceAtLeast(movable.extentY))
            if (x != movable.cx || y != movable.cy) movable.moveTo(x, y)
        }
    }

    /** min(width, height) before [globalScale] -- see layoutControls. */
    private var baseUnit = 0f

    /** One repositionable control, wrapping the differently-shaped things the pad draws
     *  (buttons, the D-pad, sticks) behind a common centre-and-radius so edit mode doesn't
     *  need to know which is which. */
    private class Movable(
        val id: String,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        /** Half the control's drawn width and height. Only a pill differs from its radius,
         *  and only horizontally -- but it differs by nearly 2x, which is the difference
         *  between Start sitting inside the zone and hanging off it. */
        val extentX: Float,
        val extentY: Float,
        val moveTo: (Float, Float) -> Unit,
        val setRadius: (Float) -> Unit,
    )

    private fun movables(): List<Movable> {
        val list = mutableListOf<Movable>()
        buttons.filter { it.radius > 0f }.forEach { button ->
            list += Movable(
                id = button.id,
                cx = button.cx,
                cy = button.cy,
                radius = button.radius,
                extentX = button.radius * if (button.pill) 1.9f else 1f,
                extentY = button.radius * if (button.pill) 0.75f else 1f,
                moveTo = { x, y ->
                    button.cx = x
                    button.cy = y
                },
                setRadius = { r -> button.radius = r },
            )
        }
        if (dpadRadius > 0f) {
            list += Movable(
                id = "DPAD",
                cx = dpadCx,
                cy = dpadCy,
                radius = dpadRadius,
                extentX = dpadRadius,
                extentY = dpadRadius,
                moveTo = { x, y ->
                    dpadCx = x
                    dpadCy = y
                },
                setRadius = { r -> dpadRadius = r },
            )
        }
        listOf(leftStick, rightStick).filter { it.radius > 0f }.forEach { stick ->
            val id = if (stick.side == Stick.LEFT) "STICK_L" else "STICK_R"
            list += Movable(
                id = id,
                cx = stick.cx,
                cy = stick.cy,
                radius = stick.radius,
                extentX = stick.radius,
                extentY = stick.radius,
                moveTo = { x, y ->
                    stick.cx = x
                    stick.cy = y
                    stick.centreKnob()
                },
                setRadius = { r ->
                    stick.radius = r
                    stick.centreKnob()
                },
            )
        }
        return list
    }

    /**
     * One of the two size steppers shown beside the selected control in edit mode.
     *
     * Buttons rather than a pinch: the controls being resized are frequently smaller than the
     * two fingers a pinch needs, and half of them (the D-pad, the sticks) sit under a gesture
     * that already means something else while editing. A stepper is also the only one of the
     * two that can be aimed at a 12mm target with one thumb.
     */
    private class Handle(val cx: Float, val cy: Float, val delta: Float, val plus: Boolean)

    /** Deliberately measured against the *unscaled* zone: these are chrome, and the moment
     *  they matter most is when the user has made a control too small to hit comfortably. */
    private fun handleRadius() = baseUnit * 0.075f

    private fun resizeHandles(): List<Handle> {
        if (!editMode) return emptyList()
        val movable = movables().firstOrNull { it.id == selectedId } ?: return emptyList()
        val r = handleRadius()
        val gap = r * 1.3f
        // Beside the control rather than above it: the pad's own rows are stacked vertically,
        // so there is reliably more free space to the sides than there is above or below.
        fun x(value: Float) = value.coerceIn(r, (width - r).coerceAtLeast(r))
        fun y(value: Float) = value.coerceIn(r, (height - r).coerceAtLeast(r))
        return listOf(
            Handle(x(movable.cx - movable.extentX - gap), y(movable.cy), -SCALE_STEP, plus = false),
            Handle(x(movable.cx + movable.extentX + gap), y(movable.cy), SCALE_STEP, plus = true),
        )
    }

    /**
     * Resizes the selected control, saving as it goes so a size can't be lost to the app being
     * killed with the editor open -- same contract as a drag.
     *
     * A control resized but never dragged has its current default position written out with
     * the new size, since a stored size and no stored position isn't representable. The cost
     * is that this one control stops tracking later changes to the default layout; that is a
     * fair trade for a control the user has explicitly customised, and it is exactly what
     * dragging it would have done anyway.
     */
    private fun resizeSelected(delta: Float) {
        val id = selectedId ?: return
        if (width <= 0 || height <= 0) return
        val movable = movables().firstOrNull { it.id == id } ?: return
        val existing = overrides[id]
        val current = existing?.scale ?: 1f
        val next = (current + delta).coerceIn(TouchLayouts.SCALE_MIN, TouchLayouts.SCALE_MAX)
        if (next == current) return
        overrides[id] = TouchLayouts.Position(
            x = existing?.x ?: (movable.cx / width),
            y = existing?.y ?: (movable.cy / height),
            scale = next,
        )
        layoutControls(width.toFloat(), height.toFloat())
        onLayoutChanged?.invoke(overrides.toMap())
        tick()
        invalidate()
    }

    /** Puts every control back where the default layout wants it and at its default size,
     *  and forgets the saved positions entirely rather than saving the defaults as if they
     *  were choices. Deliberately leaves the global size alone: that lives in Settings, is
     *  not part of "this system's layout", and someone whose hands need bigger buttons has
     *  not changed their mind by asking for the arrangement back. */
    fun resetLayout() {
        selectedId = null
        overrides.clear()
        layoutControls(width.toFloat(), height.toFloat())
        onLayoutChanged?.invoke(emptyMap())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (system == null) return

        if (editMode) {
            // The pad looks identical whether it is live or being edited, so say which.
            //
            // Along the bottom edge, which is the only full-width band no layout occupies:
            // every arrangement here deliberately stops its lowest row short of the bottom to
            // stay clear of the system's back/home gestures, and that gap is what this sits
            // in. Measuring off the top of the zone instead ran the text through the shoulder
            // row, and a third of the way down ran it through the face buttons -- the pad
            // fills the rest of its zone, so there is nowhere else it can go without landing
            // on a control. Sized against the unscaled zone so it stays readable, and stays
            // put, whatever the controls themselves have been scaled to.
            val previousSize = textPaint.textSize
            textPaint.textSize = baseUnit * 0.045f
            textPaint.color = 0xFF9AD1FF.toInt()
            canvas.drawText(
                context.getString(R.string.touch_controls_edit_hint),
                width / 2f,
                height * 0.97f,
                textPaint
            )
            textPaint.textSize = previousSize
            textPaint.color = 0xFFDDDDDD.toInt()
        }

        drawDpad(canvas)
        drawStick(canvas, leftStick)
        drawStick(canvas, rightStick)

        buttons.forEach { button ->
            if (button.radius <= 0f) return@forEach
            fillPaint.color = if (button.pressed) pressedFill else idleFill
            strokePaint.color = outlineFor(button.id)
            if (button.pill) {
                val rect = RectF(
                    button.cx - button.radius * 1.9f,
                    button.cy - button.radius * 0.75f,
                    button.cx + button.radius * 1.9f,
                    button.cy + button.radius * 0.75f,
                )
                val r = button.radius * 0.75f
                canvas.drawRoundRect(rect, r, r, fillPaint)
                canvas.drawRoundRect(rect, r, r, strokePaint)
            } else {
                canvas.drawCircle(button.cx, button.cy, button.radius, fillPaint)
                canvas.drawCircle(button.cx, button.cy, button.radius, strokePaint)
            }
            val glyph = button.glyph
            if (glyph != null) {
                drawGlyph(canvas, glyph, button.cx, button.cy, button.radius)
            } else {
                canvas.drawText(
                    button.label,
                    button.cx,
                    button.cy - (textPaint.ascent() + textPaint.descent()) / 2f,
                    textPaint
                )
            }
        }

        drawResizeHandles(canvas)
    }

    /** Drawn after every control so the handles are never buried under a neighbour the
     *  selected control happens to overlap. */
    private fun drawResizeHandles(canvas: Canvas) {
        val handles = resizeHandles()
        if (handles.isEmpty()) return
        val r = handleRadius()
        handles.forEach { handle ->
            fillPaint.color = selectedOutline
            canvas.drawCircle(handle.cx, handle.cy, r, fillPaint)
            // A plus and a minus, drawn rather than typed: at this size the glyphs need to be
            // optically centred on the circle, which a text baseline does not give you.
            val arm = r * 0.42f
            glyphPaint.color = 0xFF0E1A24.toInt()
            glyphPaint.strokeWidth = r * 0.18f
            canvas.drawLine(handle.cx - arm, handle.cy, handle.cx + arm, handle.cy, glyphPaint)
            if (handle.plus) {
                canvas.drawLine(handle.cx, handle.cy - arm, handle.cx, handle.cy + arm, glyphPaint)
            }
        }
    }

    /**
     * All four shapes are drawn around one nominal radius so they read as a matched set,
     * which the Unicode characters did not: the cross came out visibly larger than the
     * circle and triangle because the font designs them at different sizes.
     *
     * Stroke-only, matching the pad's outlined style, with round caps so the triangle's
     * points and the cross's ends don't look chopped off at this size.
     */
    private fun drawGlyph(canvas: Canvas, glyph: Glyph, cx: Float, cy: Float, buttonRadius: Float) {
        val size = buttonRadius * 0.46f * glyph.scale
        glyphPaint.color = glyph.colour
        glyphPaint.strokeWidth = buttonRadius * 0.115f

        when (glyph) {
            Glyph.CIRCLE -> canvas.drawCircle(cx, cy, size, glyphPaint)

            Glyph.SQUARE -> canvas.drawRect(cx - size, cy - size, cx + size, cy + size, glyphPaint)

            Glyph.CROSS -> {
                val arm = size * 0.95f
                canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, glyphPaint)
                canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, glyphPaint)
            }

            Glyph.TRIANGLE -> {
                // Equilateral, sitting on its circumcircle so it stays centred in the button
                // rather than looking bottom-heavy the way a bounding-box fit would.
                glyphPath.reset()
                glyphPath.moveTo(cx, cy - size)
                glyphPath.lineTo(cx + size * 0.866f, cy + size * 0.5f)
                glyphPath.lineTo(cx - size * 0.866f, cy + size * 0.5f)
                glyphPath.close()
                canvas.drawPath(glyphPath, glyphPaint)
            }
        }
    }

    private fun drawDpad(canvas: Canvas) {
        if (dpadRadius <= 0f) return
        val arm = dpadRadius
        val thickness = dpadRadius * 0.62f
        strokePaint.color = outlineFor("DPAD")

        fun bar(rect: RectF) {
            val r = thickness * 0.25f
            canvas.drawRoundRect(rect, r, r, fillPaint)
            canvas.drawRoundRect(rect, r, r, strokePaint)
        }

        fillPaint.color = idleFill
        bar(RectF(dpadCx - arm, dpadCy - thickness / 2f, dpadCx + arm, dpadCy + thickness / 2f))
        bar(RectF(dpadCx - thickness / 2f, dpadCy - arm, dpadCx + thickness / 2f, dpadCy + arm))

        // Pressed directions are painted over the cross rather than instead of it, so a
        // diagonal shows both arms lit.
        fillPaint.color = pressedFill
        val half = thickness / 2f
        if (KeyEvent.KEYCODE_DPAD_LEFT in dpadPressed) {
            canvas.drawRect(dpadCx - arm, dpadCy - half, dpadCx - half, dpadCy + half, fillPaint)
        }
        if (KeyEvent.KEYCODE_DPAD_RIGHT in dpadPressed) {
            canvas.drawRect(dpadCx + half, dpadCy - half, dpadCx + arm, dpadCy + half, fillPaint)
        }
        if (KeyEvent.KEYCODE_DPAD_UP in dpadPressed) {
            canvas.drawRect(dpadCx - half, dpadCy - arm, dpadCx + half, dpadCy - half, fillPaint)
        }
        if (KeyEvent.KEYCODE_DPAD_DOWN in dpadPressed) {
            canvas.drawRect(dpadCx - half, dpadCy + half, dpadCx + half, dpadCy + arm, fillPaint)
        }
    }

    private fun drawStick(canvas: Canvas, stick: StickState) {
        if (stick.radius <= 0f) return
        fillPaint.color = idleFill
        strokePaint.color = outlineFor(if (stick.side == Stick.LEFT) "STICK_L" else "STICK_R")
        canvas.drawCircle(stick.cx, stick.cy, stick.radius, fillPaint)
        canvas.drawCircle(stick.cx, stick.cy, stick.radius, strokePaint)
        fillPaint.color = if (stick.active) pressedFill else knobFill
        canvas.drawCircle(stick.knobX, stick.knobY, stick.radius * 0.5f, fillPaint)
        canvas.drawCircle(stick.knobX, stick.knobY, stick.radius * 0.5f, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (system == null) return false
        if (editMode) return handleEditTouch(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                claim(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    when (val held = pointerTargets[id]) {
                        DpadHandle -> updateDpad(event.getX(index), event.getY(index))
                        is StickState -> updateStick(held, event.getX(index), event.getY(index))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                release(event.getPointerId(index))
                if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) releaseAll()
            }
        }
        invalidate()
        return true
    }

    /** Single-pointer on purpose: two fingers dragging two controls at once is a fiddly thing
     *  to do by accident and a useless one to do deliberately, so the first finger down owns
     *  the drag until it lifts. */
    private var draggingId: String? = null
    private var dragPointerId = -1

    private fun handleEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                // Handles are tested before the controls themselves: they are drawn on top,
                // they sit close enough to the selected control to overlap its touch reach,
                // and a tap that lands on one is unambiguously meant for it.
                val handleR = handleRadius()
                val handle = resizeHandles()
                    .firstOrNull { hypot(x - it.cx, y - it.cy) <= handleR * 1.35f }
                if (handle != null) {
                    resizeSelected(handle.delta)
                    return true
                }
                // Nearest centre rather than first hit: in edit mode the controls may have been
                // dragged into overlapping positions, and "the one I aimed at" is the closer of
                // the two far more often than it is the earlier one in the list.
                val target = movables()
                    .filter { hypot(x - it.cx, y - it.cy) <= it.radius * 1.6f }
                    .minByOrNull { hypot(x - it.cx, y - it.cy) }
                if (target == null) {
                    // Tapping the empty background puts the handles away, which is the only
                    // way to see the pad as it will actually look without leaving edit mode.
                    if (selectedId != null) {
                        selectedId = null
                        invalidate()
                    }
                    return false
                }
                // Selecting on the way down rather than on a tap that turns out not to be a
                // drag: the handles appear the instant a control is touched, so moving one and
                // then resizing it is a single uninterrupted gesture.
                selectedId = target.id
                draggingId = target.id
                dragPointerId = event.getPointerId(0)
                tick()
            }
            MotionEvent.ACTION_MOVE -> {
                val id = draggingId ?: return true
                val index = event.findPointerIndex(dragPointerId)
                if (index < 0) return true
                moveControl(id, event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingId != null) onLayoutChanged?.invoke(overrides.toMap())
                draggingId = null
                dragPointerId = -1
            }
        }
        invalidate()
        return true
    }

    private fun moveControl(id: String, rawX: Float, rawY: Float) {
        val movable = movables().firstOrNull { it.id == id } ?: return
        // Clamped so a control can never be dragged off the edge and become unreachable --
        // there would be no way to get it back short of a full reset. Measured against the
        // drawn extents rather than the radius so a resized control, and a pill (nearly 2x
        // its radius wide), both stop at the point where they would start to leave the zone.
        val x = rawX.coerceIn(movable.extentX, (width - movable.extentX).coerceAtLeast(movable.extentX))
        val y = rawY.coerceIn(movable.extentY, (height - movable.extentY).coerceAtLeast(movable.extentY))
        movable.moveTo(x, y)
        if (width > 0 && height > 0) {
            // Carries the existing size through: a drag moves a control, it does not reset it.
            overrides[id] = TouchLayouts.Position(
                x = x / width,
                y = y / height,
                scale = overrides[id]?.scale ?: 1f,
            )
        }
    }

    private fun claim(pointerId: Int, x: Float, y: Float) {
        // Buttons win over the sticks and D-pad when hit areas overlap: they are smaller, and a
        // stray nudge of a stick is cheaper than a missed button.
        val button = buttons.firstOrNull { it.radius > 0f && it.contains(x, y) }
        if (button != null) {
            pointerTargets[pointerId] = button
            if (!button.pressed) {
                button.pressed = true
                tick()
                onKey?.invoke(KeyEvent.ACTION_DOWN, button.keyCode)
            }
            return
        }

        val stick = listOf(leftStick, rightStick).firstOrNull {
            it.radius > 0f && hypot(x - it.cx, y - it.cy) <= it.radius * 1.15f
        }
        if (stick != null) {
            pointerTargets[pointerId] = stick
            updateStick(stick, x, y)
            return
        }

        if (dpadRadius > 0f && abs(x - dpadCx) <= dpadRadius * 1.4f &&
            abs(y - dpadCy) <= dpadRadius * 1.4f
        ) {
            pointerTargets[pointerId] = DpadHandle
            updateDpad(x, y)
        }
    }

    /** Generous by design: a touch target that matches the drawn circle exactly is much
     *  harder to hit reliably than it looks, especially once the zone is dragged small. */
    private fun Button.contains(x: Float, y: Float): Boolean {
        val reach = radius * if (pill) 2.2f else 1.35f
        val vertical = radius * if (pill) 1.1f else 1.35f
        return abs(x - cx) <= reach && abs(y - cy) <= vertical
    }

    private fun updateDpad(x: Float, y: Float) {
        val dx = x - dpadCx
        val dy = y - dpadCy
        // Diagonals matter (menus, isometric games), so each axis is tested separately rather
        // than picking a single nearest direction.
        val deadzone = dpadRadius * 0.28f
        val wanted = mutableSetOf<Int>()
        if (dx < -deadzone) wanted += KeyEvent.KEYCODE_DPAD_LEFT
        if (dx > deadzone) wanted += KeyEvent.KEYCODE_DPAD_RIGHT
        if (dy < -deadzone) wanted += KeyEvent.KEYCODE_DPAD_UP
        if (dy > deadzone) wanted += KeyEvent.KEYCODE_DPAD_DOWN
        applyDpad(wanted)
    }

    private fun applyDpad(wanted: Set<Int>) {
        val newlyPressed = wanted - dpadPressed
        (dpadPressed - wanted).forEach { onKey?.invoke(KeyEvent.ACTION_UP, it) }
        newlyPressed.forEach { onKey?.invoke(KeyEvent.ACTION_DOWN, it) }
        // One tick per change of direction, not one per direction: rolling round the D-pad
        // shouldn't feel twice as strong on the diagonals.
        if (newlyPressed.isNotEmpty()) tick()
        dpadPressed.clear()
        dpadPressed += wanted
    }

    private fun tick() {
        val strength = hapticStrength
        if (strength == HapticStrength.OFF) return
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Where the motor can't vary amplitude, every level asks for DEFAULT_AMPLITUDE and
            // the levels differ by duration alone -- coarser, but still tells them apart.
            val amplitude = if (vibrator.hasAmplitudeControl()) {
                strength.amplitude
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrator.vibrate(VibrationEffect.createOneShot(strength.durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(strength.durationMs)
        }
    }

    private fun updateStick(stick: StickState, x: Float, y: Float) {
        val dx = x - stick.cx
        val dy = y - stick.cy
        val distance = hypot(dx, dy)
        val clamped = if (distance > stick.radius) stick.radius / distance else 1f
        stick.knobX = stick.cx + dx * clamped
        stick.knobY = stick.cy + dy * clamped
        stick.active = true
        onAnalog?.invoke(
            stick.side,
            ((stick.knobX - stick.cx) / stick.radius).coerceIn(-1f, 1f),
            ((stick.knobY - stick.cy) / stick.radius).coerceIn(-1f, 1f),
        )
    }

    private fun release(pointerId: Int) {
        when (val held = pointerTargets.remove(pointerId)) {
            is Button -> {
                held.pressed = false
                onKey?.invoke(KeyEvent.ACTION_UP, held.keyCode)
            }
            DpadHandle -> applyDpad(emptySet())
            is StickState -> centreStick(held)
        }
    }

    /** Belt and braces for ACTION_UP/ACTION_CANCEL: the last pointer leaving should never
     *  leave a button stuck down or a stick deflected, which is the one failure here a player
     *  would really feel. */
    private fun releaseAll() {
        pointerTargets.keys.toList().forEach { release(it) }
        pointerTargets.clear()
        buttons.filter { it.pressed }.forEach {
            it.pressed = false
            onKey?.invoke(KeyEvent.ACTION_UP, it.keyCode)
        }
        applyDpad(emptySet())
        centreStick(leftStick)
        centreStick(rightStick)
    }

    private fun centreStick(stick: StickState) {
        if (stick.radius <= 0f) return
        stick.centreKnob()
        onAnalog?.invoke(stick.side, 0f, 0f)
    }

    /** Called when the pad is hidden or the game goes away — anything still held has to be
     *  let go, or the core keeps seeing the press forever. */
    fun releaseEverything() {
        releaseAll()
        invalidate()
    }
}
