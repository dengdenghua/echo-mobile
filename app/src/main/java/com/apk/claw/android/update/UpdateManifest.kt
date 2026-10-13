package com.apk.claw.android.update

import com.apk.claw.android.update.AppUpdater.CheckResult
import com.apk.claw.android.update.AppUpdater.ErrorKind
import com.apk.claw.android.update.AppUpdater.UpdateInfo
import org.json.JSONObject

/** The network-free half of the update check: the request URL and the server's answer. */
object UpdateManifest {

    /** Sends this install's signing certificate, so the server can pick the notes for a key change. */
    fun latestUrl(base: String, installed: ApkFacts?): String {
        val cert = installed?.signers?.singleOrNull()
        return if (cert.isNullOrEmpty()) "$base/app/latest" else "$base/app/latest?cert=$cert"
    }

    /** `GET /app/latest` → up to date, an update (flagged when it needs an uninstall first), or an error. */
    fun parse(body: String, currentVersionCode: Int, installed: ApkFacts?): CheckResult {
        val o = JSONObject(body)
        val vc = o.optInt("versionCode", 0)
        val url = o.optString("url")
        return when {
            vc <= currentVersionCode -> CheckResult.UpToDate
            url.isBlank() -> CheckResult.Error("update url is empty", ErrorKind.BAD_RESPONSE)
            else -> {
                val cert = SigningCheck.normalize(o.optString("signingCertSha256"))
                CheckResult.Available(
                    UpdateInfo(
                        versionCode = vc,
                        versionName = o.optString("versionName", "v$vc"),
                        url = url,
                        notes = o.optString("notes"),
                        force = o.optBoolean("force", false),
                        signingCertSha256 = cert,
                        reinstallRequired = SigningCheck.serverApkNeedsReinstall(cert, installed),
                    ),
                )
            }
        }
    }
}
