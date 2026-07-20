package com.apk.claw.android.octopus_mobile.workspace

import android.content.Context
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程工作空间本地缓存层 —— 让 edit_file/run_code 像操作本地文件一样操作远程文件。
 *
 * 工作原理：
 *  1. pullFile：从远程下载文件到 context.cacheDir/remote_workspace/<mountId>/<relative_path>
 *  2. 本地编辑（edit_file / run_code）直接操作缓存文件
 *  3. pushFile：上传修改回远程，检测 mtime 冲突
 *
 * mtime 冲突检测：
 *  - pull 时记录远程文件的 mtime 到元数据
 *  - push 时先 stat 远程文件，若 mtime 变化（他人修改过），返回 [PushResult.Conflict]
 *  - push 时先 stat 远程文件，若 mtime 变化（他人修改过），返回 [PushResult.Conflict]
 *  - dirty 通过本地 mtime > meta.pulledAt 隐式判断
 *  - push 成功后清除 dirty 标记
 */
object RemoteWorkspaceCache {

    private const val TAG = "RemoteWorkspaceCache"
    private const val CACHE_DIR_NAME = "remote_workspace"
    private const val META_FILE = ".cache_meta.json"

    private val gson = Gson()

    @Volatile
    private var cacheRoot: File? = null

    /** mountId → 文件元数据 map（remotePath → FileMeta） */
    private val metaMap = ConcurrentHashMap<String, MutableMap<String, FileMeta>>()

    /** 初始化（由 [RemoteWorkspaceManager.init] 调用）。 */
    fun init(context: Context) {
        cacheRoot = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        // 注册到 PathGuard,让 file_ops/browse_files 等工具能透明访问缓存文件
        com.apk.claw.android.octopus_mobile.safety.PathGuard.remoteCacheRoot =
            cacheRoot?.absolutePath
        XLog.d(TAG, "cacheRoot: ${cacheRoot?.absolutePath}")
    }

    /** 文件元数据。 */
    data class FileMeta(
        val remotePath: String,
        val remoteMtime: Long,      // pull 时的远程 mtime（Unix 秒）
        val pulledAt: Long,         // pull 时间戳（ms）
        val localSize: Long,        // pull 时的本地文件大小
    )

    /** push 结果。 */
    sealed class PushResult {
        data class Success(val message: String = "推送成功") : PushResult()
        data class Conflict(
            val remoteMtime: Long,
            val holderInfo: String = "",
            val message: String = "远程文件已被修改，请先 pull",
        ) : PushResult()
        data class Error(val message: String) : PushResult()
    }

    /** 获取挂载点的缓存目录。 */
    private fun mountCacheDir(mountId: String): File {
        val root = cacheRoot ?: throw IllegalStateException("RemoteWorkspaceCache 未初始化")
        return File(root, mountId).apply { mkdirs() }
    }

    /** 获取远程文件对应的本地缓存路径（不自动 pull）。 */
    fun getCachePath(mountId: String, remotePath: String): File {
        val p = remotePath.removePrefix("/")
        return File(mountCacheDir(mountId), p)
    }

    /**
     * 拉取远程文件到本地缓存。
     * @return 本地缓存文件
     */
    fun pullFile(mountId: String, remotePath: String): File {
        val backend = RemoteWorkspaceManager.getBackend(mountId)
            ?: throw IllegalStateException("挂载点 $mountId 未激活")
        val content = backend.readFileBytes(remotePath)
        val localFile = getCachePath(mountId, remotePath)
        localFile.parentFile?.mkdirs()
        localFile.writeBytes(content)
        // 记录元数据
        val remoteMtime = try { backend.stat(remotePath).mtime } catch (e: Exception) { 0L }
        val meta = FileMeta(remotePath, remoteMtime, System.currentTimeMillis(), localFile.length())
        getMetaMap(mountId)[remotePath] = meta
        saveMeta(mountId)
        XLog.d(TAG, "pullFile: $mountId:$remotePath -> ${localFile.absolutePath} (${content.size} bytes)")
        return localFile
    }

