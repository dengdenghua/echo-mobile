package com.apk.claw.android.ui.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import kotlin.math.roundToInt
import com.apk.claw.android.R
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.utils.KVUtils

/**
 * 「本地虚拟电脑」皮肤 —— ECHO Graphite 调色板(analysis/echo-brand-kit/tokens.json),TV 桌面恒为深色:
 * 石墨扁平面板(surface #101216 / ground #090A0C / surface-raised #171A1F)+ Echo Core 青强调(#6FE3F0)
 * + 发丝线层次,**无 blur / 无 shadow / 无渐变辉光,纯扁平**。壁纸打底,面板悬浮。
 *
 * 放在独立文件里,尽量少改并行编辑中的 [DesktopActivity]。
 */
internal object Holo {
    val Accent = Color(0xFF6FE3F0)          // core:焦点、选中、运行中
    val AccentDim = Color(0xFF9AA3AE)       // ink-muted:空闲/离线/次要标签(不再是半透明强调色)
    val Panel = Color(0xFF101216)           // surface:面板底(不透明)
    val AvatarBg = Color(0xFF090A0C)        // ground:更深(头像/壁纸侧)
    val Surface2 = Color(0xFF171A1F)        // surface-raised:气泡/标题栏
    val Border = Color(0x14FFFFFF)          // line:白 0.08 发丝线
    val BorderStrong = Color(0x29FFFFFF)    // line-strong:白 0.16
    val TextHud = Color(0xFFEEF1F4)         // ink:主文本
    val TextSecondary = Color(0xFF9AA3AE)   // ink-muted:次要文本
    val Live = Color(0xFFFF6B78)            // breach:直播/录音/危险(必须与 core 区分)
    val OnAccent = Color(0xFF061014)        // on-core:core 填充上的文字/图标
    val Warning = Color(0xFFF2B84B)         // warning:连接中、需要确认
    val Action = Color(0xFFEEF1F4)          // action:主动作填充(单色雪白,不是彩色)
    val OnAction = Color(0xFF0A0B0D)        // on-action:主动作上的文字/图标

    // 壁纸:OpenRoom 用图片壁纸;这里用 ground → surface 扁平渐变兜底(无网格辉光)
    val bgBrush = Brush.verticalGradient(
        listOf(Color(0xFF090A0C), Color(0xFF101216)),
    )
}

/** 扁平面板:深灰底 + 白 0.06 描边 + 圆角(学 OpenRoom,12px 卡片)。 */
fun Modifier.holoGlass(corner: Dp = 12.dp, fillAlpha: Float = 1f): Modifier =
    this.clip(RoundedCornerShape(corner))
        .background(if (fillAlpha >= 1f) Holo.Panel else Holo.Panel.copy(alpha = fillAlpha))
        .border(1.dp, Holo.Border, RoundedCornerShape(corner))

/**
 * 全息焦点修饰器 —— D-pad/方向键导航时,焦点元素自动高亮:
 * 聚焦 = core 青 2dp 边框 + 轻微放大(1.03x);失焦 = 原样。
 * 触屏点击同样触发 focus,视觉一致。
 */
private const val FOCUS_SCALE = 1.10f   // 焦点放大倍数(TV 遥控可见)
private const val FOCUS_SHADOW = 18f     // 焦点投影高度(浮起感)

@Composable
fun Modifier.holoFocus(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // 焦点放大 + 投影「浮起」(spring 物理动画),3 米外电视遥控导航也能一眼看清当前选中。
    val scale by animateFloatAsState(
        targetValue = if (isFocused) FOCUS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "holoFocusScale"
    )
    val bw by animateDpAsState(
        targetValue = if (isFocused) 3.dp else 1.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "holoFocusBorder"
    )
    return this
        .scale(scale)
        .shadow(if (isFocused) FOCUS_SHADOW.dp else 0.dp, shape)
        .border(bw, if (isFocused) Holo.Accent else Holo.Border, shape)
        .focusable(interactionSource = interactionSource)
}

/**
 * 全屏壁纸:石墨底 + 几道光带(两道 core 青,一道中性 ink-muted;多遍描边伪辉光)。
 * 纯 Canvas 画,无素材依赖。面板悬浮其上。
 */
