package com.apk.claw.android.octopus_mobile.persona

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.agent.ConversationContext
import com.apk.claw.android.ui.compose.screen.ChatAgentBridge

/**
 * 互聊模式编排器：把一条用户话题驱动为多 Persona 轮流发言的多人对话。
 *
 * 流程：
 * 1. 用户发话题 `topic`
 * 2. 取花名册 `roster`（PersonaStore.MultiMode.getRoster），按顺序遍历
 * 3. 对第 i 个 Persona：
 *    - 以「第 i 个角色继续发言」为提示，调 ChatAgentBridge.run，
 *      传 personaIdOverride = roster[i]
 *    - conversationContext 包含：原 user/agent 历史 + 本轮已发言 Persona 的回复
 *    - 等待 onDone 后才进入下一个 Persona
 * 4. 全部发言完毕触发 onAllDone；任一步出错触发 onError 并终止
 *
 * 不并发：ChatAgentBridge 有 busy 标志，必须串行；这里通过 onDone 链式触发下一个。
 *
 * 由 ChatScreen 在互聊模式启用时直接调用，不需要单独 UI。
 */
object MultiPersonaOrchestrator {

    /**
     * 启动一次互聊。
     *
     * @param topic 用户原始话题
     * @param priorTurns 已有对话历史(用户/助手消息),按时间正序;first=是否用户消息,second=文本
     * @param onTurnStart 某个 Persona 开始发言(主线程)
     * @param onTurnDone 某个 Persona 发言完毕(主线程)，reply 为其回复文本
     * @param onAllDone 全部 Persona 发言完毕(主线程)
     * @param onTool 工具调用回调(透传给 ChatAgentBridge)
     * @param onText 流式 token 回调(透传给 ChatAgentBridge)
     * @param onError 任一步出错(主线程)；err 在 MultiPersonaOrchestrator 层已包好上下文
     */
    fun run(
        topic: String,
        priorTurns: List<Pair<Boolean, String>>,
        onTurnStart: (persona: com.apk.claw.android.octopus_mobile.persona.Persona) -> Unit,
        onTurnDone: (persona: com.apk.claw.android.octopus_mobile.persona.Persona, reply: String) -> Unit,
        onAllDone: () -> Unit,
        onTool: (icon: String, name: String, args: String, result: String?) -> Unit,
        onText: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val ctx = ClawApplication.instance
        val rosterIds = PersonaStore.MultiMode.getRoster()
        if (rosterIds.isEmpty()) {
            onError("互聊花名册为空，请先在 Persona 管理里勾选 2-4 个角色")
            return
        }
        val roster = rosterIds.mapNotNull { PersonaStore.get(ctx, it) }
        if (roster.isEmpty()) {
            onError("花名册里的 Persona 都已删除")
            return
        }

        // 累积本轮对话：起始 = priorTurns + (user, topic)；每轮 Persona 发言后追加 (false, reply)
        val acc = priorTurns.toMutableList()
        acc.add(true to topic)

        // 链式触发第 i 个 Persona
        fun fireNext(i: Int) {
            if (i >= roster.size) {
                onAllDone()
                return
            }
            val persona = roster[i]
            onTurnStart(persona)

            // 构造本回合提示：第一个 Persona 看到原话题；后续 Persona 看到「请就上面的讨论继续发言」
            val turnPrompt = if (i == 0) {
                topic
            } else {
                "（你是 ${persona.name}，请基于上面 ${roster[0].name} 与其他角色的发言，从你的视角继续这场对话。保持你的人设。）"
            }

            val convCtx = ConversationContext.build(acc)

            ChatAgentBridge.run(
                prompt = turnPrompt,
                conversationContext = convCtx,
                persona = null,  // personaIdOverride 已处理人设注入，不再走 free-form persona 路径
                personaIdOverride = persona.id,
                onTool = onTool,
                onText = onText,
                onDone = { reply ->
                    acc.add(false to reply)
                    onTurnDone(persona, reply)
                    fireNext(i + 1)
                },
                onError = { err ->
                    onError("【${persona.name}发言失败】$err")
                },
            )
        }

        fireNext(0)
    }
}
