package com.apk.claw.android.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import com.apk.claw.android.R
import androidx.core.content.withStyledAttributes

class KButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var bgColor: Int = context.getColor(R.color.colorBrandPrimary)
    private var borderColor: Int = 0x00000000
    private var cornerRadiusDp: Float = 12f

    init {
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.colorBrandOnPrimary))
        if (attrs?.getAttributeValue("http://schemas.android.com/apk/res/android", "textSize") == null) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        isClickable = true
        isFocusable = true
        // 代码里 WRAP_CONTENT 创建的按钮没有内边距，会被压成一条文字高的细条（插件管理「从文件安装」、
        // 投屏「开始投屏」）。给一个触控高度下限和左右内边距；XML 里写死高度/内边距的用法不受影响。
        minHeight = dp(MIN_HEIGHT_DP).toInt()
        if (paddingStart == 0 && paddingEnd == 0) {
            val horizontal = dp(H_PADDING_DP).toInt()
            setPaddingRelative(horizontal, paddingTop, horizontal, paddingBottom)
        }

        attrs?.let {
            context.withStyledAttributes(it, R.styleable.KButton) {
                bgColor = getColor(R.styleable.KButton_btnBackground, bgColor)
                setTextColor(getColor(R.styleable.KButton_btnTextColor, currentTextColor))
                val defaultRadiusPx = dp(12f)
                cornerRadiusDp = px2dp(getDimension(R.styleable.KButton_btnCornerRadius, defaultRadiusPx))
                borderColor = getColor(R.styleable.KButton_btnBorderColor, borderColor)
            }
        }

        applyBackground()
    }

    fun setBgColor(color: Int) {
        bgColor = color
        applyBackground()
    }

    fun setBorderColor(color: Int) {
        borderColor = color
        applyBackground()
    }

    fun setCornerRadius(radiusDp: Float) {
        cornerRadiusDp = radiusDp
        applyBackground()
    }

    private fun applyBackground() {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(bgColor)
            setCornerRadius(dp(cornerRadiusDp))
            setStroke(dp(1f).toInt(), borderColor)
        }
        background = shape
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun px2dp(px: Float): Float = px / resources.displayMetrics.density

    private companion object {
        const val MIN_HEIGHT_DP = 44f
        const val H_PADDING_DP = 16f
    }
}
