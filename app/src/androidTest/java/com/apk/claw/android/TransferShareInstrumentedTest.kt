package com.apk.claw.android

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apk.claw.android.transfer.TransferActivity
import com.apk.claw.android.transfer.TransferStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TransferShareInstrumentedTest {
    @Test fun sharedFileIsImportedThroughAndroidContentResolver() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "echo-share-${System.currentTimeMillis()}.txt"
        val bytes = "手机分享 → Echo → 电脑\n".toByteArray()
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        })!!
        val target = TransferStore(File(context.filesDir, "exchange")).file(name)
        var activity: TransferActivity? = null
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            activity = instrumentation.startActivitySync(Intent(context, TransferActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }) as TransferActivity
            val deadline = System.currentTimeMillis() + 10_000
            while (!target.isFile && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("System share must publish an exchange file", target.isFile)
            assertArrayEquals(bytes, target.readBytes())
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            resolver.delete(uri, null, null)
            target.delete()
        }
    }
}
