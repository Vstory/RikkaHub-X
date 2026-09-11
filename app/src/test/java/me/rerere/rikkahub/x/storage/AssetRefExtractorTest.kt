package me.rerere.rikkahub.x.storage

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.localFileUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用提取单测（X 存储重构 P1）。
 *
 * 钉住四条：
 * ① 只认本地 `file://` —— 远程 URL 不占本地盘，纳入回收范围只会产生无意义的数据；
 * ② 工具输出里嵌套的附件同样算引用（它们确实占着磁盘）；
 * ③ 同一消息内按「角色 + URL」去重 —— 不去重会撞 `x_asset_ref` 的主键；
 * ④ 与既有 `localFileUrls()` **不漂移**（同一套语义的两处实现，最怕改一处忘另一处）。
 */
class AssetRefExtractorTest {

    private val root = "/data/user/0/me.rerere.rikkahub/files"

    private fun fileUrl(relative: String) = "file://$root/$relative"

    private fun userMessage(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.USER, parts = parts.toList())

    // ---- 基本提取 ----

    @Test
    fun `extracts the four url bearing part types`() {
        val message = userMessage(
            UIMessagePart.Image(fileUrl("upload/a.png")),
            UIMessagePart.Video(fileUrl("upload/b.mp4")),
            UIMessagePart.Audio(fileUrl("upload/c.mp3")),
            UIMessagePart.Document(fileUrl("upload/d.pdf"), "d.pdf"),
        )
        val refs = AssetRefExtractor.extract(listOf(message))

        assertEquals(4, refs.size)
        assertEquals(
            setOf(XStorageTables.RefKinds.IMAGE, XStorageTables.RefKinds.VIDEO, XStorageTables.RefKinds.AUDIO, XStorageTables.RefKinds.DOCUMENT),
            refs.map { it.kind }.toSet(),
        )
        assertTrue("每条引用都要带上所属消息", refs.all { it.messageId == message.id.toString() })
    }

    @Test
    fun `ignores non local urls`() {
        val message = userMessage(
            UIMessagePart.Image("https://example.com/a.png"),
            UIMessagePart.Image("data:image/png;base64,AAAA"),
            UIMessagePart.Image("content://photos/a"),
            UIMessagePart.Document("content://docs/a.pdf", "a.pdf"),
        )
        assertTrue("非 file:// 一律不登记", AssetRefExtractor.extract(listOf(message)).isEmpty())
    }

    @Test
    fun `every produced kind is declared in RefKinds`() {
        // 防漂移:提取器新支持一种 part 却忘了登记常量时,这条会红
        val message = userMessage(
            UIMessagePart.Image(fileUrl("upload/a.png")),
            UIMessagePart.Video(fileUrl("upload/b.mp4")),
            UIMessagePart.Audio(fileUrl("upload/c.mp3")),
            UIMessagePart.Document(fileUrl("upload/d.pdf"), "d.pdf"),
        )
        val produced = AssetRefExtractor.extract(listOf(message)).map { it.kind }.toSet()
        assertTrue(
            "提取出的角色必须都在 XStorageTables.RefKinds.ALL 内:${produced - XStorageTables.RefKinds.ALL.toSet()}",
            XStorageTables.RefKinds.ALL.toSet().containsAll(produced),
        )
    }

    // ---- 工具输出嵌套 ----

    @Test
    fun `recurses into tool output`() {
        val message = userMessage(
            UIMessagePart.Tool(
                toolCallId = "call-1",
                toolName = "screenshot",
                input = "{}",
                output = listOf(UIMessagePart.Image(fileUrl("tool_output/shot.png"))),
            ),
        )
        val refs = AssetRefExtractor.extract(listOf(message))

        assertEquals(1, refs.size)
        assertEquals(fileUrl("tool_output/shot.png"), refs.single().url)
        // 角色按「它本身是什么」记,不是按「谁产生的」——后者属于 x_asset.origin
        assertEquals(XStorageTables.RefKinds.IMAGE, refs.single().kind)
    }

