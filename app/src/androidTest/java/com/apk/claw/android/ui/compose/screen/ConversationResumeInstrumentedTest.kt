package com.apk.claw.android.ui.compose.screen

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationResumeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun closeSystemPanels() {
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
    }

    @Test fun switchingConversationsRestoresTheAnchorAndBackgroundResultsWaitForAnExplicitJump() {
        val first = newState("first")
        val second = newState("second")
        var lane by mutableStateOf(0)
        lateinit var controller: ConversationReadingController
        lateinit var scope: CoroutineScope
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { Fixture(if (lane == 0) first else second, onBound = { c, s -> controller = c; scope = s }) {
                        lane = 1 - lane
                    } }
                } }
                awaitText("Switch conversation")
                awaitMain { controller.ready && !controller.listState.canScrollForward }
                instrumentation.runOnMainSync { controller.pause(); scope.launch { controller.listState.scrollToItem(3, 47) } }
                awaitMain { first.reading.snapshot.anchor?.offset == 47 && controller.listState.firstVisibleItemIndex == 3 }
                val original = first.reading.snapshot.anchor!!
                click("Switch conversation")
                awaitMain { controller.reading === second.reading && controller.ready }
                instrumentation.runOnMainSync {
                    first.messages.add(ChatMessage.AgentMessage("New background result", remoteTaskId = "background"))
                    ChatStore.save(first.storageId, first.messages)
                }
                awaitMain { first.reading.unreadCount == 1 }
                assertEquals(1, DeviceExecutionStore.unreadCounts(first.character, first.environment)[first.sessionId])
                click("Switch conversation")
                awaitMain { controller.reading === first.reading && controller.ready }
                instrumentation.runOnMainSync {
                    assertEquals(3, controller.listState.firstVisibleItemIndex)
                    assertEquals(47, controller.listState.firstVisibleItemScrollOffset)
                    assertFalse(controller.following.value)
                    assertEquals(original.messageId, first.reading.snapshot.anchor?.messageId)
                }
                awaitText(newResults(1))
                saveScreenshot("conversation-new-result.png")
                click(newResults(1))
                awaitMain { first.reading.unreadCount == 0 && !controller.listState.canScrollForward &&
                    !controller.listState.isScrollInProgress }
                assertEquals(0, DeviceExecutionStore.unreadCounts(first.character, first.environment)[first.sessionId] ?: 0)
            }
        } finally { forget(first, second) }
    }

    @Test fun activityPauseAndFreshStorageRestoreKeepTheSameReadingPosition() {
        val state = newState("persisted")
        lateinit var controller: ConversationReadingController
        lateinit var scope: CoroutineScope
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { Fixture(state, onBound = { c, s -> controller = c; scope = s }) {} }
                } }
                awaitText("Switch conversation")
                awaitMain { controller.ready }
                instrumentation.runOnMainSync { controller.pause(); scope.launch { controller.listState.scrollToItem(5, 61) } }
                awaitMain { state.reading.snapshot.anchor?.offset == 61 }
                scenario.moveToState(Lifecycle.State.CREATED)
                awaitMain { !state.reading.visible }
                instrumentation.runOnMainSync {
                    state.messages.add(ChatMessage.AgentMessage("Completed while backgrounded", remoteTaskId = "paused-task"))
                    ChatStore.save(state.storageId, state.messages)
                }
                awaitMain { state.reading.unreadCount == 1 }
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitText("Switch conversation")
                awaitMain { controller.ready && state.reading.visible }
                instrumentation.runOnMainSync {
                    assertEquals(5, controller.listState.firstVisibleItemIndex)
                    assertEquals(61, controller.listState.firstVisibleItemScrollOffset)
                    assertEquals(1, state.reading.unreadCount)
                }
            }
            val restoredMessages = ChatStore.load(state.storageId)!!
            val restored = ConversationReadingState(UnreadConversation(state.storageId, state.sessionId,
                state.character, state.environment.id, state.host, 0))
            restored.initialize(restoredMessages)
            assertEquals(1, restored.unreadCount)
            assertEquals(state.messages.map { it.id }, restoredMessages.map { it.id })
            val saved = state.reading.snapshot.anchor!!
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { ReadingFixture(restored, restoredMessages, false, { controller = it }) {} }
                } }
                awaitText("Switch conversation")
                awaitMain { controller.ready }
                instrumentation.runOnMainSync {
                    assertEquals(5, controller.listState.firstVisibleItemIndex)
                    assertEquals(61, controller.listState.firstVisibleItemScrollOffset)
                    assertEquals(saved.messageId, restored.snapshot.anchor?.messageId)
                    assertFalse(controller.following.value)
                }
            }
        } finally { forget(state) }
    }

    @Test fun latestReachesTheEndOfOneTallReplyAndPausedCompletionDoesNotMoveTheReader() {
        val state = newState("tall", empty = true)
        lateinit var controller: ConversationReadingController
        lateinit var scope: CoroutineScope
        instrumentation.runOnMainSync {
            state.messages.add(ChatMessage.AgentMessage((1..180).joinToString("\n") { "Long answer line $it" }, streaming = true))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { Fixture(state, tall = true, onBound = { c, s -> controller = c; scope = s }) {} }
                } }
                awaitText("Switch conversation")
                awaitMain { controller.ready && !controller.listState.canScrollForward }
                instrumentation.runOnMainSync { controller.pause(); scope.launch { controller.listState.scrollToItem(0, 83) } }
                awaitMain { state.reading.snapshot.anchor?.offset == 83 }
                instrumentation.runOnMainSync {
                    val reply = state.messages.single() as ChatMessage.AgentMessage
                    state.messages[0] = reply.copy(streaming = false)
                }
                awaitMain { state.reading.unreadCount == 1 }
                instrumentation.runOnMainSync { assertEquals(83, controller.listState.firstVisibleItemScrollOffset) }
                click(newResults(1))
                awaitMain { !controller.listState.canScrollForward && state.reading.unreadCount == 0 &&
                    !controller.listState.isScrollInProgress }
                instrumentation.runOnMainSync { assertTrue(controller.listState.firstVisibleItemScrollOffset > 83) }
            }
        } finally { forget(state) }
    }

    private fun newState(name: String, empty: Boolean = false): DeviceExecutionState {
        lateinit var state: DeviceExecutionState
        instrumentation.runOnMainSync {
            state = DeviceExecutionStore.state("reading-$name-${UUID.randomUUID()}", "general", DotEnvironment.PHONE)
            if (!empty) repeat(20) { state.messages.add(ChatMessage.AgentMessage("$name · reply $it")) }
            state.reading.updateResults(conversationResultKeys(state.messages)); state.reading.markSeen()
            ChatStore.save(state.storageId, state.messages)
        }
        return state
    }

    @Composable private fun Fixture(
        state: DeviceExecutionState, tall: Boolean = false,
        onBound: (ConversationReadingController, CoroutineScope) -> Unit, onSwitch: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        ReadingFixture(state.reading, state.messages, tall, { onBound(it, scope) }, onSwitch)
    }

    @Composable private fun ReadingFixture(
        reading: ConversationReadingState, messages: List<ChatMessage>, tall: Boolean,
        onBound: (ConversationReadingController) -> Unit, onSwitch: () -> Unit,
    ) {
        val owner = LocalLifecycleOwner.current
        var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) foreground = true
                if (event == Lifecycle.Event.ON_PAUSE) foreground = false
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        val rows = messages.map { ReadingRow("m${it.id}", listOf(it.id)) }
        val controller = rememberConversationReading(reading, rows, messages, foreground)
        onBound(controller)
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row { TextButton(onClick = onSwitch) { Text("Switch conversation") } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = controller.listState) {
                items(messages, key = { "m${it.id}" }) { message ->
                    Text((message as ChatMessage.AgentMessage).text,
                        Modifier.fillMaxWidth().then(if (tall) Modifier else Modifier.height(100.dp)))
                }
            }
            ScrollToBottomButton(visible = reading.unreadCount > 0,
                label = pluralStringResource(R.plurals.chat_new_results, reading.unreadCount, reading.unreadCount),
                onClick = { controller.jumpToLatest() })
        }
    }

    private fun forget(vararg states: DeviceExecutionState) {
        instrumentation.runOnMainSync { states.forEach { DeviceExecutionStore.forget(it.sessionId) } }
    }
    private fun newResults(count: Int) = instrumentation.targetContext.resources.getQuantityString(R.plurals.chat_new_results, count, count)
    private fun awaitMain(predicate: () -> Boolean) {
        repeat(150) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Reading position did not settle")
    }
    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        node.refresh()
        if (node.text?.toString() == text) return node
        return (0 until node.childCount).firstNotNullOfOrNull { findText(node.getChild(it), text) }
    }
    private fun awaitText(text: String) {
        repeat(100) {
            instrumentation.waitForIdleSync()
            if (findText(instrumentation.uiAutomation.rootInActiveWindow, text) != null) return
            Thread.sleep(100)
        }
        fail("Expected result reminder: $text")
    }
    private fun click(text: String) {
        awaitText(text)
        var node = requireNotNull(findText(instrumentation.uiAutomation.rootInActiveWindow, text))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun saveScreenshot(name: String) {
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = instrumentation.targetContext.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EchoMobileTests")
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
