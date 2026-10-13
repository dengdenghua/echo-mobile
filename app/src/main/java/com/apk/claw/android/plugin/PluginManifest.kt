package com.apk.claw.android.plugin

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val type: String = "dex",
    @SerializedName("entry_class")
    val entryClass: String = "",
    @SerializedName("dex_file")
    val dexFile: String = "plugin.dex",
    val permissions: List<String> = emptyList(),
    @SerializedName("min_app_version")
    val minAppVersion: Int = 0,
    val description: String = "",
    val author: String = "",

    // browser-script (inject/code): content script + host matching + block rules
    val js: String = "",
    @SerializedName("host_pattern")
    val hostPattern: String = "*",
    @SerializedName("block_rules")
    val blockRules: List<String> = emptyList(),

    // userscript extension fields (compatible with Tampermonkey/Greasemonkey .user.js format)
    val match: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    @SerializedName("run_at")
    val runAt: String = "document-idle",
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val resources: Map<String, String> = emptyMap(),
    val css: String = "",
    val script: String = "",

    // tool (declarative HTTP)
    @SerializedName("tool_name")
    val toolName: String = "",
    @SerializedName("tool_params")
    val toolParams: List<PluginToolParam> = emptyList(),
    val http: HttpRecipe? = null,

    // mini-app
    val page: String = "",
    val actions: List<PluginActionDef> = emptyList(),

    // structured permissions (new types)
    @SerializedName("allow_hosts")
    val allowHosts: List<String> = emptyList(),
    @SerializedName("allow_tools")
    val allowTools: List<String> = emptyList(),
    @SerializedName("allow_device")
    val allowDevice: List<String> = emptyList(),
    @SerializedName("allow_pay")
    val allowPay: Boolean = false
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): PluginManifest? {
            return try {
                gson.fromJson(json, PluginManifest::class.java)?.let(::sanitizeNulls)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Gson ignores Kotlin defaults: a field missing from the JSON arrives as null even when it is
         * declared non-null, and copy() then throws. Every non-null reference field must be restored
         * in this one copy, strings included, or a manifest that leaves one out (all bundled demos do)
         * is dropped.
         */
        private fun sanitizeNulls(m: PluginManifest): PluginManifest = m.copy(
            type = str(m.type, "dex"),
            entryClass = str(m.entryClass),
            dexFile = str(m.dexFile, "plugin.dex"),
            description = str(m.description),
            author = str(m.author),
            js = str(m.js),
            hostPattern = str(m.hostPattern, "*"),
            runAt = str(m.runAt, "document-idle"),
            css = str(m.css),
            script = str(m.script),
            toolName = str(m.toolName),
            page = str(m.page),
            permissions = list(m.permissions),
            blockRules = list(m.blockRules),
            toolParams = list(m.toolParams),
            allowHosts = list(m.allowHosts),
            allowTools = list(m.allowTools),
            allowDevice = list(m.allowDevice),
            actions = list(m.actions).map { it.copy(params = list(it.params)) },
            match = list(m.match),
            exclude = list(m.exclude),
            grants = list(m.grants),
            requires = list(m.requires),
            resources = m.resources.orEmptyMap(),
        )

        private fun str(value: String?, default: String = ""): String = value ?: default

        private fun <T> list(value: List<T>?): List<T> = value ?: emptyList()

        private fun Map<String, String>?.orEmptyMap(): Map<String, String> = this ?: emptyMap()

        fun fromStream(stream: java.io.InputStream): PluginManifest? {
            return try {
                val json = stream.bufferedReader().readText()
                fromJson(json)
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class PluginInfo(
    val manifest: PluginManifest,
    val source: String,
    val dexPath: String,
    var isLoaded: Boolean = false,
    var registeredTools: List<String> = emptyList(),
    var error: String? = null
)

data class PluginActionDef(
    val name: String = "",
    val description: String = "",
    val params: List<PluginToolParam> = emptyList(),
)

data class PluginToolParam(
    val name: String = "",
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false
)

data class HttpRecipe(
    val method: String = "GET",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)
