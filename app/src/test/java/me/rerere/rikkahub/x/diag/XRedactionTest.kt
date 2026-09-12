package me.rerere.rikkahub.x.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脱敏单测（诊断框架 D1）。
 *
 * 守的是一件事：**诊断包要能放心发出去**。若脱敏失效，用户每次导出前都得人工审一遍，
 * 最终结局是「懒得导出」—— 功能等于不存在。
 *
 * 另一面同样重要：**不能过度脱敏**。把该留的信息也抹掉，导出包里就没有可排查的内容了。
 * 故两个方向都有用例。
 */
class XRedactionTest {

    private val root = "/data/user/0/me.rerere.rikkahub.x/files"
    private val aliasRoot = "/data/data/me.rerere.rikkahub.x/files"
    private val hash = "a3f9c2d1" + "0".repeat(56)
    private val md5 = "b" + "1".repeat(31)

    // ---- 完整信息开关 ----

    @Test
    fun `full mode leaves everything untouched`() {
        val text = "路径 $root/assets/ab/cd/$hash.png"
        assertEquals(text, XRedaction.redact(text, root, full = true))
    }

    @Test
    fun `default mode redacts`() {
        val out = XRedaction.redact("路径 $root/assets/ab/cd/$hash.png", root)
        assertFalse("默认应脱敏路径", out.contains(root))
        assertFalse("默认应脱敏哈希", out.contains(hash))
    }

    // ---- 路径 ----

    @Test
    fun `files root prefix is replaced with placeholder`() {
        val out = XRedaction.redact("$root/assets/ab/cd/x.png", root)
        assertEquals("<files>/assets/ab/cd/x.png", out)
    }

    @Test
    fun `data data variant is also stripped`() {
        // Android 在不同版本/场景下给出 /data/data/... 形态,二者指向同一目录
        assertEquals(
            "<files>/assets/x.png",
            XRedaction.redact("$aliasRoot/assets/x.png", aliasRoot),
        )
    }

    @Test
    fun `strips the other variant of the same root too`() {
        // 传入 user/0 形态时,data/data 形态也应被抹掉 —— 日志里两种写法都可能出现
        val out = XRedaction.redact("$aliasRoot/assets/x.png", root)
        assertEquals("<files>/assets/x.png", out)
    }

    @Test
    fun `longer variant is replaced first`() {
        // 若短前缀先命中,会在文本里留下半截路径
        val text = "$aliasRoot/assets/x.png 与 $root/assets/y.png"
        val out = XRedaction.redact(text, root)
        assertFalse(out.contains("data/user"))
        assertFalse(out.contains("data/data"))
    }

    @Test
    fun `null or empty root only redacts hashes`() {
        val text = "$root/assets/$hash.png"
        val out = XRedaction.redact(text, filesRoot = null)
        assertTrue("无根目录时路径原样保留", out.contains(root))
        assertFalse("但哈希仍要脱敏", out.contains(hash))
        assertEquals(out, XRedaction.redact(text, filesRoot = ""))
    }

    @Test
    fun `trailing slash on root is tolerated`() {
        assertEquals(
            "<files>/a.png",
            XRedaction.redact("$root/a.png", "$root/"),
        )
    }

    @Test
    fun `root appearing mid text is redacted everywhere`() {
        val out = XRedaction.redact("前 $root/a.png 后 $root/b.png", root)
        assertEquals("前 <files>/a.png 后 <files>/b.png", out)
    }

    // ---- 哈希 ----

    @Test
    fun `sha256 keeps only the leading prefix`() {
        val out = XRedaction.redactHashes("id=$hash")
        assertEquals("id=${hash.take(8)}…", out)
    }

    @Test
    fun `md5 keeps only the leading prefix`() {
        assertEquals("${md5.take(8)}…", XRedaction.redactHashes(md5))
    }

    @Test
    fun `sha256 is not split into two md5 sized pieces`() {
        // 先处理 32 位会把一个 64 位哈希切两半,得到 xxxxxxxx…xxxxxxxx… 这种怪结果
        val out = XRedaction.redactHashes(hash)
        assertEquals(1, out.count { it == '…' })
        assertEquals(hash.take(8) + "…", out)
    }

    @Test
    fun `uppercase hex is left alone`() {
        // 我们的哈希一律小写;大写串更可能是外部数据(如 base64 片段),不动以免误伤
        val upper = hash.uppercase()
        assertEquals(upper, XRedaction.redactHashes(upper))
    }

    @Test
    fun `short hex is left alone`() {
        assertEquals("abcd1234", XRedaction.redactHashes("abcd1234"))
    }

    @Test
    fun `numbers are not mistaken for hashes`() {
        // 纯数字的长串在日志里比哈希常见得多(时间戳拼接、长 ID),不该被抹掉。
        // 实现上靠在哈希正则里要求「至少含一个 a~f 字母」来区分
        assertEquals("12345678901234567890123456789012", XRedaction.redactHashes("12345678901234567890123456789012"))
        assertEquals(
            "1234567890123456789012345678901234567890123456789012345678901234",
            XRedaction.redactHashes("1234567890123456789012345678901234567890123456789012345678901234"),
        )
    }

    @Test
    fun `hash embedded in a word is not truncated`() {
        // 词边界不成立更可能是拼接错误而非哈希;保守不处理,避免误伤正常文本
        val embedded = "abc${hash}def"
        assertEquals(embedded, XRedaction.redactHashes(embedded))
    }

    @Test
    fun `path and hash are both handled in one pass`() {
        val out = XRedaction.redact("$root/assets/ab/cd/$hash.png 大小 12345", root)
        assertTrue(out.startsWith("<files>/assets/ab/cd/"))
        assertTrue(out.contains("${hash.take(8)}…"))
        assertTrue("普通数字不该被动", out.contains("12345"))
    }

    // ---- 不该动的东西 ----

    @Test
    fun `ordinary words and ids survive`() {
        val text = "会话 550e8400-e29b-41d4-a716-446655440000 引用 2 条"
        assertEquals(text, XRedaction.redact(text, root))
    }
}
