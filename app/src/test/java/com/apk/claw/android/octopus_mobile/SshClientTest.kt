@file:Suppress("PackageNaming", "MagicNumber", "TooGenericExceptionCaught")

package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.octopus_mobile.ssh.SshClient
import com.apk.claw.android.octopus_mobile.ssh.SshClient.Connection
import com.apk.claw.android.octopus_mobile.ssh.SshClient.ExecResult
import com.apk.claw.android.octopus_mobile.ssh.SftpOperations.FileEntry
import com.jcraft.jsch.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.lang.reflect.Field

/**
 * SshClient / SftpOperations 单元测试.
 *
 * 测试范围:
 *  - [ExecResult.toSummary]:stdout/stderr 截断、空值处理、exit code 格式化
 *  - [FileEntry.toLsLine]:目录/文件/符号链接格式、大小占位
 *  - [SshClient] 连接池管理:listConnections / getConnection / activeCount / disconnect / disconnectAll / LRU 淘汰
 *
 * 不测试:真实 SSH 握手 / 命令执行 / SFTP 操作(需真实 SSH 服务器,属集成测试范畴).
 *
 * 纯 JVM:Mock Session 通过 Mockito 注入,反射访问 SshClient.sessions 私有字段.
 */
class SshClientTest {

    @Before
    fun setUp() {
        // 清理可能残留的连接(其他测试可能注入过 mock Connection)
        SshClient.disconnectAll()
    }

    // ── ExecResult.toSummary ──

    @Test
    fun `ExecResult isSuccess true when exitCode is zero`() {
        val r = ExecResult(stdout = "ok", stderr = "", exitCode = 0)
        assertTrue(r.isSuccess)
    }

    @Test
    fun `ExecResult isSuccess false when exitCode nonzero`() {
        val r = ExecResult(stdout = "", stderr = "err", exitCode = 1)
        assertFalse(r.isSuccess)
    }

    @Test
    fun `toSummary includes exit code`() {
        val r = ExecResult(stdout = "hello", stderr = "world", exitCode = 42)
        val s = r.toSummary()
        assertTrue(s.contains("exit=42"))
    }

    @Test
    fun `toSummary includes full stdout when under limit`() {
        val r = ExecResult(stdout = "line1\nline2", stderr = "", exitCode = 0)
        val s = r.toSummary()
        assertTrue(s.contains("line1"))
        assertTrue(s.contains("line2"))
        assertTrue(s.contains("stdout"))
    }

    @Test
    fun `toSummary truncates stdout over 4000 chars`() {
        val long = "x".repeat(5000)
        val r = ExecResult(stdout = long, stderr = "", exitCode = 0)
        val s = r.toSummary()
        assertTrue("应含截断标记", s.contains("truncated"))
        // 截断后 stdout 部分 = 4000 字符,加上其他行总长度有界
        assertTrue("stdout 部分不超过 4000", s.indexOf("truncated") < 4100)
    }

    @Test
    fun `toSummary truncates stderr over 2000 chars`() {
        val long = "e".repeat(3000)
        val r = ExecResult(stdout = "", stderr = long, exitCode = 0)
        val s = r.toSummary()
        assertTrue("应含截断标记", s.contains("truncated"))
    }

    @Test
    fun `toSummary with empty stdout and stderr`() {
        val r = ExecResult(stdout = "", stderr = "", exitCode = 0)
        val s = r.toSummary()
        assertTrue(s.contains("exit=0"))
        assertFalse(s.contains("stdout"))
        assertFalse(s.contains("stderr"))
    }

    @Test
    fun `toSummary with blank stdout omits stdout section`() {
        val r = ExecResult(stdout = "   ", stderr = "err", exitCode = 1)
        val s = r.toSummary()
        assertFalse(s.contains("stdout"))
        assertTrue(s.contains("stderr"))
    }

    // ── FileEntry.toLsLine ──

    @Test
    fun `toLsLine for directory uses d prefix and dash size`() {
        val e = FileEntry(
            name = "docs", path = "/var/docs", isDir = true, isSymlink = false,
            size = 4096, mtime = 1700000000L, permissions = "rwxr-xr-x",
            owner = "uid=0", group = "gid=0",
        )
        val line = e.toLsLine()
        assertTrue(line.startsWith("d"))
        assertTrue(line.contains("docs"))
        // 目录大小显示为 -,而非实际字节数 4096
        assertFalse("目录不应显示实际大小 4096", line.contains("4096"))
    }

