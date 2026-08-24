package com.tacoboy

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline

/**
 * Wires up the drag-to-adjust black-zone boundary shared by every
 * clamp-aware screen (TacoBoyActivity's game view, RomLibraryActivity's
 * ROM list) so the Pocket Taco clamp position only has to be set once and
 * applies everywhere — no screen should ever require removing the
 * controller to reach content hidden below the clamp line.
 */
class BoundaryController(
    private val context: Context,
    private val guideline: Guideline,
    occlusionZone: View,
    boundaryHandle: View,
    private val hintView: View? = null
) {
    private var dragStartRawY = 0f
    private var dragStartPercent = 0f

    /** Tightens the lower limit while something below the boundary needs a minimum height --
     *  currently only the on-screen pad. Null means the shared MAX_PERCENT applies. */
    private var maxPercentOverride: Float? = null

    init {
        refresh()
        occlusionZone.setOnTouchListener { _, _ -> true }
        boundaryHandle.setOnTouchListener(::onHandleTouch)
        if (hintView != null && !TacoBoyPrefs.hasSeenBoundaryHint(context)) {
            hintView.visibility = View.VISIBLE
        }
    }

    /**
     * Re-reads the persisted boundary. The value can change on another
     * clamp-aware screen (e.g. dragged in RomLibraryActivity) without this
     * Activity being recreated, so call this from onResume too, not just
     * once at setup.
     */
    fun refresh() {
        applyBoundary(TacoBoyPrefs.getBoundaryPercent(context))
    }

    /** Re-clamps immediately, so turning the pad on with the boundary already dragged past
     *  the limit pulls it back rather than waiting for the next drag. */
    fun setMaxPercentOverride(percent: Float?) {
        maxPercentOverride = percent
        applyBoundary(currentGuidelinePercent())
    }

    private fun applyBoundary(percent: Float) {
        val max = minOf(TacoBoyPrefs.MAX_PERCENT, maxPercentOverride ?: TacoBoyPrefs.MAX_PERCENT)
        guideline.setGuidelinePercent(percent.coerceIn(TacoBoyPrefs.MIN_PERCENT, max))
    }

    private fun dismissHint() {
        if (hintView == null || hintView.visibility == View.GONE) return
        hintView.visibility = View.GONE
        TacoBoyPrefs.setBoundaryHintShown(context)
    }

    private fun currentGuidelinePercent(): Float {
        return (guideline.layoutParams as ConstraintLayout.LayoutParams).guidePercent
    }

    private fun onHandleTouch(view: View, event: MotionEvent): Boolean {
        val rootHeight = (guideline.parent as View).height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawY = event.rawY
                dragStartPercent = currentGuidelinePercent()
                dismissHint()
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaPercent = (event.rawY - dragStartRawY) / rootHeight
                applyBoundary(dragStartPercent + deltaPercent)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                TacoBoyPrefs.setBoundaryPercent(context, currentGuidelinePercent())
            }
        }
        return true
    }
}
