package com.example.novel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novel.data.HomeTab
import com.example.novel.data.ChapterCache
import com.example.novel.data.LocalStore
import com.example.novel.data.NovelBook
import com.example.novel.data.NovelRepository
import com.example.novel.data.ReaderStyle
import com.example.novel.data.ReadingProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val isRefreshingBookshelf: Boolean = false,
    val isSyncingChapters: Boolean = false,
    val initialCatalogSyncDone: Boolean = true,
    val syncScannedPages: Int = 0,
    val syncMaxPages: Int = 0,
    val syncFoundChapters: Int = 0,
    val cacheStats: List<CacheItemUi> = emptyList(),
    val message: String? = null,
    val progress: Map<String, ReadingProgress> = emptyMap(),
    val error: String? = null
)

data class CacheItemUi(
    val bookId: String,
    val title: String,
    val chapterCount: Int,
    val totalBytes: Long,
    val inBookshelf: Boolean
)

class NovelViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val repo = NovelRepository()
    private val chapterCache = ChapterCache(application)
    private val catalogSyncJobs = mutableMapOf<String, Job>()
    private val completedCatalogSyncBookIds = mutableSetOf<String>()

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
                refreshCacheStats()
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
        viewModelScope.launch {
            store.catalogSyncedFlow.collect { synced ->
                completedCatalogSyncBookIds.clear()
                completedCatalogSyncBookIds.addAll(synced)
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
                completedCatalogSyncBookIds.remove(imported.id)
                store.saveCatalogSyncedBooks(completedCatalogSyncBookIds)
                _uiState.update { it.copy(isImporting = false, message = "导入成功，已加入书架（后台继续补全目录）") }
                launchBackgroundCatalogFill(imported.id, reportProgress = false, markInitialDone = true)
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
            chapterCache.clearBook(book.id)
            catalogSyncJobs.remove(book.id)?.cancel()
            completedCatalogSyncBookIds.remove(book.id)
            store.saveCatalogSyncedBooks(completedCatalogSyncBookIds)
        }
    }

    fun clearAllCachedChapters() {
        chapterCache.clearAll()
        refreshCacheStats()
        _uiState.update { it.copy(message = "章节缓存已清除") }
    }

    fun refreshCacheStats() {
        val bookshelf = _uiState.value.bookshelf
        val titleById = bookshelf.associate { it.id to it.title }
        val stats = chapterCache.listCacheStats().map { c ->
            CacheItemUi(
                bookId = c.bookId,
                title = titleById[c.bookId] ?: c.title ?: "未知书籍（已不在书架）",
                chapterCount = c.chapterCount,
                totalBytes = c.totalBytes,
                inBookshelf = titleById.containsKey(c.bookId)
            )
        }
        _uiState.update { it.copy(cacheStats = stats) }
    }

    fun clearCacheForBook(bookId: String) {
        chapterCache.clearBook(bookId)
        refreshCacheStats()
        _uiState.update { it.copy(message = "已清除该书缓存") }
    }

    fun syncCurrentBookChapters() {
        val currentBook = _selectedBook.value ?: return
        if (!_uiState.value.initialCatalogSyncDone || _uiState.value.isSyncingChapters) {
            _uiState.update { it.copy(message = "目录后台补全中，请稍后再同步") }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSyncingChapters = true,
                    syncScannedPages = 0,
                    syncMaxPages = 120,
                    syncFoundChapters = currentBook.chapters.size
                )
            }
            val synced = repo.syncBookIncremental(
                currentBook,
                maxCatalogPages = 120
            ) { scanned, total, found ->
                _uiState.update {
                    it.copy(
                        isSyncingChapters = true,
                        syncScannedPages = scanned,
                        syncMaxPages = total,
                        syncFoundChapters = found
                    )
                }
            } ?: run {
                _uiState.update { it.copy(isSyncingChapters = false, message = "同步失败，请稍后重试") }
                return@launch
            }
            _uiState.update { it.copy(isSyncingChapters = false, syncScannedPages = 0, syncMaxPages = 0) }
            if (synced.chapters.size > currentBook.chapters.size) {
                applyBookUpdate(synced)
                _uiState.update { it.copy(message = "同步完成，新增 ${synced.chapters.size - currentBook.chapters.size} 章") }
            } else {
                _uiState.update { it.copy(message = "已是最新章节") }
            }
            completedCatalogSyncBookIds += currentBook.id
            store.saveCatalogSyncedBooks(completedCatalogSyncBookIds)
            _uiState.update { it.copy(initialCatalogSyncDone = true) }
        }
    }

    fun refreshCurrentChapter() {
        val book = _selectedBook.value ?: return
        val progress = _uiState.value.progress[book.id]
        val initialParagraph = if (progress?.chapterIndex == _uiState.value.currentChapterIndex) {
            progress.paragraphIndex
        } else {
            0
        }
        loadChapter(
            book = book,
            chapterIndex = _uiState.value.currentChapterIndex,
            initialParagraph = initialParagraph,
            forceReload = true
        )
    }

    fun refreshBookshelf() {
        val current = _uiState.value.bookshelf
        if (current.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingBookshelf = true, message = null) }
            val refreshed = coroutineScope {
                val semaphore = Semaphore(3)
                current.map { old ->
                    async {
                        semaphore.withPermit {
                            val entryUrl = old.catalogUrl ?: old.textUrl ?: return@withPermit old
                            val latest = repo.importBookFromUrl(entryUrl) ?: return@withPermit old
                            latest.copy(
                                id = old.id,
                                source = old.source.ifBlank { latest.source }
                            )
                        }
                    }
                }.awaitAll()
            }
            val updatedCount = refreshed.mapIndexed { index, fresh ->
                fresh.chapters.size > current[index].chapters.size
            }.count { it }
            store.saveBookshelf(refreshed)
            _uiState.update {
                it.copy(
                    isRefreshingBookshelf = false,
                    message = if (updatedCount > 0) "同步完成：$updatedCount 本有更新" else "同步完成：暂无更新"
                )
            }
        }
    }

    private fun launchBackgroundCatalogFill(
        bookId: String,
        reportProgress: Boolean,
        markInitialDone: Boolean
    ) {
        if (catalogSyncJobs[bookId]?.isActive == true) return
        val job = viewModelScope.launch {
            val book = _uiState.value.bookshelf.firstOrNull { it.id == bookId } ?: return@launch
            val shouldReportAtStart = reportProgress || _selectedBook.value?.id == bookId
            if (shouldReportAtStart) {
                _uiState.update {
                    it.copy(
                        isSyncingChapters = true,
                        initialCatalogSyncDone = false,
                        syncScannedPages = 0,
                        syncMaxPages = 120,
                        syncFoundChapters = book.chapters.size
                    )
                }
            }
            var synced: NovelBook? = null
            var attempt = 0
            while (attempt < 20 && synced == null) {
                synced = repo.syncBookIncremental(
                    book,
                    maxCatalogPages = 120
                ) { scanned, total, found ->
                    if (_uiState.value.bookshelf.none { it.id == bookId }) {
                        catalogSyncJobs[bookId]?.cancel()
                    } else if (_selectedBook.value?.id == bookId) {
                        _uiState.update {
                            it.copy(
                                isSyncingChapters = true,
                                syncScannedPages = scanned,
                                syncMaxPages = total,
                                syncFoundChapters = found
                            )
                        }
                    }
                }
                if (synced == null) {
                    attempt++
                    if (attempt < 20) delay(1800)
                }
            }
            if (_uiState.value.bookshelf.none { it.id == bookId }) {
                if (_selectedBook.value?.id == bookId) {
                    _uiState.update {
                        it.copy(
                            isSyncingChapters = false,
                            syncScannedPages = 0,
                            syncMaxPages = 0
                        )
                    }
                }
                return@launch
            }
            val finalSynced = synced ?: run {
                if ((_selectedBook.value?.id == bookId) || reportProgress || markInitialDone) {
                    _uiState.update {
                        it.copy(
                            isSyncingChapters = false,
                            initialCatalogSyncDone = false,
                            syncScannedPages = 0,
                            syncMaxPages = 0
                        )
                    }
                }
                return@launch
            }
            if (finalSynced.chapters.size > book.chapters.size) {
                applyBookUpdate(finalSynced)
            }
            completedCatalogSyncBookIds += bookId
            store.saveCatalogSyncedBooks(completedCatalogSyncBookIds)
            if ((_selectedBook.value?.id == bookId) || reportProgress || markInitialDone) {
                _uiState.update {
                    it.copy(
                        isSyncingChapters = false,
                        initialCatalogSyncDone = true,
                        syncScannedPages = 0,
                        syncMaxPages = 0,
                        syncFoundChapters = finalSynced.chapters.size
                    )
                }
            }
        }
        catalogSyncJobs[bookId] = job
        job.invokeOnCompletion { catalogSyncJobs.remove(bookId) }
    }

    private suspend fun applyBookUpdate(updatedBook: NovelBook) {
        val oldBook = _uiState.value.bookshelf.firstOrNull { it.id == updatedBook.id }
        val mergedBook = if (oldBook == null) updatedBook else {
            updatedBook.copy(
                id = oldBook.id,
                source = oldBook.source.ifBlank { updatedBook.source }
            )
        }
        val replaced = _uiState.value.bookshelf.map { if (it.id == mergedBook.id) mergedBook else it }
        store.saveBookshelf(replaced)
        if (_selectedBook.value?.id == mergedBook.id) {
            _selectedBook.value = mergedBook
            if (mergedBook.chapters.isNotEmpty()) {
                _uiState.update {
                    val safeChapterIndex = it.currentChapterIndex.coerceIn(0, mergedBook.chapters.lastIndex)
                    it.copy(
                        chapterTitles = mergedBook.chapters.mapIndexed { idx, ch -> ch.title to idx },
                        currentChapterIndex = safeChapterIndex
                    )
                }
            }
        }
    }

    fun openBook(book: NovelBook) {
        _selectedBook.value = book
        val running = catalogSyncJobs[book.id]?.isActive == true
        val done = completedCatalogSyncBookIds.contains(book.id)
        if (done) {
            _uiState.update {
                it.copy(
                    initialCatalogSyncDone = true,
                    isSyncingChapters = false,
                    syncScannedPages = 0,
                    syncMaxPages = 0,
                    syncFoundChapters = book.chapters.size
                )
            }
        } else if (!running) {
            _uiState.update {
                it.copy(
                    initialCatalogSyncDone = false,
                    isSyncingChapters = true,
                    syncScannedPages = 0,
                    syncMaxPages = 120,
                    syncFoundChapters = book.chapters.size
                )
            }
        } else if (running) {
            _uiState.update {
                it.copy(
                    initialCatalogSyncDone = false,
                    isSyncingChapters = true,
                    syncScannedPages = 0,
                    syncMaxPages = 120,
                    syncFoundChapters = book.chapters.size
                )
            }
        }
        val saved = _uiState.value.progress[book.id] ?: ReadingProgress()
        loadChapter(book, saved.chapterIndex, initialParagraph = saved.paragraphIndex)
        if (!done) {
            launchBackgroundCatalogFill(book.id, reportProgress = true, markInitialDone = true)
        }
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
        moveToChapterEnd: Boolean = false,
        forceReload: Boolean = false
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
            val cachedContent = if (forceReload) null else chapterCache.read(book.id, chapterIndex)
            val content = cachedContent ?: repo.loadBookContent(book, chapterIndex).also {
                val isFallback = it.contains("当前页面未提取到正文") && it.contains("优先使用小说目录页链接导入")
                if (it.isNotBlank() && !isFallback) chapterCache.write(book.id, chapterIndex, it, title = book.title)
            }
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
