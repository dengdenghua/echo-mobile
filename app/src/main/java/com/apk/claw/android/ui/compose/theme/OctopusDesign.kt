package com.apk.claw.android.ui.compose.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 玻璃主题(UiStyle.Glass / OctopusGlassQuality / OctopusGlass)已整体移除 —— App 统一扁平 Standard 视觉。

/**
 * 全局配色 —— ECHO 家族调色板（analysis/echo-brand-kit/tokens.json），与 XML colors.xml 一一对齐。
 *
 * 设计决策：
 * - 浅色 = Snow，深色 = Graphite；颜色值与 `values/colors.xml` + `values-night/colors.xml` 对齐。
 * - 主动作是单色的：[Primary] 浅色为 ink #111317、深色为 snow #EEF1F4，[OnPrimary] 取反。
 * - 颜色只表达状态：[Accent] / [Success] = Echo Core 青，[Info] = signal 蓝，[Warning] = 琥珀，[Error] = breach 红。
 * - 通过 [isLight] 按主题动态计算，[isLight] 是 Compose State，切换时触发重组。
 * - 顶层属性别名（如 `val FMuted get() = OctopusColors.TextMuted`）可正常工作，
 *   因为各字段是普通 getter（非 @Composable），适合在非 Composable 上下文引用。
 *
 * 注意：各处别名必须写成 `get() = OctopusColors.X`（而非 `= OctopusColors.X`），
 * 否则会在类加载时被定格成一种配色，主题切换不生效。
 */
object OctopusColors {
    private val _isLight = mutableStateOf(false)
    var isLight: Boolean
        get() = _isLight.value
        set(value) { _isLight.value = value }