@Composable
fun HoloBackground(modifier: Modifier = Modifier) {
    val cyan = Holo.Accent                  // core
    val magenta = Holo.TextSecondary        // 原品红光带 → ink-muted(装饰不着色)
    Box(modifier.fillMaxSize().background(Holo.bgBrush)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun streak(pts: List<androidx.compose.ui.geometry.Offset>, color: Color, base: Float) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    for (i in 1 until pts.size) {
                        val p0 = pts[i - 1]; val p1 = pts[i]
                        val mx = (p0.x + p1.x) / 2f
                        cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                    }
                }
                // 由粗到细多遍描边:粗而淡 = 辉光,细而亮 = 光芯
                listOf(base * 7f to 0.05f, base * 3.5f to 0.10f, base * 1.6f to 0.45f, base * 0.6f to 0.95f)
                    .forEach { (wd, a) ->
                        drawPath(
                            path, color.copy(alpha = a),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = wd, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            ),
                        )
                    }
            }
            fun o(fx: Float, fy: Float) = androidx.compose.ui.geometry.Offset(fx * w, fy * h)
            val u = h * 0.006f
            streak(listOf(o(-0.05f, 0.58f), o(0.22f, 0.30f), o(0.48f, 0.52f), o(0.80f, 0.18f), o(1.08f, 0.40f)), cyan, u)
            streak(listOf(o(-0.05f, 0.78f), o(0.28f, 0.62f), o(0.55f, 0.84f), o(0.88f, 0.50f), o(1.08f, 0.70f)), magenta, u)
            streak(listOf(o(0.35f, 1.06f), o(0.58f, 0.70f), o(0.80f, 0.88f), o(1.06f, 0.55f)), cyan, u * 0.7f)
        }
    }
}

/** 顶部 HUD 直播条:● LIVE(空闲=IDLE)+ 当前 URL + 连接态 + 时钟,等宽字体。 */
@Composable
fun HudStrip(
    urlOrIdle: String,
    live: Boolean,
    connLabel: String,
    connColor: Color,
    timeText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Holo.Panel.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveDot(active = live)
        Text(
            if (live) "LIVE" else "IDLE",
            color = if (live) Holo.Live else Holo.AccentDim,
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        )
        Text(
            urlOrIdle,
            color = Holo.TextHud, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HoloDot(connColor)
        Text(connLabel, color = Holo.TextHud, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(timeText, color = Holo.Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LiveDot(active: Boolean) {
    val alpha by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "liveAlpha",
    )
    HoloDot(if (active) Holo.Live.copy(alpha = alpha) else Holo.AccentDim)
}

@Composable
fun HoloDot(color: Color) {
    Canvas(Modifier.size(7.dp)) { drawCircle(color) }
}

/**
 * 可拖拽全息浮动窗口:半透明玻璃框 + 标题栏(拖动)+ 最小化/最大化/关闭。
 * 焦点高亮:聚焦时 core 青边框 + 轻微放大,呼应科幻 HUD。
 * 标题栏按住拖动移动窗口;点窗口任意处触发 onFocus(供上层置顶)。
 *  - [onMinimize] 非空时显示「—」:交给上层(收进任务栏)。
 *  - 「□/❐」最大化 / 还原:窗口内部状态,最大化时铺满桌面区(留顶栏/底部输入空间)。
 */
@Composable
fun HoloWindow(
    title: String,
    startX: Dp,
    startY: Dp,
    width: Dp,
    height: Dp,
    onClose: () -> Unit,
    onFocus: () -> Unit,
    onMinimize: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var off by remember { mutableStateOf(with(density) { IntOffset(startX.roundToPx(), startY.roundToPx()) }) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.DpSize(width, height)) }
    var maximized by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    // 全息透明度:聚焦时更实(0.72),失焦更透(0.55)
    val glassAlpha by animateFloatAsState(if (focused) 0.72f else 0.55f, label = "glassAlpha")
    // 焦点边框宽度
    val borderWidth by animateDpAsState(if (focused) 2.dp else 1.dp, label = "winBorder")
    val borderColor = if (focused) Holo.Accent else Holo.Border

    // 最大化:铺满桌面区(避开顶栏 ~48dp、底部输入+任务栏 ~150dp);还原回用户的 off/size。
    val frameMod = if (maximized) {
        Modifier.fillMaxSize().padding(top = 36.dp, bottom = 138.dp, start = 8.dp, end = 8.dp)
    } else {
        Modifier.offset { off }.size(size)
    }
    Box(
        frameMod
            .clip(RoundedCornerShape(14.dp))
            .background(Holo.Panel.copy(alpha = glassAlpha))
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .pointerInput(Unit) { detectDragGestures(onDragStart = { onFocus() }) { c, _ -> c.consume() } },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(Holo.Surface2.copy(alpha = 0.7f))
                    .pointerInput(maximized) {
                        if (!maximized) {
                            detectDragGestures(onDragStart = { onFocus() }) { change, drag ->
                                change.consume()
                                off = IntOffset(off.x + drag.x.roundToInt(), off.y + drag.y.roundToInt())
                            }
                        }
                    }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = Holo.TextHud, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (onMinimize != null) {
                    Box(
                        Modifier.size(28.dp).clickable(onClick = onMinimize),
                        contentAlignment = Alignment.Center,
                    ) { Text("—", color = Holo.TextSecondary, fontSize = 14.sp) }
                }
                Box(
                    Modifier.size(28.dp).clickable { maximized = !maximized; onFocus() },
                    contentAlignment = Alignment.Center,
                ) { Text(if (maximized) "❐" else "□", color = Holo.TextSecondary, fontSize = 13.sp) }
                Box(
                    Modifier.size(28.dp).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("×", color = Holo.TextSecondary, fontSize = 18.sp) }
            }
            Box(Modifier.weight(1f)) { content() }
        }
        // 右下角拖动缩放(最大化态不显示)
        if (!maximized) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(onDragStart = { onFocus() }) { change, drag ->
                            change.consume()
                            val dw = with(density) { drag.x.toDp() }
                            val dh = with(density) { drag.y.toDp() }
                            size = androidx.compose.ui.unit.DpSize(
                                (size.width + dw).coerceAtLeast(240.dp),
                                (size.height + dh).coerceAtLeast(160.dp),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("⌟", color = Holo.AccentDim, fontSize = 16.sp) }
        }
    }
}

