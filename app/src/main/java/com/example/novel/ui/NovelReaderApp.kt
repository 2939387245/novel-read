package com.example.novel.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.novel.NovelUiState
import com.example.novel.NovelViewModel
import com.example.novel.data.HomeTab
import com.example.novel.data.NovelBook
import com.example.novel.data.ReaderStyle
import com.example.novel.ui.component.BookListItem
import com.example.novel.ui.theme.Dawn
import com.example.novel.ui.theme.NovelTheme
import com.example.novel.ui.theme.Warm
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun NovelReaderApp(vm: NovelViewModel = viewModel()) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val style by vm.readerStyle.collectAsStateWithLifecycle()
    val selectedBook by vm.selectedBook.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(ui.message) {
        ui.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearMessage()
        }
    }

    NovelTheme(darkTheme = style.darkMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (selectedBook != null) {
                ReaderPage(
                    uiState = ui,
                    book = selectedBook!!,
                    style = style,
                    onBack = vm::closeReader,
                    onStyleChange = vm::updateReaderStyle,
                    onBackgroundModeChange = vm::updateBackgroundMode,
                    onPageModeChange = vm::updatePageMode,
                    onSaveProgress = vm::saveProgress,
                    onOpenChapter = vm::openChapter,
                    onPrevChapter = vm::prevChapter,
                    onPrevChapterToEnd = vm::prevChapterToEnd,
                    onNextChapter = vm::nextChapter
                )
            } else {
                HomePage(
                    ui = ui,
                    onTabChanged = vm::setTab,
                    onImportUrlChanged = vm::updateImportUrl,
                    onImportFromUrl = vm::importFromUrl,
                    onOpenBook = vm::openBook,
                    onRemoveBookshelf = vm::removeFromBookshelf,
                    style = style,
                    onStyleChange = vm::updateReaderStyle,
                    onDarkModeChange = vm::toggleDarkMode,
                    onBackgroundModeChange = vm::updateBackgroundMode,
                    onPageModeChange = vm::updatePageMode
                )
            }
        }
    }
}

@Composable
private fun HomePage(
    ui: NovelUiState,
    onTabChanged: (HomeTab) -> Unit,
    onImportUrlChanged: (String) -> Unit,
    onImportFromUrl: () -> Unit,
    onOpenBook: (NovelBook) -> Unit,
    onRemoveBookshelf: (NovelBook) -> Unit,
    style: ReaderStyle,
    onStyleChange: (ReaderStyle) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onBackgroundModeChange: (String) -> Unit,
    onPageModeChange: (String) -> Unit
) {
    val gradient = Brush.verticalGradient(listOf(Dawn, MaterialTheme.colorScheme.background))
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            BottomAppBar {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = ui.selectedTab == tab,
                        onClick = { onTabChanged(tab) },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    HomeTab.SEARCH -> Icons.Default.Search
                                    HomeTab.BOOKSHELF -> Icons.Default.AutoStories
                                    HomeTab.SETTINGS -> Icons.Default.Settings
                                },
                                contentDescription = tab.title
                            )
                        },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(innerPadding)
        ) {
            when (ui.selectedTab) {
                HomeTab.SEARCH -> SearchTab(
                    ui = ui,
                    onImportUrlChanged = onImportUrlChanged,
                    onImportFromUrl = onImportFromUrl
                )

                HomeTab.BOOKSHELF -> BookshelfTab(
                    books = ui.bookshelf,
                    onOpenBook = onOpenBook,
                    onRemoveBookshelf = onRemoveBookshelf
                )

                HomeTab.SETTINGS -> SettingsTab(
                    style = style,
                    onStyleChange = onStyleChange,
                    onDarkModeChange = onDarkModeChange,
                    onBackgroundModeChange = onBackgroundModeChange,
                    onPageModeChange = onPageModeChange
                )
            }
        }
    }
}

