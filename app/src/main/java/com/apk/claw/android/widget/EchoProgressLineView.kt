package com.apk.claw.android.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.apk.claw.android.R

/**
 * The View-system twin of the Compose `EchoProgressLine` (BRAND.md: no spinners; loading is one thin `core`
 * line). A 40%-wide segment in [lineColor] slides across a hairline [trackColor] track every 1.6 s on the brand
 * curve. With animations turned off (animator duration scale 0) the segment rests at the start of the track.
 *
 * XML: `app:echoLineColor` / `app:echoTrackColor`; the default height is 2 dp.
 */
class EchoProgressLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var lineColor: Int = ContextCompat.getColor(context, R.color.colorSuccessPrimary)
        set(value) { field = value; invalidate() }

    var trackColor: Int = ContextCompat.getColor(context, R.color.colorBorderBase)
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var position = STILL_POSITION
    private val defaultHeight = (DEFAULT_HEIGHT_DP * resources.displayMetrics.density).toInt()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = PathInterpolator(EASE_X1, EASE_Y1, EASE_X2, EASE_Y2)
        addUpdateListener {
            position = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.EchoProgressLineView).apply {
            lineColor = getColor(R.styleable.EchoProgressLineView_echoLineColor, lineColor)
            trackColor = getColor(R.styleable.EchoProgressLineView_echoTrackColor, trackColor)
            recycle()
        }
        contentDescription = context.getString(R.string.common_loading)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(defaultHeight, heightMeasureSpec),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncAnimation()
    }

    private fun syncAnimation() {
        val run = isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
        when {
            run && !animator.isStarted -> animator.start()
            !run && animator.isStarted -> { animator.cancel(); position = STILL_POSITION; invalidate() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = trackColor
        canvas.drawRect(0f, 0f, w, h, paint)
        val segment = w * SEGMENT_FRACTION
        val start = -segment + position * segment * TRAVEL_IN_SEGMENTS
        var left = start.coerceIn(0f, w)
        var right = (start + segment).coerceIn(0f, w)
        if (right <= left) return
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            val l = w - right
            right = w - left
            left = l
        }
        paint.color = lineColor
        canvas.drawRect(left, 0f, right, h, paint)
    }

    private companion object {
        const val DEFAULT_HEIGHT_DP = 2
        const val CYCLE_MS = 1600L
        const val SEGMENT_FRACTION = 0.4f
        const val TRAVEL_IN_SEGMENTS = 3.5f
        const val STILL_POSITION = 1f / TRAVEL_IN_SEGMENTS

        // cubic-bezier(0.2, 0.8, 0.2, 1)
        const val EASE_X1 = 0.2f
        const val EASE_Y1 = 0.8f
        const val EASE_X2 = 0.2f
        const val EASE_Y2 = 1f
    }
}
