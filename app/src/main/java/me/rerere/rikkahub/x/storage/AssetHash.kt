// [X-custom] RikkaHub-X 存储管理重构(P0)：内容哈希与落盘路径
package me.rerere.rikkahub.x.storage

import java.io.InputStream
import java.security.MessageDigest

/**
 * 资产的内容哈希（内容寻址）。
 *
 * 这是 X 存储重构的**地基**：把「按路径登记文件」换成「按内容登记」后，
 * 才谈得上去重、才算得准引用、才敢删文件。
 *
 * 选 SHA-256 而非更短的摘要：仅用于去重与完整性核对，不参与安全判定，
 * 但碰撞代价为零成本（本地计算），故直接用标准算法、不做自创。
 */
object AssetHash {

    const val ALGORITHM = "SHA-256"

    /** 十六进制长度（SHA-256 = 32 字节）。 */
    const val HEX_LENGTH = 64

    /** 落盘根目录（相对 filesDir）。 */
    const val ROOT = "assets"

    private const val READ_BUFFER_BYTES = 64 * 1024
    private const val FALLBACK_EXTENSION = "bin"
    private const val SHARD_LENGTH = 2
    private const val HEX_DIGITS = "0123456789abcdef"

    /** 扩展名白名单：短小的字母数字。其余（含 `tar.gz`）一律回退 `bin`。 */
    private val EXTENSION_PATTERN = Regex("[a-z0-9]{1,8}")

    /** 计算字节数组的哈希。 */
    fun of(bytes: ByteArray): String =
        toHex(MessageDigest.getInstance(ALGORITHM).digest(bytes))

    /**
     * 流式计算哈希 —— 大文件不必整体读入内存。
     *
     * 循环写法刻意容忍 `read` 返回 0（合法但无进展）而不当作 EOF，
     * 只有 `-1` 才是结束；测试用「每次只吐 7 字节」的流覆盖这条路径。
     */
    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read != -1) {
            if (read > 0) digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
        return toHex(digest.digest())
    }

    /** 是否为合法的（小写十六进制）SHA-256。 */
    fun isValid(hash: String): Boolean =
        hash.length == HEX_LENGTH && hash.all { it in '0'..'9' || it in 'a'..'f' }

    /** 扩展名归一：`".PNG"`/`"png"` → `png`；缺失或不合规 → `bin`。 */
    fun normalizeExtension(extension: String?): String {
        val candidate = extension?.trim()?.lowercase()?.removePrefix(".").orEmpty()
        return if (EXTENSION_PATTERN.matches(candidate)) candidate else FALLBACK_EXTENSION
    }

    /**
     * 哈希 + 扩展名 → 相对落盘路径。
     *
     * 两级分片（`ab/cd/`）避免单目录堆积上万文件 —— 目录项过多会让
     * 文件系统枚举与删除明显变慢，而本设计要频繁按目录扫描孤儿文件。
     */
    fun relativePath(hash: String, extension: String?): String {
        require(isValid(hash)) { "不是合法的 SHA-256 十六进制:$hash" }
        val shardA = hash.substring(0, SHARD_LENGTH)
        val shardB = hash.substring(SHARD_LENGTH, SHARD_LENGTH * 2)
        return "$ROOT/$shardA/$shardB/$hash.${normalizeExtension(extension)}"
    }

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            // 显式掩码:Byte 在 Kotlin 是有符号的,直接格式化负数会得到错误结果
            val value = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[value ushr 4])
            out.append(HEX_DIGITS[value and 0x0F])
        }
        return out.toString()
    }
}
