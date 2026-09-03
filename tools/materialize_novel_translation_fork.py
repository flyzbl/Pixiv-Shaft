#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected 1 match, got {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


def replace_between(path, start, end, replacement):
    text = read(path)
    a = text.find(start)
    if a < 0:
        raise RuntimeError(f"{path}: start marker not found: {start!r}")
    b = text.find(end, a)
    if b < 0:
        raise RuntimeError(f"{path}: end marker not found: {end!r}")
    write(path, text[:a] + replacement + text[b:])


# ---- Build identity ---------------------------------------------------------
replace_once("app/build.gradle", 'applicationId "ceui.pixiv"', 'applicationId "ceui.pixiv.pshaft.novel"')
replace_once("app/build.gradle", 'versionName "4.9.2"', 'versionName "4.9.2-novel.1"')
replace_once(
    "app/build.gradle",
    '            applicationIdSuffix ".cshaft"\n            resValue "string", "app_name", "Shaft(Debug)"',
    '            resValue "string", "app_name", "Shaft 小说"',
)
replace_once(
    "app/build.gradle",
    '            applicationIdSuffix ".pshaft"\n            resValue "string", "app_name", "Shaft"',
    '            resValue "string", "app_name", "Shaft 小说"',
)

# ---- Paginator: caption paint + translated source-coordinate mapping --------
paginator = "app/src/main/java/ceui/pixiv/ui/novel/reader/paginate/Paginator.kt"
new_emit = '''    private fun emitParagraph(token: ContentToken.Paragraph) {
        val width = geometry.contentWidth.toInt()
        if (width <= 0) return
        val paragraphPaint = if (token.isSecondary) style.captionPaint else style.textPaint
        val indent = style.firstLineIndentPx.toInt()
        val source = if (indent > 0) TextMeasurer.withFirstLineIndent(token.text, indent) else token.text
        val layout = measurer.measure(
            text = source,
            paint = paragraphPaint,
            width = width,
            lineSpacingMultiplier = style.lineSpacingMultiplier,
            lineSpacingExtra = style.lineSpacingExtra,
        )
        val total = layout.lineCount
        var cursor = 0
        while (cursor < total) {
            val remainingHeight = (geometry.height - geometry.paddingBottom) - currentY
            if (remainingHeight <= 0f) {
                finishPage()
                continue
            }
            val startTop = layout.getLineTop(cursor)
            val firstLineBottom = layout.getLineBottom(cursor).toFloat()
            val firstLineHeight = firstLineBottom - startTop
            if (firstLineHeight > remainingHeight && currentElements.isNotEmpty()) {
                finishPage()
                continue
            }
            var linesFit = 0
            var pxUsed = 0f
            for (i in cursor until total) {
                val pxIfIncluded = (layout.getLineBottom(i) - startTop).toFloat()
                if (pxIfIncluded > remainingHeight && linesFit > 0) break
                linesFit = i - cursor + 1
                pxUsed = pxIfIncluded
                if (pxIfIncluded > remainingHeight) break
            }
            if (linesFit == 0) {
                finishPage()
                continue
            }

            val startCharInLayout = layout.getLineStart(cursor).coerceIn(0, token.text.length)
            val endCharInLayout = layout.getLineEnd(cursor + linesFit - 1).coerceIn(0, token.text.length)
            val absoluteStart = sourceOffsetForDisplay(token, startCharInLayout)
            val absoluteEnd = sourceOffsetForDisplay(token, endCharInLayout)
            val sliceText = token.text.substring(startCharInLayout, endCharInLayout)

            val element = PageElement.Text(
                top = currentY,
                bottom = currentY + pxUsed,
                absoluteCharStart = absoluteStart,
                absoluteCharEnd = absoluteEnd,
                text = sliceText,
                paragraphIndex = token.sourceStart,
                isFirstLineOfParagraph = cursor == 0,
                isLastLineOfParagraph = (cursor + linesFit) == total,
                lineCount = linesFit,
                isSecondary = token.isSecondary,
            )
            currentElements += element
            ensureStartTracked(absoluteStart)
            currentY += pxUsed
            currentCharEnd = maxOf(currentCharEnd, absoluteEnd)
            cursor += linesFit

            if (cursor < total) {
                finishPage()
            } else {
                currentY += style.paragraphSpacingPx
            }
        }
    }

    private fun sourceOffsetForDisplay(token: ContentToken.Paragraph, displayOffset: Int): Int {
        if (!token.isTranslated) {
            return (token.textSourceStart + displayOffset).coerceIn(token.sourceStart, token.sourceEnd)
        }
        if (token.text.isEmpty()) return token.sourceStart
        val span = (token.sourceEnd - token.sourceStart).coerceAtLeast(0)
        val mapped = (span.toLong() * displayOffset.coerceIn(0, token.text.length)) / token.text.length
        return (token.sourceStart + mapped.toInt()).coerceIn(token.sourceStart, token.sourceEnd)
    }

'''
replace_between(paginator, "    private fun emitParagraph(token: ContentToken.Paragraph) {", "    private fun ensureStartTracked(start: Int) {", new_emit)