    /**
     * 推送本地修改回远程（带 mtime 冲突检测）。
     */
    fun pushFile(mountId: String, remotePath: String): PushResult {
        val backend = RemoteWorkspaceManager.getBackend(mountId)
            ?: return PushResult.Error("挂载点 $mountId 未激活")
        val localFile = getCachePath(mountId, remotePath)
        if (!localFile.exists()) {
            return PushResult.Error("本地缓存文件不存在: $remotePath")
        }
        // mtime 冲突检测
        val meta = getMetaMap(mountId)[remotePath]
        if (meta != null && meta.remoteMtime > 0) {
            val currentRemoteMtime = try { backend.stat(remotePath).mtime } catch (e: Exception) { 0L }
            if (currentRemoteMtime > 0 && currentRemoteMtime > meta.remoteMtime) {
                XLog.w(TAG, "pushFile conflict: $remotePath remote mtime $currentRemoteMtime > pulled ${meta.remoteMtime}")
                return PushResult.Conflict(currentRemoteMtime)
            }
        }
        // 上传
        return try {
            backend.writeFileBytes(remotePath, localFile.readBytes())
            // 更新元数据
            val newMtime = try { backend.stat(remotePath).mtime } catch (e: Exception) { 0L }
            getMetaMap(mountId)[remotePath] = FileMeta(remotePath, newMtime, System.currentTimeMillis(), localFile.length())
            saveMeta(mountId)
            XLog.d(TAG, "pushFile success: $mountId:$remotePath")
            PushResult.Success()
        } catch (e: Exception) {
            PushResult.Error("推送失败: ${e.message}")
        }
    }

    /**
     * 获取远程文件的本地缓存路径（不存在自动 pull）。
     * edit_file / run_code 通过此方法透明访问远程文件。
     */
    fun getLocalPath(mountId: String, remotePath: String): File {
        val localFile = getCachePath(mountId, remotePath)
        if (!localFile.exists()) {
            pullFile(mountId, remotePath)
        }
        return localFile
    }

    /** 标记文件为 dirty（编辑后调用）。 */
    fun markDirty(mountId: String, remotePath: String) {
        // dirty 通过「本地 mtime > meta.pulledAt」隐式判断，无需显式标记
        // 但如果 meta 不存在（首次编辑新文件），需要创建一条占位 meta
        val map = getMetaMap(mountId)
        if (map[remotePath] == null) {
            map[remotePath] = FileMeta(remotePath, 0L, 0L, 0L)
            saveMeta(mountId)
        }
    }

    /** 返回挂载点下所有 dirty 文件（本地修改未推送）。 */
    fun isDirty(mountId: String): List<String> {
        val map = getMetaMap(mountId)
        val dirty = mutableListOf<String>()
        for ((remotePath, meta) in map) {
            val localFile = getCachePath(mountId, remotePath)
            if (localFile.exists() && localFile.lastModified() > meta.pulledAt) {
                dirty.add(remotePath)
            }
        }
        return dirty
    }

    /** 清理挂载点的所有缓存。 */
    fun clear(mountId: String) {
        mountCacheDir(mountId).deleteRecursively()
        metaMap.remove(mountId)
        XLog.d(TAG, "clear: $mountId")
    }

    // ── 元数据持久化 ──

    private fun getMetaMap(mountId: String): MutableMap<String, FileMeta> {
        return metaMap.getOrPut(mountId) { loadMeta(mountId).toMutableMap() }
    }

    private fun metaFile(mountId: String): File = File(mountCacheDir(mountId), META_FILE)

    private fun loadMeta(mountId: String): Map<String, FileMeta> {
        val f = metaFile(mountId)
        if (!f.exists()) return emptyMap()
        return try {
            gson.fromJson<Map<String, FileMeta>>(
                f.readText(),
                object : TypeToken<Map<String, FileMeta>>() {}.type,
            ) ?: emptyMap()
        } catch (e: Exception) {
            XLog.w(TAG, "loadMeta failed: ${e.message}")
            emptyMap()
        }
    }

    private fun saveMeta(mountId: String) {
        val map = metaMap[mountId] ?: return
        try {
            metaFile(mountId).writeText(gson.toJson(map))
        } catch (e: Exception) {
            XLog.w(TAG, "saveMeta failed: ${e.message}")
        }
    }

    /** 解析 remote://<mountId>/<path> 为 (mountId, remotePath)。 */
    object PathParser {
        private const val PREFIX = "remote://"

        /** 判断是否是远程路径。 */
        fun isRemote(path: String): Boolean = path.startsWith(PREFIX)

        /** 解析 remote://<mountId>/<path> → (mountId, path)，非法返回 null。 */
        fun parse(path: String): Pair<String, String>? {
            if (!path.startsWith(PREFIX)) return null
            val rest = path.removePrefix(PREFIX)
            val mountId = rest.substringBefore('/', "")
            val remotePath = rest.substringAfter('/', "/")
            if (mountId.isEmpty()) return null
            return mountId to "/$remotePath".replace("//", "/")
        }
    }
}