    @Test
    fun `toLsLine for regular file uses dash prefix and numeric size`() {
        val e = FileEntry(
            name = "readme.txt", path = "/home/user/readme.txt", isDir = false, isSymlink = false,
            size = 1234, mtime = 1700000000L, permissions = "rw-r--r--",
            owner = "uid=1000", group = "gid=1000",
        )
        val line = e.toLsLine()
        assertTrue(line.startsWith("-"))
        assertTrue(line.contains("1234"))
        assertTrue(line.contains("readme.txt"))
    }

    @Test
    fun `toLsLine for symlink uses l prefix`() {
        val e = FileEntry(
            name = "link", path = "/tmp/link", isDir = false, isSymlink = true,
            size = 5, mtime = 1700000000L, permissions = "rwxrwxrwx",
            owner = "uid=1000", group = "gid=1000",
        )
        val line = e.toLsLine()
        assertTrue(line.startsWith("l"))
        assertTrue(line.contains("link"))
    }

    @Test
    fun `toLsLine includes permissions and owner group`() {
        val e = FileEntry(
            name = "f", path = "/f", isDir = false, isSymlink = false,
            size = 1, mtime = 0L, permissions = "rw-------",
            owner = "uid=42", group = "gid=42",
        )
        val line = e.toLsLine()
        assertTrue(line.contains("rw-------"))
        assertTrue(line.contains("uid=42"))
        assertTrue(line.contains("gid=42"))
    }

    // ── SshClient 连接池管理(空池)──

    @Test
    fun `empty pool - listConnections returns empty`() {
        assertTrue(SshClient.listConnections().isEmpty())
    }

    @Test
    fun `empty pool - activeCount is zero`() {
        assertEquals(0, SshClient.activeCount())
    }

    @Test
    fun `empty pool - getConnection returns null`() {
        assertNull(SshClient.getConnection("nonexistent"))
    }

    @Test
    fun `disconnect on unknown id is no-op`() {
        // 不应抛异常
        SshClient.disconnect("nonexistent")
        assertEquals(0, SshClient.activeCount())
    }

    // ── SshClient 连接池管理(注入 mock 连接)──

    @Test
    fun `injected connection appears in listConnections`() {
        val mockSession = Mockito.mock(Session::class.java)
        injectConnection("test-1", mockSession, "host1.example.com", 22, "user1", 1000L)

        val list = SshClient.listConnections()
        assertEquals(1, list.size)
        assertEquals("test-1", list[0].id)
        assertEquals("host1.example.com", list[0].host)
        assertEquals("user1", list[0].user)
    }

    @Test
    fun `getConnection returns injected connection`() {
        val mockSession = Mockito.mock(Session::class.java)
        injectConnection("conn-xyz", mockSession, "10.0.0.1", 2222, "root", 2000L)

        val conn = SshClient.getConnection("conn-xyz")
        assertNotNull(conn)
        assertEquals("10.0.0.1", conn!!.host)
        assertEquals(2222, conn.port)
        assertEquals("root", conn.user)
    }

    @Test
    fun `activeCount reflects injected connections`() {
        val mockSession = Mockito.mock(Session::class.java)
        injectConnection("a", mockSession, "h1", 22, "u1", 1000L)
        injectConnection("b", mockSession, "h2", 22, "u2", 2000L)

        assertEquals(2, SshClient.activeCount())
    }

    @Test
    fun `listConnections sorted by connectedAtMs ascending`() {
        val mockSession = Mockito.mock(Session::class.java)
        injectConnection("youngest", mockSession, "h3", 22, "u3", 3000L)
        injectConnection("oldest", mockSession, "h1", 22, "u1", 1000L)
        injectConnection("middle", mockSession, "h2", 22, "u2", 2000L)

        val list = SshClient.listConnections()
        assertEquals(3, list.size)
        assertEquals("oldest", list[0].id)
        assertEquals("middle", list[1].id)
        assertEquals("youngest", list[2].id)
    }

    @Test
    fun `disconnect removes connection from pool`() {
        val mockSession = Mockito.mock(Session::class.java)
        injectConnection("to-remove", mockSession, "h", 22, "u", 1000L)
        assertEquals(1, SshClient.activeCount())

        SshClient.disconnect("to-remove")
        assertEquals(0, SshClient.activeCount())
        assertNull(SshClient.getConnection("to-remove"))
    }