# ---- Paged renderer: smaller gray bilingual original + proportional mapping -
block = "app/src/main/java/ceui/pixiv/ui/novel/reader/render/ReaderTextBlockView.kt"
replace_once(block, "import android.text.style.BackgroundColorSpan\n", "import android.text.style.BackgroundColorSpan\nimport android.text.style.ForegroundColorSpan\n")
replace_once(
    block,
    "    private data class Segment(val localStart: Int, val localEnd: Int, val absoluteStart: Int)\n",
    "    private data class Segment(\n        val localStart: Int,\n        val localEnd: Int,\n        val absoluteStart: Int,\n        val absoluteEnd: Int,\n    )\n",
)
new_bind = '''    fun bindTextGroup(elements: List<PageElement.Text>, style: TypeStyle) {
        segments.clear()
        val sb = SpannableStringBuilder()
        elements.forEachIndexed { idx, element ->
            val rawSlice = element.text.toString().trimEnd('\\n')
            val segLocalStart = sb.length
            sb.append(rawSlice)
            val segLocalEnd = sb.length
            segments += Segment(
                localStart = segLocalStart,
                localEnd = segLocalEnd,
                absoluteStart = element.absoluteCharStart,
                absoluteEnd = element.absoluteCharEnd,
            )

            if (element.isFirstLineOfParagraph && style.firstLineIndentPx > 0f) {
                sb.setSpan(
                    LeadingMarginSpan.Standard(style.firstLineIndentPx.toInt(), 0),
                    segLocalStart, segLocalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            val elementPaint = if (element.isSecondary) style.captionPaint else style.textPaint
            val fm = elementPaint.fontMetrics
            val naturalLineHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
            val textLineHeight = (
                naturalLineHeight * style.lineSpacingMultiplier.coerceAtLeast(0.8f) + style.lineSpacingExtra
            ).roundToInt().coerceAtLeast(1)
            if (segLocalEnd > segLocalStart) {
                sb.setSpan(
                    TextMeasurer.FixedLineHeightSpan(textLineHeight),
                    segLocalStart, segLocalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                if (element.isSecondary) {
                    sb.setSpan(
                        AbsoluteSizeSpan(style.captionPaint.textSize.roundToInt()),
                        segLocalStart, segLocalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        ForegroundColorSpan(style.secondaryTextColor),
                        segLocalStart, segLocalEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }

            if (idx < elements.size - 1) {
                val pixelGap = (elements[idx + 1].top - element.bottom).coerceAtLeast(0f).roundToInt()
                sb.append('\\n')
                val gapLineStart = sb.length
                sb.append('\\u200B')
                val gapLineEnd = sb.length
                sb.append('\\n')
                if (pixelGap > 0) {
                    sb.setSpan(AbsoluteSizeSpan(1), gapLineStart, gapLineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(GapLineHeightSpan(pixelGap), gapLineStart, gapLineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textPaint.textSize)
        typeface = style.textPaint.typeface
        setTextColor(style.textPaint.color)
        letterSpacing = style.textPaint.letterSpacing
        setLineSpacing(0f, 1f)
        text = sb
        highlightColor = style.selectionColor
    }

'''
replace_between(block, "    fun bindTextGroup(elements: List<PageElement.Text>, style: TypeStyle) {", "    /**\n     * Apply a list of absolute-coord highlight ranges", new_bind)
new_highlights = '''    fun applyOverlayHighlights(hits: List<HighlightRange>) {
        val t = text as? Spannable ?: return
        t.getSpans(0, t.length, SearchHighlightSpan::class.java).forEach { t.removeSpan(it) }
        if (segments.isEmpty()) return
        for (hit in hits) {
            for (seg in segments) {
                val s = maxOf(hit.absoluteStart, seg.absoluteStart)
                val e = minOf(hit.absoluteEnd, seg.absoluteEnd)
                if (e <= s) continue
                val localStart = absoluteToLocal(seg, s)
                val localEnd = absoluteToLocal(seg, e)
                if (localEnd <= localStart) continue
                t.setSpan(SearchHighlightSpan(hit.color), localStart, localEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        invalidate()
    }

    private fun absoluteToLocal(seg: Segment, absoluteOffset: Int): Int {
        val localLength = seg.localEnd - seg.localStart
        val absoluteLength = seg.absoluteEnd - seg.absoluteStart
        if (localLength <= 0 || absoluteLength <= 0) return seg.localStart
        val rel = (absoluteOffset - seg.absoluteStart).coerceIn(0, absoluteLength)
        return seg.localStart + ((localLength.toLong() * rel) / absoluteLength).toInt()
    }

'''
replace_between(block, "    fun applyOverlayHighlights(hits: List<HighlightRange>) {", "    /** Marker subclass", new_highlights)
new_local = '''    fun localToAbsolute(localOffset: Int): Int {
        if (segments.isEmpty()) return 0
        for (seg in segments) {
            if (localOffset <= seg.localEnd) {
                val localLength = seg.localEnd - seg.localStart
                val absoluteLength = seg.absoluteEnd - seg.absoluteStart
                if (localLength <= 0 || absoluteLength <= 0) return seg.absoluteStart
                val rel = localOffset.coerceIn(seg.localStart, seg.localEnd) - seg.localStart
                return seg.absoluteStart + ((absoluteLength.toLong() * rel) / localLength).toInt()
            }
        }
        return segments.last().absoluteEnd
    }

'''
replace_between(block, "    fun localToAbsolute(localOffset: Int): Int {", "    private fun buildActionModeCallback(): ActionMode.Callback {", new_local)

