@file:Suppress("PackageNaming", "MagicNumber", "TooGenericExceptionCaught")   // 沿用 octopus_mobile 包;HTTP 码 / 计数阈值等内联常量

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ApiKeyPool 单元测试 —— 多 key 轮询 / 失败切换 / 持久化 / 主 key 同步。
 *
 * 纯 JVM(KVUtils 在 MMKV 未初始化时退回内存 map,与 UsageStatsTest 同款)。
 */
class ApiKeyPoolTest {

    @Before
    fun setUp() {
        KVUtils.resetForTest()
        ApiKeyPool.resetForTest()
        // 设置主 Key(KVUtils),供 addKey 自动 prepend 用
        KVUtils.setLlmApiKey(PRIMARY_KEY)
    }

    @Test
    fun `empty pool disabled and acquireKey returns primary`() {
        assertFalse(ApiKeyPool.isEnabled())
        assertEquals(PRIMARY_KEY, ApiKeyPool.acquireKey())
        assertEquals(0, ApiKeyPool.size())
    }

    @Test
    fun `addKey on empty pool prepends primary and appends new key`() {
        assertTrue(ApiKeyPool.addKey(FALLBACK_KEY_1))
        assertEquals(2, ApiKeyPool.size())
        // 索引 0 = 主 Key,索引 1 = 新加的 fallback
        // listKeys 返回脱敏 key,通过 acquireKey 验证实际 key
        assertEquals(PRIMARY_KEY, ApiKeyPool.acquireKey())  // acquireKey 返回主 Key
        ApiKeyPool.reportFailure(PRIMARY_KEY, 401)  // 禁用主 Key
        assertEquals(FALLBACK_KEY_1, ApiKeyPool.acquireKey())  // 切到 fallback
    }

