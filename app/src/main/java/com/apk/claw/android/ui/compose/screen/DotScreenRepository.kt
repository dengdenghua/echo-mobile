package com.apk.claw.android.ui.compose.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.apk.claw.android.utils.OctoHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetch only the selected host's screen; never acquire its control lease. */
internal object DotScreenRepository {
    class ScreenException(val code: Int) : IOException()
    private val client = OctoHttp.shared.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    suspend fun frame(environment: DotEnvironment): Bitmap {
        require(environment != DotEnvironment.PHONE)
        val connection = DotPairingRepository.connection(environment)
        require(DotRepository.validBaseUrl(connection.base))
        val request = Request.Builder().url(connection.base + "/api/computer/screen").apply {
            if (connection.token.isNotBlank()) header("Authorization", "Bearer ${connection.token}")
        }.build()
        val bytes = suspendCancellableCoroutine<ByteArray> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use {
                            if (!it.isSuccessful) throw ScreenException(it.code)
                            val body = requireNotNull(it.body)
                            require(body.contentLength() <= 8 * 1024 * 1024)
                            body.byteStream().use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= 8 * 1024 * 1024)
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                        if (continuation.isActive) continuation.resume(data)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
        return withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val options = BitmapFactory.Options().apply {
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2
            }
            requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        }
    }
}