# ---- Vertical renderer ------------------------------------------------------
scroll = "app/src/main/java/ceui/pixiv/ui/novel/reader/render/NovelScrollReaderView.kt"
replace_once(scroll, "        private var boundSourceStart: Int = 0\n", "        private var boundSourceStart: Int = 0\n        private var boundSourceEnd: Int = 0\n        private var boundTranslated: Boolean = false\n")
replace_once(scroll, "notifyTvSelection(tv, boundSourceStart, onSelectionStarted)", "notifyTvSelection(tv, boundSourceStart, boundSourceEnd, boundTranslated, onSelectionStarted)")
# onSelectionChanged appears twice.
text = read(scroll)
old_call = "notifyTvSelection(tv, boundSourceStart, onSelectionChanged)"
if text.count(old_call) != 2:
    raise RuntimeError(f"{scroll}: expected 2 changed-selection calls, got {text.count(old_call)}")
write(scroll, text.replace(old_call, "notifyTvSelection(tv, boundSourceStart, boundSourceEnd, boundTranslated, onSelectionChanged)"))
new_scroll_bind = '''        fun bind(token: ContentToken.Paragraph, style: TypeStyle, hits: List<HighlightRange>) {
            boundSourceStart = token.sourceStart
            boundSourceEnd = token.sourceEnd
            boundTranslated = token.isTranslated
            val paragraphPaint = if (token.isSecondary) style.captionPaint else style.textPaint
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, paragraphPaint.textSize)
            tv.typeface = paragraphPaint.typeface
            tv.setTextColor(paragraphPaint.color)
            tv.letterSpacing = paragraphPaint.letterSpacing
            tv.setLineSpacing(style.lineSpacingExtra, style.lineSpacingMultiplier)

            val spannable = SpannableString(token.text)
            val indent = style.firstLineIndentPx.toInt()
            if (indent > 0 && token.text.isNotEmpty()) {
                spannable.setSpan(
                    LeadingMarginSpan.Standard(indent, 0),
                    0, token.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            applyInlineSpans(spannable, token.inlineSpans, style)
            tv.movementMethod = if (token.inlineSpans.any { it.tag is InlineTag.Link }) {
                android.text.method.LinkMovementMethod.getInstance()
            } else {
                null
            }
            tv.setTextIsSelectable(true)
            tv.text = spannable
            applyHighlights(hits)
        }

'''
replace_between(scroll, "        fun bind(token: ContentToken.Paragraph, style: TypeStyle, hits: List<HighlightRange>) {", "        fun applyHighlights(hits: List<HighlightRange>) {", new_scroll_bind)
replace_once(scroll, "            if (hits.isEmpty()) return\n            val anchorStart = boundSourceStart\n", "            if (hits.isEmpty() || boundTranslated) return\n            val anchorStart = boundSourceStart\n")
new_notify = '''    private fun notifyTvSelection(
        tv: AppCompatTextView,
        sourceStart: Int,
        sourceEnd: Int,
        translated: Boolean,
        cb: ((Int, Int, String) -> Unit)?,
    ) {
        if (cb == null) return
        val s = tv.selectionStart.coerceAtLeast(0)
        val e = tv.selectionEnd.coerceAtLeast(s)
        if (e <= s || e > tv.text.length) return
        val sliced = tv.text.subSequence(s, e).toString()
        if (!translated || tv.text.isEmpty()) {
            cb(sourceStart + s, sourceStart + e, sliced)
            return
        }
        val sourceLength = (sourceEnd - sourceStart).coerceAtLeast(0)
        val displayLength = tv.text.length.coerceAtLeast(1)
        val absStart = sourceStart + ((sourceLength.toLong() * s) / displayLength).toInt()
        val absEnd = sourceStart + ((sourceLength.toLong() * e) / displayLength).toInt()
        cb(absStart, absEnd, sliced)
    }

'''
replace_between(scroll, "    private fun notifyTvSelection(\n", "    // ---- Inline markup spans", new_notify)

