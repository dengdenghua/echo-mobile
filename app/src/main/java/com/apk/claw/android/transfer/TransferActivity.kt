@file:Suppress("MagicNumber") // Native layout spacing and text sizes in this SAF screen.

package com.apk.claw.android.transfer

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** User-selected imports and exports use SAF; no broad storage permission is needed. */
class TransferActivity : ComponentActivity() {
    private lateinit var store: TransferStore
    private lateinit var items: LinearLayout
    private lateinit var status: TextView
    private var exporting: String? = null
    private var displayed: List<TransferStore.Entry>? = null
    private var working = false
    private var refreshing = false
    private var shareHandled = false

    private val choose = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            "已加入，电脑文件面板会自动更新：${importFile(uri)}"
        }
    }
    private val save = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val name = exporting
        if (uri != null && name != null) work {
            contentResolver.openOutputStream(uri)!!.use { output ->
                store.file(name).inputStream().use { it.copyTo(output) }
            }
            "已保存：$name"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exporting = savedInstanceState?.getString("exporting")
        shareHandled = savedInstanceState?.getBoolean("shareHandled") ?: false
        store = TransferStore(File(filesDir, "exchange"))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 24)
        }
        layout.addView(TextView(this).apply { text = "Echo 文件收发"; textSize = 24f })
        layout.addView(TextView(this).apply {
            text = "电脑传来的文件自动出现在这里。也可在其他应用选择文件 → 分享 → Echo 文件收发，随后在已配对电脑下载。单文件上限 100 MiB。"
            setPadding(0, 20, 0, 20)
        })
        layout.addView(Button(this).apply { text = "加入手机文件"; setOnClickListener { choose.launch(arrayOf("*/*")) } })
        layout.addView(Button(this).apply { text = "刷新接收文件"; setOnClickListener { refresh() } })
        status = TextView(this)
        layout.addView(status)
        items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(items)
        layout.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(layout) })
        refresh()
        receiveShare()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { refresh(); delay(2000) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exporting", exporting)
        outState.putBoolean("shareHandled", shareHandled)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { store.list() }
                if (entries != displayed) {
                    displayed = entries
                    items.removeAllViews()
                    entries.forEach { entry ->
                        items.addView(TextView(this@TransferActivity).apply {
                            text = "${entry.name} · ${entry.size} B"; setPadding(0, 16, 0, 0)
                        })
                        items.addView(Button(this@TransferActivity).apply {
                            text = "打开"
                            setOnClickListener { openFile(entry.name) }
                        })
                        items.addView(Button(this@TransferActivity).apply {
                            text = "分享"
                            setOnClickListener { shareFile(entry.name) }
                        })
                        items.addView(Button(this@TransferActivity).apply {
                            text = "保存到手机"
                            setOnClickListener { exporting = entry.name; save.launch(entry.name) }
                        })
                    }
                }
            } finally { refreshing = false }
        }
    }

    private fun work(action: () -> String) {
        if (working) return
        working = true
        status.text = "正在处理…"
        lifecycleScope.launch {
            status.text = withContext(Dispatchers.IO) {
                runCatching(action).getOrElse { "失败：${it.message}" }
            }
            refresh()
            working = false
        }
    }

    private fun importFile(uri: Uri): String {
        require(uri.scheme == "content" && uri.authority != "$packageName.fileprovider") { "请选择其他应用共享的文件" }
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "phone-file-${System.currentTimeMillis()}"
        contentResolver.openInputStream(uri)?.use { store.importFile(name, it) } ?: error("无法读取文件")
        return name
    }

    private fun receiveShare() {
        if (shareHandled) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            )
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java,
            ).orEmpty()
            else -> return
        }.distinct()
        shareHandled = true
        if (uris.isEmpty() || uris.size > 20) {
            status.text = "请选择 1–20 个文件分享；纯文本请先保存为文件。"
        } else work {
            val results = uris.map { uri -> runCatching { importFile(uri) } }
            val failed = results.mapNotNull { it.exceptionOrNull()?.message }
            "已加入 ${results.count { it.isSuccess }} 个文件，电脑文件面板会自动更新。" +
                if (failed.isEmpty()) "" else "\n失败：${failed.joinToString("；")}"
        }
    }

    private fun shareFile(name: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", store.file(name))
            val share = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "打开或分享 $name"))
        }.onFailure { status.text = "无法打开：${it.message}" }
    }

    private fun openFile(name: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", store.file(name))
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { status.text = "没有可打开此文件的应用，请使用分享或保存到手机。" }
    }
}
