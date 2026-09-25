package com.apk.claw.android.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = TransferStore(temporary.newFolder())

    @Test fun binaryChunksResumeAfterRecreationAndCommitExactly() {
        val root = temporary.newFolder()
        val store = TransferStore(root)
        val bytes = ByteArray(40_001) { (it % 251).toByte() }
        val source = temporary.newFile().apply { writeBytes(bytes) }
        val upload = store.begin("照片.bin", bytes.size.toLong(), TransferStore.digest(source))
        val first = bytes.copyOfRange(0, TransferStore.CHUNK_BYTES)
        assertEquals(first.size.toLong(), store.chunk(upload.id, 0, first))
        val resumed = TransferStore(root)
        assertEquals(upload.id, resumed.begin(upload.name, upload.size, upload.sha256).id)
        assertEquals(first.size.toLong(), resumed.chunk(upload.id, 0, first))
        var offset = first.size
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + TransferStore.CHUNK_BYTES)
            resumed.chunk(upload.id, offset.toLong(), bytes.copyOfRange(offset, end))
            offset = end
        }
        assertEquals(bytes.size.toLong(), resumed.complete(upload.id).size)
        assertArrayEquals(bytes, resumed.file(upload.name).readBytes())
        assertEquals(bytes.size.toLong(), resumed.complete(upload.id).size)
        assertEquals(true, resumed.status(upload.id)["complete"])
    }

    @Test fun traversalAndHiddenNamesAreRejected() {
        val store = store()
        listOf("../secret", "..", ".hidden", "C:\\secret", "a/b", "a\u0000b").forEach {
            assertThrows(IllegalArgumentException::class.java) { store.file(it) }
        }
    }

    @Test fun incompleteOrCorruptFileIsNeverPublished() {
        val store = store()
        val upload = store.begin("bad.bin", 3, "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { store.complete(upload.id) }
        store.chunk(upload.id, 0, byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { store.complete(upload.id) }
        assertFalse(store.file("bad.bin").exists())
        assertTrue(store.list().isEmpty())
        store.cancel(upload.id)
        assertThrows(java.io.FileNotFoundException::class.java) { store.status(upload.id) }
    }

    @Test fun offsetMismatchAndDifferentRetryAreRejected() {
        val store = store()
        val upload = store.begin("offset.bin", 9, "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { store.chunk(upload.id, 1, byteArrayOf(1)) }
        store.chunk(upload.id, 0, byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { store.chunk(upload.id, 0, byteArrayOf(2)) }
        assertThrows(IllegalArgumentException::class.java) { store.read("../bad", 0) }
    }

    @Test fun importUsesSameDownloadFolderAndNeverOverwrites() {
        val store = store()
        val bytes = byteArrayOf(0, -1, 2, 0)
        store.importFile("phone.bin", bytes.inputStream())
        assertArrayEquals(bytes, store.read("phone.bin", 0))
        assertThrows(IllegalArgumentException::class.java) {
            store.importFile("phone.bin", byteArrayOf(3).inputStream())
        }
        assertArrayEquals(bytes, store.read("phone.bin", 0))
    }

    @Test fun completedSessionsDoNotConsumeActiveUploadSlots() {
        val store = store()
        val empty = temporary.newFile()
        repeat(20) { index ->
            val upload = store.begin("empty-$index.bin", 0, TransferStore.digest(empty))
            store.complete(upload.id)
        }
        assertEquals(20, store.list().size)
    }
}