# ---- ViewModel display stream ------------------------------------------------
vm = "app/src/main/java/ceui/pixiv/ui/novel/reader/NovelReaderV3ViewModel.kt"
replace_once(vm, "import kotlinx.coroutines.Job\n", "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay\n")
replace_once(vm, "class NovelReaderV3ViewModel(\n", "class NovelReaderV3ViewModel(\n")
replace_once(
    vm,
    "    /** 本地 txt 源：非空时 [load] 走离线分支，不碰网络 / ObjectPool。 */\n",
    "    enum class TranslationViewMode { Original, Translated, Bilingual }\n\n    /** 本地 txt 源：非空时 [load] 走离线分支，不碰网络 / ObjectPool。 */\n",
)
replace_once(
    vm,
    "    private val _illustMixVersion = MutableLiveData(0)\n    val illustMixVersion: LiveData<Int> = _illustMixVersion\n\n",
    "    private val _illustMixVersion = MutableLiveData(0)\n    val illustMixVersion: LiveData<Int> = _illustMixVersion\n\n    private var translationViewMode = TranslationViewMode.Original\n    private var translatedParagraphs: List<String> = emptyList()\n    private var translationRefreshJob: Job? = null\n    private val _displayVersion = MutableLiveData(0)\n    val displayVersion: LiveData<Int> = _displayVersion\n\n",
)
new_display = '''    fun displayTokens(): List<ContentToken> {
        val source = ReaderSettings.illustMixSource
        val base = if (source == NovelIllustSource.None || mixIllustsSource != source || mixIllusts.isEmpty()) {
            tokens
        } else {
            IllustMixInserter.insert(tokens, mixIllusts.map { it.id })
        }
        if (translationViewMode == TranslationViewMode.Original || translatedParagraphs.isEmpty()) return base

        val result = ArrayList<ContentToken>(if (translationViewMode == TranslationViewMode.Bilingual) base.size * 2 else base.size)
        var paragraphIndex = 0
        for (token in base) {
            if (token !is ContentToken.Paragraph) {
                result += token
                continue
            }
            val translated = translatedParagraphs.getOrNull(paragraphIndex).orEmpty()
            paragraphIndex++
            if (translated.isBlank()) {
                result += token
                continue
            }
            val translatedToken = token.copy(
                text = translated,
                textSourceStart = token.sourceStart,
                inlineSpans = emptyList(),
                isTranslated = true,
                isSecondary = false,
            )
            when (translationViewMode) {
                TranslationViewMode.Original -> result += token
                TranslationViewMode.Translated -> result += translatedToken
                TranslationViewMode.Bilingual -> {
                    result += translatedToken
                    result += token.copy(isSecondary = true)
                }
            }
        }
        return result
    }

    fun currentTranslationViewMode(): TranslationViewMode = translationViewMode

    fun setTranslationViewMode(mode: TranslationViewMode) {
        if (translationViewMode == mode) return
        translationViewMode = mode
        refreshTranslatedDisplay(immediate = true)
    }

    fun updateNovelTranslations(translations: List<String>) {
        if (translatedParagraphs == translations) return
        translatedParagraphs = translations.toList()
        if (translationViewMode != TranslationViewMode.Original) refreshTranslatedDisplay(immediate = false)
    }

    private fun refreshTranslatedDisplay(immediate: Boolean) {
        translationRefreshJob?.cancel()
        if (immediate) {
            repaginateIfReady()
            _displayVersion.value = (_displayVersion.value ?: 0) + 1
            return
        }
        translationRefreshJob = viewModelScope.launch {
            delay(250)
            repaginateIfReady()
            _displayVersion.value = (_displayVersion.value ?: 0) + 1
        }
    }

'''
replace_between(vm, "    fun displayTokens(): List<ContentToken> {", "    /** 内嵌插图照旧走 webNovel 的对象表", new_display)
replace_once(vm, "        paginationJob?.cancel()\n        paginationThread.quitSafely()\n", "        paginationJob?.cancel()\n        translationRefreshJob?.cancel()\n        paginationThread.quitSafely()\n")

