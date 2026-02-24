package com.example.novel.data

import kotlinx.serialization.Serializable

@Serializable
data class BookChapter(
    val title: String,
    val url: String
)

@Serializable
data class NovelBook(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val textUrl: String? = null,
    val catalogUrl: String? = null,
    val chapters: List<BookChapter> = emptyList(),
    val source: String
)

@Serializable
data class ChapterItem(
    val title: String,
    val paragraphIndex: Int
)

@Serializable
data class ReadingProgress(
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0
)

@Serializable
data class ReaderStyle(
    val fontSizeSp: Float = 20f,
    val lineHeightEm: Float = 1.6f,
    val paragraphSpacingDp: Float = 14f,
    val horizontalPaddingDp: Float = 20f,
    val verticalPaddingDp: Float = 24f,
    val fontFamily: String = "serif",
    val darkMode: Boolean = false,
    val backgroundMode: String = "paper",
    val pageMode: String = "scroll"
)

enum class HomeTab(val title: String) {
    SEARCH("导入"),
    BOOKSHELF("书架"),
    SETTINGS("样式")
}
