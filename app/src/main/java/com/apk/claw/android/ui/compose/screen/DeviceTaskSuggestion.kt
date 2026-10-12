package com.apk.claw.android.ui.compose.screen

import androidx.annotation.StringRes
import com.apk.claw.android.R
import java.util.Locale

/** A conservative local hint. It never dispatches work or changes the selected device. */
internal object DeviceTaskSuggestion {
    data class Suggestion(val environment: DotEnvironment, @StringRes val reason: Int)

    fun suggest(text: String): Suggestion? {
        val query = text.trim().lowercase(Locale.ROOT)
        if (query.length < 4) return null
        // Questions, comparisons and negated instructions are not execution requests.
        if (listOf("不要", "不用", "别在", "不在", "对比", "区别", "如何选择", "怎么选择",
                "don't", "do not", "compare", "difference").any(query::contains)) return null
        fun has(vararg phrases: String) = phrases.any { phrase ->
            if (phrase.any { it.code > 127 }) query.contains(phrase)
            else Regex("(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])").containsMatchIn(query)
        }
        val explicit = buildList {
            if (has("在nas", "在 nas", "nas上", "nas 上", "on nas", "on my nas")) add(DotEnvironment.HOME)
            if (has("在电脑", "电脑上", "on my computer", "on my desktop")) add(DotEnvironment.COMPUTER)
            if (has("在手机", "手机上", "on my phone", "on this phone")) add(DotEnvironment.PHONE)
        }
        if (explicit.distinct().size > 1) return null
        val candidates = buildList {
            if (has("打开微信", "打开支付宝", "读取短信", "发送短信", "拍照", "获取定位", "手机通知",
                    "take a photo", "read my sms", "open wechat", "phone location"))
                add(Suggestion(DotEnvironment.PHONE, R.string.dot_env_reason_phone))
            if (has("修改代码", "编译项目", "电脑文件", "桌面应用", "打开终端", "电脑浏览器",
                    "桌面软件", "电脑软件", "桌面仿真", "软件仿真", "电脑上的软件",
                    "build the project", "edit the code", "desktop app", "desktop software", "open terminal"))
                add(Suggestion(DotEnvironment.COMPUTER, R.string.dot_env_reason_computer))
            if (has("定时任务", "每天定时", "每小时", "后台持续", "长期运行", "定时备份", "定时下载",
                    "scheduled backup", "scheduled download", "every hour", "run in background"))
                add(Suggestion(DotEnvironment.HOME, R.string.dot_env_reason_nas))
        }
        val required = candidates.singleOrNull()
        // Mentioning the phone as the user's entry point doesn't make desktop software a phone tool.
        if (required?.environment == DotEnvironment.COMPUTER &&
            has("桌面软件", "电脑软件", "电脑上的软件", "桌面应用", "桌面仿真", "软件仿真", "desktop app", "desktop software")) return required
        if (explicit.size == 1) return Suggestion(explicit.single(), R.string.dot_env_reason_explicit)
        return required
    }
}