    @Test
    fun `recurses through nested tool output`() {
        val message = userMessage(
            UIMessagePart.Tool(
                toolCallId = "outer",
                toolName = "outer",
                input = "{}",
                output = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "inner",
                        toolName = "inner",
                        input = "{}",
                        output = listOf(UIMessagePart.Image(fileUrl("tool_output/deep.png"))),
                    ),
                ),
            ),
        )
        assertEquals(1, AssetRefExtractor.extract(listOf(message)).size)
    }

    // ---- 去重 ----

    @Test
    fun `deduplicates same url within one message`() {
        // 同一条消息里贴两次同一张图 → 不去重会撞主键 (message_id, asset_id, kind)
        val message = userMessage(
            UIMessagePart.Image(fileUrl("upload/a.png")),
            UIMessagePart.Image(fileUrl("upload/a.png")),
        )
        assertEquals(1, AssetRefExtractor.extract(listOf(message)).size)
    }

    @Test
    fun `keeps same url coming from different messages`() {
        // 两条消息引用同一文件 → 两行引用(回收时要等所有引用都消失)
        val a = userMessage(UIMessagePart.Image(fileUrl("upload/a.png")))
        val b = userMessage(UIMessagePart.Image(fileUrl("upload/a.png")))
        val refs = AssetRefExtractor.extract(listOf(a, b))

        assertEquals(2, refs.size)
        assertEquals(2, refs.map { it.messageId }.toSet().size)
    }

    @Test
    fun `same url with different kinds yields one ref per kind`() {
        // 同一个文件既是图片附件又是文档附件 → 角色不同,各记一条(主键含 kind)
        val message = userMessage(
            UIMessagePart.Image(fileUrl("upload/a.bin")),
            UIMessagePart.Document(fileUrl("upload/a.bin"), "a.bin"),
        )
        assertEquals(2, AssetRefExtractor.extract(listOf(message)).size)
    }

    // ---- 与既有 localFileUrls 的一致性 ----

    @Test
    fun `url set matches existing localFileUrls semantics`() {
        // 同一套语义在两处实现,最怕改一处忘另一处 —— 这条把二者钉在一起
        val messages = listOf(
            userMessage(
                UIMessagePart.Image(fileUrl("upload/a.png")),
                UIMessagePart.Video(fileUrl("upload/b.mp4")),
                UIMessagePart.Image("https://example.com/remote.png"),
                UIMessagePart.Text("正文里恰好出现 file://$root/upload/a.png 这串字"),
            ),
            userMessage(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "t",
                    input = "{}",
                    output = listOf(UIMessagePart.Document(fileUrl("tool_output/d.pdf"), "d.pdf")),
                ),
            ),
        )

        val mine = AssetRefExtractor.extract(messages).map { it.url }.toSet()
        val existing = messages.flatMap { it.parts.localFileUrls() }.toSet()

        assertEquals("两处实现必须给出同一批 URL", existing, mine)
    }

    // ---- URL → 相对路径 ----

    @Test
    fun `maps file url to path relative to files dir`() {
        assertEquals("upload/a.png", AssetRefExtractor.toRelativePath(fileUrl("upload/a.png"), root))
        assertEquals(
            "assets/ab/cd/x.png",
            AssetRefExtractor.toRelativePath(fileUrl("assets/ab/cd/x.png"), root),
        )
        // 结尾多余的斜杠不影响
        assertEquals("upload/a.png", AssetRefExtractor.toRelativePath(fileUrl("upload/a.png"), "$root/"))
    }

    @Test
    fun `decodes percent escapes`() {
        // 注意相对路径含 upload/ 前缀 —— 断言漏写前缀正是离线验算抓到的那次
        assertEquals(
            "upload/\u7167\u7247.png",
            AssetRefExtractor.toRelativePath("file://$root/upload/%E7%85%A7%E7%89%87.png", root),
        )
    }

    @Test
    fun `keeps literal plus sign`() {
        // URLDecoder 把 '+' 当空格是表单语义;路径里 '+' 是字面量
        assertEquals("upload/a+b.png", AssetRefExtractor.toRelativePath(fileUrl("upload/a+b.png"), root))
    }

    @Test
    fun `rejects urls outside the files dir`() {
        assertNull(
            "缓存目录不属于可回收范围",
            AssetRefExtractor.toRelativePath("file:///data/user/0/me.rerere.rikkahub/cache/a.png", root),
        )
        assertNull(
            "外部存储不属于本应用",
            AssetRefExtractor.toRelativePath("file:///storage/emulated/0/Download/a.png", root),
        )
        assertNull("非 file:// 协议", AssetRefExtractor.toRelativePath("https://example.com/a.png", root))
        assertNull("空字符串", AssetRefExtractor.toRelativePath("", root))
    }

    @Test
    fun `rejects traversal and malformed urls`() {
        assertNull(
            "上跳必须拒绝",
            AssetRefExtractor.toRelativePath("file://$root/../secret", root),
        )
        assertNull(
            "夹带上跳必须拒绝",
            AssetRefExtractor.toRelativePath("file://$root/upload/../../x.png", root),
        )
        assertNull(
            "畸形百分号转义不抛异常,返回 null",
            AssetRefExtractor.toRelativePath("file://$root/upload/%ZZ.png", root),
        )
        assertNull(
            "恰为根目录时无相对路径",
            AssetRefExtractor.toRelativePath("file://$root", root),
        )
    }
}
