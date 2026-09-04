package ceui.pixiv.ui.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ceui.lisa.R
import ceui.pixiv.ui.translate.ComicTextDetector
import ceui.pixiv.ui.translate.DetectionBox
import ceui.pixiv.ui.translate.MangaTranslateModels
import ceui.pixiv.ui.translate.TextMask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * OCR 文本框,**坐标系契约:统一在"原图"分辨率下**。
 * MangaOcr/CTD 内部为防 OOM 会先 sample 降采样,但 [MangaOcr.recognize] 返回前
 * 会把 region 坐标乘回 sample 倍,所以下游(渲染/回填)直接按原图分辨率处理就行。
 */
data class OcrTextRegion(
    val text: String,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
    val orientation: Int, // 0=horizontal, 1=vertical
    val prob: Float, // detection 置信(框是否真是文本框)
    val corners: List<Pair<Float, Float>>,
    val recogConfidence: Float = 1f, // manga-ocr 重识别置信(模型自己对识别多确定)
)

/** 把 region 所有空间字段等比缩放,用于 sample 升/降到目标坐标系。 */
fun OcrTextRegion.scaledBy(factor: Float): OcrTextRegion {
    if (factor == 1f) return this
    return copy(
        cx = cx * factor,
        cy = cy * factor,
        width = width * factor,
        height = height * factor,
        corners = corners.map { (x, y) -> (x * factor) to (y * factor) },
    )
}

/**
 * [MangaOcr.recognize] 的返回值。
 *
 * @property regions 文本 region 列表,坐标系是「原图」(已乘 sample 还原)
 * @property textMask 像素级文本 mask,坐标系是「OCR-sampled bitmap」(即 原图 / [ocrSample])。
 *  调用方按相同 sample 解码自己的 bitmap → mask 自然对齐;dim 不匹配时调用方应该回退到无 mask 路径。
 *  null = 模型未提供 mask 输出。
 * @property ocrSample OCR 时用的 inSampleSize,调用方用相同值解 bitmap 才能跟 mask 对齐
 */
data class MangaOcrResult(
    val regions: List<OcrTextRegion>,
    val textMask: TextMask?,
    val ocrSample: Int,
)

object MangaOcr {

    /**
     * CTD 检测置信下限。CTD 自身已在 [ComicTextDetector] 做了 0.4 阈值 + NMS,
     * 这里再放一道 0.3 当兜底,正常情况下不会再过滤掉东西。
     */
    private const val MIN_DETECTION_PROB = 0.3f

    /**
     * OCR 图片短边目标上限(严格约束:`finalShort <= MAX`)。
     * 2400 兼顾内存(<= ~25MB ARGB_8888)和精度(CTD 内部 letterbox 到 1024)。
     */
    private const val MAX_INPUT_SHORT_SIDE = 2400

    /**
     * region 短边动态门槛:按输入图短边的 0.8% 估算"合理小字",下限 8 px。
     * 1920 → 15.4px, 960 → 7.7px(clamp 8), 4000 → 32px。
     */
    private fun minRegionShortSide(imageShortEdge: Int): Float =
        maxOf(imageShortEdge * 0.008f, 8f)

    /** 省略号 + 句末符号 + 括号引号,不计入"实际字符"。括号常被 manga-ocr 在噪声 crop 上幻读。 */
    private val PUNCTUATION_TO_IGNORE = setOf(
        '.', '…', '。', ',', '、', ' ', '\n', '\t',
        '!', '！', '?', '？', '~', '〜', '・',
        '〈', '〉', '《', '》', '「', '」', '『', '』', '【', '】',
        '(', ')', '（', '）', '[', ']', '［', '］',
        '"', '\'', '‘', '’', '“', '”',
    )

    /**
     * region 级 manga-ocr 置信度门槛。
     * 真文字典型 0.6-0.95;模型在装饰/反光/小图标上 hallucinate 通常 < 0.3。
     */
    private const val MIN_RECOG_CONFIDENCE = 0.3f

