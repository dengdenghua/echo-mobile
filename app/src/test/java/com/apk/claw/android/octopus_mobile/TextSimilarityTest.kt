@file:Suppress("PackageNaming")   // 沿用既有 octopus_mobile 包(带下划线)

package com.apk.claw.android.octopus_mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextSimilarity 测试 —— 纯 JVM。近似(空白/填充词/高度重合)抓;真不同的(共享结构但语义不同)不误合并。
 */
class TextSimilarityTest {

    @Test
    fun `exact and whitespace variants are near duplicates`() {
        assertTrue(TextSimilarity.isNearDuplicate("别点广告", "别点广告"))
        assertTrue(TextSimilarity.isNearDuplicate("订机票", "  订机票  "))
    }

    @Test
    fun `filler words added still near duplicate (containment)`() {
        // 「订机票」的词项 ⊆「帮我订机票」→ 包含度高 → 近似
        assertTrue(TextSimilarity.isNearDuplicate("订机票", "帮我订机票"))
        assertTrue(TextSimilarity.isNearDuplicate("open taobao close popup", "please open taobao close popup first"))
    }

    @Test
    fun `distinct rules sharing structure are NOT merged`() {
        // 共享「打开/先关/关弹/弹窗」但淘宝≠京东 → 保守判定为不近似,保留两条
        assertFalse(TextSimilarity.isNearDuplicate("打开淘宝先关弹窗", "打开京东先关弹窗"))
    }

    @Test
    fun `unrelated texts are not near duplicates`() {
        assertFalse(TextSimilarity.isNearDuplicate("订机票", "查天气预报"))
        assertFalse(TextSimilarity.isNearDuplicate("我对花生过敏", "我住北京朝阳区"))
    }

    @Test
    fun `blank never matches`() {
        assertFalse(TextSimilarity.isNearDuplicate("", "订机票"))
        assertFalse(TextSimilarity.isNearDuplicate("   ", ""))
    }
}
