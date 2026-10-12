package com.apk.claw.android.tool.impl

import com.apk.claw.android.agent.ToolOutputContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GitCommandStreamingTest {
    @Test
    fun `output is observable before process exit and split UTF8 remains intact`() {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val dir = Files.createTempDirectory("git-stream").toFile()
        val pool = Executors.newSingleThreadExecutor()
        val firstOutput = CountDownLatch(1)
        val chunks = StringBuffer()
        try {
            val result = pool.submit<GitCommandRunner.GitCommandResult> {
                ToolOutputContext.withSink({ chunks.append(it); firstOutput.countDown() }) {
                    GitCommandRunner.runCommand(dir, "sh", "-c",
                        "printf '开始\\n'; while [ ! -f continue ]; do sleep 0.01; done; " +
                            "printf '\\360\\237'; printf '\\230\\200结束\\n'; printf '错误\\n' >&2",
                        timeoutSec = 10)
                }
            }
            assertTrue("must deliver output before exit", firstOutput.await(5, TimeUnit.SECONDS))
            assertFalse("process is still awaiting release", result.isDone)
            dir.resolve("continue").writeText("go")
            val completed = result.get(5, TimeUnit.SECONDS)
            assertTrue(completed.success)
            assertEquals("开始\n😀结束\n", completed.stdout)
            assertEquals("错误\n", completed.stderr)
            assertTrue(chunks.contains("😀结束"))
            assertTrue(chunks.contains("--- stderr ---"))
            assertFalse(chunks.contains("�"))
        } finally {
            dir.resolve("continue").writeText("go")
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }
}