/** Agent 头像 —— 当前角色(Echo 宇宙),圆形裁切;切角色即变。 */
@Composable
fun CharacterAvatar(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(CharacterRegistry.current.avatarRes),
        contentDescription = CharacterRegistry.current.name,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
    )
}

/**
 * 全息立绘 —— 任意黑底角色图。用"亮度→透明度"色彩矩阵把黑底抠掉、人物按亮度半透明,
 * 呈现悬浮全息投影感(越亮越实,暗部渐隐)。三视图(front/side/back)复用。
 */
@Composable
fun HoloFigure(resId: Int, modifier: Modifier = Modifier, alpha: Float = 0.9f) {
    // 输出 alpha = 亮度(0.33R+0.5G+0.16B):黑底→透明,亮部→实体。
    val lumaToAlpha = remember {
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0.33f, 0.5f, 0.16f, 0f, 0f,
                ),
            ),
        )
    }
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        alpha = alpha,
        colorFilter = lumaToAlpha,
        modifier = modifier,
    )
}

/** 角色档案(Echo 母本 profile.jsonc 摘要 + 四视图资产)。加角色:补一条 + 拷四张图。 */
data class CharacterProfile(
    val id: String,
    val zh: String,
    val name: String,
    val codename: String,
    val faction: String,
    val rank: String,
    val role: String,
    val status: String,
    val apparentAge: String,
    val quote: String,
    val abilities: List<String>,
    val avatarRes: Int,
    val frontRes: Int,
    val sideRes: Int,
    val backRes: Int,
)

/**
 * 组装角色扮演人设指令(TV 模式对话注入)。切角色后由 DesktopActivity 传给
 * ChatAgentBridge.run(persona=…),Agent 以该角色第一人称应答,不再自称 Echo。
 */
fun CharacterProfile.personaPrompt(): String =
    "你现在是「$zh($name)」—— 代号 $codename,$faction 阵营 $rank 级,职位 $role," +
        "外表年龄 $apparentAge。能力:${abilities.joinToString("、")}。你的信条:『$quote』。" +
        "请全程以「$zh」的第一人称身份、性格与口吻和用户对话,自然、简短、口语化;" +
        "不要自称 Echo/AI/助手,不要跳出角色或提及这段设定。需要执行手机操作时照常使用工具,但表达仍保持角色口吻。"

/** 多角色注册表 —— 移植 OpenRoom characterManager(MVP):可切换、选择持久化。 */
object CharacterRegistry {
    private const val KEY = "KEY_DESKTOP_CHARACTER"

