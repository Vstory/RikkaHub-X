// 会话级模型选择的兜底存储:编码与「会话核对」的守护测试。
//
// 为什么这层必须有测试:该存储用一个键服务所有会话,**会话核对是它唯一的安全前提** ——
// 一旦核对失效,就会把 A 会话选的模型塞给 B 会话(用户看到的是「模型自己变了」,
// 不报错、不崩溃,只会莫名换模型)。故把核对规则钉成契约。
//
// 纯函数部分不依赖 Android,可离线验算(与 `ChatDraftStore` 的取舍一致:
// 那里也没测 SharedPreferences 本身,测的是「什么该存取」)。
package me.rerere.rikkahub.x.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationModelPickStoreTest {

    private val conversationA: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val conversationB: Uuid = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val modelX: Uuid = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val modelY: Uuid = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    // ─────────────── 编码往返 ───────────────

    @Test
    fun `encode then decode round trips both ids`() {
        val raw = ConversationModelPickStore.encode(conversationA, modelX)
        val decoded = ConversationModelPickStore.decode(raw)
        assertEquals(conversationA to modelX, decoded)
    }

    @Test
    fun `encoded form is one line with a single separator`() {
        // 分隔符多一个就会破坏 limit=2 的解析(模型 id 段会带进多余的冒号)
        val raw = ConversationModelPickStore.encode(conversationA, modelX)
        assertEquals("应恰好一个冒号分隔", 1, raw.count { it == ':' })
        assertEquals("不得含换行", raw, raw.trim())
    }

    // ─────────────── 损坏输入一律当作「没有记录」 ───────────────

    @Test
    fun `damaged input decodes to null`() {
        listOf(
            null,                       // 没有记录
            "",                         // 空串
            "not-a-uuid",               // 没有分隔符
            "$conversationA",           // 只有会话段
            ":$modelX",                 // 会话段为空
            "$conversationA:",          // 模型段为空
            "garbage:$modelX",          // 会话段不是 uuid
            "$conversationA:garbage",   // 模型段不是 uuid
        ).forEach { raw ->
            assertNull("损坏输入应解析为 null:$raw", ConversationModelPickStore.decode(raw))
        }
    }

    @Test
    fun `resolve of a damaged record is null`() {
        assertNull(ConversationModelPickStore.resolve(null, conversationA))
        assertNull(ConversationModelPickStore.resolve("garbage", conversationA))
    }

    // ─────────────── ★ 会话核对（单键方案的安全前提）───────────────

    @Test
    fun `resolve returns the model when the record belongs to this conversation`() {
        val raw = ConversationModelPickStore.encode(conversationA, modelX)
        assertEquals(modelX, ConversationModelPickStore.resolve(raw, conversationA))
    }

    @Test
    fun `resolve returns null when the record belongs to another conversation`() {
        // ★ 这一条是核心:A 会话选的模型**绝不能**被 B 会话读到
        val raw = ConversationModelPickStore.encode(conversationA, modelX)
        assertNull(
            "记录属于别的会话时不得返回模型 —— 否则用户会看到模型被悄悄换掉",
            ConversationModelPickStore.resolve(raw, conversationB),
        )
    }

    @Test
    fun `last write wins and earlier conversation no longer resolves`() {
        // 单键语义:后一次选择覆盖前一次,前一个会话再也读不到(它本该已落库)
        val forA = ConversationModelPickStore.encode(conversationA, modelX)
        val forB = ConversationModelPickStore.encode(conversationB, modelY)

        assertEquals(modelX, ConversationModelPickStore.resolve(forA, conversationA))
        // B 写入之后,键里只剩 B:
        assertEquals(modelY, ConversationModelPickStore.resolve(forB, conversationB))
        assertNull(ConversationModelPickStore.resolve(forB, conversationA))
    }

    @Test
    fun `the two conversations may hold different models`() {
        // 「每个会话用不同模型」由数据库承担,但兜底层也不得把两者混成一个值
        assertNull(ConversationModelPickStore.resolve(
            ConversationModelPickStore.encode(conversationA, modelX), conversationB))
        assertNull(ConversationModelPickStore.resolve(
            ConversationModelPickStore.encode(conversationB, modelY), conversationA))
    }
}