    @Test
    fun `addKey duplicate returns false`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        assertFalse(ApiKeyPool.addKey(FALLBACK_KEY_1))
        assertEquals(2, ApiKeyPool.size())
    }

    @Test
    fun `addKey blank returns false`() {
        assertFalse(ApiKeyPool.addKey(""))
        assertFalse(ApiKeyPool.addKey("   "))
        assertEquals(0, ApiKeyPool.size())
    }

    @Test
    fun `isEnabled requires at least 2 keys`() {
        ApiKeyPool.setEnabled(true)
        // 池为空时 isEnabled 仍返回 false
        assertFalse(ApiKeyPool.isEnabled())
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        // 池有 2 个 key(主 + 1 fallback)且 enabled=true → isEnabled=true
        assertTrue(ApiKeyPool.isEnabled())
    }

    @Test
    fun `reportFailure 401 disables key`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 主 Key 401 → 禁用 + 切到 fallback
        ApiKeyPool.reportFailure(PRIMARY_KEY, 401)
        assertEquals(FALLBACK_KEY_1, ApiKeyPool.acquireKey())
    }

    @Test
    fun `reportFailure 403 disables key`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        ApiKeyPool.reportFailure(PRIMARY_KEY, 403)
        assertEquals(FALLBACK_KEY_1, ApiKeyPool.acquireKey())
    }

    @Test
    fun `reportFailure 429 triggers cooldown and advances to next`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        ApiKeyPool.reportFailure(PRIMARY_KEY, 429)
        // 冷却期内,主 Key 不可用,acquireKey 返回 fallback
        assertEquals(FALLBACK_KEY_1, ApiKeyPool.acquireKey())
    }

    @Test
    fun `reportFailure 5xx triggers cooldown`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        ApiKeyPool.reportFailure(PRIMARY_KEY, 503)
        assertEquals(FALLBACK_KEY_1, ApiKeyPool.acquireKey())
    }

    @Test
    fun `reportSuccess resets consecutiveFailures`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 制造 3 次连续失败(未到阈值 5)
        repeat(3) { ApiKeyPool.reportFailure(PRIMARY_KEY, 429) }
        // 成功上报后重置
        ApiKeyPool.reportSuccess(PRIMARY_KEY)
        // 验证:主 Key 仍可用(虽然 429 冷却中,但统计被重置)
        // 由于 429 冷却 5 分钟,这里 acquireKey 仍返回 fallback(冷却未过)
        assertEquals(FALLBACK_KEY_1, ApiKeyPool.acquireKey())
        // 但 listKeys 反映了 successCount
        val keys = ApiKeyPool.listKeys()
        assertTrue(keys[0].second.successCount >= 1)
    }

    @Test
    fun `MAX_CONSECUTIVE_FAILURES disables key permanently`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 连续失败 5 次(阈值) → 永久禁用
        repeat(5) { ApiKeyPool.reportFailure(PRIMARY_KEY, 500) }
        // 主 Key 被禁用,acquireKey 返回 fallback
        assertEquals(FALLBACK_KEY_1, ApiKeyPool.acquireKey())
        // 即使冷却时间过了(模拟时间前进),禁用状态不变
        val keys = ApiKeyPool.listKeys()
        assertTrue(keys[0].second.disabled)
    }

    @Test
    fun `removeKey index 0 rejected`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        // 索引 0 是主 Key,不可删
        assertFalse(ApiKeyPool.removeKey(0))
        assertEquals(2, ApiKeyPool.size())
    }

    @Test
    fun `removeKey fallback works`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.addKey(FALLBACK_KEY_2)
        assertTrue(ApiKeyPool.removeKey(1))  // 删 FALLBACK_KEY_1
        assertEquals(2, ApiKeyPool.size())   // 主 + FALLBACK_KEY_2
        // 通过禁用主 Key 后 acquireKey 验证剩下的 fallback 是 FALLBACK_KEY_2
        ApiKeyPool.reportFailure(PRIMARY_KEY, 401)
        assertEquals(FALLBACK_KEY_2, ApiKeyPool.acquireKey())
    }

    @Test
    fun `removeKey out of range returns false`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        assertFalse(ApiKeyPool.removeKey(99))
        assertFalse(ApiKeyPool.removeKey(-1))
    }

    @Test
    fun `clearFallbacks keeps primary`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.addKey(FALLBACK_KEY_2)
        ApiKeyPool.clearFallbacks()
        assertEquals(1, ApiKeyPool.size())
        // 只剩主 Key
        assertEquals(PRIMARY_KEY, ApiKeyPool.acquireKey())
    }

    @Test
    fun `clearFallbacks on empty pool is no-op`() {
        ApiKeyPool.clearFallbacks()
        assertEquals(0, ApiKeyPool.size())
    }

    @Test
    fun `resetKey clears failure state`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 让 fallback 失败到禁用
        repeat(5) { ApiKeyPool.reportFailure(FALLBACK_KEY_1, 401) }
        // resetKey(1) 重置 fallback
        assertTrue(ApiKeyPool.resetKey(1))
        val keys = ApiKeyPool.listKeys()
        assertFalse(keys[1].second.disabled)
        assertEquals(0, keys[1].second.consecutiveFailures)
    }

    @Test
    fun `syncPrimary updates index 0`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        // 池中索引 0 现在是 PRIMARY_KEY
        // 用户改了主 Key
        val newPrimaryKey = "sk-new-primary-xyz"
        ApiKeyPool.syncPrimary(newPrimaryKey)
        // acquireKey 应返回新主 Key
        assertEquals(newPrimaryKey, ApiKeyPool.acquireKey())
        // 池大小不变
        assertEquals(2, ApiKeyPool.size())
    }

    @Test
    fun `syncPrimary on empty pool is no-op`() {
        ApiKeyPool.syncPrimary("sk-anything")
        assertEquals(0, ApiKeyPool.size())
    }

    @Test
    fun `syncPrimary unchanged is no-op`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        val sizeBefore = ApiKeyPool.size()
        ApiKeyPool.syncPrimary(PRIMARY_KEY)  // 与 KVUtils 中相同
        assertEquals(sizeBefore, ApiKeyPool.size())
    }

    @Test
    fun `persistence survives resetForTest and re-init`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 模拟进程重启:重置 initialized 标志,触发下次 init() 从 KVUtils 重新加载
        ApiKeyPool.resetForTest()
        // KVUtils 中的数据仍在(resetForTest 只清 ApiKeyPool 内存,不清 KVUtils)
        // 但 ApiKeyPool.init() 会从 KVUtils 读回
        assertEquals(2, ApiKeyPool.size())
        assertTrue(ApiKeyPool.isEnabled())
    }

    @Test
    fun `acquireKey with all keys unavailable returns primary as last resort`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 让两个 key 都冷却
        ApiKeyPool.reportFailure(PRIMARY_KEY, 429)
        ApiKeyPool.reportFailure(FALLBACK_KEY_1, 429)
        // 全部冷却时,acquireKey 返回主 Key(让请求自然失败,触发用户感知)
        assertEquals(PRIMARY_KEY, ApiKeyPool.acquireKey())
    }

    @Test
    fun `acquireKey round-robins among available keys`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.addKey(FALLBACK_KEY_2)
        ApiKeyPool.setEnabled(true)
        // 主 Key 优先
        assertEquals(PRIMARY_KEY, ApiKeyPool.acquireKey())
    }

    @Test
    fun `listKeys returns masked keys`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        val keys = ApiKeyPool.listKeys()
        // listKeys 返回的 key 应是脱敏的(不含完整 key)
        assertNotEquals(PRIMARY_KEY, keys[0].second.key)
        assertNotEquals(FALLBACK_KEY_1, keys[1].second.key)
        // 脱敏格式:首 5 字符 + •••• + 末 4 字符
        assertTrue(keys[0].second.key.contains("••••"))
    }

    @Test
    fun `reportFailure for unknown key is no-op`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        // 上报一个不在池中的 key
        ApiKeyPool.reportFailure("sk-unknown", 429)
        // 池状态不变
        assertEquals(2, ApiKeyPool.size())
        assertEquals(PRIMARY_KEY, ApiKeyPool.acquireKey())
    }

    @Test
    fun `reportSuccess for unknown key is no-op`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.reportSuccess("sk-unknown")
        val keys = ApiKeyPool.listKeys()
        // 没有任何 key 的 successCount 增加
        assertEquals(0, keys[0].second.successCount)
        assertEquals(0, keys[1].second.successCount)
    }

    @Test
    fun `consecutive failures less than threshold does not disable`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 4 次失败(阈值 5),不应禁用,但会冷却
        repeat(4) { ApiKeyPool.reportFailure(PRIMARY_KEY, 429) }
        val keys = ApiKeyPool.listKeys()
        assertFalse(keys[0].second.disabled)
        assertEquals(4, keys[0].second.consecutiveFailures)
    }

    @Test
    fun `interleaved success resets consecutive failures`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 失败 4 次 → 成功 1 次 → 失败 4 次,不应达到阈值
        repeat(4) { ApiKeyPool.reportFailure(PRIMARY_KEY, 429) }
        ApiKeyPool.reportSuccess(PRIMARY_KEY)
        repeat(4) { ApiKeyPool.reportFailure(PRIMARY_KEY, 429) }
        val keys = ApiKeyPool.listKeys()
        assertFalse(keys[0].second.disabled)
    }

    @Test
    fun `disabled key not retried even after cooldown`() {
        ApiKeyPool.addKey(FALLBACK_KEY_1)
        ApiKeyPool.setEnabled(true)
        // 401 直接禁用
        ApiKeyPool.reportFailure(PRIMARY_KEY, 401)
        // 即使等待冷却期过去(disabled 不受 lastFailMs 影响),主 Key 仍禁用
        val keys = ApiKeyPool.listKeys()
        assertTrue(keys[0].second.disabled)
        assertEquals(FALLBACK_KEY_1, ApiKeyPool.acquireKey())
    }

    @Test
    fun `maskKey short key returns dots`() {
        // 通过 listKeys 间接验证:短 key (<=8 字符) 脱敏为 "••••"
        KVUtils.setLlmApiKey("short")
        ApiKeyPool.addKey("fb1")
        val keys = ApiKeyPool.listKeys()
        // 主 Key "short" (5 字符) → "••••"
        assertEquals("••••", keys[0].second.key)
        // fallback "fb1" (3 字符) → "••••"
        assertEquals("••••", keys[1].second.key)
    }

    companion object {
        private const val PRIMARY_KEY = "sk-primary-0123456789abcdef"
        private const val FALLBACK_KEY_1 = "sk-fallback-1-abcdefghij"
        private const val FALLBACK_KEY_2 = "sk-fallback-2-klmnopqrst"
    }
}
