package com.apk.claw.android.ui.featurescreens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.EchoProgressLineCenteredWidth
import com.apk.claw.android.ui.compose.component.EchoProgressLine
import com.apk.claw.android.ui.compose.component.EchoProgressLineInlineWidth
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.channel.wechat.WeChatApiClient
import com.apk.claw.android.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 微信扫码登录页。复用既有 [WeChatApiClient] 的扫码登录接口:
 *   getQrCode() → 展示二维码 → pollQrCodeStatus()(长轮询)→ confirmed 后拿到 botToken/baseUrl
 *   → 存入 MMKV → ChannelManager.reinitWeChatFromStorage() 启动消息长轮询。
 */
class WeChatLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { WeChatLoginScreen(onBack = { finish() }) }
    }
}

private sealed class QrUi {
    object Idle : QrUi()
    object Loading : QrUi()
    data class Show(val bmp: Bitmap) : QrUi()
    object Success : QrUi()
    object Failed : QrUi()
}

@Composable
private fun WeChatLoginScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val alreadyLinked = remember { KVUtils.getWechatBotToken().isNotEmpty() }
    var ui by remember { mutableStateOf<QrUi>(QrUi.Idle) }

    fun startLogin() {
        scope.launch {
            ui = QrUi.Loading
            val client = WeChatApiClient()
            val qr = withContext(Dispatchers.IO) { client.getQrCode() }
            val bmp = qr?.let { withContext(Dispatchers.IO) { decodeQr(it.qrcodeImgContent) } }
            if (qr == null || bmp == null) { ui = QrUi.Failed; return@launch }
            ui = QrUi.Show(bmp)
            // 长轮询(每次超时返回 null),最多约 40 轮
            var auth = withContext(Dispatchers.IO) { client.pollQrCodeStatus(qr.qrcode) }
            var tries = 1
            while (auth == null && tries < 40 && isActive && ui is QrUi.Show) {
                auth = withContext(Dispatchers.IO) { client.pollQrCodeStatus(qr.qrcode) }
                tries++
            }
            val confirmed = auth
            if (confirmed != null) {
                KVUtils.setWechatBotToken(confirmed.botToken)
                KVUtils.setWechatApiBaseUrl(confirmed.baseUrl)
                withContext(Dispatchers.IO) { runCatching { ChannelManager.reinitWeChatFromStorage() } }
                ui = QrUi.Success
            } else if (ui is QrUi.Show) {
                ui = QrUi.Failed
            }
        }
    }

    FeatureScaffold(title = stringResource(R.string.wechat_login_title), onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.wechat_login_hint),
                color = FSub, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(20.dp))

            when (val s = ui) {
                is QrUi.Idle -> {
                    if (alreadyLinked) {
                        Text(stringResource(R.string.wechat_login_linked), color = FSuccess, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(16.dp))
                    }
                    PrimaryButton(stringResource(if (alreadyLinked) R.string.wechat_login_relogin else R.string.wechat_login_get_qr)) { startLogin() }
                }
                is QrUi.Loading -> EchoProgressLine(Modifier.width(EchoProgressLineCenteredWidth))
                is QrUi.Show -> {
                    Surface(shape = RoundedCornerShape(12.dp), color = androidx.compose.ui.graphics.Color.White) {
                        Image(bitmap = s.bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(240.dp).padding(8.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EchoProgressLine(Modifier.width(EchoProgressLineInlineWidth))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.wechat_login_waiting), color = FMuted, fontSize = 13.sp)
                    }
                }
                is QrUi.Success -> {
                    Text(stringResource(R.string.wechat_login_success), color = FSuccess, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(stringResource(R.string.wechat_login_done)) { onBack() }
                }
                is QrUi.Failed -> {
                    Text(
                        stringResource(R.string.wechat_login_failed),
                        color = FError, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(stringResource(R.string.wechat_login_get_qr)) { startLogin() }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = FPrimary,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            color = com.apk.claw.android.ui.compose.theme.OctopusColors.OnPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
        )
    }
}

private const val QR_SIZE_PX = 480

/**
 * 把服务端返回的 `qrcode_img_content` 转成二维码 Bitmap。
 *
 * 实测该字段是「要编码成二维码的 URL 字符串」(如 https://liteapp.weixin.qq.com/q/... ),**不是** base64
 * 图片 —— 旧实现直接 Base64.decode 会因 URL 里的 `:/?&` 非法字符抛异常返回 null,导致二维码出不来。
 * 现改为:是 base64 图片(data:image / 裸 PNG·JPEG 头)就解码;否则(常态)用 ZXing 把字符串编码成二维码。
 */
@Suppress("ReturnCount")
private fun decodeQr(content: String): Bitmap? {
    val c = content.trim()
    if (c.isBlank()) return null
    if (c.startsWith("data:image") || c.startsWith("iVBOR") || c.startsWith("/9j/")) {
        val b64 = if (c.contains("base64,")) c.substringAfter("base64,") else c
        runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.let { return it }
    }
    return encodeQr(c, QR_SIZE_PX)
}

/** ZXing:把文本编码为二维码 Bitmap(与 SettingsViewModel.generateQrBitmap 同一套)。 */
private fun encodeQr(content: String, size: Int): Bitmap? = runCatching {
    val hints = mapOf(
        com.google.zxing.EncodeHintType.MARGIN to 1,
        com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    bmp
}.getOrNull()
