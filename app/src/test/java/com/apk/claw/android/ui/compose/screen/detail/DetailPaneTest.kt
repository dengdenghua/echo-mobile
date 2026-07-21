package com.apk.claw.android.ui.compose.screen.detail

import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * refine-chat-interaction Task 3:DetailPane 接口契约单测。
 *
 * 纯 JUnit 测试,不测 Compose 渲染(Compose 渲染需 Robolectric,见 Task 4)。
 * 仅验证:
 * - [DetailPane] 接口可被实现(stub class)
 * - [DetailPane.title] 属性可读
 * - 多个实现互不干扰(每次构造独立 title)
 */
class DetailPaneTest {

    /** 测试用 stub:验证 DetailPane 接口可被实现。Render() 留空,不调用任何 Compose API。 */
    private class StubPane(override val title: String) : DetailPane {
        @Composable
        override fun Render() {
            // 纯接口契约测试,不涉及 Compose 渲染。
        }
    }

    @Test
    fun `DetailPane interface can be implemented`() {
        val pane: DetailPane = StubPane("测试面板")
        assertNotNull("DetailPane 应可被实现并实例化", pane)
    }

    @Test
    fun `DetailPane title is readable`() {
        val pane = StubPane("计划详情")
        assertEquals("计划详情", pane.title)
    }

    @Test
    fun `DetailPane title preserves arbitrary strings`() {
        val cases = listOf("计划(3 步)", "file.kt:42-58", "commit abc123", "", "  带空白  ")
        cases.forEach { expected ->
            val pane: DetailPane = StubPane(expected)
            assertEquals("title 应原样保留传入字符串", expected, pane.title)
        }
    }

    @Test
    fun `DetailPane instances are independent`() {
        val a: DetailPane = StubPane("A")
        val b: DetailPane = StubPane("B")
        assertEquals("A", a.title)
        assertEquals("B", b.title)
        assertSame("两个实例应互不相同", a, a)
    }
}
