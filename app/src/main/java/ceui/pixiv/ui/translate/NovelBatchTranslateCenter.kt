package ceui.pixiv.ui.translate

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.ui.novel.reader.ReaderProgressStore
import ceui.pixiv.ui.novel.reader.model.ContentToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Process-level whole-novel translation center.
 *
 * Translation survives ordinary configuration while the reader view exists, but when the
 * reader view lifecycle is destroyed (the user leaves that novel), the matching batch is
 * cancelled silently. Completed paragraphs are persisted and can be resumed next time.
 *
 * Scheduling is reader-first rather than book-first: when a batch starts, the persisted reading
 * char index is used as an anchor. Untranslated paragraphs at/after that position are translated
 * before earlier paragraphs. A small priority batch around the anchor is completed first so the
 * text the user is looking at can appear quickly; only then does the normal high-throughput batch
 * process the rest of the novel.
 */
object NovelBatchTranslateCenter {

    data class Snapshot(
        val novelId: Long,
        val targetLang: String,
        val translations: List<String>,
        val total: Int,
    ) {
        val done: Int get() = translations.count { it.isNotBlank() }
    }

    data class BatchStatus(
        val novelId: Long,
        val title: String,
        val targetLang: String,
        val done: Int,
        val total: Int,
        val running: Boolean,
        val failed: Int = 0,
    )

    private data class Piece(val paragraphIndex: Int, val pieceIndex: Int, val text: String)

