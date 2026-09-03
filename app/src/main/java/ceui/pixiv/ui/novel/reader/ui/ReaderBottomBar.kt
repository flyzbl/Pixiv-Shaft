package ceui.pixiv.ui.novel.reader.ui

import android.view.View
import android.widget.SeekBar
import ceui.lisa.R
import ceui.lisa.databinding.LayoutReaderBottomBarBinding
import ceui.pixiv.ui.novel.reader.NovelReaderV3ViewModel

class ReaderBottomBar(private val binding: LayoutReaderBottomBarBinding) {

    enum class Mode { Paged, VerticalScroll }

    val view: View get() = binding.root

    var onPrevChapter: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null
    var onChaptersClick: (() -> Unit)? = null
    var onSeriesClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onThemeToggleClick: (() -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null
    var onTranslationOriginalClick: (() -> Unit)? = null
    var onTranslationTranslatedClick: (() -> Unit)? = null
    var onTranslationBilingualClick: (() -> Unit)? = null
    var onSeekStart: (() -> Unit)? = null
    var onSeekChanged: ((pageIndex: Int) -> Unit)? = null
    var onSeekCommit: ((pageIndex: Int) -> Unit)? = null
    var onScrollSeekCommit: ((fraction: Float) -> Unit)? = null

    private var suppressSeekListener = false
    private var mode: Mode = Mode.Paged

    init {
        binding.btnPrevChapter.setOnClickListener { onPrevChapter?.invoke() }
        binding.btnNextChapter.setOnClickListener { onNextChapter?.invoke() }
        binding.btnChapters.setOnClickListener { onChaptersClick?.invoke() }
        binding.btnSeries.setOnClickListener { onSeriesClick?.invoke() }
        binding.btnSettings.setOnClickListener { onSettingsClick?.invoke() }
        binding.btnThemeToggle.setOnClickListener { onThemeToggleClick?.invoke() }
        binding.btnSearch.setOnClickListener { onSearchClick?.invoke() }
        binding.btnTranslateOriginal.setOnClickListener { onTranslationOriginalClick?.invoke() }
        binding.btnTranslateTranslated.setOnClickListener { onTranslationTranslatedClick?.invoke() }
        binding.btnTranslateBilingual.setOnClickListener { onTranslationBilingualClick?.invoke() }

        binding.skProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressSeekListener) return
                if (mode == Mode.Paged) onSeekChanged?.invoke(progress)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                onSeekStart?.invoke()
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                if (mode == Mode.Paged) {
                    onSeekCommit?.invoke(s.progress)
                } else {
                    val max = s.max.coerceAtLeast(1)
                    onScrollSeekCommit?.invoke(s.progress.toFloat() / max)
                }
            }
        })
    }

    fun setProgress(currentPage: Int, totalPages: Int) {
        mode = Mode.Paged
        val totalForBar = (totalPages - 1).coerceAtLeast(0)
        suppressSeekListener = true
        try {
            binding.skProgress.max = totalForBar
            binding.skProgress.progress = currentPage.coerceIn(0, totalForBar)
        } finally {
            suppressSeekListener = false
        }
        binding.txtProgress.text = if (totalPages == 0) {
            binding.root.context.getString(R.string.reader_progress_empty)
        } else {
            binding.root.context.getString(R.string.reader_progress_format, currentPage + 1, totalPages)
        }
    }

    fun setScrollProgress(fraction: Float) {
        mode = Mode.VerticalScroll
        val clamped = fraction.coerceIn(0f, 1f)
        suppressSeekListener = true
        try {
            binding.skProgress.max = SCROLL_MAX
            binding.skProgress.progress = (clamped * SCROLL_MAX).toInt()
        } finally {
            suppressSeekListener = false
        }
        val pct = (clamped * 100).toInt().coerceIn(0, 100)
        binding.txtProgress.text = "$pct%"
    }

    fun setTranslationMode(mode: NovelReaderV3ViewModel.TranslationViewMode) {
        binding.btnTranslateOriginal.alpha = if (mode == NovelReaderV3ViewModel.TranslationViewMode.Original) 1f else 0.55f
        binding.btnTranslateTranslated.alpha = if (mode == NovelReaderV3ViewModel.TranslationViewMode.Translated) 1f else 0.55f
        binding.btnTranslateBilingual.alpha = if (mode == NovelReaderV3ViewModel.TranslationViewMode.Bilingual) 1f else 0.55f
    }

    fun setTranslationProgress(done: Int, total: Int, running: Boolean) {
        if (total <= 0) {
            binding.txtTranslateProgress.visibility = View.GONE
            return
        }
        binding.txtTranslateProgress.visibility = View.VISIBLE
        binding.txtTranslateProgress.text = binding.root.context.getString(
            R.string.novel_translate_progress,
            done.coerceIn(0, total),
            total,
        )
        binding.txtTranslateProgress.alpha = if (running) 1f else 0.72f
    }

    fun setSeriesVisible(visible: Boolean) {
        binding.btnSeries.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setDarkMode(dark: Boolean) {
        binding.txtThemeToggle.text = binding.root.context.getString(
            if (dark) R.string.reader_btn_theme_day else R.string.reader_btn_theme_night,
        )
    }

    private companion object {
        const val SCROLL_MAX = 1000
    }
}