    // ── Brand（与 XML colorBrand* 对齐：单色主动作 ink #111317 / snow #EEF1F4）──
    val Primary: Color get() = if (isLight) Color(0xFF111317) else Color(0xFFEEF1F4)
    val OnPrimary: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF0A0B0D)
    val PrimaryContainer: Color get() = if (isLight) Color(0xFFE6E9ED) else Color(0xFF1E2228)
    val OnPrimaryContainer: Color get() = if (isLight) Color(0xFF111317) else Color(0xFFEEF1F4)
    val PrimaryVariant: Color get() = if (isLight) Color(0xFF59616C) else Color(0xFF9AA3AE)
    val OnVariant: Color get() = if (isLight) Color(0xFFE6E9ED) else Color(0xFF262A31)
    val LowButtonBg: Color get() = if (isLight) Color(0xFFE6E9ED) else Color(0xFF1E2228)
    val FixedOnContainer: Color get() = Color(0xFF111317)  // 不随主题切换（固定 ink）

    /** 辅色 = Echo Core 青（稳定同步：选中、进行中、成功）。面积保持很小。 */
    val Accent: Color get() = if (isLight) Color(0xFF08788A) else Color(0xFF6FE3F0)

    // ── Semantic（与 XML colorError/Warning/Success/Info 对齐：breach / warning / core / signal）──
    val Success: Color get() = if (isLight) Color(0xFF08788A) else Color(0xFF6FE3F0)
    val SuccessOnContainer: Color get() = if (isLight) Color(0xFF065F6E) else Color(0xFF6FE3F0)
    val SuccessContainer: Color get() = if (isLight) Color(0xFFE6F2F3) else Color(0xFF1B2B30)
    val Warning: Color get() = if (isLight) Color(0xFF8A5600) else Color(0xFFF2B84B)
    val WarningOnContainer: Color get() = if (isLight) Color(0xFF6E4400) else Color(0xFFF2B84B)
    val WarningContainer: Color get() = if (isLight) Color(0xFFF3EEE6) else Color(0xFF2B261C)
    val Error: Color get() = if (isLight) Color(0xFFC42638) else Color(0xFFFF6B78)
    val ErrorOnContainer: Color get() = if (isLight) Color(0xFFA11F2F) else Color(0xFFFF9AA3)
    val ErrorContainer: Color get() = if (isLight) Color(0xFFFAEBED) else Color(0xFF2D1D22)
    val Info: Color get() = if (isLight) Color(0xFF2F5DB8) else Color(0xFFA9C7FF)
    val InfoOnContainer: Color get() = if (isLight) Color(0xFF244A93) else Color(0xFFA9C7FF)
    val InfoContainer: Color get() = if (isLight) Color(0xFFEAEFF8) else Color(0xFF222832)

    /**
     * Resonance pink (Echo Core light language: emotional resonance). Only for emotional moments such as a
     * liked heart, persona and memory; never for errors (that is [Error], breach red) and never as decoration.
     */
    val Resonance: Color get() = if (isLight) Color(0xFFA83669) else Color(0xFFF4A6C8)

    // ── Fills（与 XML colorFill* 对齐：ink / 白色按 16% · 12% · 8% · 5% 叠加）──
    val FillPrimary: Color get() = if (isLight) Color(0x290A0C10) else Color(0x29FFFFFF)
    val FillSecondary: Color get() = if (isLight) Color(0x1F0A0C10) else Color(0x1FFFFFFF)
    val FillTertiary: Color get() = if (isLight) Color(0x140A0C10) else Color(0x14FFFFFF)
    val FillQuaternary: Color get() = if (isLight) Color(0x0D0A0C10) else Color(0x0DFFFFFF)

    // ── Backgrounds（与 XML colorBg* / colorContainer* 对齐：ground / surface / raised / sunken）──
    val Background: Color get() = if (isLight) Color(0xFFEEF0F3) else Color(0xFF090A0C)
    val BackgroundSecondary: Color get() = if (isLight) Color(0xFFE6E9ED) else Color(0xFF060708)
    val BackgroundTertiary: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF171A1F)
    val ContainerBrighten: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF171A1F)
    val Surface: Color get() = ContainerBrighten
    val ContainerLow: Color get() = if (isLight) Color(0xFFF8F9FA) else Color(0xFF101216)
    val SurfaceDeep: Color get() = ContainerLow
    val ContainerBase: Color get() = if (isLight) Color(0xFFE6E9ED) else Color(0xFF1E2228)
    val SurfaceVariant: Color get() = ContainerBase
    val ContainerHigh: Color get() = if (isLight) Color(0xFFDDE1E6) else Color(0xFF262A31)
    val SurfaceHigh: Color get() = ContainerHigh
    val ContainerHighest: Color get() = if (isLight) Color(0xFFD2D7DD) else Color(0xFF2E333B)
    val SurfaceHighest: Color get() = ContainerHighest
    val ContainerInverse: Color get() = if (isLight) Color(0xFF171A1F) else Color(0xFFEEF1F4)

    // ── Borders（与 XML colorBorder* 对齐：发丝线 line 8% / line-strong 16%）──
    val Border: Color get() = if (isLight) Color(0x140A0C10) else Color(0x14FFFFFF)
    val BorderLow: Color get() = if (isLight) Color(0x0D0A0C10) else Color(0x0DFFFFFF)
    val BorderHigh: Color get() = if (isLight) Color(0x290A0C10) else Color(0x29FFFFFF)

    // ── Text（与 XML colorText* 对齐：ink / ink-muted）──
    val TextPrimary: Color get() = if (isLight) Color(0xFF111317) else Color(0xFFEEF1F4)
    val TextSecondary: Color get() = if (isLight) Color(0xFF59616C) else Color(0xFF9AA3AE)
    // 需要阅读的次要文字用 ink-muted（WCAG AA）；ink-faint 只给禁用态，见 XML colorTextTertiary。
    val TextTertiary: Color get() = if (isLight) Color(0xFF59616C) else Color(0xFF9AA3AE)
    val TextDisabled: Color get() = if (isLight) Color(0x33111317) else Color(0x33EEF1F4)
    val TextInverse: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF0A0B0D)

    /** 向后兼容别名：TextMuted = TextTertiary（原 OctopusColors.TextMuted 语义） */
    val TextMuted: Color get() = TextTertiary

    // ── Fixed Text（不随主题切换，用于深色/浅色背景上的固定对比文字）──
    val TextFixedWhitePrimary: Color get() = Color(0xFFEEF1F4)
    val TextFixedWhiteSecondary: Color get() = Color(0x99EEF1F4)
    val TextFixedBlackPrimary: Color get() = Color(0xFF111317)
    val TextFixedBlackSecondary: Color get() = Color(0xFF59616C)

    // ── Overlays（与 XML colorOverlay* 对齐）──
    /** 半透明遮罩（用于抽屉/弹窗背景） */
    val OverlayDim: Color get() = if (isLight) Color(0x80090A0C) else Color(0xAD000000)
    val OverlayScrim: Color get() = if (isLight) Color(0x33090A0C) else Color(0x52000000)
    val OverlayLabel: Color get() = if (isLight) Color(0xAD000000) else Color(0xAD000000)

    /** 各 Activity 的 window.statusBarColor 用它，随主题自适应。 */
    val statusBarArgb: Int get() = Background.toArgb()
}

