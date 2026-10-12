package com.apk.claw.android.floating

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.octopus_mobile.VoiceInput
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.ui.compose.screen.ChatAgentBridge
import com.apk.claw.android.ui.desktop.CharacterRegistry
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks

/**
 * 统一悬浮助手球 —— 章鱼 + 语音 + 无障碍 三合一。
 *
 * - **待机**：🐙 章鱼圆球，颜色表示无障碍状态（core 青=开，breach 红=关），可拖拽
 * - **点击**：展开语音面板（脉冲动画 + 实时识别 + 停止），识别完自动发给 AI
 * - **长按**：跳转无障碍设置页
 * - 任务运行时仍由 [LiveControlOverlay] 在顶部显示控制条
 */
object FloatingCircleManager {

    private const val TAG = "octopus_float"
    private const val IDLE_OFFSET_X_DP = 16
    private const val IDLE_OFFSET_Y_DP = 300
    private const val LISTENING_OFFSET_Y_DP = 100
    private const val AVATAR_SIZE_DP = 50
    private const val RING_MARGIN_DP = 3
    private val main = Handler(Looper.getMainLooper())
    private var showing = false
    private var listening = false
    private var voiceInput: VoiceInput? = null

    private val cSuccess = Color.parseColor("#6FE3F0")
    private val cError = Color.parseColor("#FF6B78")
    private val cPrimary = Color.parseColor("#6FE3F0")
    private val cDanger = Color.parseColor("#C42638")
    private val cBg = Color.parseColor("#171A1F")
    private val cText = Color.parseColor("#EEF1F4")

