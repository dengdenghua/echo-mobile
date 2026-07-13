@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.memory.MemoryStore

/**
 * 本机知识的采集与恢复 —— 把「用户规矩([InteractionLedger] manual)+ 记忆([MemoryStore])」
 * 与 [KnowledgeBundle] 之间的转换收敛到一处,供剪贴板 / 文件 / 局域网三种备份模态共用,避免各写一遍。
 *
 * gather:本机知识 → 包文本;restore:包文本 → 导入本机(幂等去重,重复导入不翻倍)。
 * 依赖 KVUtils/InteractionLedger 存储,可靠 init(tmpDir) + KVUtils 内存回退纯 JVM 测。
 */
object KnowledgeLocal {

    /** 采集本机的规矩 + 记忆,序列化成知识包文本。 */
    fun gather(): String {
        val rules = InteractionLedger.snapshot().filter { it.manual }.map { it.title }
        val mems = MemoryStore().getMemories()
            .map { KnowledgeBundle.MemItem(it.content, it.type.name) }
        return KnowledgeBundle.export(rules, mems)
    }

    /**
     * 把知识包文本导入本机。返回 (导入规矩数, 导入记忆数);文本非法知识包返回 null。
     */
    fun restore(json: String): Pair<Int, Int>? {
        if (!KnowledgeBundle.looksValid(json)) return null
        val parsed = KnowledgeBundle.parse(json)
        parsed.rules.forEach { InteractionLedger.addManualRule(it) }
        val store = MemoryStore()
        parsed.memories.forEach {
            val type = runCatching { MemoryStore.MemoryType.valueOf(it.type) }
                .getOrDefault(MemoryStore.MemoryType.FACT)
            store.addUserFact(it.content, type)
        }
        return parsed.rules.size to parsed.memories.size
    }
}
