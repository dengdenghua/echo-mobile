package com.apk.claw.android.ui.compose.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Outlined icons for agent tools and prompt skills. They replace the emoji the Skills screen used
 * as tile icons (ECHO: no emoji as chrome icons; one outlined family, one stroke weight).
 */
object ToolIcons {

    /** The icon for a tool, by its registry name; unknown tools get the generic extension icon. */
    @Suppress("CyclomaticComplexMethod")
    fun forTool(name: String): ImageVector = when (name) {
        "tap", "long_press", "swipe", "scroll_to_find" -> Icons.Outlined.TouchApp
        "input_text" -> Icons.Outlined.Keyboard
        "take_screenshot", "look_at_screen" -> Icons.Outlined.Screenshot
        "get_screen_info", "find_node_info" -> Icons.Outlined.Visibility
        "open_app" -> Icons.Outlined.Apps
        "system_key" -> Icons.Outlined.Tune
        "wait" -> Icons.Outlined.HourglassEmpty
        "finish", "finish_task" -> Icons.Outlined.CheckCircle
        else -> when {
            "file" in name || "read" in name || "write" in name -> Icons.Outlined.Description
            "http" in name || "web" in name || "url" in name || "browser" in name -> Icons.Outlined.Language
            "search" in name -> Icons.Outlined.Search
            "image" in name || "photo" in name || "video" in name || "gen" in name -> Icons.Outlined.Image
            "sms" in name || "call" in name || "phone" in name || "contact" in name -> Icons.Outlined.Call
            "notify" in name || "notification" in name -> Icons.Outlined.Notifications
            "app" in name -> Icons.Outlined.Apps
            else -> Icons.Outlined.Extension
        }
    }

    /** Prompt-skill keywords → icon, checked in order; the first hit wins. */
    private val SKILL_KEYWORDS: List<Pair<List<String>, ImageVector>> = listOf(
        listOf("shizuku", "adb", "配对", "调试") to Icons.Outlined.DeveloperMode,
        listOf("自动化", "编排", "automat") to Icons.Outlined.AccountTree,
        listOf("海报", "视觉", "design", "设计") to Icons.Outlined.Palette,
        listOf("抓取", "爬", "scrape", "crawl", "网页") to Icons.Outlined.TravelExplore,
        listOf("批量", "batch") to Icons.Outlined.DynamicFeed,
    )

    /** The icon for a prompt skill, guessed from its name and description. */
    fun forSkill(name: String, desc: String): ImageVector {
        val s = "$name $desc".lowercase()
        return SKILL_KEYWORDS.firstOrNull { (words, _) -> words.any { it in s } }?.second ?: Icons.Outlined.AutoAwesome
    }
}