    /**
     * manga-ocr 在真气泡识别完后,常在末尾多吐一个括号字 hallucinate。
     * 这些字符不会作为合法日文句末出现,recognize 完直接 trim。
     */
    private val TRAILING_NOISE_CHARS = setOf(
        '〈', '〉', '《', '》', '「', '」', '『', '』', '【', '】',
        '(', ')', '（', '）', '[', ']', '［', '］', '〔', '〕',
        '"', '\'', '‘', '’', '“', '”',
    )

    /**
     * Recognize text in a manga page.
     * Pipeline: comic-text-detector (气泡级 AABB) → manga-ocr (ViT+GPT2) 重识别。
     */
    suspend fun recognize(
        context: Context,
        models: MangaTranslateModels,
        inputFile: File,
        onProgress: ((stage: String, fraction: Float) -> Unit)? = null
    ): MangaOcrResult? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (!models.ocr.isLoaded) {
            Timber.e("MangaOcr: manga-ocr model not loaded")
            return@withContext null
        }
        if (!models.detector.isLoaded) {
            Timber.e("MangaOcr: comic-text-detector model not loaded")
            return@withContext null
        }
        var bitmap: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(inputFile.absolutePath, bounds)
            val origShort = minOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (origShort / sample > MAX_INPUT_SHORT_SIDE) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, opts)
            if (bitmap == null) {
                Timber.e("MangaOcr: failed to decode input")
                return@withContext null
            }
            coroutineContext.ensureActive()
            Timber.d(
                "MangaOcr: orig ${bounds.outWidth}x${bounds.outHeight} sample=$sample → ${bitmap!!.width}x${bitmap!!.height}"
            )

            // 检测阶段没有可靠百分比 → 用 NaN 通知 caller 切 indeterminate ring
            onProgress?.invoke(context.getString(R.string.string_ai_ocr_detecting), Float.NaN)

            coroutineContext.ensureActive()
            val detResult = models.detector.detect(bitmap!!)
            coroutineContext.ensureActive()
            val rawRegions = detResult.boxes.map { it.toOcrTextRegion() }
            Timber.d(
                "MangaOcr: CTD returned ${rawRegions.size} regions, mask=${detResult.textMask?.let { "${it.width}x${it.height}" } ?: "null"}"
            )

            // 不把 OCR/CTD 的中间调试图写入 MediaStore 或任何相册目录。
            // 检测框只作为内存中的 region 数据继续传给 OCR 和翻译回填。
            val minShort = minRegionShortSide(minOf(bitmap!!.width, bitmap!!.height))
            val viableRegions = rawRegions.filter { r ->
                val short = minOf(r.width, r.height)
                r.prob >= MIN_DETECTION_PROB && short >= minShort
            }
            Timber.d(
                "MangaOcr: detection ${rawRegions.size} → viable ${viableRegions.size} " +
                    "(prob>=$MIN_DETECTION_PROB, short>=$minShort)"
            )

            val total = viableRegions.size
            val enhanced = viableRegions.mapIndexedNotNull { idx, region ->
                coroutineContext.ensureActive()
                onProgress?.invoke(
                    context.getString(R.string.string_ai_ocr_recognizing, idx + 1, total),
                    idx.toFloat() / total.coerceAtLeast(1)
                )
                var cropped: Bitmap? = null
                try {
                    cropped = aabbCrop(bitmap!!, region)
                    val result = models.ocr.recognize(cropped)
                    val trimmed = result.text.trimEnd { it in TRAILING_NOISE_CHARS }
                    Timber.d(
                        "MangaOcr: → [${result.text}]" +
                            (if (trimmed != result.text) " trimmed→[$trimmed]" else "") +
                            " conf=%.2f".format(result.confidence)
                    )
                    if (trimmed.isBlank()) null
                    else region.copy(text = trimmed, recogConfidence = result.confidence)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "MangaOcr: manga-ocr failed for region, dropping")
                    null
                } finally {
                    cropped?.recycle()
                }
            }
            coroutineContext.ensureActive()
            onProgress?.invoke(context.getString(R.string.string_ai_ocr_done), 1f)

            val confident = enhanced.filter { it.recogConfidence >= MIN_RECOG_CONFIDENCE }
            Timber.d(
                "MangaOcr: ${enhanced.size} → ${confident.size} after recog-confidence filter (>=$MIN_RECOG_CONFIDENCE)"
            )

            val finalRegions = confident
                .filter { it.isMeaningfulJapanese() }
                .let { mangaReadingOrder(it) }
                .map { it.scaledBy(sample.toFloat()) }
            if (sample > 1 && finalRegions.isNotEmpty()) {
                val sample0 = finalRegions[0]
                Timber.d(
                    "MangaOcr: scaled %d regions by x%d → original coords; sample[0] cx=%.0f cy=%.0f w=%.0f h=%.0f",
                    finalRegions.size, sample, sample0.cx, sample0.cy, sample0.width, sample0.height
                )
            }
            MangaOcrResult(
                regions = finalRegions,
                textMask = detResult.textMask,
                ocrSample = sample,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "MangaOcr error")
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /** 把 CTD 输出转成 OcrTextRegion。 */
    private fun DetectionBox.toOcrTextRegion(): OcrTextRegion {
        val left = cx - width / 2f
        val top = cy - height / 2f
        val right = cx + width / 2f
        val bottom = cy + height / 2f
        val orient = if (height > width * 1.2f) 1 else 0
        return OcrTextRegion(
            text = "",
            cx = cx,
            cy = cy,
            width = width,
            height = height,
            angle = 0f,
            orientation = orient,
            prob = confidence,
            corners = listOf(
                left to top, right to top,
                right to bottom, left to bottom,
            ),
        )
    }

    /** AABB crop,带 4% padding(以短边为基准),给 manga-ocr 留点气泡背景。 */
    private fun aabbCrop(bitmap: Bitmap, region: OcrTextRegion): Bitmap {
        val pad = (minOf(region.width, region.height) * 0.04f).toInt().coerceAtLeast(2)
        val left = (region.cx - region.width / 2f - pad).toInt().coerceIn(0, bitmap.width - 1)
        val top = (region.cy - region.height / 2f - pad).toInt().coerceIn(0, bitmap.height - 1)
        val right = (region.cx + region.width / 2f + pad).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (region.cy + region.height / 2f + pad).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /**
     * 合法日文 region 判据:
     * - 含至少一个日文/汉字字符
     * - 去掉省略号/括号/标点后,实际字符数 >= 2
     * - 或 == 1 且 detection 和 recognition 置信都 >= 0.85
     */
    private fun OcrTextRegion.isMeaningfulJapanese(): Boolean {
        val coreChars = text.count { it !in PUNCTUATION_TO_IGNORE }
        if (coreChars == 0) return false
        val hasJa = text.any { ch ->
            val block = Character.UnicodeBlock.of(ch) ?: return@any false
            block === Character.UnicodeBlock.HIRAGANA ||
                block === Character.UnicodeBlock.KATAKANA ||
                block === Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block === Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block === Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        }
        if (!hasJa) return false
        return coreChars >= 2 || (prob >= 0.85f && recogConfidence >= 0.85f)
    }

    /** 阅读顺序:先按行从上到下,每行内从右到左。 */
    private fun mangaReadingOrder(regions: List<OcrTextRegion>): List<OcrTextRegion> {
        if (regions.size <= 1) return regions
        val byCy = regions.sortedBy { it.cy }
        val rows = mutableListOf<MutableList<OcrTextRegion>>()
        for (r in byCy) {
            val cur = rows.lastOrNull()
            if (cur == null) {
                rows += mutableListOf(r)
                continue
            }
            val rowBottom = cur.maxOf { it.cy + it.height / 2f }
            if (r.cy - r.height / 2f < rowBottom) cur += r
            else rows += mutableListOf(r)
        }
        return rows.flatMap { row -> row.sortedByDescending { it.cx } }
    }
}
