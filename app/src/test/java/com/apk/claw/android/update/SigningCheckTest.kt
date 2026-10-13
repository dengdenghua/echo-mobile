package com.apk.claw.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater must tell "signed with another key, uninstall first" apart from a normal update
 * before it hands the APK to the system installer (which only says "App not installed").
 */
class SigningCheckTest {

    private val oldKey = "a".repeat(64)
    private val newKey = "b".repeat(64)
    private val pkg = "com.octopus.mobile"

    private fun facts(
        vararg signers: String,
        versionCode: Long = 15,
        packageName: String = pkg,
        lineage: Set<String> = signers.toSet(),
    ) = ApkFacts(packageName, versionCode, signers.toSet(), lineage, multipleSigners = signers.size > 1)

    @Test
    fun `same key and newer version installs`() {
        val verdict = SigningCheck.verdict(facts(newKey, versionCode = 15), facts(newKey, versionCode = 16))
        assertEquals(InstallVerdict.OK, verdict)
    }

    @Test
    fun `different key is a signature mismatch`() {
        // 1.0.0 (lost key) meeting 1.1.0 (new key), or 1.1.0 meeting a future rotation without lineage.
        assertEquals(
            InstallVerdict.SIGNATURE_MISMATCH,
            SigningCheck.verdict(facts(oldKey, versionCode = 14), facts(newKey, versionCode = 15)),
        )
    }

    @Test
    fun `a rotated key whose lineage contains the installed key is accepted`() {
        val rotated = facts(newKey, versionCode = 16, lineage = setOf(oldKey, newKey))
        assertEquals(InstallVerdict.OK, SigningCheck.verdict(facts(oldKey, versionCode = 15), rotated))
    }

    @Test
    fun `unparseable download, another app and an older build never reach the installer`() {
        val installed = facts(newKey, versionCode = 15)
        assertEquals(InstallVerdict.CORRUPT, SigningCheck.verdict(installed, null))
        assertEquals(
            InstallVerdict.OTHER_APP,
            SigningCheck.verdict(installed, facts(newKey, versionCode = 16, packageName = "com.example.other")),
        )
        assertEquals(InstallVerdict.NOT_NEWER, SigningCheck.verdict(installed, facts(newKey, versionCode = 15)))
        assertEquals(InstallVerdict.NOT_NEWER, SigningCheck.verdict(installed, facts(newKey, versionCode = 14)))
    }

    @Test
    fun `unknown certificates leave the decision to the system installer`() {
        val noInstalledCerts = SigningCheck.verdict(facts(versionCode = 15), facts(newKey, versionCode = 16))
        val noApkCerts = SigningCheck.verdict(facts(newKey, versionCode = 15), facts(versionCode = 16))
        assertEquals(InstallVerdict.UNKNOWN, noInstalledCerts)
        assertEquals(InstallVerdict.UNKNOWN, noApkCerts)
    }

    @Test
    fun `several signers must match exactly`() {
        val installed = facts(oldKey, newKey, versionCode = 15)
        assertEquals(InstallVerdict.OK, SigningCheck.verdict(installed, facts(oldKey, newKey, versionCode = 16)))
        val oneOfTwo = SigningCheck.verdict(installed, facts(newKey, versionCode = 16))
        assertEquals(InstallVerdict.SIGNATURE_MISMATCH, oneOfTwo)
    }

    @Test
    fun `server fingerprint decides before any download`() {
        val printed = newKey.chunked(2).joinToString(":").uppercase() // apksigner / keytool style
        assertTrue(SigningCheck.serverApkNeedsReinstall(printed, facts(oldKey)))
        assertFalse(SigningCheck.serverApkNeedsReinstall(printed, facts(newKey)))
        assertFalse(SigningCheck.serverApkNeedsReinstall(printed, facts(oldKey, lineage = setOf(newKey, oldKey))))
        // Nothing known → do not cry wolf; the download-time check still runs.
        assertFalse(SigningCheck.serverApkNeedsReinstall("", facts(oldKey)))
        assertFalse(SigningCheck.serverApkNeedsReinstall(printed, null))
        assertFalse(SigningCheck.serverApkNeedsReinstall(printed, facts()))
    }

    @Test
    fun `fingerprints normalize and hash like apksigner`() {
        assertEquals("ab12", SigningCheck.normalize(" AB:12 "))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SigningCheck.sha256Hex(ByteArray(0)),
        )
    }
}