/**
 * 分类图标色（工具卡片图标、标签、分类入口等）。
 *
 * ECHO 品牌规定颜色只表达状态、不区分模块或分类（区分靠图标字形），所以这里的每个分类
 * 都落到中性的 ink-muted（Snow #59616C / Graphite #9AA3AE）；调用方用 `.copy(alpha = …)`
 * 叠出的浅底随之变成中性浅底。名字保留以免改动调用点；没有任何一项代表系统状态，
 * 状态色请用 [OctopusColors] 的 Accent / Success / Info / Warning / Error。
 * 写成 getter，随 [OctopusColors.isLight] 切换主题。
 */
object OctopusTints {
    private val Neutral: Color get() = if (OctopusColors.isLight) Color(0xFF59616C) else Color(0xFF9AA3AE)

    val Skill: Color get() = Neutral      // 技能
    val Plugin: Color get() = Neutral     // 插件
    val Routine: Color get() = Neutral    // 例程
    val Cloud: Color get() = Neutral      // 云盘
    val Memory: Color get() = Neutral     // 记忆
    val Video: Color get() = Neutral      // 视频
    val Window: Color get() = Neutral     // 多窗口
    val Evolve: Color get() = Neutral     // 进化
    val Trust: Color get() = Neutral      // 信任
    val Browser: Color get() = Neutral    // 浏览器

    /** 「热门」标签（FeatureHubScreen 重复使用 3 次）：热度不是系统状态，同样中性。 */
    val Hot: Color get() = Neutral

    /** 浏览器分类色（DiscoverScreen 分类图标 + 快捷方式） */
    val CatAI: Color get() = Neutral
    val CatVideo: Color get() = Neutral
    val CatDev: Color get() = Neutral
    val CatKnowledge: Color get() = Neutral
}

/**
 * 统一的 Shape token（合并原 OctopusShape 与 OctopusShapes 双轨）。
 *
 * 圆角较保守以贴合扁平 UI(避免气泡感):
 * - small  = 6dp（小控件：Chip、Tag）
 * - medium = 8dp（中控件：按钮、输入框）
 * - large  = 10dp（大控件：卡片）
 * - xl     = 12dp（面板、底部弹窗）
 * - capsule = 50%（胶囊）
 *
 * 注意：原 OctopusShape（裸 Dp）已废弃，请使用本对象的 RoundedCornerShape 字段。
 */
object OctopusShape {
    val small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    val medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val large = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val xl = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    /** `radius-lg`: dialogs, bottom sheets and panels such as the chat composer. */
    val dialog = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    /** `radius-xl`: floating chrome bars (the Dock, the chat header capsule). */
    val dock = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val capsule = androidx.compose.foundation.shape.RoundedCornerShape(50)

