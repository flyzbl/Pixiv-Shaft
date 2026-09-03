package ceui.pixiv.ui.translate

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import ceui.lisa.R
import ceui.lisa.utils.Common
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

        val pieces = ArrayList<Piece>()
        val pieceResults = Array(total) { mutableListOf<String?>() }
        for ((paragraphIndex, paragraph) in paragraphs.withIndex()) {
            if (outputs[paragraphIndex].isNotBlank()) continue
            val split = splitParagraph(paragraph.text)
            pieceResults[paragraphIndex].addAll(List(split.size) { null })
            split.forEachIndexed { pieceIndex, text ->
                pieces += Piece(paragraphIndex, pieceIndex, text)
            }
        }

        // A single IO writer serializes cache updates. The conflated channel keeps only the
        // newest pending snapshot when translation is faster than disk, so long novels persist
        // incremental progress without an unbounded write queue or stale-write races.
        val persistChannel = Channel<List<String>>(Channel.CONFLATED)
        val persistJob = scope.launch(Dispatchers.IO) {
            for (pending in persistChannel) {
                NovelTranslateCache.save(novelId, targetLang, tokens, pending)
            }
        }

        var requestFailure: Exception? = null
        try {
            currentTranslator().translateBatch(
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
