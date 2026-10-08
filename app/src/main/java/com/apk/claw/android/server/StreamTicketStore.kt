package com.apk.claw.android.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 屏幕流的一次性短期票据（内存存储）。
 *
 * `<img src>` 无法携带 Authorization 头，而长期 Bearer token 又不得进入 URL，
 * 所以控制台先用 Bearer 头 POST 换一张不透明票据，再用 `?ticket=` 打开流：
 * - 随机 24 字节，base64url；有效期 [ttlMs]（默认 30s）；只能消费一次。
 * - 数量上限 [maxTickets]：先清理过期，仍满则淘汰最旧，避免被刷爆内存。
 * - 比较使用 [MessageDigest.isEqual] 并遍历全部条目（不因命中提前返回），避免时序侧信道。
 * - 票据值永不写日志。
 */
class StreamTicketStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxTickets: Int = DEFAULT_MAX_TICKETS,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Entry(val bytes: ByteArray, val expiresAtMs: Long)

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun issue(nowMs: Long): String {
        purge(nowMs)
        while (entries.size >= maxTickets) entries.removeAt(0)
        val raw = ByteArray(TICKET_BYTES).also { random.nextBytes(it) }
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        entries.add(Entry(ticket.toByteArray(Charsets.UTF_8), nowMs + ttlMs))
        return ticket
    }

    /** 校验并消费；成功返回 true，且该票据立即失效。 */
    @Synchronized
    fun consume(ticket: String?, nowMs: Long): Boolean {
        purge(nowMs)
        if (ticket.isNullOrEmpty() || ticket.length > MAX_TICKET_CHARS) return false
        val provided = ticket.toByteArray(Charsets.UTF_8)
        var matched: Entry? = null
        for (e in entries) {
            if (MessageDigest.isEqual(e.bytes, provided)) matched = e
        }
        matched?.let { entries.remove(it) }
        return matched != null
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun purge(nowMs: Long) {
        entries.removeAll { it.expiresAtMs <= nowMs }
    }

    companion object {
        const val DEFAULT_TTL_MS = 30_000L
        const val DEFAULT_MAX_TICKETS = 32
        private const val TICKET_BYTES = 24
        private const val MAX_TICKET_CHARS = 128
    }
}
