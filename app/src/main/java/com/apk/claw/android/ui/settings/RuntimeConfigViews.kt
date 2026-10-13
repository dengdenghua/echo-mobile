package com.apk.claw.android.ui.settings

import android.text.InputType
import android.widget.EditText
import androidx.annotation.StringRes
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.ConnectionState

/**
 * Makes [field] a single-line field whose text stays masked.
 *
 * The order matters: setSingleLine() installs a single-line transformation, so calling it after a
 * password inputType replaces the password transformation and shows the secret in clear text
 * (the Echo Runtime auth token was shown this way). The password inputType is already single line.
 */
internal fun EditText.makeSecretSingleLine() {
    setSingleLine()
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
}

/** User-facing label for a runtime connection state (they used to be English enum-style text). */
@StringRes
internal fun runtimeStatusLabel(state: ConnectionState): Int = when (state) {
    ConnectionState.DISCONNECTED -> R.string.runtime_status_disconnected
    ConnectionState.CONNECTING -> R.string.runtime_status_connecting
    ConnectionState.CONNECTED -> R.string.runtime_status_connected
    ConnectionState.HELLO_SENT -> R.string.runtime_status_hello_sent
    ConnectionState.ONLINE -> R.string.runtime_status_online
    ConnectionState.RECONNECTING -> R.string.runtime_status_reconnecting
    ConnectionState.OFFLINE -> R.string.runtime_status_offline
}
