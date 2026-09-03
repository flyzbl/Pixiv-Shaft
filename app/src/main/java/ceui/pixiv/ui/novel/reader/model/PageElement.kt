package ceui.pixiv.ui.novel.reader.model

import android.text.Layout

sealed class PageElement {
    abstract val top: Float
    abstract val bottom: Float
    abstract val absoluteCharStart: Int
    abstract val absoluteCharEnd: Int

    data class Text(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
        val text: CharSequence,
        val paragraphIndex: Int,
        val isFirstLineOfParagraph: Boolean,
        val isLastLineOfParagraph: Boolean,
        val lineCount: Int,
        val isSecondary: Boolean = false,
    ) : PageElement()

    data class Chapter(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
        val title: String,
        val layout: Layout,
    ) : PageElement()

    data class Image(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
        val imageType: ImageType,
        val resourceId: Long,
        val pageIndexInIllust: Int,
        val imageUrl: String?,
        val isMix: Boolean = false,
    ) : PageElement() {
        enum class ImageType { PixivImage, UploadedImage }
    }

    data class Space(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
    ) : PageElement()

    data class Jump(
        override val top: Float,
        override val bottom: Float,
        override val absoluteCharStart: Int,
        override val absoluteCharEnd: Int,
        val target: Int,
    ) : PageElement()
}
