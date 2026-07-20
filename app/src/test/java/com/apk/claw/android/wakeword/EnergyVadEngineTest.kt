package com.apk.claw.android.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EnergyVadEngine 单元测试 —— 仅覆盖纯计算逻辑,不依赖 AudioRecord / Robolectric。
 *
 * computeRmsDb / bytesToShorts 标记为 internal,测试可访问。
 * 引擎的 start/stop 涉及 AudioRecord 真实硬件,在 JVM 上无法测试,留给真机验证。
 */
class EnergyVadEngineTest {

    private val engine = EnergyVadEngine()

    // ── computeRmsDb ──

    @Test
    fun `computeRmsDb returns -100 for silence`() {
        val silence = ShortArray(160) { 0 } // 100ms @ 16k
        val db = engine.computeRmsDb(silence)
        assertEquals(-100.0, db, 0.001)
    }

    @Test
    fun `computeRmsDb returns approximately 0 for full-scale`() {
        val fullScale = ShortArray(160) { 32767 }
        val db = engine.computeRmsDb(fullScale)
        // 0 dB = 满幅,允许 0.01 误差(log10 数值精度)
        assertEquals(0.0, db, 0.01)
    }

    @Test
    fun `computeRmsDb returns approximately -6 for half-scale`() {
        // 振幅 16384 = 32767 / 2,功率减半 → -6 dB
        val halfScale = ShortArray(160) { 16384 }
        val db = engine.computeRmsDb(halfScale)
        assertEquals(-6.02, db, 0.05)
    }

    @Test
    fun `computeRmsDb returns negative for normal speech`() {
        // 振幅 ~ 1500(典型说话音量),约 -27 dB
        val speech = ShortArray(160) { 1500 }
        val db = engine.computeRmsDb(speech)
        assertTrue("说话音量应低于 0 dB,实际=$db", db < 0)
        assertTrue("说话音量应高于 -50 dB,实际=$db", db > -50)
    }

    @Test
    fun `computeRmsDb returns -100 for empty input`() {
        val db = engine.computeRmsDb(shortArrayOf())
        assertEquals(-100.0, db, 0.001)
    }

    @Test
    fun `computeRmsDb handles mixed signal`() {
        // 混合:一半静音 + 一半满幅 → RMS ≈ 32767 / sqrt(2) ≈ 23170 → -3 dB
        val mixed = ShortArray(160).also {
            for (i in 80 until 160) it[i] = 32767
        }
        val db = engine.computeRmsDb(mixed)
        assertEquals(-3.01, db, 0.1)
    }

    // ── bytesToShorts ──

    @Test
    fun `bytesToShorts decodes little-endian zero`() {
        val bytes = ByteArray(320) { 0 } // 160 shorts * 2 bytes
        val shorts = engine.bytesToShorts(bytes, 320)
        assertEquals(160, shorts.size)
        for (s in shorts) assertEquals(0, s.toInt())
    }

    @Test
    fun `bytesToShorts decodes little-endian full-scale positive`() {
        // 0xFF 0x7F = 32767 (little-endian)
        val bytes = ByteArray(320)
        for (i in 0 until 320 step 2) {
            bytes[i] = 0xFF.toByte()
            bytes[i + 1] = 0x7F.toByte()
        }
        val shorts = engine.bytesToShorts(bytes, 320)
        for (s in shorts) assertEquals(32767, s.toInt())
    }

    @Test
    fun `bytesToShorts decodes little-endian negative`() {
        // 0x00 0x80 = -32768 (little-endian, two's complement)
        val bytes = ByteArray(320)
        for (i in 0 until 320 step 2) {
            bytes[i] = 0x00
            bytes[i + 1] = 0x80.toByte()
        }
        val shorts = engine.bytesToShorts(bytes, 320)
        for (s in shorts) assertEquals(-32768, s.toInt())
    }

    @Test
    fun `bytesToShorts handles odd length gracefully`() {
        // 奇数长度应丢弃最后 1 byte
        val bytes = byteArrayOf(0x01, 0x00, 0x02)
        val shorts = engine.bytesToShorts(bytes, 3)
        assertEquals(1, shorts.size)
        assertEquals(1, shorts[0].toInt())
    }

    // ── 集成:bytesToShorts + computeRmsDb ──

    @Test
    fun `bytesToShorts plus computeRmsDb matches direct short input`() {
        val shorts = ShortArray(160) { (it * 100).toShort() }
        // shorts → bytes(little-endian)
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        val fromBytes = engine.computeRmsDb(engine.bytesToShorts(bytes, bytes.size))
        val fromShorts = engine.computeRmsDb(shorts)
        assertEquals(fromShorts, fromBytes, 0.001)
    }

    // ── engine 元信息 ──

    @Test
    fun `engine id is energy_vad`() {
        assertEquals(WakeWordSettings.ENGINE_ENERGY_VAD, engine.id)
    }

    @Test
    fun `engine is not running initially`() {
        // 新建实例未调 start,应处于非运行状态
        assertTrue(!engine.isRunning())
    }

    @Test
    fun `engine stop is idempotent when never started`() {
        // 多次 stop 不应抛异常
        engine.stop()
        engine.stop()
        assertTrue(!engine.isRunning())
    }
}
