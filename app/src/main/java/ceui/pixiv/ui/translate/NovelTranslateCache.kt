package ceui.pixiv.ui.translate

import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.novel.reader.model.ContentToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 小说段落译文落盘缓存。
 * key = novelId + targetLang；token 数量 / 段落数 / 正文指纹任一变化即失效。
 */
object NovelTranslateCache {

    private const val VERSION = 1

    fun load(novelId: Long, targetLang: String, tokens: List<ContentToken>): List<String>? {
        val paragraphs = tokens.filterIsInstance<ContentToken.Paragraph>()
        val file = cacheFile(novelId, targetLang)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("version", -1) != VERSION) return@runCatching null
            if (root.optInt("tokenCount", -1) != tokens.size) return@runCatching null
            if (root.optInt("paragraphCount", -1) != paragraphs.size) return@runCatching null
            if (root.optString("sourceHash") != sourceHash(tokens)) return@runCatching null
            val arr = root.optJSONArray("translations") ?: return@runCatching null
            if (arr.length() != paragraphs.size) return@runCatching null
            List(arr.length()) { i ->
                val value = arr.opt(i)
                if (value == null || value == JSONObject.NULL) "" else value.toString()
            }
        }.getOrNull()
    }

    fun save(
        novelId: Long,
        targetLang: String,
        tokens: List<ContentToken>,
        translations: List<String>,
    ) {
        val paragraphCount = tokens.count { it is ContentToken.Paragraph }
        if (translations.size != paragraphCount) return
        val file = cacheFile(novelId, targetLang)
        file.parentFile?.mkdirs()
        val root = JSONObject().apply {
            put("version", VERSION)
            put("novelId", novelId)
            put("targetLang", targetLang)
            put("tokenCount", tokens.size)
            put("paragraphCount", paragraphCount)
            put("sourceHash", sourceHash(tokens))
            put("translations", JSONArray().apply { translations.forEach(::put) })
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    internal fun sourceHash(tokens: List<ContentToken>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (token in tokens) {
            val kind = when (token) {
                is ContentToken.Paragraph -> "p"
                is ContentToken.BlankLine -> "b"
                is ContentToken.PageBreak -> "n"
                is ContentToken.Chapter -> "c"
                is ContentToken.PixivImage -> "pi"
                is ContentToken.UploadedImage -> "ui"
                is ContentToken.Jump -> "j"
            }
            digest.update(kind.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(token.sourceStart.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(token.sourceEnd.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            when (token) {
                is ContentToken.Paragraph -> digest.update(token.text.toByteArray(Charsets.UTF_8))
                is ContentToken.Chapter -> digest.update(token.title.toByteArray(Charsets.UTF_8))
                else -> Unit
            }
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(novelId: Long, targetLang: String): File {
        val safeLang = targetLang.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(
            File(Shaft.getContext().filesDir, "novel_translate_v1"),
            "${novelId}_$safeLang.json",
        )
    }
}
