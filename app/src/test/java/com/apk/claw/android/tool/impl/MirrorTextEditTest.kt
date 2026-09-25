package com.apk.claw.android.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorTextEditTest {
    @Test fun insertionPreservesSurroundingText() {
        assertEquals(MirrorTextEdit.Result("hello你好 world", 7, 7),
            MirrorTextEdit.apply("hello world", 5, 5, "insert", "你好"))
    }
    @Test fun selectedTextIsReplacedEvenWithReversedSelection() {
        assertEquals(MirrorTextEdit.Result("你好 Echo", 2, 2), MirrorTextEdit.apply("hello Echo", 5, 0, "insert", "你好"))
    }
    @Test fun deletionDoesNotSplitEmoji() {
        assertEquals(MirrorTextEdit.Result("ab", 1, 1), MirrorTextEdit.apply("a😀b", 3, 3, "delete_backward"))
        assertEquals(MirrorTextEdit.Result("ab", 1, 1), MirrorTextEdit.apply("a😀b", 1, 1, "delete_forward"))
    }
    @Test fun cursorAndSelectAllKeepText() {
        assertEquals(MirrorTextEdit.Result("a😀b", 1, 1), MirrorTextEdit.apply("a😀b", 3, 3, "move_left"))
        assertEquals(MirrorTextEdit.Result("你好", 0, 2), MirrorTextEdit.apply("你好", 1, 1, "select_all"))
    }
    @Test(expected = IllegalArgumentException::class) fun missingCursorCannotOverwriteField() {
        MirrorTextEdit.apply("important", -1, -1, "insert", "bad")
    }
}
