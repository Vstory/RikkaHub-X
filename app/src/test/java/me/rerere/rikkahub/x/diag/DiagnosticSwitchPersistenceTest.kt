package me.rerere.rikkahub.x.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断开关的**持久化契约**自检（2026-09-11 用户指出后补）。
 *
 * ## 这条用例守的是什么（一个曾被忽略的硬缺陷）
 *
 * 改造前开关只是个内存变量：**进程一重启就归零**。而本项目最需要取证的恰恰是启动期的事 ——
 * 存量回填在开机时跑、启动健壮性要查的就是「打不开 App」这类启动期故障。
 * **要取证的事在启动期，开关却活不过启动期**，逻辑上自相矛盾。
 *
 * Android 侧的落盘由 `DiagnosticSwitchStore` 负责，这里用**注入的假读写器**验协议本身：
 * 初值能读回、每次翻转都会写出去。这样「开关会不会被持久化」不必装机去试。
 *
 * ⚠️ 用 `@After` 复位成「未接持久化」的空状态：`XDiagnostics` 是进程内单例，
 * 一个用例留下的持久化回调会污染下一个用例（本项目在别的单例上也踩过这个坑）。
 */
class DiagnosticSwitchPersistenceTest {

    @After
    fun detach() {
        // 复位：不接持久化、开关归零，避免单例状态跨用例泄漏
        XDiagnostics.attachPersistence(initial = false, write = {})
        XDiagnostics.setEnabled(false)
    }

    @Test
    fun `initial value is applied from the store`() {
        XDiagnostics.attachPersistence(initial = true, write = {})
        assertTrue("上次是开着 → 启动后应当仍开着", XDiagnostics.isEnabled())
    }

    @Test
    fun `default is off when nothing was stored`() {
        XDiagnostics.attachPersistence(initial = false, write = {})
        assertFalse("从未开过 → 应当是关的（不因为改动而默认打开）", XDiagnostics.isEnabled())
    }

    @Test
    fun `turning on writes to the store`() {
        val written = mutableListOf<Boolean>()
        XDiagnostics.attachPersistence(initial = false, write = { written.add(it) })

        XDiagnostics.setEnabled(true)

        assertEquals("应当落盘一次且值为 true", listOf(true), written)
        assertTrue("内存里的开关也要立即生效", XDiagnostics.isEnabled())
    }

    @Test
    fun `turning off writes to the store`() {
        val written = mutableListOf<Boolean>()
        XDiagnostics.attachPersistence(initial = true, write = { written.add(it) })

        XDiagnostics.setEnabled(false)

        assertEquals("关掉也要落盘 —— 否则下次启动又变回开着", listOf(false), written)
        assertFalse(XDiagnostics.isEnabled())
    }

    @Test
    fun `works without persistence attached`() {
        // JVM 单测与「尚未接线」的状态下不能崩：开关只在内存里翻转，与改造前行为一致
        XDiagnostics.attachPersistence(initial = false, write = {})
        XDiagnostics.setEnabled(true)
        assertTrue(XDiagnostics.isEnabled())
    }

    @Test
    fun `write failure does not break the in memory switch`() {
        // 落盘失败（磁盘满 / 权限）时，本次会话的开关仍应有效 ——
        // 否则用户点一下开关「什么都没发生」，比报错更难查。
        XDiagnostics.attachPersistence(initial = false, write = { error("disk full") })

        XDiagnostics.setEnabled(true)

        assertTrue("落盘失败不该回滚内存状态", XDiagnostics.isEnabled())
    }

    @Test
    fun `write failure is recorded where the user will see it`() {
        // 落盘失败**不能静默**：它的后果是「下次启动开关归零」，而用户会以为开关坏了。
        // 记进内存缓冲（诊断页导出里能看到），而不是只写系统日志 ——
        // 后者在真机上用户根本看不到（这正是本次改造要解决的那类问题）。
        XDiagnostics.attachPersistence(initial = false, write = { error("disk full") })

        XDiagnostics.setEnabled(true)

        val entries = XDiagnostics.entries(XDomain.CORE)
        assertTrue(
            "落盘失败必须留下记录:$entries",
            entries.any { it.event == XDiagnostics.PERSIST_FAIL_EVENT },
        )
        assertTrue(
            "记录里应带原始异常，便于判断是权限还是空间:${entries.map { it.message }}",
            entries.any { it.message.contains("disk full") },
        )
    }

    @Test
    fun `persist failure event name is conventional`() {
        // 与其它事件名同规矩：三段式、全小写、可 grep
        val event = XDiagnostics.PERSIST_FAIL_EVENT
        assertEquals("应为「域.动作.结果」三段:$event", 3, event.split(".").size)
        assertEquals("应全小写:$event", event, event.lowercase())
    }

    @Test
    fun `install is idempotent`() {
        // Application.onCreate 可能因多进程/重复调用而跑多次
        XDiagnostics.attachPersistence(initial = true, write = {})
        XDiagnostics.attachPersistence(initial = true, write = {})
        assertTrue(XDiagnostics.isEnabled())
    }
}
