package com.apk.claw.android.skill

import android.content.Context
import com.apk.claw.android.utils.XLog
import java.io.File
import java.io.IOException

/**
 * 技能注册表(单例)。
 *
 * 管理所有已加载技能,来源两类:
 * 1. **内置**:打包在 assets/skills/ 下的 .md 文件,随 APK 发布,不可卸载
 * 2. **用户安装**:下载落盘到 filesDir/skills/ 下的 .md 文件,可卸载
 *
 * 加载流程:[loadAll] 扫描两个目录,用 [SkillMdLoader] 解析,同名技能用户安装版本覆盖内置版本
 * (因为用户安装通常意味着更新)。[loadAll] 同时缓存 applicationContext,使后续
 * [install] / [uninstall] / [reload] 无需再传 Context。
 *
 * 线程安全:所有读写都经 [synchronized] 保护;[reload] 全量重建列表。
 */
object SkillRegistry {

    private const val TAG = "SkillRegistry"
    private const val ASSET_DIR = "skills"
    private const val USER_DIR = "skills"

    private val lock = Any()

    @Volatile
    private var skills: List<SkillDefinition> = emptyList()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installer: SkillInstaller? = null

    /**
     * 全量加载:扫描 assets + filesDir,解析所有 SKILL.md。
     *
     * 调用时机:App 启动 / 设置页进入 / 安装或卸载后。
     * 解析失败的文件会跳过并记日志,不阻断其他技能加载。
     * 同时缓存 applicationContext 供后续 [install] / [uninstall] / [reload] 使用。
     */
    fun loadAll(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            appContext = ctx
            if (installer == null) installer = SkillInstaller(ctx)
            val loaded = mutableListOf<SkillDefinition>()

            // 1. 内置:assets/skills/*.md
            loadFromAssets(ctx).forEach { loaded.add(it) }

            // 2. 用户安装:filesDir/skills/*.md(同名覆盖内置)
            loadFromFilesDir(ctx).forEach { userDef ->
                val idx = loaded.indexOfFirst { it.name == userDef.name }
                if (idx >= 0) loaded[idx] = userDef else loaded.add(userDef)
            }

            skills = loaded.toList()
            XLog.i(
                TAG,
                "loaded ${skills.size} skills " +
                    "(${loaded.count { it.source == SkillSource.BUILTIN }} builtin, " +
                    "${loaded.count { it.source == SkillSource.USER_INSTALLED }} user)",
            )
        }
    }

    /** 当前已加载的全部技能。未调用 [loadAll] 时返回空列表。 */
    fun list(): List<SkillDefinition> = skills

    /** 按名称查找;也匹配 [SkillDefinition.aliases]。 */
    fun get(name: String): SkillDefinition? {
        skills.firstOrNull { it.name == name }?.let { return it }
        return skills.firstOrNull { name in it.aliases }
    }

    /**
     * 从 URL 安装技能,委托 [SkillInstaller]。
     * 安装成功后自动 [reload]。
     *
     * 需先调用 [loadAll] 初始化 applicationContext,否则返回失败。
     */
    fun install(url: String): Result<SkillDefinition> {
        val ctx = appContext ?: run {
            XLog.w(TAG, "install called before loadAll — context not initialized")
            return Result.failure(IllegalStateException("SkillRegistry not initialized: call loadAll() first"))
        }
        val inst = installer ?: SkillInstaller(ctx).also { installer = it }
        val result = inst.install(url)
        if (result.isSuccess) reload()
        return result
    }

    /**
     * 卸载用户安装的技能。
     *
     * 仅可卸载 [SkillSource.USER_INSTALLED] 的技能;内置技能返回 false。
     * 卸载后自动 [reload]。
     *
     * @return true 成功卸载;false 技能不存在、为内置、或未初始化
     */
    fun uninstall(name: String): Boolean {
        val ctx = appContext ?: run {
            XLog.w(TAG, "uninstall called before loadAll — context not initialized")
            return false
        }
        val def = get(name) ?: return false
        if (def.source != SkillSource.USER_INSTALLED) {
            XLog.w(TAG, "cannot uninstall builtin skill: $name")
            return false
        }
        val deleted = File(ctx.filesDir, "$USER_DIR/$name.md").delete()
        if (deleted) {
            XLog.i(TAG, "skill uninstalled: $name")
            reload()
        }
        return deleted
    }

    /** 重新扫描两个目录,刷新内存列表。需先调用 [loadAll] 初始化。 */
    fun reload() {
        val ctx = appContext ?: run {
            XLog.w(TAG, "reload called before loadAll — context not initialized")
            return
        }
        loadAll(ctx)
    }

    // ── 内部加载实现 ──────────────────────────────────────────────

    private fun loadFromAssets(context: Context): List<SkillDefinition> {
        val out = mutableListOf<SkillDefinition>()
        try {
            val files = context.assets.list(ASSET_DIR) ?: return emptyList()
            for (file in files) {
                if (!file.endsWith(".md")) continue
                val content = try {
                    context.assets.open("$ASSET_DIR/$file").bufferedReader().use { it.readText() }
                } catch (e: IOException) {
                    XLog.w(TAG, "skip asset $file: ${e.message}")
                    continue
                }
                val def = parseOrSkip(content, file, SkillSource.BUILTIN) ?: continue
                out.add(def)
            }
        } catch (e: IOException) {
            XLog.w(TAG, "assets list failed: ${e.message}")
        }
        return out
    }

    private fun loadFromFilesDir(context: Context): List<SkillDefinition> {
        val dir = File(context.filesDir, USER_DIR)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val out = mutableListOf<SkillDefinition>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { file ->
            val content = try {
                file.readText()
            } catch (e: IOException) {
                XLog.w(TAG, "skip user skill ${file.name}: ${e.message}")
                return@forEach
            }
            val def = parseOrSkip(content, file.name, SkillSource.USER_INSTALLED) ?: return@forEach
            out.add(def)
        }
        return out
    }

    private fun parseOrSkip(
        content: String,
        sourceName: String,
        source: SkillSource,
    ): SkillDefinition? {
        return try {
            SkillMdLoader.parse(content).copy(source = source)
        } catch (e: SkillParseException) {
            XLog.w(TAG, "skip $sourceName: ${e.message}")
            null
        }
    }
}
