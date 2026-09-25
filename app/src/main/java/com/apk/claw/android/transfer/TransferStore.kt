package com.apk.claw.android.transfer

import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Private exchange folder. No remote path can address files outside this directory. */
@Suppress("TooManyFunctions") // Cohesive transactional file protocol with private path validation.
class TransferStore(private val root: File) {
    data class Entry(val name: String, val size: Long)
    data class Upload(val id: String, val name: String, val size: Long, val sha256: String)

    init { check(root.mkdirs() || root.isDirectory) }

    fun list(): List<Entry> = synchronized(lock) {
        root.listFiles().orEmpty().filter { !it.name.startsWith(".") && it.isFile }
            .sortedBy { it.name }.map { Entry(it.name, it.length()) }
    }

    fun file(name: String): File {
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS && !name.startsWith(".")) {
            "Invalid file name"
        }
        require(name.none { it == '/' || it == '\\' || it == ':' || it.code < FIRST_PRINTABLE_CHAR }) {
            "Invalid file name"
        }
        return File(root, name).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }

    fun begin(name: String, size: Long, sha256: String): Upload = synchronized(lock) {
        file(name)
        require(size in 0..MAX_FILE_BYTES && sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid size or SHA-256" }
        // An identical begin resumes after a lost response or reconnection.
        val sessions = root.listFiles().orEmpty().filter { it.name.endsWith(".upload") }
        sessions.forEach { metadata ->
            if (System.currentTimeMillis() - metadata.lastModified() > EXPIRY_MS) {
                File(root, metadata.name.removeSuffix(".upload") + ".part").delete()
                metadata.delete()
            } else {
                val upload = Gson().fromJson(metadata.readText(), Upload::class.java)
                if (upload.name == name && upload.size == size && upload.sha256 == sha256) return@synchronized upload
            }
        }
        require(!file(name).exists()) { "File already exists; choose a different name" }
        require(sessions.count {
            File(root, it.name.removeSuffix(".upload") + ".part").exists()
        } < MAX_SESSIONS) { "Too many unfinished uploads" }
        require(root.listFiles().orEmpty().sumOf { it.length() } + size <= MAX_FOLDER_BYTES) {
            "Exchange folder is full"
        }
        val upload = Upload(UUID.randomUUID().toString(), name, size, sha256)
        part(upload.id).createNewFile()
        metadata(upload.id).writeText(Gson().toJson(upload))
        upload
    }

    fun status(id: String): Map<String, Any> = synchronized(lock) {
        val upload = load(id)
        val complete = !part(id).exists() && file(upload.name).isFile
        mapOf("id" to id, "name" to upload.name, "size" to upload.size,
            "offset" to if (complete) upload.size else part(id).length(), "complete" to complete)
    }

    fun chunk(id: String, offset: Long, bytes: ByteArray): Long = synchronized(lock) {
        val upload = load(id)
        require(bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES) { "Invalid chunk size" }
        require(offset >= 0 && offset + bytes.size <= upload.size) { "Chunk outside file" }
        RandomAccessFile(part(id), "rw").use { output ->
            require(offset <= output.length()) { "Unexpected chunk offset" }
            output.seek(offset)
            if (offset < output.length()) {
                require(offset + bytes.size <= output.length()) { "Overlapping chunk" }
                val previous = ByteArray(bytes.size)
                output.readFully(previous)
                require(previous.contentEquals(bytes)) { "Retry content mismatch" }
            } else {
                require(root.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= MAX_FOLDER_BYTES) {
                    "Exchange folder is full"
                }
                output.write(bytes)
                output.fd.sync()
            }
            metadata(id).setLastModified(System.currentTimeMillis())
            output.length()
        }
    }

    fun complete(id: String): Entry = synchronized(lock) {
        val upload = load(id)
        val target = file(upload.name)
        // Completion may have succeeded before the response was lost.
        if (!part(id).exists() && target.isFile) {
            require(target.length() == upload.size && digest(target) == upload.sha256)
        } else {
            require(part(id).length() == upload.size) { "Upload is incomplete" }
            require(digest(part(id)) == upload.sha256) { "SHA-256 mismatch" }
            require(!target.exists()) { "File already exists" }
            check(part(id).renameTo(target)) { "Cannot commit upload" }
        }
        Entry(target.name, target.length())
    }

    fun cancel(id: String): Boolean = synchronized(lock) {
        part(id).delete()
        metadata(id).delete()
    }

    fun read(name: String, offset: Long): ByteArray = synchronized(lock) {
        val source = file(name)
        require(source.isFile && offset in 0..source.length()) { "File or offset unavailable" }
        RandomAccessFile(source, "r").use { input ->
            input.seek(offset)
            ByteArray(minOf(CHUNK_BYTES.toLong(), input.length() - offset).toInt()).also { input.readFully(it) }
        }
    }

    fun importFile(name: String, input: InputStream): Entry = synchronized(lock) {
        val target = file(name)
        require(!target.exists()) { "File already exists" }
        val temporary = File(root, ".import-${UUID.randomUUID()}")
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(CHUNK_BYTES)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_FILE_BYTES) { "File exceeds 100 MiB" }
                    output.write(buffer, 0, count)
                }
            }
            require(root.listFiles().orEmpty().sumOf { it.length() } <= MAX_FOLDER_BYTES) { "Exchange folder is full" }
            check(temporary.renameTo(target))
            Entry(name, target.length())
        } finally { temporary.delete() }
    }

    private fun load(id: String): Upload = Gson().fromJson(metadata(id).readText(), Upload::class.java)
    private fun metadata(id: String): File = sessionFile(id, ".upload")
    private fun part(id: String): File = sessionFile(id, ".part")
    private fun sessionFile(id: String, suffix: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid transfer id" }
        return File(root, ".$id$suffix")
    }

    companion object {
        private const val DIGEST_BUFFER_BYTES = 65_536
        private const val MAX_NAME_CHARS = 120
        private const val FIRST_PRINTABLE_CHAR = 32
        private const val MAX_SESSIONS = 16
        const val CHUNK_BYTES = 12 * 1024
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        private const val MAX_FOLDER_BYTES = 500L * 1024 * 1024
        private const val EXPIRY_MS = 86_400_000L
        private val lock = Any()
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DIGEST_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