    private fun dp(v: Int): Int = (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    var onFloatClick: () -> Unit = {}

    private fun cancelRunning() {
        runCatching { ClawApplication.appViewModelInstance.cancelCurrentTask() }
    }

    // ── 公开 API ──

    fun show(application: Application, x: Int? = null, y: Int? = null) {
        if (!Settings.canDrawOverlays(application)) return
        main.post {
            if (showing) return@post
            voiceInput = VoiceInput(application)

            EasyFloat.with(application)
                .setLayout(buildIdleView(application))
                .setShowPattern(ShowPattern.ALL_TIME)
                .setSidePattern(SidePattern.DEFAULT)
                .setGravity(Gravity.END or Gravity.BOTTOM, dp(IDLE_OFFSET_X_DP), dp(IDLE_OFFSET_Y_DP))
                .setDragEnable(true)
                .hasEditText(false)
                .setTag(TAG)
                .registerCallbacks(object : OnFloatCallbacks {
                    override fun createdResult(isCreated: Boolean, msg: String?, view: View?) { showing = isCreated }
                    override fun dismiss() { showing = false; cleanup() }
                    override fun hide(view: View) { showing = false }
                    override fun show(view: View) { showing = true }
                    override fun drag(view: View, event: MotionEvent) {}
                    override fun dragEnd(view: View) {}
                    override fun touchEvent(view: View, event: MotionEvent) {}
                })
                .show()
        }
    }

    fun hide() {
        LiveControlOverlay.hide()
        if (showing) {
            runCatching { EasyFloat.dismiss(TAG) }
            showing = false
            cleanup()
        }
    }

    fun isShowing(): Boolean = showing

    fun setIdleState() = LiveControlOverlay.hide()

    /** 更新外圈颜色（无障碍状态变化时调用；头像不变，只换状态环） */
    fun updateOctopusState() {
        if (listening) return // 聆听中不切换视图
        main.post {
            val view = EasyFloat.getFloatView(TAG) ?: return@post
            view.background = ringDrawable(a11yRingColor())
        }
    }

    fun showTaskNotify(taskText: String, channel: Channel) {
        LiveControlOverlay.show("📨 " + taskText.take(40)) { cancelRunning() }
    }

    fun setRunningState(round: Int, channel: Channel) {
        LiveControlOverlay.show(ClawApplication.instance.getString(R.string.floating_circle_running_state)) { cancelRunning() }
    }

    fun setSuccessState() = LiveControlOverlay.finish(true, ClawApplication.instance.getString(R.string.floating_circle_success_state))
    fun setErrorState() = LiveControlOverlay.finish(false, ClawApplication.instance.getString(R.string.floating_circle_error_state))

    // ── 内部实现 ──

    private fun cleanup() {
        voiceInput?.destroy()
        voiceInput = null
        listening = false
    }

    /** 无障碍状态环颜色:core 青=开、breach 红=关。 */
    private fun a11yRingColor(): Int = if (ClawAccessibilityService.isRunning()) cSuccess else cError

    /** 圆形状态环(深底兜底 + 状态色描边),头像叠在内层。 */
    private fun ringDrawable(ringColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(cBg)
        cornerRadius = dp(28).toFloat()
        setStroke(dp(3), ringColor)
    }

    /**
     * 助手球头像 = 当前角色头像(与桌面 agent、控制中心同一来源 [CharacterRegistry]);
     * 圆形裁切、内缩 3dp 露出外圈状态环。八爪鱼图标只留给应用外品牌位(桌面图标/通知/闪屏)。
     */
    private fun buildAvatarView(app: android.content.Context, size: Int): View =
        ShapeableImageView(app).apply {
            setImageResource(CharacterRegistry.current.avatarRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes((size / 2).toFloat())
                .build()
        }

    /** 待机助手球:角色头像 + 无障碍状态外圈,可拖拽/点击(语音)/长按(无障碍设置)。 */
    private fun buildIdleView(app: android.content.Context): View {
        return FrameLayout(app).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
            background = ringDrawable(a11yRingColor())
            elevation = dp(6).toFloat()

            addView(buildAvatarView(app, dp(AVATAR_SIZE_DP)).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                    val m = dp(RING_MARGIN_DP); setMargins(m, m, m, m)
                }
            })

            // 点击 → 语音
            setOnClickListener { startVoiceListening() }
            // 长按 → 无障碍设置
            setOnLongClickListener {
                app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.widget.Toast.makeText(app, app.getString(R.string.floating_accessibility_hint), android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    /** 开始语音聆听 */
    private fun startVoiceListening() {
        val app = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(app, app.getString(R.string.voice_assistant_need_mic), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        listening = true
        switchView { buildListeningPanel(app) }
        EasyFloat.updateFloat(TAG, dp(IDLE_OFFSET_X_DP), dp(LISTENING_OFFSET_Y_DP))

        voiceInput?.start(
            onPartial = { text ->
                main.post {
                    val tv = EasyFloat.getFloatView(TAG)?.findViewById<TextView>(R.id.vaPartialText)
                    tv?.text = text.ifBlank { app.getString(R.string.voice_assistant_listening) }
                }
            },
            onResult = { text ->
                main.post {
                    listening = false
                    if (text.isNotBlank()) {
                        ChatAgentBridge.run(
                            text,
                            onTool = { _, _, _, _ -> },
                            onText = { },
                            onDone = { switchToIdle() },
                            onError = { switchToIdle() },
                        )
                        // 短暂显示识别结果后切回
                        val tv = EasyFloat.getFloatView(TAG)?.findViewById<TextView>(R.id.vaPartialText)
                        tv?.text = text.take(60)
                        main.postDelayed({ switchToIdle() }, 1200)
                    } else {
                        switchToIdle()
                    }
                }
            },
            onError = { err ->
                main.post {
                    listening = false
                    if (err.isNotEmpty()) android.widget.Toast.makeText(app, err, android.widget.Toast.LENGTH_SHORT).show()
                    switchToIdle()
                }
            },
        )
    }

    /** 语音聆听面板 */
    private fun buildListeningPanel(app: android.content.Context): View {
        return LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(dp(260), FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(20), dp(24), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(cBg)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.argb(40, 255, 255, 255))
            }
            elevation = dp(8).toFloat()

            // 🐙 + 脉冲圆点
            addView(LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

                addView(buildAvatarView(app, dp(24)).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                        marginEnd = dp(8)
                    }
                })

                for (i in 0..2) {
                    addView(View(app).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                            marginStart = dp(3)
                            marginEnd = dp(3)
                        }
                        background = GradientDrawable().apply {
                            setColor(cPrimary)
                            cornerRadius = dp(4).toFloat()
                        }
                        val anim = android.animation.ValueAnimator.ofFloat(0.5f, 1.3f, 0.5f).apply {
                            duration = 600L + i * 200L
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            addUpdateListener { v ->
                                val s = v.animatedValue as Float
                                scaleX = s; scaleY = s; alpha = 0.5f + (s - 0.5f)
                            }
                            startDelay = i * 150L
                        }
                        tag = "pulse_$i"
                        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) { anim.start() }
                            override fun onViewDetachedFromWindow(v: View) { anim.cancel() }
                        })
                    })
                }
            })

            // 实时识别文本
            addView(TextView(app).apply {
                id = R.id.vaPartialText
                text = app.getString(R.string.voice_assistant_listening)
                textSize = 15f
                setTextColor(cText)
                gravity = Gravity.CENTER
                maxLines = 3
                setLineSpacing(0f, 1.2f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                }
            })

            // 停止按钮
            addView(TextView(app).apply {
                text = app.getString(R.string.voice_assistant_stop)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(cDanger)
                    cornerRadius = dp(18).toFloat()
                }
                setPadding(dp(24), dp(10), dp(24), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(14)
                }
                setOnClickListener { voiceInput?.stop() }
            })
        }
    }

    /** 切换视图的通用方法 */
    private fun switchView(builder: (android.content.Context) -> View) {
        val view = EasyFloat.getFloatView(TAG) ?: return
        val parent = view.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(view)
        parent.removeView(view)
        parent.addView(builder(ClawApplication.instance), index)
    }

    /** 切回待机章鱼球 */
    private fun switchToIdle() {
        if (listening) return
        val app = ClawApplication.instance
        switchView { buildIdleView(it) }
        EasyFloat.updateFloat(TAG, dp(IDLE_OFFSET_X_DP), dp(IDLE_OFFSET_Y_DP))
    }
}