# ---- Fragment: UI wiring + menu + in-app selection translation --------------
frag = "app/src/main/java/ceui/pixiv/ui/novel/reader/NovelReaderV3Fragment.kt"
replace_once(
    frag,
    "import ceui.pixiv.ui.translate.appTranslateTargetLang\n",
    "import ceui.pixiv.ui.translate.NovelBatchTranslateCenter\nimport ceui.pixiv.ui.translate.appTranslateTargetLang\nimport ceui.pixiv.ui.translate.currentTranslator\nimport ceui.pixiv.ui.translate.showTranslatedDialog\n",
)
replace_once(
    frag,
    '    private var progressOverlayText: String = ""\n\n',
    '    private var progressOverlayText: String = ""\n    private var translationBound = false\n    private var latestTranslationSnapshot: NovelBatchTranslateCenter.Snapshot? = null\n\n',
)
replace_once(
    frag,
    "        bb.onScrollSeekCommit = { fraction ->\n            scrollReaderView?.takeIf { it.visibility == View.VISIBLE }?.scrollToFraction(fraction)\n        }\n",
    "        bb.onScrollSeekCommit = { fraction ->\n            scrollReaderView?.takeIf { it.visibility == View.VISIBLE }?.scrollToFraction(fraction)\n        }\n        bb.onTranslationOriginalClick = { setTranslationViewMode(NovelReaderV3ViewModel.TranslationViewMode.Original) }\n        bb.onTranslationTranslatedClick = { setTranslationViewMode(NovelReaderV3ViewModel.TranslationViewMode.Translated) }\n        bb.onTranslationBilingualClick = { setTranslationViewMode(NovelReaderV3ViewModel.TranslationViewMode.Bilingual) }\n",
)
replace_once(
    frag,
    "                loadWatchlistStateForSeries(seriesId)\n                pushStyleAndGeometryIfReady()\n",
    "                loadWatchlistStateForSeries(seriesId)\n                bindNovelTranslation(state, bb)\n                pushStyleAndGeometryIfReady()\n",
)
replace_once(
    frag,
    "        viewModel.illustMixVersion.observe(viewLifecycleOwner) { version ->\n            if ((version ?: 0) > 0) rebindScrollViewIfActive()\n        }\n\n",
    "        viewModel.illustMixVersion.observe(viewLifecycleOwner) { version ->\n            if ((version ?: 0) > 0) rebindScrollViewIfActive()\n        }\n\n        viewModel.displayVersion.observe(viewLifecycleOwner) { version ->\n            if ((version ?: 0) > 0) rebindScrollViewIfActive()\n        }\n\n        NovelBatchTranslateCenter.status.observe(viewLifecycleOwner) { refreshTranslationProgress(bb) }\n\n",
)
replace_once(frag, "        bb.setDarkMode(currentThemeIsDark())\n", "        bb.setDarkMode(currentThemeIsDark())\n        bb.setTranslationMode(viewModel.currentTranslationViewMode())\n")
helpers = '''    private fun bindNovelTranslation(loaded: NovelReaderV3ViewModel.LoadState.Loaded, bb: ReaderBottomBar) {
        if (translationBound) return
        translationBound = true
        val targetLang = appTranslateTargetLang()
        NovelBatchTranslateCenter.translationsOf(viewModel.novelId, targetLang).observe(viewLifecycleOwner) { snapshot ->
            latestTranslationSnapshot = snapshot
            if (snapshot != null) viewModel.updateNovelTranslations(snapshot.translations)
            refreshTranslationProgress(bb)
        }
        NovelBatchTranslateCenter.prepare(viewModel.novelId, loaded.tokens, targetLang)
    }

    private fun setTranslationViewMode(mode: NovelReaderV3ViewModel.TranslationViewMode) {
        viewModel.setTranslationViewMode(mode)
        bottomBar?.setTranslationMode(mode)
        if (mode != NovelReaderV3ViewModel.TranslationViewMode.Original) {
            val snapshot = latestTranslationSnapshot
            if (snapshot == null || snapshot.done < snapshot.total) startNovelTranslation()
        }
    }

    private fun startNovelTranslation() {
        val loaded = viewModel.loadState.value as? NovelReaderV3ViewModel.LoadState.Loaded ?: run {
            Toaster.showShort(R.string.msg_novel_not_ready)
            return
        }
        val targetLang = appTranslateTargetLang()
        if (NovelBatchTranslateCenter.isRunningFor(viewModel.novelId, targetLang)) return
        val title = loaded.novel?.title ?: loaded.webNovel.title.orEmpty()
        if (!NovelBatchTranslateCenter.start(viewModel.novelId, title, loaded.tokens)) {
            Toaster.showShort(R.string.novel_translate_busy)
        }
    }

    private fun refreshTranslationProgress(bb: ReaderBottomBar) {
        val targetLang = appTranslateTargetLang()
        val status = NovelBatchTranslateCenter.status.value
            ?.takeIf { it.novelId == viewModel.novelId && it.targetLang == targetLang }
        val snapshot = latestTranslationSnapshot
        bb.setTranslationProgress(
            done = status?.done ?: snapshot?.done ?: 0,
            total = status?.total ?: snapshot?.total ?: 0,
            running = status?.running == true,
        )
    }

'''
replace_once(frag, "    // ---- Actions ------------------------------------------------------------\n", helpers + "    // ---- Actions ------------------------------------------------------------\n")
replace_once(
    frag,
    "    private fun V3MenuBuilder.addReaderMenuItems() {\n        item(getString(R.string.menu_bookmarks), R.drawable.ic_baseline_bookmark_24) {\n",
    "    private fun V3MenuBuilder.addReaderMenuItems() {\n        val targetLang = appTranslateTargetLang()\n        val translating = NovelBatchTranslateCenter.isRunningFor(viewModel.novelId, targetLang)\n        item(\n            getString(if (translating) R.string.novel_translate_stop else R.string.novel_translate_full),\n            R.drawable.ic_baseline_translate_24,\n        ) {\n            if (translating) NovelBatchTranslateCenter.cancel(viewModel.novelId, targetLang)\n            else startNovelTranslation()\n        }\n        item(getString(R.string.menu_bookmarks), R.drawable.ic_baseline_bookmark_24) {\n",
)
new_translate = '''    private fun translateSelection() {
        val source = activeSelection?.text?.trim().orEmpty()
        if (source.isEmpty()) return
        val ctx = requireContext()
        Toaster.showShort(R.string.string_translating)
        viewLifecycleOwner.lifecycleScope.launch {
            val translated = try {
                currentTranslator().translate(source, appTranslateTargetLang())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "novel selection in-app translation failed; fallback to PROCESS_TEXT")
                null
            }
            if (!translated.isNullOrBlank()) {
                showTranslatedDialog(ctx, translated)
                return@launch
            }
            launchExternalTranslation(source)
        }
    }

    private fun launchExternalTranslation(text: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(Intent.createChooser(intent, getString(R.string.chooser_translate)))
        } else {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://translate.google.com/?sl=auto&tl=${appTranslateTargetLang()}&text=${Uri.encode(text)}&op=translate"
                )))
            }.onFailure { Toaster.showShort(getString(R.string.msg_no_translate_app)) }
        }
    }

'''
replace_between(frag, "    private fun translateSelection() {", "    // ---- Layout push --------------------------------------------------------\n", new_translate)

# Remove this one-shot helper and its temporary workflow from the final tree.
for rel in ("tools/materialize_novel_translation_fork.py", ".github/workflows/materialize-novel-fork.yml"):
    p = ROOT / rel
    if p.exists():
        p.unlink()

print("materialized novel translation fork source changes")
