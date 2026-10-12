package com.apk.claw.android.ui.compose.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.ui.compose.component.OctopusTextPill
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.ui.compose.theme.tvFocusable
import com.apk.claw.android.ui.compose.theme.tvOverscan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 发帖编辑页(小红书式)。
 *
 * - 标题(必填,1-100 字) + 正文(选填,5000 字内) + 多图(最多 9 张,Photo Picker)
 * + 标签(选填)
 * - 图片先调 SquarePostApi.uploadImage 上传到服务端拿 URL,再随帖一起 publish
 * - 未登录直接拦截提示;发布中禁用按钮 + 转圈;成功后 onPublished 回调让上层关闭页面
 */
private const val MAX_POST_PRICE_CREDITS = 1000
private const val MAX_PRICE_DIGITS = 4

private val CREATE_POST_TOPICS = listOf(
    "recommend" to R.string.agent_square_tab_recommend,
    "automation" to R.string.agent_topic_automation,
    "efficiency" to R.string.agent_topic_efficiency,
    "life" to R.string.agent_topic_life,
    "learning" to R.string.agent_topic_learning,
    "device" to R.string.agent_topic_device,
)

@Composable
fun CreatePostScreen(
    onBack: () -> Unit,
    onPublished: () -> Unit = {},
    onMessage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    val imageUris = rememberSaveable(
        saver = listSaver(
            save = { it.map { uri -> uri.toString() } },
            restore = { restored ->
                mutableStateListOf<Uri>().apply {
                    restored.forEach { add(Uri.parse(it)) }
                }
            },
        ),
    ) { mutableStateListOf<Uri>() }
    val uploadedUrls = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { mutableStateListOf<String>().apply { addAll(it) } },
        ),
    ) { mutableStateListOf<String>() }
    var publishing by remember { mutableStateOf(false) }
    var topic by rememberSaveable { mutableStateOf("recommend") }
    var appRef by rememberSaveable { mutableStateOf("") }
    var priceText by rememberSaveable { mutableStateOf("") }

    // Photo Picker:多选,上限 9 张
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 控制总张数 ≤ 9
            val remain = 9 - imageUris.size
            imageUris.addAll(uris.take(remain))
        }
    }

    fun pickImages() {
        if (imageUris.size >= 9) {
            onMessage("最多 9 张图")
            return
        }
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun publish() {
        val t = title.trim()
        if (t.isEmpty()) {
            onMessage("请填写标题")
            return
        }
        if (!AccountStore.isLoggedIn) {
            onMessage("请先登录")
            return
        }
        scope.launch {
            publishing = true
            try {
                // 1) 先上传所有图片(串行,避免并发打爆服务端限流)
                val urls = mutableListOf<String>()
                for (u in imageUris) {
                    val file = withContext(Dispatchers.IO) { uriToFile(context, u) } ?: continue
                    val r = SquarePostApi.uploadImage(file)
                    if (r.url.isNotBlank()) urls.add(r.url)
                }
                // 2) 发帖(带分类;appRef 非空则为可复刻应用帖 + 定价)
                val r = SquarePostApi.publishPost(
                    t, content.trim(), urls, tag.trim(),
                    topic = topic,
                    appRef = appRef.trim(),
                    priceCredits = priceText.toIntOrNull()?.coerceIn(0, MAX_POST_PRICE_CREDITS) ?: 0,
                )
                if (r.ok) {
                    onMessage(r.message.ifBlank { "已提交,审核通过后就会出现在广场" })
                    // 清空草稿
                    title = ""
                    content = ""
                    tag = ""
                    imageUris.clear()
                    uploadedUrls.clear()
                    topic = "recommend"
                    appRef = ""
                    priceText = ""
                    onPublished()
                } else {
                    onMessage(r.reason.ifBlank { "发布失败" })
                }
            } catch (e: Exception) {
                onMessage("发布失败:${e.message ?: e::class.simpleName}")
            } finally {
                publishing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .tvOverscan(),
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = OctopusSpacing.sm, end = OctopusSpacing.lg, top = OctopusSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = OctopusColors.TextPrimary,
                )
            }
            Text(
                stringResource(R.string.create_post_title),
                modifier = Modifier.weight(1f),
                color = OctopusColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Button(
                onClick = { publish() },
                enabled = !publishing && title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = OctopusColors.Primary),
                contentPadding = PaddingValues(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.xs),
                modifier = Modifier.tvFocusable(),
            ) {
                if (publishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = OctopusColors.OnPrimary,
                    )
                    Spacer(Modifier.width(OctopusSpacing.xs))
                    Text(
                        stringResource(R.string.create_post_publishing),
                        color = OctopusColors.OnPrimary,
                        fontSize = OctopusType.body,
                    )
                } else {
                    Text(
                        stringResource(R.string.create_post_publish),
                        color = OctopusColors.OnPrimary,
                        fontSize = OctopusType.body,
                    )
                }
            }
        }

        // ── 表单 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        ) {
            // 标题
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 100) title = it },
                label = { Text(stringResource(R.string.create_post_title_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors(),
                shape = OctopusShape.medium,
            )

            // 正文
            OutlinedTextField(
                value = content,
                onValueChange = { if (it.length <= 5000) content = it },
                label = { Text(stringResource(R.string.create_post_content_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                colors = fieldColors(),
                shape = OctopusShape.medium,
            )

            // 图片选择(第一张即封面;点其他图设为封面)
            Text(
                stringResource(R.string.create_post_images_label),
                color = OctopusColors.TextSecondary,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (imageUris.isEmpty()) "加图后,第一张自动作为封面" else "第一张为封面 · 点其他图可设为封面",
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.tag,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
                contentPadding = PaddingValues(end = OctopusSpacing.lg),
            ) {
                itemsIndexed(imageUris) { index, uri ->
                    ImageThumb(
                        uri = uri,
                        isCover = index == 0,
                        onSetCover = {
                            // 移到首位 = 设为封面(服务端取 images[0] 作 cover)
                            if (index > 0) {
                                imageUris.removeAt(index)
                                imageUris.add(0, uri)
                            }
                        },
                        onRemove = { imageUris.remove(uri) },
                    )
                }
                if (imageUris.size < 9) {
                    item { AddImageButton(remaining = 9 - imageUris.size, onClick = { pickImages() }) }
                }
            }

            // 标签
            OutlinedTextField(
                value = tag,
                onValueChange = { if (it.length <= 20) tag = it },
                label = { Text(stringResource(R.string.create_post_tag_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors(),
                shape = OctopusShape.medium,
            )

            // 分类(小红书式;每帖必有一个 topic,驱动灵感 feed 的分类筛选)
            Text(
                "分类",
                color = OctopusColors.TextSecondary,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
            ) {
                CREATE_POST_TOPICS.forEach { (key, res) ->
                    OctopusTextPill(
                        text = stringResource(res),
                        tint = OctopusColors.Primary,
                        selected = topic == key,
                        onClick = { topic = key },
                    )
                }
            }

            // 关联可复刻应用(选填):填你已发布到广场的小程序 slug,别人可付费/免费复刻
            OutlinedTextField(
                value = appRef,
                onValueChange = { appRef = it.trim() },
                label = { Text("关联应用 slug(选填,你已发布的小程序)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors(),
                shape = OctopusShape.medium,
            )
            if (appRef.isNotBlank()) {
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { v -> priceText = v.filter { it.isDigit() }.take(MAX_PRICE_DIGITS) },
                    label = { Text("复刻价格(积分,0=免费,上限 1000)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = fieldColors(),
                    shape = OctopusShape.medium,
                )
            }

            Spacer(Modifier.height(OctopusSpacing.lg))
        }
    }
}

@Composable
private fun ImageThumb(uri: Uri, isCover: Boolean, onSetCover: () -> Unit, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OctopusColors.SurfaceVariant)
            .then(
                if (isCover) Modifier.border(2.dp, OctopusColors.Primary, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .clickable(enabled = !isCover, onClick = onSetCover),
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // 封面徽标(第一张)
        if (isCover) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(OctopusColors.Primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("封面", color = OctopusColors.OnPrimary, fontSize = OctopusType.tag, fontWeight = FontWeight.Bold)
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun AddImageButton(remaining: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OctopusBackground.cardSurface)
            .border(1.dp, OctopusBackground.cardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .tvFocusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = OctopusColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                "$remaining/9",
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.tag,
            )
        }
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = OctopusBackground.cardSurface,
    unfocusedContainerColor = OctopusBackground.cardSurface,
    focusedIndicatorColor = OctopusColors.Primary,
    unfocusedIndicatorColor = OctopusColors.Border,
    focusedTextColor = OctopusColors.TextPrimary,
    unfocusedTextColor = OctopusColors.TextPrimary,
    focusedLabelColor = OctopusColors.Primary,
    unfocusedLabelColor = OctopusColors.TextMuted,
)

/** Uri → 临时 File(复制到 cacheDir,供上传用)。 */
private fun uriToFile(context: android.content.Context, uri: Uri): File? = runCatching {
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
    val file = File.createTempFile("upload_", ".$ext", context.cacheDir)
    context.contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    file
}.getOrNull()