    /** 向后兼容：原 OctopusShape.Card/Panel/Control 的 Dp 值 */
    val Card = 10.dp
    val Panel = 12.dp
    val Control = 8.dp

    /** 对话气泡：统一圆角，无尾角，扁平化 */
    val agentBubble = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val userBubble = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
}

/**
 * 统一的间距网格（4dp 基准）。
 */
object OctopusSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

/**
 * 统一的图标尺寸。
 */
object OctopusIconSize {
    val small = 16.dp
    val medium = 20.dp
    val large = 24.dp
}

/**
 * 顶级 Compose 页面通用布局尺寸。
 *
 * Scaffold 已经把底部导航高度注入 innerPadding，页面只需要再留出少量
 * 视觉呼吸空间；集中在这里，避免各 Tab 各写一套 magic number。
 */
object OctopusLayout {
    val bottomNavContentPadding = 24.dp
    val bottomNavHeight = 64.dp
    val bottomNavItemHeight = 48.dp
    val bottomNavElevation = 14.dp
    val bottomNavBorder = 1.dp
}

/**
 * 全局玻璃背景 token。
 *
 * 浏览器首页先完成了暖色液态玻璃方向，这里把同一套背景/玻璃面板语义提升为
 * App 级别 token，避免一级页面各自散落一套颜色。
 */
object OctopusThemeStyle {
    /** 卡片阴影:玻璃主题已移除,统一扁平 UI,恒为 0(保留函数签名,调用点零改动)。 */
    @Suppress("UnusedParameter")
    fun cardShadow(elevation: Dp): Dp = 0.dp
}

object OctopusBackground {
    /** 页面背景:扁平纯色(玻璃主题已移除,不再有暖色/液态渐变)。 */
    fun pageBrush(): Brush =
        Brush.linearGradient(listOf(OctopusColors.Background, OctopusColors.Background))

    /** 卡片表面:统一实底(玻璃透明度机制已移除)。 */
    val cardSurface: Color get() = if (OctopusColors.isLight) Color.White else Color(0xFF171A1F)

    /** 卡片描边:统一实底细描边。 */
    val cardBorder: Color get() = if (OctopusColors.isLight) Color(0x140A0C10) else Color(0x14FFFFFF)

    /** 实底表面/描边 —— 弹窗、文字密集内容显式引用(与 cardSurface/cardBorder 同义)。 */
    val solidSurface: Color get() = cardSurface

    val solidBorder: Color get() = cardBorder
}

/**
 * 统一的字号阶梯（sp）。token 取值与现有 UI 实际字号一一对齐，迁移为纯别名、零视觉变化。
 *
 * - micro      = 12sp（极小徽章，如「已就绪」状态点）
 * - tag        = 12sp（标签、计数、底部导航选中文字）
 * - caption    = 12sp（辅助说明、副标题）
 *
 * ECHO 字号从 caption 12 起，中文不再出现 8–11 的字号，所以 micro / tag 和 caption 同为 12sp；
 * 名字保留，是为了不动几十处调用。
 * - label      = 13sp（次要正文、分区标签）
 * - body       = 14sp（正文）
 * - bodyStrong = 15sp（强调正文）
 * - bodyLg     = 16sp（大正文 / 搜索提示）
 * - title      = 17sp（卡片 / 分区标题）
 * - titleSm    = 17sp（次级标题，如未选中 Tab）
 * - titleLg    = 19sp（页面标题）
 * - headlineSm = 21sp（小号大标题：顶栏品牌名 / 首页标题）
 * - headline   = 23sp（大标题 / Tab 选中态）
 * - display    = 26sp（空状态 Hero 标题）
 */
object OctopusType {
    val micro = 12.sp
    val tag = 12.sp
    val caption = 12.sp
    val label = 13.sp
    val body = 14.sp
    val bodyStrong = 15.sp
    val bodyLg = 16.sp
    val title = 17.sp
    val titleSm = 17.sp
    val titleLg = 19.sp
    val headlineSm = 21.sp
    val headline = 23.sp
    val display = 26.sp
}
