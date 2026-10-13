package com.apk.claw.android.ui.settings

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.apk.claw.android.octopus_mobile.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Found on the release golden path: Settings > Advanced > Echo Runtime showed the auth token from
 * an `echo://join` invite in clear text, and the connection state as English enum names
 * ("DISCONNECTED", "CONNECTING...") in the Chinese UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeConfigViewsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the old order, password type then setSingleLine, shows the secret`() {
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        assertFalse(field.transformationMethod is PasswordTransformationMethod)
    }

    @Test
    fun `a secret single-line field stays masked`() {
        val field = EditText(context).apply { makeSecretSingleLine() }
        assertTrue(field.transformationMethod is PasswordTransformationMethod)
        assertEquals(1, field.maxLines)
    }

    @Test
    @Config(qualifiers = "zh")
    fun `every connection state has a Chinese label`() {
        val labels = ConnectionState.values().associateWith { context.getString(runtimeStatusLabel(it)) }
        assertEquals("未连接", labels[ConnectionState.DISCONNECTED])
        assertEquals("连接中…", labels[ConnectionState.CONNECTING])
        assertEquals(ConnectionState.values().size, labels.values.toSet().size)
        labels.values.forEach { assertFalse("untranslated: $it", it.matches(Regex("[A-Z_ .()✓]+"))) }
    }
}
