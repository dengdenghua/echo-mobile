package com.apk.claw.android.octopus_mobile.uitree

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UiNode / UiSelector 单元测试。
 *
 * 不依赖 Android Framework（纯数据类 + 逻辑），可在 JVM 跑。
 * AccessibilityNodeInfo 相关的 fromA11y 测试需 Robolectric，此处只测纯逻辑。
 */
class UiNodeTest {

    private fun makeNode(
        text: String = "",
        desc: String = "",
        viewId: String = "",
        bounds: Rect = Rect(0, 0, 100, 100),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        children: List<UiNode> = emptyList(),
    ): UiNode = UiNode(
        stableId = "$viewId:${text.hashCode()}:$bounds",
        className = "android.widget.Button",
        text = text,
        desc = desc,
        viewId = viewId,
        packageName = "com.test",
        bounds = bounds,
        normBounds = RectF(0f, 0f, 1f, 1f),
        isClickable = clickable,
        isLongClickable = false,
        isScrollable = scrollable,
        isEditable = false,
        isChecked = false,
        isEnabled = true,
        isFocused = false,
        isSelected = false,
        actions = mutableListOf<String>().apply {
            if (clickable) add("CLICK")
            if (scrollable) add("SCROLL")
        },
        children = children,
    )

    @Test
    fun `toText contains essential fields`() {
        val node = makeNode(text = "登录", viewId = "com.x:id/btn_login", clickable = true)
        val text = node.toText()
        assertTrue("text should contain button label", text.contains("登录"))
        assertTrue("text should contain viewId", text.contains("com.x:id/btn_login"))
        assertTrue("text should contain clickable flag", text.contains("[clickable]"))
    }

    @Test
    fun `toJson produces valid JSON structure`() {
        val node = makeNode(text = "hello", clickable = true)
        val json = node.toJson()
        assertEquals("hello", json.getString("text"))
        assertEquals("android.widget.Button", json.getString("class"))
        assertTrue(json.getJSONArray("flags").toString().contains("clickable"))
        assertTrue(json.getJSONArray("actions").toString().contains("CLICK"))
    }

    @Test
    fun `stableId is deterministic for same input`() {
        val n1 = makeNode(text = "登录", viewId = "id1", bounds = Rect(0, 0, 100, 200))
        val n2 = makeNode(text = "登录", viewId = "id1", bounds = Rect(0, 0, 100, 200))
        assertEquals(n1.stableId, n2.stableId)
    }

    @Test
    fun `UiTree flatten traverses depth-first`() {
        val leaf1 = makeNode(text = "leaf1")
        val leaf2 = makeNode(text = "leaf2")
        val mid = makeNode(text = "mid", children = listOf(leaf1, leaf2))
        val root = makeNode(text = "root", children = listOf(mid))
        val tree = UiTree(root, 1080, 1920, "com.test", 0L, "a11y")

        val flat = tree.flatten()
        assertEquals(4, flat.size) // root + mid + leaf1 + leaf2
        assertEquals("root", flat[0].text)
        assertEquals("mid", flat[1].text)
    }

    @Test
    fun `selector byText matches exact text`() {
        val tree = UiTree(
            makeNode(text = "root", children = listOf(
                makeNode(text = "登录", clickable = true),
                makeNode(text = "注册"),
            )),
            1080, 1920, "com.test", 0L, "a11y"
        )
        val matches = tree.find(byText("登录"))
        assertEquals(1, matches.size)
        assertTrue(matches[0].isClickable)
    }

    @Test
    fun `selector byTextContains matches partial`() {
        val tree = UiTree(
            makeNode(children = listOf(
                makeNode(text = "用户名输入框"),
                makeNode(text = "密码输入框"),
            )),
            1080, 1920, "com.test", 0L, "a11y"
        )
        val matches = tree.find(byTextContains("输入框"))
        assertEquals(2, matches.size)
    }

    @Test
    fun `selector compound AND conditions`() {
        val tree = UiTree(
            makeNode(children = listOf(
                makeNode(text = "登录", clickable = true),
                makeNode(text = "登录", clickable = false), // 不可点的同名节点
                makeNode(text = "注册", clickable = true),
            )),
            1080, 1920, "com.test", 0L, "a11y"
        )
        val matches = tree.find(byText("登录") and byClickable())
        assertEquals(1, matches.size)
        assertEquals("登录", matches[0].text)
    }

    @Test
    fun `findAt returns smallest node containing point`() {
        val outer = makeNode(bounds = Rect(0, 0, 500, 500))
        val inner = makeNode(bounds = Rect(100, 100, 200, 200), text = "inner")
        val tree = UiTree(
            outer.copy(children = listOf(inner)),
            500, 500, "com.test", 0L, "a11y"
        )
        val hit = tree.findAt(150, 150)
        assertNotNull(hit)
        assertEquals("inner", hit!!.text)
    }

    @Test
    fun `findAt returns null for point outside any node`() {
        val tree = UiTree(
            makeNode(bounds = Rect(0, 0, 100, 100)),
            500, 500, "com.test", 0L, "a11y"
        )
        assertNull(tree.findAt(400, 400))
    }

    @Test
    fun `center returns midpoint of bounds`() {
        val node = makeNode(bounds = Rect(0, 0, 100, 200))
        val (cx, cy) = node.center()
        assertEquals(50, cx)
        assertEquals(100, cy)
    }

    @Test
    fun `anyOf matches any selector in disjunction`() {
        val tree = UiTree(
            makeNode(children = listOf(
                makeNode(text = "登录"),
                makeNode(text = "注册"),
                makeNode(text = "取消"),
            )),
            1080, 1920, "com.test", 0L, "a11y"
        )
        val matches = tree.find(anyOf(byText("登录"), byText("取消")))
        assertEquals(2, matches.size)
    }

    @Test
    fun `first returns first match or null`() {
        val tree = UiTree(
            makeNode(children = listOf(
                makeNode(text = "登录"),
                makeNode(text = "登录"),
            )),
            1080, 1920, "com.test", 0L, "a11y"
        )
        assertNotNull(tree.first(byText("登录")))
        assertNull(tree.first(byText("不存在")))
    }
}
