package me.rerere.rikkahub.x.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容寻址写入决策单测（X 存储重构 P1）。
 *
 * 钉住三件事：
 * ① **同一份内容只落一次盘**（去重成立）；
 * ② 文件丢了要按**原路径**重写 —— 换路径会把已有引用全部打断；
 * ③ 删除前的路径护栏不许放过内容寻址根之外的东西。
 */
class AssetStorePolicyTest {

    private val hash = "ab" + "cd" + "0".repeat(60)
    private val expectedPath = "assets/ab/cd/$hash.png"

    // ---- 三种情形 ----

    @Test
    fun `writes new when content never seen`() {
        val plan = AssetStorePolicy.plan(hash, "png", known = null)
        assertEquals(AssetStorePlan.WriteNew(expectedPath), plan)
        assertTrue("首次见到的内容必须写盘", AssetStorePolicy.requiresWrite(plan))
    }

    @Test
    fun `reuses existing when file is on disk`() {
        val plan = AssetStorePolicy.plan(
            hash,
            "png",
            known = KnownAsset(relativePath = expectedPath, fileExists = true),
        )
        assertEquals(AssetStorePlan.ReuseExisting(expectedPath), plan)
        assertFalse("命中已有内容时不该再写盘", AssetStorePolicy.requiresWrite(plan))
    }

    @Test
    fun `rewrites at original path when file vanished`() {
        val plan = AssetStorePolicy.plan(
            hash,
            "png",
            known = KnownAsset(relativePath = expectedPath, fileExists = false),
        )
        assertEquals(AssetStorePlan.RewriteMissing(expectedPath), plan)
        assertTrue("文件不在时必须补写", AssetStorePolicy.requiresWrite(plan))
    }

    // ---- 扩展名以首次落盘者为准 ----

    @Test
    fun `keeps original extension when content matched by another extension`() {
        // 同一串字节先后以 bin 与 png 两种扩展名进来:
        // 内容寻址要求「一份内容一个文件」,故沿用首次落盘的路径与后缀
        val storedAsBin = "assets/ab/cd/$hash.bin"
        val plan = AssetStorePolicy.plan(
            hash,
            "png",
            known = KnownAsset(relativePath = storedAsBin, fileExists = true),
        )
        assertEquals(
            "命中已有内容时应沿用旧路径,不因本次扩展名不同而改名",
            AssetStorePlan.ReuseExisting(storedAsBin),
            plan,
        )
    }

    @Test
    fun `normalizes extension when creating new path`() {
        assertEquals(
            AssetStorePlan.WriteNew("assets/ab/cd/$hash.png"),
            AssetStorePolicy.plan(hash, ".PNG", known = null),
        )
        // 不合规的扩展名回退 bin(见 AssetHash.normalizeExtension)
        assertEquals(
            AssetStorePlan.WriteNew("assets/ab/cd/$hash.bin"),
            AssetStorePolicy.plan(hash, "tar.gz", known = null),
        )
    }

    // ---- 删除前的路径护栏 ----

    @Test
    fun `accepts paths inside content root`() {
        assertTrue(AssetStorePolicy.isManagedPath("assets/ab/cd/$hash.png"))
        assertTrue(AssetStorePolicy.isManagedPath("assets/x"))
    }

    @Test
    fun `rejects paths outside content root`() {
        assertFalse("upload 目录不属于内容寻址", AssetStorePolicy.isManagedPath("upload/abc.png"))
        assertFalse("skills 目录不属于内容寻址", AssetStorePolicy.isManagedPath("skills/a.md"))
        assertFalse("空路径拒绝", AssetStorePolicy.isManagedPath(""))
        // 同前缀但不同目录 —— 用 startsWith(\"assets\") 会误放过
        assertFalse("同前缀目录必须拒绝", AssetStorePolicy.isManagedPath("assets_evil/x"))
        assertFalse("裸目录名不带分隔符,拒绝", AssetStorePolicy.isManagedPath("assets"))
    }

    @Test
    fun `rejects traversal and absolute paths`() {
        assertFalse("绝对路径拒绝", AssetStorePolicy.isManagedPath("/assets/a.png"))
        assertFalse("上跳拒绝", AssetStorePolicy.isManagedPath("assets/../../etc/passwd"))
        assertFalse("夹带上跳拒绝", AssetStorePolicy.isManagedPath("assets/ab/../upload/x.png"))
        assertFalse("反斜杠拒绝(Windows 风格分隔符可能绕过前缀判定)", AssetStorePolicy.isManagedPath("assets\\a.png"))
    }
}