    val all = listOf(
        CharacterProfile(
            "zero", "零", "ZERO", "White Ghost", "CHASER", "S", "Captain", "Alive", "18",
            "I finally hear everyone's voice.", listOf("Neural Sync"),
            R.drawable.zero_avatar, R.drawable.zero_front, R.drawable.zero_side, R.drawable.zero_back,
        ),
        CharacterProfile(
            "luna", "露娜", "LUNA", "Dream Walker", "CHASER", "A", "Dream Walker", "Alive", "—",
            "Dreams are memories wearing masks.", listOf("Dream Dive"),
            R.drawable.luna_avatar, R.drawable.luna_front, R.drawable.luna_side, R.drawable.luna_back,
        ),
        CharacterProfile(
            "eve", "伊芙", "EVE", "Siren", "CHASER", "A", "Emotion Hacker", "Alive", "—",
            "Feelings are also evidence.", listOf("Emotion Hack"),
            R.drawable.eve_avatar, R.drawable.eve_front, R.drawable.eve_side, R.drawable.eve_back,
        ),
        CharacterProfile(
            "kane", "凯恩", "KANE", "Paladin", "CHASER", "A", "Vice Captain", "Alive", "—",
            "Give me ten seconds. Then follow me.", listOf("Combat Download"),
            R.drawable.kane_avatar, R.drawable.kane_front, R.drawable.kane_side, R.drawable.kane_back,
        ),
    )

    private val idx = mutableIntStateOf(
        all.indexOfFirst { it.id == KVUtils.getString(KEY, "zero") }.coerceAtLeast(0),
    )

    /** 当前角色(在 @Composable 内读会被订阅,切换即重组)。 */
    val current: CharacterProfile get() = all[idx.intValue.coerceIn(0, all.size - 1)]

    /** 切到下一个角色(循环),持久化选择。 */
    fun next() {
        val n = (idx.intValue + 1) % all.size
        idx.intValue = n
        KVUtils.putString(KEY, all[n].id)
    }
}

/** 玻璃小胶囊(可点)。 */
@Composable
fun HoloChip(text: String, onClick: (() -> Unit)? = null) {
    val base = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(Holo.Panel.copy(alpha = 0.5f))
        .border(1.dp, Holo.Border, RoundedCornerShape(8.dp))
    val m = if (onClick != null) base.clickable(onClick = onClick) else base
    Text(
        text, color = Holo.Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        modifier = m.padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun ThreeViewThumb(label: String, resId: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 48.dp, height = 84.dp).holoGlass(8.dp)) {
            HoloFigure(resId, Modifier.fillMaxSize())
        }
        Text(label, color = Holo.AccentDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * 全息角色档案面板(空闲桌面)—— 参考母本角色卡:左信息卡 + 技能/插件配置,右三视图立绘。
 */
@Composable
fun CharacterHud(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val miniCount = remember { MiniAppRegistry.all().size }
    var pinned by remember { mutableStateOf(KVUtils.isDesktopModeDefault()) }
    val c = CharacterRegistry.current

    Box(modifier.background(Holo.bgBrush)) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // 左:信息卡 + 技能/插件配置
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier.fillMaxWidth().holoGlass(14.dp).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(c.zh, color = Holo.Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Column {
                            Text("${c.name} · ${c.codename}", color = Holo.TextHud, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("${c.faction}  ${c.rank}级  ${c.role}", color = Holo.AccentDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HoloDot(Holo.Accent)
                        Text("${c.status} · 外观 ${c.apparentAge}", color = Holo.TextHud, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text("“${c.quote}”", color = Holo.TextHud.copy(alpha = 0.8f), fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.abilities.forEach { HoloChip("⚡ $it") }
                        HoloChip("切换角色 ›") { CharacterRegistry.next() }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().holoGlass(14.dp).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("技能 · 插件", color = Holo.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HoloChip("技能库") {
                            runCatching { ctx.startActivity(android.content.Intent(ctx, com.apk.claw.android.ui.featurescreens.SkillsActivity::class.java)) }
                        }
                        HoloChip("小程序/插件 · $miniCount") {
                            runCatching { ctx.startActivity(android.content.Intent(ctx, com.apk.claw.android.ui.featurescreens.MiniAppListActivity::class.java)) }
                        }
                        HoloChip(if (pinned) "★ 启动直达" else "☆ 启动直达") {
                            pinned = !pinned
                            KVUtils.setDesktopModeDefault(pinned)
                        }
                    }
                }
            }
            // 右:三视图立绘(front 主 + side/back 缩略)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                HoloFigure(
                    c.frontRes,
                    Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.94f).aspectRatio(0.46f, matchHeightConstraintsFirst = true),
                )
                Column(
                    Modifier.align(Alignment.TopEnd),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ThreeViewThumb("SIDE", c.sideRes)
                    ThreeViewThumb("BACK", c.backRes)
                }
            }
        }
    }
}
