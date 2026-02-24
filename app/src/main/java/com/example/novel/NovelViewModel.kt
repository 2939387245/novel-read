package com.example.novel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novel.data.HomeTab
import com.example.novel.data.LocalStore
import com.example.novel.data.NovelBook
import com.example.novel.data.NovelRepository
import com.example.novel.data.ReaderStyle
import com.example.novel.data.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NovelUiState(
    val selectedTab: HomeTab = HomeTab.SEARCH,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<NovelBook> = emptyList(),
    val bookshelf: List<NovelBook> = emptyList(),
    val isLoadingContent: Boolean = false,
    val content: String = "",
    val contentParagraphs: List<String> = emptyList(),
    val chapterTitles: List<Pair<String, Int>> = emptyList(),
    val currentChapterIndex: Int = 0,
    val importUrl: String = "",
    val isImporting: Boolean = false,
    val message: String? = null,
    val progress: Map<String, ReadingProgress> = emptyMap(),
    val error: String? = null
)

class NovelViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val repo = NovelRepository()

    private val _uiState = MutableStateFlow(NovelUiState())
    val uiState: StateFlow<NovelUiState> = _uiState.asStateFlow()

    private val _readerStyle = MutableStateFlow(ReaderStyle())
    val readerStyle: StateFlow<ReaderStyle> = _readerStyle.asStateFlow()

    private val _selectedBook = MutableStateFlow<NovelBook?>(null)
    val selectedBook: StateFlow<NovelBook?> = _selectedBook.asStateFlow()

    init {
        viewModelScope.launch {
            store.bookshelfFlow.collect { books ->
                _uiState.update { it.copy(bookshelf = books) }
            }
        }
        viewModelScope.launch {
            store.styleFlow.collect { style ->
                _readerStyle.value = style
            }
        }
        viewModelScope.launch {
            store.progressFlow.collect { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
        }
    }

    fun setTab(tab: HomeTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun updateQuery(text: String) {
        _uiState.update { it.copy(searchQuery = text) }
    }

    fun search() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            val results = repo.search(query)
            _uiState.update { it.copy(isSearching = false, searchResults = results) }
        }
    }

    fun updateImportUrl(url: String) {
        _uiState.update { it.copy(importUrl = url) }
    }

    fun importFromUrl() {
        val url = _uiState.value.importUrl.trim()
        if (url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _uiState.update { it.copy(message = "请输入完整网址（http/https）") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, message = null, error = null) }
            val imported = repo.importBookFromUrl(url)
            if (imported != null) {
                val books = (listOf(imported) + _uiState.value.bookshelf).distinctBy { it.id }
                store.saveBookshelf(books)
                _uiState.update { it.copy(isImporting = false, message = "导入成功，已加入书架") }
            } else {
                _uiState.update { it.copy(isImporting = false, message = "导入失败，请换一个目录页或正文页链接") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun addToBookshelf(book: NovelBook) {
        viewModelScope.launch {
            val imported = book.textUrl?.let { repo.importBookFromUrl(it) } ?: book
            val finalBook = imported ?: book
            val current = _uiState.value.bookshelf
            if (current.any { it.id == finalBook.id }) return@launch
            store.saveBookshelf((listOf(finalBook) + current).distinctBy { it.id })
            _uiState.update { it.copy(message = "已加入书架") }
        }
    }

    fun removeFromBookshelf(book: NovelBook) {
        val current = _uiState.value.bookshelf
        viewModelScope.launch {
            store.saveBookshelf(current.filterNot { it.id == book.id })
        }
    }

    fun openBook(book: NovelBook) {
        _selectedBook.value = book
        val saved = _uiState.value.progress[book.id] ?: ReadingProgress()
        loadChapter(book, saved.chapterIndex, initialParagraph = saved.paragraphIndex)
    }

    fun openChapter(index: Int) {
        val book = _selectedBook.value ?: return
        loadChapter(book, index, initialParagraph = 0)
    }

    fun nextChapter() {
        val book = _selectedBook.value ?: return
        if (book.chapters.isEmpty()) return
        val next = (_uiState.value.currentChapterIndex + 1).coerceAtMost(book.chapters.lastIndex)
        loadChapter(book, next, initialParagraph = 0)
    }

    fun prevChapter() {
        val book = _selectedBook.value ?: return
        if (book.chapters.isEmpty()) return
        val prev = (_uiState.value.currentChapterIndex - 1).coerceAtLeast(0)
        loadChapter(book, prev, initialParagraph = 0)
    }

    fun prevChapterToEnd() {
        val book = _selectedBook.value ?: return
        if (book.chapters.isEmpty()) return
        val prev = (_uiState.value.currentChapterIndex - 1).coerceAtLeast(0)
        loadChapter(book, prev, moveToChapterEnd = true)
    }

    private fun loadChapter(
        book: NovelBook,
        chapterIndex: Int,
        initialParagraph: Int? = null,
        moveToChapterEnd: Boolean = false
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingContent = true,
                    content = "",
                    contentParagraphs = emptyList(),
                    chapterTitles = emptyList(),
                    currentChapterIndex = chapterIndex,
                    error = null
                )
            }
            val content = repo.loadBookContent(book, chapterIndex)
            val paragraphs = splitParagraphs(content)
            val lastIndex = (paragraphs.size - 1).coerceAtLeast(0)
            val paragraphForProgress = when {
                moveToChapterEnd -> lastIndex
                initialParagraph != null -> initialParagraph.coerceIn(0, lastIndex)
                else -> 0
            }

            val chapterTitles = if (book.chapters.isNotEmpty()) {
                book.chapters.mapIndexed { idx, ch -> ch.title to idx }
            } else {
                repo.buildChapters(content, paragraphs).map { it.title to it.paragraphIndex }
            }

            val updatedProgress = _uiState.value.progress.toMutableMap().apply {
                this[book.id] = ReadingProgress(chapterIndex = chapterIndex, paragraphIndex = paragraphForProgress)
            }

            _uiState.update {
                it.copy(
                    isLoadingContent = false,
                    content = content,
                    contentParagraphs = paragraphs,
                    chapterTitles = chapterTitles,
                    currentChapterIndex = chapterIndex,
                    progress = updatedProgress
                )
            }
            store.saveProgress(updatedProgress)
        }
    }

    fun closeReader() {
        _selectedBook.value = null
    }

    fun updateReaderStyle(style: ReaderStyle) {
        _readerStyle.value = style
        viewModelScope.launch {
            store.saveReaderStyle(style)
        }
    }

    fun toggleDarkMode(enabled: Boolean) {
        updateReaderStyle(_readerStyle.value.copy(darkMode = enabled))
    }

    fun updateBackgroundMode(mode: String) {
        updateReaderStyle(_readerStyle.value.copy(backgroundMode = mode))
    }

    fun updatePageMode(mode: String) {
        updateReaderStyle(_readerStyle.value.copy(pageMode = mode))
    }

    fun saveProgress(bookId: String, chapterIndex: Int, paragraphIndex: Int) {
        val current = _uiState.value.progress[bookId]
        if (current?.chapterIndex == chapterIndex && current.paragraphIndex == paragraphIndex) return
        val updated = _uiState.value.progress.toMutableMap().apply {
            this[bookId] = ReadingProgress(chapterIndex = chapterIndex, paragraphIndex = paragraphIndex)
        }
        _uiState.update { it.copy(progress = updated) }
        viewModelScope.launch {
            store.saveProgress(updated)
        }
    }

    private fun splitParagraphs(content: String): List<String> {
        val lines = content
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.size > 1) return lines
        val single = lines.firstOrNull().orEmpty()
        if (single.length <= 120) return lines
        return single
            .split(Regex("(?<=[。！？!?；;])"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .chunked(2)
            .map { it.joinToString("") }
    }
}