@Composable
private fun SearchTab(
    ui: NovelUiState,
    onImportUrlChanged: (String) -> Unit,
    onImportFromUrl: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Text("网址导入", style = MaterialTheme.typography.headlineMedium)
        Text("复制小说目录链接，直接整本导入书架", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = ui.importUrl,
                onValueChange = onImportUrlChanged,
                placeholder = { Text("粘贴小说目录网址（推荐）") },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(10.dp))
            Button(onClick = onImportFromUrl, enabled = !ui.isImporting) {
                Text(if (ui.isImporting) "导入中" else "整本导入")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("提示：导入目录页成功后会自动抓章节，阅读时可用上一章/下一章。", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BookshelfTab(
    books: List<NovelBook>,
    onOpenBook: (NovelBook) -> Unit,
    onRemoveBookshelf: (NovelBook) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Text("我的书架", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))
        if (books.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("去导入页粘贴小说链接后，就可以在这里继续阅读。")
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(books, key = { it.id }) { book ->
                    BookListItem(
                        book = book,
                        onPrimaryAction = { onOpenBook(book) },
                        onSecondaryAction = { onRemoveBookshelf(book) },
                        primaryLabel = "开始阅读",
                        secondaryLabel = "移除"
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    style: ReaderStyle,
    onStyleChange: (ReaderStyle) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onBackgroundModeChange: (String) -> Unit,
    onPageModeChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Text("阅读样式", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("夜间模式")
            Switch(checked = style.darkMode, onCheckedChange = onDarkModeChange)
        }
        Spacer(modifier = Modifier.height(10.dp))
        BackgroundSelector(style.backgroundMode, onBackgroundModeChange)
        Spacer(modifier = Modifier.height(10.dp))
        PageModeSelector(style.pageMode, onPageModeChange)
        Spacer(modifier = Modifier.height(12.dp))
        StylePanel(style = style, onStyleChange = onStyleChange)
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPage(
    uiState: NovelUiState,
    book: NovelBook,
    style: ReaderStyle,
    onBack: () -> Unit,
    onStyleChange: (ReaderStyle) -> Unit,
    onBackgroundModeChange: (String) -> Unit,
    onPageModeChange: (String) -> Unit,
    onSaveProgress: (String, Int, Int) -> Unit,
    onOpenChapter: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onPrevChapterToEnd: () -> Unit,
    onNextChapter: () -> Unit
) {
    var showStyleSheet by remember { mutableStateOf(false) }
    var showChapterSheet by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val progress = uiState.progress[book.id]
    val initialParagraph = if (progress?.chapterIndex == uiState.currentChapterIndex) {
        progress.paragraphIndex
    } else {
        0
    }
    val listState = rememberLazyListState()
    val isHorizontalPaging = style.pageMode == "horizontal"
    val textMeasurer = rememberTextMeasurer()
    val hasPrevChapter = book.chapters.isNotEmpty() && uiState.currentChapterIndex > 0
    val hasNextChapter = book.chapters.isNotEmpty() && uiState.currentChapterIndex < book.chapters.lastIndex
    var chapterSwipeTriggered by remember(book.id, uiState.currentChapterIndex, isHorizontalPaging) {
        mutableStateOf(false)
    }

    val fontFamily = when (style.fontFamily) {
        "sans" -> FontFamily.SansSerif
        "mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.Serif
    }

    val (bgColor, textColor) = readerColors(style)

    LaunchedEffect(book.id, uiState.currentChapterIndex, uiState.contentParagraphs.size, isHorizontalPaging) {
        if (!isHorizontalPaging && uiState.contentParagraphs.isNotEmpty()) {
            val safeIndex = initialParagraph.coerceIn(0, (uiState.contentParagraphs.size - 1).coerceAtLeast(0))
            listState.scrollToItem(safeIndex)
        }
    }

    LaunchedEffect(book.id, uiState.currentChapterIndex, listState, uiState.contentParagraphs.size, isHorizontalPaging) {
        if (!isHorizontalPaging) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .map { it.coerceAtLeast(0) }
                .distinctUntilChanged()
                .debounce(300)
                .filter { uiState.contentParagraphs.isNotEmpty() }
                .collect { onSaveProgress(book.id, uiState.currentChapterIndex, it) }
        }
    }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(bgColor)
                .padding(horizontal = style.horizontalPaddingDp.dp, vertical = style.verticalPaddingDp.dp)
        ) {
            if (uiState.isLoadingContent) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isHorizontalPaging) {
                            if (!isHorizontalPaging) {
                                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                            }
                        }
                ) {
                    if (isHorizontalPaging) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val density = LocalDensity.current
                            val pageTextStyle = TextStyle(
                                fontFamily = fontFamily,
                                fontSize = style.fontSizeSp.sp,
                                lineHeight = (style.fontSizeSp * style.lineHeightEm).sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                                textIndent = TextIndent(firstLine = 2.em)
                            )
                            val paragraphBreakCount = when {
                                style.paragraphSpacingDp <= 8f -> 1
                                style.paragraphSpacingDp <= 18f -> 2
                                style.paragraphSpacingDp <= 28f -> 3
                                else -> 4
                            }
                            val widthPx = with(density) { maxWidth.roundToPx() }
                            val heightPx = with(density) { maxHeight.roundToPx() }
                            val pages = remember(
                                uiState.contentParagraphs,
                                widthPx,
                                heightPx,
                                pageTextStyle,
                                paragraphBreakCount,
                                textMeasurer
                            ) {
                                paginatePagesByLayout(
                                    paragraphs = uiState.contentParagraphs,
                                    textStyle = pageTextStyle,
                                    textMeasurer = textMeasurer,
                                    widthPx = widthPx,
                                    heightPx = heightPx,
                                    paragraphBreakCount = paragraphBreakCount
                                )
                            }
                            val contentInitialPage = remember(pages, initialParagraph) {
                                pages.indexOfLast { it.first <= initialParagraph }.coerceAtLeast(0)
                            }
                            val startOffset = if (hasPrevChapter) 1 else 0
                            val endOffset = if (hasNextChapter) 1 else 0
                            val pagerCount = (pages.size + startOffset + endOffset).coerceAtLeast(1)
                            val initialPage = (contentInitialPage + startOffset).coerceIn(0, pagerCount - 1)
                            val pagerState = rememberPagerState(
                                initialPage = initialPage,
                                pageCount = { pagerCount }
                            )

                            LaunchedEffect(book.id, uiState.currentChapterIndex) {
                                chapterSwipeTriggered = false
                            }

                            LaunchedEffect(book.id, uiState.currentChapterIndex, pagerState, pages) {
                                snapshotFlow { pagerState.currentPage }
                                    .distinctUntilChanged()
                                    .collect { page ->
                                        if (hasPrevChapter && page == 0) {
                                            if (!chapterSwipeTriggered) {
                                                chapterSwipeTriggered = true
                                                onPrevChapterToEnd()
                                            }
                                        } else if (hasNextChapter && page == pagerCount - 1) {
                                            if (!chapterSwipeTriggered) {
                                                chapterSwipeTriggered = true
                                                onNextChapter()
                                            }
                                        } else {
                                            val contentPage = (page - startOffset).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                                            val paragraph = pages.getOrNull(contentPage)?.first ?: 0
                                            onSaveProgress(book.id, uiState.currentChapterIndex, paragraph)
                                        }
                                    }
                            }

                            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                val isPrevSentinel = hasPrevChapter && page == 0
                                val isNextSentinel = hasNextChapter && page == pagerCount - 1
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(page, pagerCount, hasPrevChapter, hasNextChapter, chromeVisible) {
                                            detectTapGestures(
                                                onTap = { offset ->
                                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                                    when {
                                                        offset.x < w * 0.30f -> {
                                                            if (pagerState.currentPage > 0) {
                                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                                            } else if (hasPrevChapter) {
                                                                onPrevChapterToEnd()
                                                            }
                                                        }

                                                        offset.x > w * 0.70f -> {
                                                            if (pagerState.currentPage < pagerCount - 1) {
                                                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                                            } else if (hasNextChapter) {
                                                                onNextChapter()
                                                            }
                                                        }

                                                        else -> chromeVisible = !chromeVisible
                                                    }
                                                }
                                            )
                                        }
                                ) {
                                    if (isPrevSentinel || isNextSentinel) {
                                        Text(
                                            text = if (isPrevSentinel) "继续右滑进入上一章" else "继续左滑进入下一章",
                                            color = textColor.copy(alpha = 0.85f),
                                            style = pageTextStyle,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    } else {
                                        val contentPage = (page - startOffset).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                                        val text = pages.getOrElse(contentPage) { 0 to "" }.second
                                        Text(
                                            text = text,
                                            color = textColor,
                                            style = pageTextStyle,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(bottom = 20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp)) {
                            if (book.chapters.isNotEmpty() && uiState.currentChapterIndex > 0) {
                                item {
                                    TextButton(onClick = onPrevChapterToEnd) {
                                        Text("跳到上一章")
                                    }
                                }
                            }
                            itemsIndexed(uiState.contentParagraphs) { index, paragraph ->
                                Text(
                                    text = paragraph,
                                    color = textColor,
                                    style = TextStyle(
                                        fontFamily = fontFamily,
                                        fontSize = style.fontSizeSp.sp,
                                        lineHeight = (style.fontSizeSp * style.lineHeightEm).sp,
                                        textIndent = TextIndent(firstLine = 2.em)
                                    )
                                )
                                if (index == uiState.contentParagraphs.lastIndex) {
                                    Spacer(modifier = Modifier.height(40.dp))
                                }
                            }
                            if (book.chapters.isNotEmpty() && uiState.currentChapterIndex < book.chapters.lastIndex) {
                                item {
                                    TextButton(onClick = onNextChapter) {
                                        Text("跳到下一章")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (chromeVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                ) {
                    TopAppBar(
                        title = { Text(text = book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showChapterSheet = true }) {
                                Icon(Icons.Default.List, contentDescription = "目录")
                            }
                            IconButton(onClick = { showStyleSheet = true }) {
                                Icon(Icons.Rounded.Tune, contentDescription = "样式")
                            }
                        }
                    )
                    if (book.chapters.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = onPrevChapter, enabled = uiState.currentChapterIndex > 0) {
                                Text("上一章")
                            }
                            Text(
                                text = book.chapters.getOrNull(uiState.currentChapterIndex)?.title ?: "",
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Button(onClick = onNextChapter, enabled = uiState.currentChapterIndex < book.chapters.lastIndex) {
                                Text("下一章")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStyleSheet) {
        ModalBottomSheet(onDismissRequest = { showStyleSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text("阅读样式", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                BackgroundSelector(style.backgroundMode, onBackgroundModeChange)
                Spacer(modifier = Modifier.height(8.dp))
                PageModeSelector(style.pageMode, onPageModeChange)
                Spacer(modifier = Modifier.height(8.dp))
                StylePanel(style = style, onStyleChange = onStyleChange)
            }
        }
    }

    if (showChapterSheet) {
        ModalBottomSheet(onDismissRequest = { showChapterSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 18.dp)) {
                Text("章节目录", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                if (uiState.chapterTitles.isEmpty()) {
                    Text("未识别到章节")
                }
                LazyColumn(modifier = Modifier.height(420.dp)) {
                    items(uiState.chapterTitles) { chapter ->
                        Text(
                            text = chapter.first,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showChapterSheet = false
                                if (book.chapters.isNotEmpty()) {
                                    onOpenChapter(chapter.second)
                                } else {
                                    scope.launch { listState.scrollToItem(chapter.second) }
                                }
                            }.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundSelector(currentMode: String, onBackgroundModeChange: (String) -> Unit) {
    Text("阅读背景")
    val options = listOf("paper" to "米白", "green" to "护眼绿", "gray" to "浅灰", "dark" to "深夜")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        options.forEach { (value, label) ->
            FilterChip(selected = currentMode == value, onClick = { onBackgroundModeChange(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun PageModeSelector(currentMode: String, onPageModeChange: (String) -> Unit) {
    Text("翻页方式")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = currentMode == "scroll", onClick = { onPageModeChange("scroll") }, label = { Text("竖向滚动") })
        FilterChip(selected = currentMode == "horizontal", onClick = { onPageModeChange("horizontal") }, label = { Text("横向翻页") })
    }
}

@Composable
private fun StylePanel(style: ReaderStyle, onStyleChange: (ReaderStyle) -> Unit) {
    Text("字号 ${style.fontSizeSp.toInt()}sp")
    Slider(value = style.fontSizeSp, onValueChange = { onStyleChange(style.copy(fontSizeSp = it)) }, valueRange = 14f..36f)
    Text("行距 ${"%.1f".format(style.lineHeightEm)}")
    Slider(value = style.lineHeightEm, onValueChange = { onStyleChange(style.copy(lineHeightEm = it)) }, valueRange = 1.2f..2.2f)
    Text("段间距 ${style.paragraphSpacingDp.toInt()}dp")
    Slider(value = style.paragraphSpacingDp, onValueChange = { onStyleChange(style.copy(paragraphSpacingDp = it)) }, valueRange = 4f..34f)
    Text("左右边距 ${style.horizontalPaddingDp.toInt()}dp")
    Slider(value = style.horizontalPaddingDp, onValueChange = { onStyleChange(style.copy(horizontalPaddingDp = it)) }, valueRange = 8f..42f)
    Text("上下边距 ${style.verticalPaddingDp.toInt()}dp")
    Slider(value = style.verticalPaddingDp, onValueChange = { onStyleChange(style.copy(verticalPaddingDp = it)) }, valueRange = 8f..48f)
    Text("字体")
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        FontButton("衬线", selected = style.fontFamily == "serif") { onStyleChange(style.copy(fontFamily = "serif")) }
        FontButton("无衬线", selected = style.fontFamily == "sans") { onStyleChange(style.copy(fontFamily = "sans")) }
        FontButton("等宽", selected = style.fontFamily == "mono") { onStyleChange(style.copy(fontFamily = "mono")) }
        FontButton("手写", selected = style.fontFamily == "cursive") { onStyleChange(style.copy(fontFamily = "cursive")) }
    }
}

@Composable
private fun FontButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Warm else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) Dawn else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(text = text)
    }
}

private fun paginatePagesByLayout(
    paragraphs: List<String>,
    textStyle: TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    widthPx: Int,
    heightPx: Int,
    paragraphBreakCount: Int
): List<Pair<Int, String>> {
    if (paragraphs.isEmpty()) return listOf(0 to "")
    val separator = "\n".repeat(paragraphBreakCount.coerceIn(1, 4))
    val fullText = paragraphs.joinToString(separator)
    val paraStarts = IntArray(paragraphs.size)
    var cursor = 0
    paragraphs.forEachIndexed { idx, p ->
        paraStarts[idx] = cursor
        cursor += p.length + if (idx == paragraphs.lastIndex) 0 else separator.length
    }

    fun paragraphIndexByOffset(offset: Int): Int {
        var l = 0
        var r = paraStarts.lastIndex
        while (l <= r) {
            val m = (l + r) ushr 1
            if (paraStarts[m] <= offset) l = m + 1 else r = m - 1
        }
        return r.coerceAtLeast(0)
    }

    val pages = mutableListOf<Pair<Int, String>>()
    var start = 0
    while (start < fullText.length) {
        var lo = start + 1
        var hi = fullText.length
        var best = lo
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val candidate = fullText.substring(start, mid)
            val result = textMeasurer.measure(
                text = candidate,
                style = textStyle,
                constraints = Constraints(maxWidth = widthPx)
            )
            if (result.size.height <= heightPx) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best <= start) best = (start + 1).coerceAtMost(fullText.length)
        val safeBest = preferBreakPoint(fullText, start, best)
        val pageText = fullText.substring(start, safeBest).trimEnd()
        pages += paragraphIndexByOffset(start) to pageText
        start = safeBest
        while (start < fullText.length && fullText[start].isWhitespace()) start++
    }
    return if (pages.isEmpty()) listOf(0 to "") else pages
}

private fun preferBreakPoint(text: String, start: Int, end: Int): Int {
    if (end - start < 30) return end
    val windowStart = (end - 60).coerceAtLeast(start)
    for (i in end - 1 downTo windowStart) {
        val c = text[i]
        if (c == '\n' || c == '。' || c == '！' || c == '？' || c == ';' || c == '；') {
            return i + 1
        }
    }
    return end
}

private fun readerColors(style: ReaderStyle): Pair<Color, Color> {
    return when (style.backgroundMode) {
        "green" -> Color(0xFFE9F6E4) to Color(0xFF243321)
        "gray" -> Color(0xFFF0F0F0) to Color(0xFF222222)
        "dark" -> Color(0xFF1C1C1E) to Color(0xFFEDEDED)
        else -> Color(0xFFFFF7E8) to Color(0xFF2B2520)
    }
}
