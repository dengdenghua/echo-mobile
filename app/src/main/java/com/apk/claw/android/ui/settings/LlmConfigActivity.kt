package com.apk.claw.android.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.octopus_mobile.ApiKeyPool
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

/**
 * LLM 配置页（自行填写 API Key、Base URL、模型名）
 */
class LlmConfigActivity : BaseActivity() {

    private lateinit var tvKeyPoolStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.llm_config_title))
            showBackButton(true) { finish() }
        }

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val etVisionApiKey = findViewById<EditText>(R.id.etVisionApiKey)
        val etVisionBaseUrl = findViewById<EditText>(R.id.etVisionBaseUrl)
        val etVisionModelName = findViewById<EditText>(R.id.etVisionModelName)

        etApiKey.setText(KVUtils.getLlmApiKey())
        etBaseUrl.setText(KVUtils.getLlmBaseUrl())
        etModelName.setText(KVUtils.getLlmModelName())
        etVisionApiKey.setText(KVUtils.getVisionApiKey())
        etVisionBaseUrl.setText(KVUtils.getVisionBaseUrl())
        etVisionModelName.setText(KVUtils.getVisionModelName())

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim().ifEmpty { "" }

            if (apiKey.isEmpty()) {
                Toast.makeText(this, getString(R.string.llm_config_api_key_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            KVUtils.setLlmApiKey(apiKey)
            KVUtils.setLlmBaseUrl(baseUrl)
            KVUtils.setLlmModelName(modelName)
            // 配置了自己的模型 = 选择 BYO 路径(会员特权);默认仍是平台路径
            com.apk.claw.android.account.AccountConfig.modelSource = "byo"
            // 视觉模型（可选，留空则复用主模型）
            KVUtils.setVisionApiKey(etVisionApiKey.text.toString().trim())
            KVUtils.setVisionBaseUrl(etVisionBaseUrl.text.toString().trim())
            KVUtils.setVisionModelName(etVisionModelName.text.toString().trim())

            // 主 Key 变更后,同步到池中索引 0(若池非空)。
            // syncPrimary 内部会判断是否真的变了,不变则 no-op。
            ApiKeyPool.syncPrimary(apiKey)

            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            Toast.makeText(this, getString(R.string.llm_config_saved), Toast.LENGTH_SHORT).show()
            finish()
        }

        // ── API Key 池 ──
        tvKeyPoolStatus = findViewById(R.id.tvKeyPoolStatus)
        val switchKeyPool = findViewById<SwitchCompat>(R.id.switchKeyPool)
        switchKeyPool.isChecked = ApiKeyPool.isEnabled()
        switchKeyPool.setOnCheckedChangeListener { _, isChecked ->
            ApiKeyPool.setEnabled(isChecked)
            refreshPoolStatus()
        }

        findViewById<KButton>(R.id.btnAddKey).setOnClickListener {
            showAddKeyDialog()
        }

        // 点击状态文本 → 弹出管理单个 Key 的对话框(重置统计 / 删除)
        tvKeyPoolStatus.setOnClickListener {
            showKeyManagementDialog()
        }

        findViewById<KButton>(R.id.btnClearKeys).setOnClickListener {
            if (ApiKeyPool.size() <= 1) {
                Toast.makeText(this, R.string.key_pool_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setMessage(R.string.key_pool_clear_confirm)
                .setPositiveButton(R.string.common_confirm) { _, _ ->
                    ApiKeyPool.clearFallbacks()
                    refreshPoolStatus()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }

        refreshPoolStatus()
    }

    private fun showAddKeyDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.key_pool_add_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.key_pool_add)
            .setView(input)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                val key = input.text.toString().trim()
                when {
                    key.isEmpty() -> Toast.makeText(this, R.string.key_pool_add_empty, Toast.LENGTH_SHORT).show()
                    ApiKeyPool.addKey(key) -> {
                        Toast.makeText(this, R.string.key_pool_add_success, Toast.LENGTH_SHORT).show()
                        // 添加后若池已有 ≥2 个 key 且未启用,提示用户启用
                        if (!ApiKeyPool.isEnabled() && ApiKeyPool.size() >= 2) {
                            ApiKeyPool.setEnabled(true)
                            findViewById<SwitchCompat>(R.id.switchKeyPool).isChecked = true
                        }
                        refreshPoolStatus()
                    }
                    else -> Toast.makeText(this, R.string.key_pool_add_duplicate, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showKeyManagementDialog() {
        val keys = ApiKeyPool.listKeys()
        if (keys.isEmpty()) {
            Toast.makeText(this, R.string.key_pool_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val cooldownMs = 5 * 60 * 1000L  // 与 ApiKeyPool.COOLDOWN_MS 一致
        val items = keys.map { (idx, entry) ->
            val tags = mutableListOf<String>()
            if (idx == "0") tags.add(getString(R.string.key_pool_primary_tag))
            if (entry.disabled) tags.add(getString(R.string.key_pool_disabled_tag))
            else if (entry.lastFailMs > 0 && now - entry.lastFailMs < cooldownMs) {
                tags.add(getString(R.string.key_pool_cooldown_tag))
            }
            val tagStr = if (tags.isEmpty()) "" else " [${tags.joinToString(",")}]"
            val stats = "✓${entry.successCount} ✗${entry.failCount}"
            "${entry.key}$tagStr $stats"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.key_pool_pick_action)
            .setItems(items) { _, which ->
                val (idx, _) = keys[which]
                val index = idx.toInt()
                // 主 key (index=0) 不可删/重置,提示
                if (index == 0) {
                    Toast.makeText(this, R.string.key_pool_primary_tag, Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                AlertDialog.Builder(this)
                    .setMessage(R.string.key_pool_remove_confirm)
                    .setPositiveButton(R.string.key_pool_remove) { _, _ ->
                        ApiKeyPool.removeKey(index)
                        refreshPoolStatus()
                    }
                    .setNeutralButton(R.string.key_pool_reset) { _, _ ->
                        ApiKeyPool.resetKey(index)
                        refreshPoolStatus()
                    }
                    .setNegativeButton(R.string.common_cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.common_close, null)
            .show()
    }

    private fun refreshPoolStatus() {
        val keys = ApiKeyPool.listKeys()
        if (keys.isEmpty()) {
            tvKeyPoolStatus.text = getString(R.string.key_pool_empty)
            tvKeyPoolStatus.visibility = View.VISIBLE
            return
        }
        tvKeyPoolStatus.visibility = View.VISIBLE
        val now = System.currentTimeMillis()
        val cooldownMs = 5 * 60 * 1000L
        val summary = keys.joinToString(", ") { (idx, entry) ->
            val tag = when {
                idx == "0" -> "(${getString(R.string.key_pool_primary_tag)})"
                entry.disabled -> "(${getString(R.string.key_pool_disabled_tag)})"
                entry.lastFailMs > 0 && now - entry.lastFailMs < cooldownMs -> "(${getString(R.string.key_pool_cooldown_tag)})"
                else -> ""
            }
            "${entry.key}$tag"
        }
        tvKeyPoolStatus.text = getString(R.string.key_pool_status, keys.size, summary)
    }
}