    private class TranslationLiveData(
        private val translationKey: String,
    ) : MutableLiveData<Snapshot?>(null) {
        override fun observe(owner: LifecycleOwner, observer: Observer<in Snapshot?>) {
            owner.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        NovelBatchTranslateCenter.cancelKey(translationKey, showToast = false)
                    }
                },
            )
            super.observe(owner, observer)
        }
    }

    private const val MAX_PIECE_CHARS = 2800

    /**
     * First request budget around the current reading position. This is deliberately well below
     * AiTranslator's normal 3000-char batch size: the goal here is time-to-first-visible-
     * translation, not aggregate throughput. Whole paragraphs are kept intact; an unusually long
     * paragraph may therefore exceed this budget rather than being displayed partially.
     */
    private const val PRIORITY_BATCH_CHARS = 1200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val buckets = LinkedHashMap<String, TranslationLiveData>()
    private val preparing = HashSet<String>()

    private val _status = MutableLiveData<BatchStatus?>(null)
    val status: LiveData<BatchStatus?> get() = _status

    private var job: Job? = null
    private var currentKey: String? = null
    private var jobGeneration: Long = 0L

    fun translationsOf(novelId: Long, targetLang: String): LiveData<Snapshot?> =
        bucket(key(novelId, targetLang))

    fun prepare(novelId: Long, tokens: List<ContentToken>, targetLang: String) {
        val key = key(novelId, targetLang)
        val live = bucket(key)
        val total = tokens.count { it is ContentToken.Paragraph }
        if (live.value?.total == total || !preparing.add(key)) return
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    NovelTranslateCache.load(novelId, targetLang, tokens)
                }
                if (currentKey == key && job?.isActive == true) return@launch
                live.value = Snapshot(
                    novelId = novelId,
                    targetLang = targetLang,
                    translations = cached ?: List(total) { "" },
                    total = total,
                )
            } finally {
                preparing.remove(key)
            }
        }
    }

    fun isRunningFor(novelId: Long, targetLang: String): Boolean =
        job?.isActive == true && currentKey == key(novelId, targetLang)

    fun start(
        novelId: Long,
        title: String,
        tokens: List<ContentToken>,
    ): Boolean {
        if (job?.isActive == true) return false
        val targetLang = appTranslateTargetLang()
        val key = key(novelId, targetLang)
        val generation = ++jobGeneration
        currentKey = key
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runBatch(novelId, title, targetLang, tokens, key, generation)
            } catch (_: CancellationException) {
                if (jobGeneration == generation) {
                    _status.value = _status.value?.copy(running = false)
                }
            } catch (e: Exception) {
                Timber.e(e, "NovelBatchTranslateCenter: batch failed")
                if (jobGeneration == generation) {
                    _status.value = _status.value?.copy(running = false)
                    Common.showToast(R.string.novel_translate_failed)
                }
            } finally {
                // A cancelled previous reader can finish its NonCancellable cache flush after a
                // new reader has already started. Only the generation that still owns the slot may
                // clear the shared job/currentKey fields.
                if (jobGeneration == generation) {
                    currentKey = null
                    job = null
                }
            }
        }
        job = newJob
        newJob.start()
        return true
    }

    fun cancel(novelId: Long, targetLang: String) {
        cancelKey(key(novelId, targetLang), showToast = true)
    }

    private fun cancelKey(translationKey: String, showToast: Boolean) {
        val oldJob = job
        if (oldJob?.isActive != true || currentKey != translationKey) return

        // Release the single active translation slot before cancelling the old coroutine.
        // The old batch may spend noticeable time in NonCancellable while flushing completed
        // paragraphs to disk; that cleanup must not block the next novel from starting.
        ++jobGeneration
        currentKey = null
        job = null
        _status.value?.let { status ->
            if (key(status.novelId, status.targetLang) == translationKey) {
                _status.value = status.copy(running = false)
            }
        }

        oldJob.cancel()
        if (showToast) Common.showToast(R.string.novel_translate_cancelled)
    }

    private suspend fun runBatch(
        novelId: Long,
        title: String,
        targetLang: String,
        tokens: List<ContentToken>,
        key: String,
        generation: Long,
    ) {
        val paragraphs = tokens.filterIsInstance<ContentToken.Paragraph>()
        val total = paragraphs.size
        val cached = withContext(Dispatchers.IO) {
            NovelTranslateCache.load(novelId, targetLang, tokens)
        }
        val outputs = (cached ?: List(total) { "" }).toMutableList()
        val live = bucket(key)
        live.value = Snapshot(novelId, targetLang, outputs.toList(), total)
        if (jobGeneration == generation) {
            _status.value = BatchStatus(
                novelId, title, targetLang, outputs.count { it.isNotBlank() }, total, running = true,
            )
        }

        if (total == 0 || outputs.all { it.isNotBlank() }) {
            if (jobGeneration == generation) {
                _status.value = _status.value?.copy(running = false, failed = 0)
            }
            return
        }

        // ReaderProgressStore is already updated by both paged and vertical reader paths. Use the
        // same source-of-truth here instead of maintaining a second current-page state just for
        // translation. In gaps between tokens we bias forward, matching normal reading direction.
        val readingChar = ReaderProgressStore.loadCharIndex(novelId)
        val paragraphOrder = priorityParagraphOrder(paragraphs, outputs, readingChar)

        val piecesByParagraph = arrayOfNulls<List<Piece>>(total)
        val pieceResults = Array(total) { mutableListOf<String?>() }
        for (paragraphIndex in paragraphOrder) {
            val paragraph = paragraphs[paragraphIndex]
            val split = splitParagraph(paragraph.text)
            pieceResults[paragraphIndex].addAll(List(split.size) { null })
            piecesByParagraph[paragraphIndex] = split.mapIndexed { pieceIndex, text ->
                Piece(paragraphIndex, pieceIndex, text)
            }
        }

        val priorityParagraphCount = priorityParagraphCount(
            paragraphOrder = paragraphOrder,
            paragraphs = paragraphs,
            charBudget = PRIORITY_BATCH_CHARS,
        )
        val priorityPieces = paragraphOrder.take(priorityParagraphCount)
            .flatMap { piecesByParagraph[it].orEmpty() }
        val remainingPieces = paragraphOrder.drop(priorityParagraphCount)
            .flatMap { piecesByParagraph[it].orEmpty() }

        Timber.d(
            "NovelBatchTranslateCenter: priority anchor=%d paragraph=%s firstBatchParagraphs=%d firstBatchChars=%d remainingPieces=%d",
            readingChar,
            paragraphOrder.firstOrNull()?.toString() ?: "none",
            priorityParagraphCount,
            priorityPieces.sumOf { it.text.length },
            remainingPieces.size,
        )

        // A single IO writer serializes cache updates. The conflated channel keeps only the
        // newest pending snapshot when translation is faster than disk, so long novels persist
        // incremental progress without an unbounded write queue or stale-write races.
        val persistChannel = Channel<List<String>>(Channel.CONFLATED)
        val persistJob = scope.launch(Dispatchers.IO) {
            for (pending in persistChannel) {
                NovelTranslateCache.save(novelId, targetLang, tokens, pending)
            }
        }

        val translator = currentTranslator()
        var requestFailure: Exception? = null

        suspend fun translatePieces(pieces: List<Piece>) {
            if (pieces.isEmpty()) return
            translator.translateBatch(
                inputs = pieces.map { it.text },
                outputLang = targetLang,
                onItem = { flatIndex, translated ->
                    val piece = pieces.getOrNull(flatIndex) ?: return@translateBatch
                    val parts = pieceResults[piece.paragraphIndex]
                    if (piece.pieceIndex !in parts.indices) return@translateBatch
                    parts[piece.pieceIndex] = translated
                    if (parts.isNotEmpty() && parts.all { !it.isNullOrBlank() }) {
                        outputs[piece.paragraphIndex] = parts.joinToString("") { it.orEmpty() }
                        val snapshot = Snapshot(novelId, targetLang, outputs.toList(), total)
                        live.postValue(snapshot)
                        if (jobGeneration == generation) {
                            _status.postValue(
                                BatchStatus(
                                    novelId = novelId,
                                    title = title,
                                    targetLang = targetLang,
                                    done = snapshot.done,
                                    total = total,
                                    running = true,
                                )
                            )
                        }
                        persistChannel.trySend(snapshot.translations)
                    }
                },
            )
        }

        try {
            // Stage 1: finish the paragraph(s) around the current reading position first. Because
            // this call contains only ~1200 chars in normal prose, AiTranslator creates one small
            // request instead of immediately filling all four normal batch lanes.
            translatePieces(priorityPieces)

            // Stage 2: once the visible neighborhood has been published, restore normal batching
            // and concurrency for the rest of the book. Order remains forward-from-current first,
            // then wraps around to fill untranslated paragraphs before the reading position.
            translatePieces(remainingPieces)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            requestFailure = e
            Timber.e(e, "NovelBatchTranslateCenter: translateBatch failed")
        } finally {
            // The batch Job may already be cancelled here. Persist through a sibling process
            // scope and wait non-cancellably so leaving the reader or explicit stop still keeps
            // every completed paragraph. The active slot is released before cancellation, so
            // this disk cleanup never delays translation startup for the next novel.
            withContext(NonCancellable) {
                persistChannel.trySend(outputs.toList())
                persistChannel.close()
                persistJob.join()
            }
            live.postValue(Snapshot(novelId, targetLang, outputs.toList(), total))
        }

        val failed = outputs.count { it.isBlank() }
        if (jobGeneration == generation) {
            _status.value = BatchStatus(
                novelId = novelId,
                title = title,
                targetLang = targetLang,
                done = outputs.count { it.isNotBlank() },
                total = total,
                running = false,
                failed = failed,
            )
            if (requestFailure != null || failed > 0) {
                Common.showToast(R.string.novel_translate_failed)
            }
        }
    }

    /**
     * Returns untranslated paragraph indices in reading-priority order:
     * the paragraph at/after [readingChar], then everything after it, then earlier paragraphs.
     * Earlier content is filled from nearest to farthest so a small backwards jump is more likely
     * to hit cache than the very beginning of the book.
     */
    internal fun priorityParagraphOrder(
        paragraphs: List<ContentToken.Paragraph>,
        outputs: List<String>,
        readingChar: Int,
    ): List<Int> {
        if (paragraphs.isEmpty()) return emptyList()
        val anchorIndex = when {
            readingChar <= paragraphs.first().sourceStart -> 0
            else -> paragraphs.indexOfFirst { readingChar <= it.sourceEnd }
                .takeIf { it >= 0 }
                ?: paragraphs.lastIndex
        }
        return buildList {
            for (i in anchorIndex..paragraphs.lastIndex) {
                if (outputs.getOrNull(i).isNullOrBlank()) add(i)
            }
            for (i in anchorIndex - 1 downTo 0) {
                if (outputs.getOrNull(i).isNullOrBlank()) add(i)
            }
        }
    }

    /** Keep whole paragraphs together in the latency-sensitive first request. */
    internal fun priorityParagraphCount(
        paragraphOrder: List<Int>,
        paragraphs: List<ContentToken.Paragraph>,
        charBudget: Int = PRIORITY_BATCH_CHARS,
    ): Int {
        if (paragraphOrder.isEmpty()) return 0
        var chars = 0
        var count = 0
        for (paragraphIndex in paragraphOrder) {
            val length = paragraphs.getOrNull(paragraphIndex)?.text?.length ?: continue
            if (count > 0 && chars + length > charBudget) break
            chars += length
            count++
            if (chars >= charBudget) break
        }
        return count.coerceAtLeast(1)
    }

    internal fun splitParagraph(text: String, limit: Int = MAX_PIECE_CHARS): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = ArrayList<String>((text.length / limit) + 1)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val floor = start + (limit * 2 / 3)
                var candidate = -1
                var i = end - 1
                while (i >= floor) {
                    if (text[i] == '\n' || text[i] in "。！？!?") {
                        candidate = i + 1
                        break
                    }
                    i--
                }
                if (candidate > start) end = candidate
                if (end < text.length && end > start && Character.isHighSurrogate(text[end - 1])) {
                    end--
                }
            }
            if (end <= start) end = minOf(start + limit, text.length)
            out += text.substring(start, end)
            start = end
        }
        return out
    }

    private fun key(novelId: Long, targetLang: String): String = "$novelId|$targetLang"

    private fun bucket(key: String): TranslationLiveData =
        buckets.getOrPut(key) { TranslationLiveData(key) }
}
