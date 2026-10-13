package com.apk.claw.android.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/** What an APK declares about itself, as far as "can it replace the installed app?" goes. */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    /** Current signers, SHA-256 lowercase hex. */
    val signers: Set<String>,
    /** Every certificate in the APK's signing lineage (v3 key rotation), including [signers]. */
    val lineage: Set<String>,
    val multipleSigners: Boolean,
)

/** Whether a downloaded update can be installed over this app. */
enum class InstallVerdict {
    /** Same package, newer, and a signer Android will accept. */
    OK,

    /** Different signing key: Android would refuse ("App not installed"). Uninstall first. */
    SIGNATURE_MISMATCH,

    /** Not a parseable APK (cut-off download, an HTML error page...). */
    CORRUPT,

    /** A parseable APK for some other app. */
    OTHER_APP,

    /** Same version or older: the installer would refuse or do nothing useful. */
    NOT_NEWER,

    /** Certificates could not be read on one side; let the system installer decide. */
    UNKNOWN,
}

/**
 * Signing checks for the in-app updater, so a key change shows a clear "uninstall first"
 * message instead of the system installer's bare "App not installed".
 */
object SigningCheck {

    /** Normalize a SHA-256 fingerprint: lowercase hex, no `:` or spaces (apksigner/keytool print `AB:CD:…`). */
    fun normalize(fingerprint: String?): String =
        fingerprint.orEmpty().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Pure decision, unit-tested; [apk] is null when the file is not a parseable APK. */
    fun verdict(installed: ApkFacts, apk: ApkFacts?): InstallVerdict = when {
        apk == null -> InstallVerdict.CORRUPT
        apk.packageName != installed.packageName -> InstallVerdict.OTHER_APP
        apk.versionCode <= installed.versionCode -> InstallVerdict.NOT_NEWER
        installed.signers.isEmpty() || apk.signers.isEmpty() -> InstallVerdict.UNKNOWN
        compatible(installed, apk) -> InstallVerdict.OK
        else -> InstallVerdict.SIGNATURE_MISMATCH
    }

    /**
     * Android accepts an update signed by the same key, or by a newer key whose lineage contains the
     * installed one (APK signature scheme v3 rotation). Apps with several signers need the same set.
     */
    private fun compatible(installed: ApkFacts, apk: ApkFacts): Boolean =
        if (installed.multipleSigners || apk.multipleSigners) {
            installed.signers == apk.signers
        } else {
            (installed.lineage + installed.signers).any { it in apk.lineage || it in apk.signers }
        }

    /**
     * True when the server says its APK is signed by a certificate this install cannot accept, so the
     * update dialog should explain the uninstall right away instead of downloading first.
     * Unknown on either side → false (the download-time check still runs).
     */
    fun serverApkNeedsReinstall(serverCert: String, installed: ApkFacts?): Boolean {
        val cert = normalize(serverCert)
        if (cert.isEmpty() || installed == null || installed.multipleSigners) return false
        return installed.signers.isNotEmpty() && cert !in installed.signers && cert !in installed.lineage
    }

    /** This app as installed, or null when the package manager cannot say. */
    fun installed(context: Context): ApkFacts? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        facts(info)
    }.getOrNull()

    /** The APK at [apk], or null when it is not a parseable APK. */
    fun archive(context: Context, apk: File): ApkFacts? = runCatching {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?.let(::facts)
    }.getOrNull()

    private fun facts(info: PackageInfo): ApkFacts {
        val signing: SigningInfo? = info.signingInfo
        val multiple = signing?.hasMultipleSigners() == true
        // signingCertificateHistory: oldest first, the current signer last.
        val current = when {
            signing == null -> emptyList()
            multiple -> signing.apkContentsSigners.toList()
            else -> listOfNotNull(signing.signingCertificateHistory.lastOrNull())
        }
        val history = if (signing == null || multiple) emptyList() else signing.signingCertificateHistory.toList()
        return ApkFacts(
            packageName = info.packageName.orEmpty(),
            versionCode = info.longVersionCode,
            signers = current.map { sha256Hex(it.toByteArray()) }.toSet(),
            lineage = history.map { sha256Hex(it.toByteArray()) }.toSet(),
            multipleSigners = multiple,
        )
    }
}
