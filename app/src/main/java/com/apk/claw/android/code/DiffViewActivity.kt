package com.apk.claw.android.code

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Diff 查看 Activity —— 展示 unified diff 并支持逐 hunk accept/reject.
 *
 * 数据流:
 *  - 入参:[EXTRA_DIFF_TEXT](unified diff 内容)+ [EXTRA_FILE_PATH](目标文件路径)
 *  - 出参:[EXTRA_RESULT]("accepted" / "rejected" / "partial")
 *
 * 用户操作:
 *  - Accept All → 全部 hunk 标记为 accepted,写回文件,返回 "accepted"
 *  - Reject All → 全部 hunk 标记为 rejected,不写文件,返回 "rejected"
 *  - 单 hunk Accept/Reject → 仅更新 UI 状态
 *  - 返回键 → 按当前状态合并;若有任意 hunk accepted 则写回文件,返回 "partial"/"accepted";
 *    否则不写,返回 "rejected"
 *
 * 写回文件用 [File.writeText],在 IO dispatcher 上执行。
 */
class DiffViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DIFF_TEXT = "diff_text"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_RESULT = "result"  // "accepted" / "rejected" / "partial"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val diffText = intent.getStringExtra(EXTRA_DIFF_TEXT)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (diffText.isNullOrBlank() || filePath.isNullOrBlank()) {
            Toast.makeText(this, "Missing diff_text or file_path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fileChanges = try {
            DiffParser.parse(diffText)
        } catch (e: DiffParseException) {
            Toast.makeText(this, "Diff parse error: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (fileChanges.isEmpty()) {
            Toast.makeText(this, "No file changes in diff", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // key = (fileIdx, hunkIdx);value = true(accepted)/ false(rejected)/ null(未决)
        val hunkState = mutableStateMapOf<Pair<Int, Int>, Boolean>()

        fun writeAndFinish() {
            // 找到与 filePath 匹配的 FileChange(优先 newPath,其次 oldPath,兜底第一个)
            val targetIdx = fileChanges.indexOfFirst { fc ->
                val np = fc.newPath
                val op = fc.oldPath
                (np != null && (filePath == np || filePath.endsWith("/$np"))) ||
                    (op != null && (filePath == op || filePath.endsWith("/$op")))
            }.let { if (it < 0) 0 else it }
            val target = fileChanges[targetIdx]

            val acceptedIndices = target.hunks.mapIndexedNotNull { hIdx, _ ->
                if (hunkState[targetIdx to hIdx] == true) hIdx else null
            }.toSet()

            lifecycleScope.launch {
                val writeOk = if (acceptedIndices.isEmpty()) {
                    true  // 无需写
                } else {
                    withContext(Dispatchers.IO) {
                        val file = File(filePath)
                        val original = if (file.exists()) file.readText() else ""
                        val merged = DiffParser.applyHunks(original, target.hunks, acceptedIndices)
                        runCatching { file.writeText(merged) }.isSuccess
                    }.also { ok ->
                        if (!ok) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@DiffViewActivity,
                                    "Failed to write file",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }

                if (!writeOk) return@launch

                val result = when {
                    acceptedIndices.isEmpty() -> "rejected"
                    acceptedIndices.size == target.hunks.size -> "accepted"
                    else -> "partial"
                }
                val resultIntent = Intent().putExtra(EXTRA_RESULT, result)
                withContext(Dispatchers.Main) {
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        }

        fun rejectAllAndFinish() {
            fileChanges.forEachIndexed { fIdx, fc ->
                fc.hunks.forEachIndexed { hIdx, _ ->
                    hunkState[fIdx to hIdx] = false
                }
            }
            val resultIntent = Intent().putExtra(EXTRA_RESULT, "rejected")
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        fun acceptAllAndFinish() {
            fileChanges.forEachIndexed { fIdx, fc ->
                fc.hunks.forEachIndexed { hIdx, _ ->
                    hunkState[fIdx to hIdx] = true
                }
            }
            writeAndFinish()
        }

        setContent {
            OctopusTheme {
                BackHandler(enabled = true) { writeAndFinish() }
                DiffViewScreen(
                    fileChanges = fileChanges,
                    filePath = filePath,
                    hunkState = hunkState,
                    onBack = { writeAndFinish() },
                    onAcceptAll = { acceptAllAndFinish() },
                    onRejectAll = { rejectAllAndFinish() },
                    onAcceptHunk = { fIdx, hIdx -> hunkState[fIdx to hIdx] = true },
                    onRejectHunk = { fIdx, hIdx -> hunkState[fIdx to hIdx] = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffViewScreen(
    fileChanges: List<FileChange>,
    filePath: String,
    hunkState: SnapshotStateMap<Pair<Int, Int>, Boolean>,
    onBack: () -> Unit,
    onAcceptAll: () -> Unit,
    onRejectAll: () -> Unit,
    onAcceptHunk: (Int, Int) -> Unit,
    onRejectHunk: (Int, Int) -> Unit,
) {
    val fileName = filePath.substringAfterLast('/')

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        fileName,
                        color = OctopusColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = OctopusColors.TextPrimary,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRejectAll) {
                        Text(
                            "Reject All",
                            color = OctopusColors.Warning,
                            fontSize = 13.sp,
                        )
                    }
                    TextButton(onClick = onAcceptAll) {
                        Text(
                            "Accept All",
                            color = OctopusColors.Success,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OctopusColors.Background,
                ),
            )
        },
        containerColor = OctopusColors.Background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            fileChanges.forEachIndexed { fIdx, fc ->
                item(key = "file_${fIdx}_header") { FileChangeHeader(fc) }
                fc.hunks.forEachIndexed { hIdx, hunk ->
                    item(key = "file_${fIdx}_hunk_${hIdx}") {
                        HunkCard(
                            hunk = hunk,
                            accepted = hunkState[fIdx to hIdx],
                            onAccept = { onAcceptHunk(fIdx, hIdx) },
                            onReject = { onRejectHunk(fIdx, hIdx) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FileChangeHeader(fc: FileChange) {
    val path = fc.newPath ?: fc.oldPath ?: "(unknown)"
    val (tag, tagColor) = when {
        fc.isNew -> "NEW" to OctopusColors.Success
        fc.isDeleted -> "DEL" to OctopusColors.Warning
        fc.isRenamed -> "REN" to OctopusColors.Info
        else -> "MOD" to OctopusColors.Primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = OctopusShape.small,
            color = tagColor.copy(alpha = 0.15f),
        ) {
            Text(
                tag,
                color = tagColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            path,
            color = OctopusColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HunkCard(
    hunk: Hunk,
    accepted: Boolean?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = OctopusShape.large,
        color = OctopusColors.Surface,
    ) {
        Column {
            // Hunk header
            Text(
                hunk.header,
                color = OctopusColors.TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            // Diff lines
            hunk.lines.forEach { line ->
                DiffLineRow(line)
            }

            // Accept / Reject buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rejectSelected = accepted == false
                val acceptSelected = accepted == true

                Surface(
                    shape = OctopusShape.capsule,
                    color = if (rejectSelected) OctopusColors.Warning.copy(alpha = 0.2f)
                        else OctopusColors.SurfaceVariant,
                    modifier = Modifier.clickable(onClick = onReject),
                ) {
                    Text(
                        "Reject",
                        color = if (rejectSelected) OctopusColors.Warning
                            else OctopusColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (rejectSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = OctopusShape.capsule,
                    color = if (acceptSelected) OctopusColors.Success.copy(alpha = 0.2f)
                        else OctopusColors.SurfaceVariant,
                    modifier = Modifier.clickable(onClick = onAccept),
                ) {
                    Text(
                        "Accept",
                        color = if (acceptSelected) OctopusColors.Success
                            else OctopusColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (acceptSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val bg: Color
    val fg: Color
    when (line.type) {
        DiffLineType.CONTEXT -> {
            bg = Color.Transparent
            fg = OctopusColors.TextPrimary
        }
        DiffLineType.ADDED -> {
            bg = OctopusColors.Success.copy(alpha = 0.15f)
            fg = OctopusColors.Success
        }
        DiffLineType.REMOVED -> {
            bg = OctopusColors.Error.copy(alpha = 0.15f)
            fg = OctopusColors.Error
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        // 左列:旧文件行号
        Text(
            text = line.oldLineNumber?.toString() ?: "",
            color = OctopusColors.TextTertiary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Right,
        )
        Spacer(Modifier.width(6.dp))
        // 右列:新文件行号
        Text(
            text = line.newLineNumber?.toString() ?: "",
            color = OctopusColors.TextTertiary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Right,
        )
        Spacer(Modifier.width(6.dp))
        // 内容
        Text(
            text = line.content,
            color = fg,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}
