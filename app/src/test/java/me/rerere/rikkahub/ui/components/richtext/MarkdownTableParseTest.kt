package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游 issue #1114(表格未渲染)回归测试:
 * 确保 GFM 表格能被解析器产出 TABLE/HEADER/ROW/CELL 节点 —— 渲染层(TableNode → DataTable)
 * 依赖这些节点,解析缺失即表现为"表格未渲染"。
 *
 * 注:与 Markdown.kt 生产配置保持同参(makeHttpsAutoLinks/useSafeLinks)。
 * 不调用 getTextInNode(被 Markdown.kt 的 private 同名扩展遮蔽),仅做结构断言。
 */
class MarkdownTableParseTest {

    private val parser = MarkdownParser(
        GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    )

    private fun ASTNode.findAll(type: IElementType): List<ASTNode> {
        val out = mutableListOf<ASTNode>()
        fun walk(node: ASTNode) {
            if (node.type == type) out.add(node)
            node.children.forEach { walk(it) }
        }
        walk(this)
        return out
    }

    @Test
    fun `标准GFM表格应解析出TABLE_HEADER_ROW_CELL`() {
        val md = """
            | 部位 | 归属 |
            | ---- | ---- |
            | 额头 | 横向 |
            | 鼻子 | 纵向 |
        """.trimIndent()

        val tree = parser.buildMarkdownTreeFromString(md)

        val tables = tree.findAll(GFMElementTypes.TABLE)
        assertTrue("表格 markdown 应产出 TABLE 节点, 但 AST 无 TABLE", tables.isNotEmpty())

        val table = tables.first()
        assertEquals("TABLE 应含 1 个表头 HEADER", 1, table.findAll(GFMElementTypes.HEADER).size)
        assertEquals("TABLE 应含 2 个数据行 ROW", 2, table.findAll(GFMElementTypes.ROW).size)
        // 表头 2 格 + 2 行 × 2 格 = 6 个 CELL
        assertEquals("应产出 6 个 CELL", 6, table.findAll(GFMTokenTypes.CELL).size)
    }

    @Test
    fun `无分隔线的竖线文本不应误判为表格`() {
        val md = "这不是表格 | 只是普通文本"
        val tree = parser.buildMarkdownTreeFromString(md)
        assertTrue("无分隔线的竖线行不是 GFM 表格", tree.findAll(GFMElementTypes.TABLE).isEmpty())
    }

    @Test
    fun `表格内行内格式应保留(粗体子节点不丢失)`() {
        val md = """
            | **加粗表头** | 普通列 |
            | ------------ | ------ |
            | **加粗值**   | 普通值 |
        """.trimIndent()

        val tree = parser.buildMarkdownTreeFromString(md)
        val table = tree.findAll(GFMElementTypes.TABLE).firstOrNull()
        assertTrue("应解析出 TABLE", table != null)
        // CELL 内粗体应解析为 STRONG 子节点(渲染层会套用加粗样式,而非裸露 ** 记号)
        assertTrue(
            "表格单元格内粗体应解析为 STRONG 节点",
            table!!.findAll(MarkdownElementTypes.STRONG).isNotEmpty()
        )
    }
}