    @Test
    fun `disconnectAll clears entire pool`() {
        val mockSession = Mockito.mock(Session::class.java)
        injectConnection("a", mockSession, "h1", 22, "u1", 1000L)
        injectConnection("b", mockSession, "h2", 22, "u2", 2000L)
        injectConnection("c", mockSession, "h3", 22, "u3", 3000L)
        assertEquals(3, SshClient.activeCount())

        SshClient.disconnectAll()
        assertEquals(0, SshClient.activeCount())
        assertTrue(SshClient.listConnections().isEmpty())
    }

    @Test
    fun `shutdown is alias for disconnectAll`() {
        val mockSession = Mockito.mock(Session::class.java)
        injectConnection("a", mockSession, "h1", 22, "u1", 1000L)
        assertEquals(1, SshClient.activeCount())

        SshClient.shutdown()
        assertEquals(0, SshClient.activeCount())
    }

    @Test
    fun `disconnect on connected session calls session disconnect`() {
        val mockSession = Mockito.mock(Session::class.java)
        // 模拟已连接的 session:isConnected 返回 true
        Mockito.`when`(mockSession.isConnected).thenReturn(true)
        injectConnection("connected", mockSession, "h", 22, "u", 1000L)

        SshClient.disconnect("connected")

        // 验证 session.disconnect() 被调用
        Mockito.verify(mockSession).disconnect()
    }

    @Test
    fun `disconnect on disconnected session does not call session disconnect`() {
        val mockSession = Mockito.mock(Session::class.java)
        // 默认 isConnected 返回 false(Mockito 默认 boolean = false)
        injectConnection("disconnected", mockSession, "h", 22, "u", 1000L)

        SshClient.disconnect("disconnected")

        // 验证 session.disconnect() 未被调用
        Mockito.verify(mockSession, Mockito.never()).disconnect()
    }

    @Test
    fun `disconnectAll on connected sessions calls disconnect on each`() {
        val s1 = Mockito.mock(Session::class.java)
        val s2 = Mockito.mock(Session::class.java)
        Mockito.`when`(s1.isConnected).thenReturn(true)
        Mockito.`when`(s2.isConnected).thenReturn(true)
        injectConnection("a", s1, "h1", 22, "u1", 1000L)
        injectConnection("b", s2, "h2", 22, "u2", 2000L)

        SshClient.disconnectAll()

        Mockito.verify(s1).disconnect()
        Mockito.verify(s2).disconnect()
    }

    // ── LRU 淘汰(evictOldest 经反射调用)──

    @Test
    fun `evictOldest removes the connection with smallest connectedAtMs`() {
        val mockSession = Mockito.mock(Session::class.java)
        injectConnection("newest", mockSession, "h3", 22, "u3", 3000L)
        injectConnection("oldest", mockSession, "h1", 22, "u1", 1000L)
        injectConnection("middle", mockSession, "h2", 22, "u2", 2000L)
        assertEquals(3, SshClient.activeCount())

        invokeEvictOldest()

        assertEquals(2, SshClient.activeCount())
        assertNull("oldest 应被淘汰", SshClient.getConnection("oldest"))
        assertNotNull(SshClient.getConnection("newest"))
        assertNotNull(SshClient.getConnection("middle"))
    }

    @Test
    fun `evictOldest on empty pool is no-op`() {
        invokeEvictOldest()
        assertEquals(0, SshClient.activeCount())
    }

    // ── SshException ──

    @Test
    fun `SshException preserves message and cause`() {
        val cause = RuntimeException("root")
        val ex = SshClient.SshException("wrapper", cause)
        assertEquals("wrapper", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `SshException with null cause`() {
        val ex = SshClient.SshException("just a message")
        assertEquals("just a message", ex.message)
        // cause 默认 null
    }

    // ── 辅助:反射注入 Connection 到私有 sessions map ──

    private fun injectConnection(
        id: String,
        session: Session,
        host: String,
        port: Int,
        user: String,
        connectedAtMs: Long,
    ) {
        val sessionsField = getSessionsField()
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(SshClient) as java.util.concurrent.ConcurrentHashMap<String, Connection>
        sessions[id] = Connection(
            id = id,
            session = session,
            host = host,
            port = port,
            user = user,
            connectedAtMs = connectedAtMs,
        )
    }

    private fun invokeEvictOldest() {
        val method = SshClient::class.java.getDeclaredMethod("evictOldest")
        method.isAccessible = true
        method.invoke(SshClient)
    }

    private fun getSessionsField(): Field {
        return SshClient::class.java.getDeclaredField("sessions").apply { isAccessible = true }
    }
}
