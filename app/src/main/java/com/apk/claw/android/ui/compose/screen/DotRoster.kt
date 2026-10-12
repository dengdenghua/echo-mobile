package com.apk.claw.android.ui.compose.screen

import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apk.claw.android.R
import com.apk.claw.android.utils.KVUtils

/** The desktop White Ghost persona policy, using its stable runtime IDs. */
internal data class DotMember(
    val agentId: String,
    val name: String,
    val chineseName: String,
    val specialty: String,
    val specialtyEn: String,
    @DrawableRes val avatar: Int,
)

internal object DotRoster {
    const val CHARACTER_KEY = "chat_current_character"
    val members = listOf(
        DotMember("general", "Eve", "伊芙", "办公与任务协调", "Office & coordination", R.drawable.dot_eve),
        DotMember("coder", "Kane", "凯恩", "编程与应用架构", "Code & architecture", R.drawable.dot_kane),
        DotMember("desktop_operator", "Raven", "夜鸦", "设备与桌面操作", "Device & desktop", R.drawable.dot_raven),
        DotMember("vibe_selling", "Luna", "露娜", "创作与叙事", "Creation & storytelling", R.drawable.dot_luna),
        DotMember("ecommerce_mind", "Shion", "紫苑", "电商与运营", "Commerce & operations", R.drawable.dot_shion),
        DotMember("market_researcher", "Noah", "诺亚", "市场与投资研究", "Market & investment", R.drawable.dot_noah),
        DotMember("aoi", "Zero", "零", "学术与深度研究", "Academic research", R.drawable.dot_zero),
        DotMember("admin", "Leon", "莱昂", "系统与小队管理", "System & squad", R.drawable.dot_leon),
    )
    var activeCharacter by mutableStateOf(KVUtils.getString(CHARACTER_KEY, "general"))
        private set
    fun find(id: String): DotMember? = members.firstOrNull { it.agentId == id }
    fun select(id: String) {
        activeCharacter = id
        KVUtils.putString(CHARACTER_KEY, id)
    }

    private val phoneProfiles by lazy {
        com.apk.claw.android.ClawApplication.instance.assets.open("dot-personas.json").bufferedReader().use {
            com.google.gson.JsonParser.parseReader(it).asJsonObject
        }
    }

    fun phonePersona(member: DotMember): String =
        "你是 Echo 白幽灵小队的 ${member.name}（${member.chineseName}），角色编号 ${member.agentId}。" +
            "沿用以下角色的身份、专长、性格和口吻；用用户的语言自然交流。" +
            "当前运行环境是用户的手机，使用已授权的手机工具执行操作，实际完成后才报告完成。\n" +
            phoneProfiles.getAsJsonObject(member.agentId).toString()
}
