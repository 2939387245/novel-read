package com.example.novel.data

import com.example.novel.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URI
import java.nio.charset.Charset

class NovelRepository {
    suspend fun search(query: String): List<NovelBook> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val fromRss = runCatching { searchFromBingRss(query) }.getOrDefault(emptyList())
        if (fromRss.isNotEmpty()) return@withContext fromRss
        runCatching { searchFromBingHtml(query) }.getOrDefault(emptyList())
    }

    suspend fun importBookFromUrl(url: String): NovelBook? = withContext(Dispatchers.IO) {
        runCatching {
            val html = runCatching { fetchRaw(url) }.getOrElse {
                Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36")
                    .referrer(url)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(15000)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .get()
                    .html()
            }
            val doc = Jsoup.parse(html, url)
            val scope = buildCatalogScope(url)
            val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            val source = when {
                host.contains("biqu") -> "笔趣阁"
                host.contains("81zw") || host.contains("zw81") || host.contains("81") -> "八一中文网"
                host.contains("aisiluoke") || host.contains("siluke") -> "爱思路客"
                else -> "网页导入"
            }

            val firstPageChapters = extractChaptersFromDocument(doc, url, scope)
            val rawFallbackChapters = if (firstPageChapters.size >= 6) {
                emptyList()
            } else {
                extractChaptersFromRawHtml(baseUrl = url, html = html, scope = scope)
            }
            val firstMerged = (firstPageChapters + rawFallbackChapters).distinctBy { it.url }
            val chapters = if (firstPageChapters.size >= 6) {
                firstPageChapters
            } else if (firstMerged.size >= 6) {
                firstMerged
            } else {
                extractChaptersWithCatalogPaging(startUrl = url, firstDoc = doc, scope = scope, maxCatalogPages = 8)
            }
            val title = resolveTitle(doc).ifBlank {
                chapters.firstOrNull()?.title?.substringBefore("第")?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "网页小说"
            }

            NovelBook(
                id = "web:${url.hashCode()}",
                title = title,
                author = resolveAuthor(doc),
                coverUrl = resolveCoverUrl(doc, url),
                description = doc.select("meta[name=description]").attr("content").takeIf { it.isNotBlank() },
                textUrl = if (chapters.isEmpty()) url else chapters.first().url,
                catalogUrl = if (chapters.isNotEmpty()) url else null,
                chapters = chapters,
                source = source
            )
        }.getOrNull()
    }

    suspend fun syncBookIncremental(
        book: NovelBook,
        maxCatalogPages: Int = 120,
        onProgress: ((scannedPages: Int, totalPages: Int, foundChapters: Int) -> Unit)? = null
    ): NovelBook? = withContext(Dispatchers.IO) {
        val entryUrl = book.catalogUrl ?: book.textUrl ?: return@withContext null
        runCatching {
            val html = fetchRaw(entryUrl)
            val doc = Jsoup.parse(html, entryUrl)
            val scope = buildCatalogScope(entryUrl)
            val existingKeySet = book.chapters.map { chapterIdentity(it.title, it.url) }.toHashSet()
            val lastKnownKey = book.chapters.lastOrNull()?.let { chapterIdentity(it.title, it.url) }
            val all = extractChaptersWithCatalogPaging(
                startUrl = entryUrl,
                firstDoc = doc,
                scope = scope,
                maxCatalogPages = maxCatalogPages,
                existingChapterKeys = existingKeySet,
                lastKnownChapterKey = lastKnownKey,
                preferTail = true,
                onProgress = onProgress
            )
            if (all.isEmpty()) return@runCatching book
            if (book.chapters.isEmpty()) {
                return@runCatching book.copy(
                    chapters = all,
                    textUrl = all.firstOrNull()?.url ?: book.textUrl,
                    catalogUrl = entryUrl
                )
            }
            val lastKnown = book.chapters.lastOrNull()
            val appended = if (lastKnown != null) {
                val lastKey = chapterIdentity(lastKnown.title, lastKnown.url)
                val idx = all.indexOfFirst { chapterIdentity(it.title, it.url) == lastKey }
                if (idx >= 0) {
                    all.drop(idx + 1).filter { !existingKeySet.contains(chapterIdentity(it.title, it.url)) }
                } else {
                    all.filter { !existingKeySet.contains(chapterIdentity(it.title, it.url)) }
                }
            } else {
                all.filter { !existingKeySet.contains(chapterIdentity(it.title, it.url)) }
            }
            if (appended.isEmpty()) {
                book.copy(catalogUrl = entryUrl, coverUrl = book.coverUrl ?: resolveCoverUrl(doc, entryUrl))
            } else {
                book.copy(
                    chapters = book.chapters + appended,
                    catalogUrl = entryUrl,
                    coverUrl = book.coverUrl ?: resolveCoverUrl(doc, entryUrl)
                )
            }
        }.getOrNull()
    }

    suspend fun loadBookContent(book: NovelBook, chapterIndex: Int): String = withContext(Dispatchers.IO) {
        val (targetUrl, nextChapterUrl) = when {
            book.chapters.isNotEmpty() -> {
                val safe = chapterIndex.coerceIn(0, book.chapters.lastIndex)
                val current = book.chapters[safe].url
                val next = book.chapters.getOrNull(safe + 1)?.url
                current to next
            }
            else -> book.textUrl to null
        }
        if (targetUrl.isNullOrBlank()) return@withContext fallbackContent(book)

        runCatching {
            val merged = fetchChapterWithPagination(startUrl = targetUrl, nextChapterUrl = nextChapterUrl)
            sanitizeText(merged).ifBlank { fallbackContent(book) }
        }.getOrDefault(fallbackContent(book))
    }

    private fun fallbackContent(book: NovelBook): String {
        val intro = book.description ?: "当前页面未提取到正文。建议使用目录页地址重新导入。"
        return buildString {
            appendLine(book.title)
            appendLine()
            appendLine("作者：${book.author}")
            appendLine("来源：${book.source}")
            appendLine()
            appendLine(intro)
            appendLine()
            appendLine("提示：优先使用小说目录页链接导入，可自动抓取整本章节。")
        }
    }

    fun buildChapters(content: String, paragraphs: List<String>): List<ChapterItem> {
        if (paragraphs.isEmpty()) return emptyList()
        val chapterRegex = Regex(
            "^(\\u7B2C[\\p{L}\\p{N}\\u4E00\\u4E8C\\u4E09\\u56DB\\u4E94\\u516D\\u4E03\\u516B\\u4E5D\\u5341\\u767E\\u5343\\u96F6\\u4E24 0-9]{1,12}[\\u7AE0\\u8282\\u56DE\\u5377\\u96C6\\u90E8\\u7BC7].*|Chapter\\s+[\\p{L}\\p{N}IVXLC0-9]+.*)$"
                .replace(" ", "")
        )
        val detected = paragraphs.mapIndexedNotNull { index, line ->
            val title = line.trim()
            if (chapterRegex.matches(title)) ChapterItem(title.take(32), index) else null
        }
        if (detected.isNotEmpty()) return detected

        val fallbackStep = when {
            content.length > 180_000 -> 80
            content.length > 100_000 -> 65
            else -> 50
        }
        return paragraphs.indices
            .step(fallbackStep)
            .mapIndexed { idx, paragraphIndex ->
                ChapterItem("第${idx + 1}节", paragraphIndex)
            }
    }

    private fun sanitizeText(raw: String): String {
        return raw
            .replace("\uFEFF", "")
            .replace(Regex("\\r\\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .trim()
            .take(260_000)
    }

    private fun fetchRaw(url: String): String {
        val candidates = buildFetchCandidates(url)
        var lastError: Throwable? = null
        for (target in candidates.distinct()) {
            try {
                val request = Request.Builder()
                    .url(target)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/122 Mobile Safari/537.36")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", URI(target).let { "${it.scheme}://${it.host}" })
                    .build()
                return NetworkModule.client.newCall(request).execute().use { response ->
                    val body = response.body ?: return@use ""
                    val bytes = body.bytes()
                    if (bytes.isEmpty() && !response.isSuccessful) {
                        throw IllegalStateException("http ${response.code} for $target")
                    }
                    decodeHtml(bytes, body.contentType()?.charset())
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("fetch failed")
    }

    private fun buildFetchCandidates(url: String): List<String> {
        val parsed = url.toHttpUrlOrNull() ?: return listOf(url)
        val hostNoWww = parsed.host.removePrefix("www.")
        val path = buildString {
            append(parsed.encodedPath)
            parsed.encodedQuery?.let { append("?").append(it) }
        }
        val candidates = linkedSetOf<String>()
        fun add(scheme: String, host: String) {
            candidates += "$scheme://$host$path"
        }
        add(parsed.scheme, parsed.host)
        if (parsed.scheme == "http") add("https", parsed.host)
        if (parsed.host.startsWith("www.")) {
            add(parsed.scheme, hostNoWww)
            if (parsed.scheme == "http") add("https", hostNoWww)
        } else {
            add(parsed.scheme, "www.$hostNoWww")
            if (parsed.scheme == "http") add("https", "www.$hostNoWww")
        }
        if (!hostNoWww.startsWith("m.")) {
            add(parsed.scheme, "m.$hostNoWww")
            if (parsed.scheme == "http") add("https", "m.$hostNoWww")
        }
        return candidates.toList()
    }

    private fun decodeHtml(bytes: ByteArray, charsetFromHeader: Charset?): String {
        charsetFromHeader?.let { return bytes.toString(it) }
        val probe = bytes.toString(Charsets.ISO_8859_1)
        val fromMeta = Regex("(?i)charset\\s*=\\s*['\\\"]?([a-zA-Z0-9_\\-]+)")
            .find(probe)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        fromMeta?.let { return bytes.toString(it) }
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('�') || utf8.contains("锟斤拷")) {
            runCatching { bytes.toString(Charset.forName("GB18030")) }.getOrElse { utf8 }
        } else {
            utf8
        }
    }

    private fun extractReadableText(url: String, html: String): String {
        val doc = Jsoup.parse(html, url)
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val candidates = when {
            host.contains("biqu") -> listOf("#content", "#chaptercontent", ".content", "#booktxt")
            host.contains("81zw") || host.contains("zw81") || host.contains("81") -> listOf("#content", ".article-content", ".content", "#txt")
            host.contains("cheyil") -> listOf("#nr1", "#content", ".nr_nr", ".content", ".yd_text2", "#chaptercontent")
            host.contains("xp600") -> listOf("#content", "#nr1", ".content", ".article-content")
            host.contains("xhytd") -> listOf("#content", "#nr1", ".content", ".article-content")
            host.contains("aisiluoke") || host.contains("siluke") -> listOf("#chaptercontent", "#content", ".txt", ".content")
            else -> listOf("article", "#content", ".content", ".article", ".chapter", "main")
        }
        val text = candidates.asSequence()
            .flatMap { selector -> doc.select(selector).asSequence() }
            .map { element ->
                val extracted = extractTextWithBreaks(element)
                val rawLen = element.text().length.coerceAtLeast(1)
                val linkLen = element.select("a").text().length
                val linkRatio = linkLen.toFloat() / rawLen.toFloat()
                val score = extracted.length - (linkRatio * 700).toInt()
                extracted to score
            }
            .filter { it.first.isNotBlank() && it.first.length >= 60 }
            .maxByOrNull { it.second }
            ?.first
        return text ?: extractTextWithBreaks(doc.body())
    }

    private fun resolveTitle(doc: Document): String {
        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.title() }
            .trim()
        return if (title.isBlank()) "网页小说" else title.take(80)
    }

    private fun resolveAuthor(doc: Document): String {
        val direct = doc.select("meta[name=author]").attr("content").trim()
        if (direct.isNotBlank()) return direct
        val body = doc.body().text()
        val match = Regex("作者[:：]\\s*([\\p{L}\\p{N}_\\-·]{2,20})").find(body)
        return match?.groupValues?.getOrNull(1) ?: "未知作者"
    }

    private fun extractChaptersWithCatalogPaging(
        startUrl: String,
        firstDoc: Document,
        scope: CatalogScope,
        maxCatalogPages: Int,
        existingChapterKeys: Set<String> = emptySet(),
        lastKnownChapterKey: String? = null,
        preferTail: Boolean = false,
        onProgress: ((scannedPages: Int, totalPages: Int, foundChapters: Int) -> Unit)? = null
    ): List<BookChapter> {
        val visited = linkedSetOf<String>()
        val initialUrls = if (preferTail && existingChapterKeys.isNotEmpty()) {
            buildTailSeedUrls(startUrl = startUrl, knownChapters = existingChapterKeys.size)
        } else {
            listOf(startUrl)
        }
        val queued = linkedSetOf<String>().apply { addAll(initialUrls) }
        val queue = ArrayDeque<String>().apply { initialUrls.forEach { add(it) } }
        val chapterMap = linkedMapOf<String, BookChapter>()
        var pageCount = 0
        var tailAnchorSeen = lastKnownChapterKey == null
        var noNewAfterTail = 0

        while (queue.isNotEmpty() && pageCount < maxCatalogPages) {
            val currentUrl = queue.removeFirst()
            if (!visited.add(currentUrl)) continue
            val html = if (currentUrl == startUrl) {
                firstDoc.outerHtml()
            } else {
                runCatching { fetchRaw(currentUrl) }.getOrNull() ?: continue
            }
            val doc = if (currentUrl == startUrl) firstDoc else Jsoup.parse(html, currentUrl)
            val pageChapters = (
                extractChaptersFromDocument(doc, currentUrl, scope) +
                    extractChaptersFromRawHtml(baseUrl = currentUrl, html = html, scope = scope)
                )
                .distinctBy { it.url }
            pageChapters.forEach { chapter ->
                val key = chapterIdentity(chapter.title, chapter.url)
                if (!chapterMap.containsKey(key)) chapterMap[key] = chapter
            }
            if (!tailAnchorSeen && lastKnownChapterKey != null) {
                tailAnchorSeen = pageChapters.any { chapterIdentity(it.title, it.url) == lastKnownChapterKey }
            }
            if (tailAnchorSeen && lastKnownChapterKey != null) {
                val newOnPage = pageChapters.count { !existingChapterKeys.contains(chapterIdentity(it.title, it.url)) }
                noNewAfterTail = if (newOnPage == 0) noNewAfterTail + 1 else 0
                if (noNewAfterTail >= 3 && pageCount >= 2) {
                    pageCount++
                    val newCount = chapterMap.keys.count { !existingChapterKeys.contains(it) }
                    onProgress?.invoke(pageCount, maxCatalogPages, existingChapterKeys.size + newCount)
                    break
                }
            }
            resolveCatalogPageCandidates(doc, currentUrl, scope, preferTail).forEach { nextUrl ->
                if (visited.contains(nextUrl) || queued.contains(nextUrl)) return@forEach
                queued += nextUrl
                queue += nextUrl
            }
            pageCount++
            val newCount = chapterMap.keys.count { !existingChapterKeys.contains(it) }
            onProgress?.invoke(pageCount, maxCatalogPages, existingChapterKeys.size + newCount)
        }

        return chapterMap.values
            .toList()
            .take(5000)
            .let { list -> if (list.size >= 6) list else emptyList() }
    }

    private fun extractChaptersFromDocument(
        doc: Document,
        baseUrl: String,
        scope: CatalogScope
    ): List<BookChapter> {
        val baseHost = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
        val links = doc.select("a[href]")
        val chapters = mutableListOf<BookChapter>()
        links.forEach { a ->
            val title = a.text().trim()
            if (title.length < 2 || title.length > 120) return@forEach
            if (!isLikelyChapterTitle(title)) return@forEach
            val abs = a.absUrl("href").trim()
            if (abs.isBlank() || !abs.startsWith("http")) return@forEach
            if (!isLikelyChapterUrl(abs, baseUrl)) return@forEach
            val specialTitle = isSpecialChapterTitle(title)
            if (!pathBelongsToBook(abs, scope) && !specialTitle) return@forEach
            val host = abs.toHttpUrlOrNull()?.host.orEmpty()
            if (baseHost.isNotBlank() && host.isNotBlank() && !isSameSiteHost(baseHost, host)) return@forEach
            chapters += BookChapter(title = title, url = abs)
        }
        // Some sites place chapter links in <option value="..."> instead of <a>.
        doc.select("option[value]").forEach { op ->
            val title = op.text().trim()
            if (title.length < 2 || title.length > 120) return@forEach
            if (!isLikelyChapterTitle(title)) return@forEach
            val raw = op.attr("value").trim()
            if (raw.isBlank()) return@forEach
            val abs = runCatching { URI(baseUrl).resolve(raw).toString() }.getOrDefault(raw).trim()
            if (!abs.startsWith("http")) return@forEach
            if (!isLikelyChapterUrl(abs, baseUrl)) return@forEach
            val specialTitle = isSpecialChapterTitle(title)
            if (!pathBelongsToBook(abs, scope) && !specialTitle) return@forEach
            val host = abs.toHttpUrlOrNull()?.host.orEmpty()
            if (baseHost.isNotBlank() && host.isNotBlank() && !isSameSiteHost(baseHost, host)) return@forEach
            chapters += BookChapter(title = title, url = abs)
        }
        var result = chapters
            .distinctBy { it.url }
            .take(3000)
        if (result.size >= 6) return result

        // Fallback for sites using unconventional chapter labels.
        val relaxed = mutableListOf<BookChapter>()
        links.forEach { a ->
            val title = a.text().trim()
            if (title.isBlank() || title.length > 120) return@forEach
            val abs = a.absUrl("href").trim()
            if (abs.isBlank() || !abs.startsWith("http")) return@forEach
            if (!isLikelyChapterUrl(abs, baseUrl)) return@forEach
            if (!pathBelongsToBook(abs, scope)) return@forEach
            val path = abs.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
            if (!(path.endsWith(".html") || path.contains("/chapter") || path.contains("/read"))) return@forEach
            relaxed += BookChapter(title = title, url = abs)
        }
        result = (result + relaxed)
            .distinctBy { it.url }
            .take(3000)
        return result
    }

    private fun isLikelyChapterTitle(title: String): Boolean {
        val cleaned = title.replace(Regex("\\s+"), "")
        val normal = cleaned.contains("第") && (cleaned.contains("章") || cleaned.contains("节") || cleaned.contains("回"))
        val english = Regex("^Chapter\\s*[\\p{L}\\p{N}IVXLC]+", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
        val special = Regex("(完本感言|番外|后记|尾声|终章|大结局|新书|单章|请假条)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
        val specialPrefix = Regex("^(番外|人物篇|角色篇|篇章|后日谈|if线|IF线|后传|外传|特别篇|附录)").containsMatchIn(cleaned)
        val themed = Regex("[\\p{L}\\p{N}\\u4E00-\\u9FA5]{1,16}篇[（(]?[\\u4E00-\\u9FA5\\p{N}IVXLC]{0,8}[）)]?.*").containsMatchIn(cleaned)
        val numbered = Regex("^\\d{1,4}[\\.、:：_-][\\p{L}\\p{N}\\u4E00-\\u9FA5].+").containsMatchIn(cleaned)
        val numberedLoose = Regex("^\\d{1,4}[\\.、\\-_:：\\s]+[\\p{L}\\p{N}\\u4E00-\\u9FA5].+").containsMatchIn(cleaned)
        return normal || english || special || specialPrefix || themed || numbered || numberedLoose
    }

    private fun isSpecialChapterTitle(title: String): Boolean {
        val cleaned = title.replace(Regex("\\s+"), "")
        return Regex("^(番外|人物篇|角色篇|外传|后传|后日谈|特别篇|附录|完本感言|尾声|后记)").containsMatchIn(cleaned)
    }

    private fun isLikelyChapterUrl(url: String, baseUrl: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val base = baseUrl.toHttpUrlOrNull()
        val path = parsed.encodedPath.lowercase()
        if (path.endsWith("/")) {
            // Two numeric segments are usually catalog; three often represent chapter detail.
            if (Regex("^/\\d+/\\d+/$").containsMatchIn(path)) return false
            if (Regex("^/\\d+/\\d+/\\d+/$").containsMatchIn(path)) {
                val sameAsBase = base != null && base.encodedPath.lowercase() == path && base.query == parsed.query
                return !sameAsBase
            }
        }
        if (path.contains("all.html") || path.contains("/all/")) return false
        if (path.contains("index") || path.contains("list") || path.contains("mulu") || path.contains("catalog")) return false
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            val sameAsBase = base != null && base.encodedPath.lowercase() == path && base.query == parsed.query
            return !sameAsBase
        }
        if (Regex("^/\\d+/\\d+/\\d+/?$").containsMatchIn(path)) {
            val sameAsBase = base != null && base.encodedPath.lowercase() == path && base.query == parsed.query
            return !sameAsBase
        }
        if (path.contains("/chapter") || path.contains("/read/")) return true
        val q = parsed.query?.lowercase().orEmpty()
        if (q.contains("chapter") || q.contains("cid=")) return true
        return false
    }

    private fun extractChaptersFromRawHtml(
        baseUrl: String,
        html: String,
        scope: CatalogScope
    ): List<BookChapter> {
        val baseHost = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
        val result = mutableListOf<BookChapter>()
        val anchorRegex = Regex("(?is)<a[^>]*href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*>(.*?)</a>")
        anchorRegex.findAll(html).forEach { m ->
            val rawHref = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val rawTitle = Jsoup.parse(m.groupValues.getOrNull(2).orEmpty()).text().trim()
            if (rawHref.isBlank() || rawTitle.length !in 2..120) return@forEach
            if (!isLikelyChapterTitle(rawTitle)) return@forEach
            val abs = runCatching { URI(baseUrl).resolve(rawHref).toString() }.getOrDefault(rawHref).trim()
            if (!abs.startsWith("http")) return@forEach
            if (!isLikelyChapterUrl(abs, baseUrl)) return@forEach
            val specialTitle = isSpecialChapterTitle(rawTitle)
            if (!pathBelongsToBook(abs, scope) && !specialTitle) return@forEach
            val host = abs.toHttpUrlOrNull()?.host.orEmpty()
            if (baseHost.isNotBlank() && host.isNotBlank() && !isSameSiteHost(baseHost, host)) return@forEach
            result += BookChapter(title = rawTitle, url = abs)
        }

        val scriptRegexA =
            Regex("(?is)(?:title|name)\\s*[:=]\\s*['\\\"]([^'\\\"]{2,120})['\\\"][^\\n\\r]{0,200}?(?:url|href)\\s*[:=]\\s*['\\\"]([^'\\\"]+)['\\\"]")
        scriptRegexA.findAll(html).forEach { m ->
            val rawTitle = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val rawHref = m.groupValues.getOrNull(2)?.trim().orEmpty()
            if (rawHref.isBlank() || rawTitle.length !in 2..120) return@forEach
            if (!isLikelyChapterTitle(rawTitle)) return@forEach
            val abs = runCatching { URI(baseUrl).resolve(rawHref).toString() }.getOrDefault(rawHref).trim()
            if (!abs.startsWith("http")) return@forEach
            if (!isLikelyChapterUrl(abs, baseUrl)) return@forEach
            val specialTitle = isSpecialChapterTitle(rawTitle)
            if (!pathBelongsToBook(abs, scope) && !specialTitle) return@forEach
            result += BookChapter(title = rawTitle, url = abs)
        }

        val scriptRegexB =
            Regex("(?is)(?:url|href)\\s*[:=]\\s*['\\\"]([^'\\\"]+)['\\\"][^\\n\\r]{0,200}?(?:title|name)\\s*[:=]\\s*['\\\"]([^'\\\"]{2,120})['\\\"]")
        scriptRegexB.findAll(html).forEach { m ->
            val rawHref = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val rawTitle = m.groupValues.getOrNull(2)?.trim().orEmpty()
            if (rawHref.isBlank() || rawTitle.length !in 2..120) return@forEach
            if (!isLikelyChapterTitle(rawTitle)) return@forEach
            val abs = runCatching { URI(baseUrl).resolve(rawHref).toString() }.getOrDefault(rawHref).trim()
            if (!abs.startsWith("http")) return@forEach
            if (!isLikelyChapterUrl(abs, baseUrl)) return@forEach
            val specialTitle = isSpecialChapterTitle(rawTitle)
            if (!pathBelongsToBook(abs, scope) && !specialTitle) return@forEach
            result += BookChapter(title = rawTitle, url = abs)
        }

        return result.distinctBy { it.url }.take(3000)
    }

    private fun resolveCatalogPageCandidates(
        doc: Document,
        currentUrl: String,
        scope: CatalogScope,
        preferTail: Boolean = false
    ): List<String> {
        val current = currentUrl.toHttpUrlOrNull() ?: return emptyList()
        val currentPath = current.encodedPath
        val currentHost = current.host
        val currentStem = currentPath.substringBeforeLast('/', "").ifBlank { currentPath }
        val candidates = doc.select("a[href]").mapNotNull { a ->
            val text = a.text().trim()
            if (text.isNotBlank() && isLikelyChapterTitle(text)) return@mapNotNull null
            val abs = a.absUrl("href").trim()
            if (abs.isBlank() || !abs.startsWith("http")) return@mapNotNull null
            if (!pathBelongsToBook(abs, scope)) return@mapNotNull null
            val target = abs.toHttpUrlOrNull() ?: return@mapNotNull null
            if (!isSameSiteHost(currentHost, target.host)) return@mapNotNull null
            val targetPath = target.encodedPath
            if (targetPath.contains("sitemap", ignoreCase = true)) return@mapNotNull null
            if (targetPath.contains("map", ignoreCase = true) && !targetPath.contains("mulu", ignoreCase = true)) return@mapNotNull null
            val looksPagerText = Regex("(下一页|下页|下一頁|next|>|>>|\\d+\\s*-\\s*\\d+\\s*章)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
            val looksPagerHref = Regex("(index|list|mulu|catalog|page|_\\d+|/\\d+_\\d+)", RegexOption.IGNORE_CASE)
                .containsMatchIn(targetPath)
            val stem = targetPath.substringBeforeLast('/', "").ifBlank { targetPath }
            val sameBookPath = stem == currentStem
            val pureChapterHref = Regex("/\\d+\\.html?$", RegexOption.IGNORE_CASE).containsMatchIn(targetPath)
            if (pureChapterHref && !looksPagerText && !looksPagerHref) return@mapNotNull null
            if (!(looksPagerText || looksPagerHref || sameBookPath)) return@mapNotNull null
            val hint = extractPageHint(text = text, path = targetPath)
            Triple(abs, hint, text)
        }
        val ordered = if (preferTail) {
            candidates.sortedByDescending { it.second }
        } else {
            candidates.sortedBy { it.second }
        }
        return ordered.map { it.first }.distinct()
    }

    private fun resolveCoverUrl(doc: Document, baseUrl: String): String? {
        val selectors = listOf(
            "meta[property=og:image]",
            "meta[name=twitter:image]",
            "meta[itemprop=image]"
        )
        selectors.forEach { selector ->
            val value = doc.select(selector).attr("content").trim()
            if (value.isNotBlank()) {
                val resolved = runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()
                if (!resolved.isNullOrBlank()) return resolved
            }
        }
        val img = doc.select("img[src]").firstOrNull() ?: return null
        val src = img.absUrl("src").trim().ifBlank { img.attr("src").trim() }
        if (src.isBlank()) return null
        return runCatching { URI(baseUrl).resolve(src).toString() }.getOrDefault(src)
    }

    private fun extractPageHint(text: String, path: String): Int {
        val textNums = Regex("(\\d+)").findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
        if (textNums.isNotEmpty()) return textNums.maxOrNull() ?: 0
        val pathNums = Regex("(\\d+)").findAll(path).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
        return pathNums.maxOrNull() ?: 0
    }

    private fun buildTailSeedUrls(startUrl: String, knownChapters: Int): List<String> {
        val parsed = startUrl.toHttpUrlOrNull() ?: return listOf(startUrl)
        val estimatedPage = (knownChapters / 50).coerceAtLeast(1)
        val pages = listOf(estimatedPage - 1, estimatedPage, estimatedPage + 1).filter { it >= 1 }.distinct()
        val scheme = parsed.scheme
        val host = parsed.host
        val path = parsed.encodedPath
        val query = parsed.encodedQuery
        val seeds = linkedSetOf<String>()
        for (p in pages) {
            val basePath = when {
                path.endsWith("/") -> "${path}index_$p.html"
                Regex("index\\.html?$", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
                    path.replace(Regex("index\\.html?$", RegexOption.IGNORE_CASE), "index_$p.html")
                Regex("index_\\d+\\.html?$", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
                    path.replace(Regex("index_\\d+\\.html?$", RegexOption.IGNORE_CASE), "index_$p.html")
                Regex("_\\d+\\.html?$", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
                    path.replace(Regex("_\\d+\\.html?$", RegexOption.IGNORE_CASE), "_$p.html")
                else -> path
            }
            val full = if (query.isNullOrBlank()) {
                "$scheme://$host$basePath"
            } else {
                "$scheme://$host$basePath?$query"
            }
            seeds += full
            if (query != null && query.contains("page=", ignoreCase = true)) {
                seeds += "$scheme://$host$path?${query.replace(Regex("(?i)page=\\d+"), "page=$p")}"
            }
        }
        seeds += startUrl
        return seeds.toList()
    }

    private fun isSameSiteHost(a: String, b: String): Boolean {
        val left = a.lowercase().removePrefix("www.")
        val right = b.lowercase().removePrefix("www.")
        if (left == right) return true
        return siteCore(left) == siteCore(right)
    }

    private fun chapterIdentity(title: String, url: String): String {
        val normalizedTitle = title
            .lowercase()
            .replace(Regex("[\\s\\u3000\\-_:：·,.，。!！?？、]+"), "")
        if (normalizedTitle.isNotBlank()) return normalizedTitle
        return url.toHttpUrlOrNull()?.encodedPath?.lowercase() ?: url.lowercase()
    }

    private data class CatalogScope(
        val hostRoot: String,
        val pathPrefix: String,
        val bookKey: String?
    )

    private fun buildCatalogScope(url: String): CatalogScope {
        val parsed = url.toHttpUrlOrNull()
        val hostRoot = rootDomain(parsed?.host.orEmpty())
        val seg = parsed?.encodedPath?.split('/')?.filter { it.isNotBlank() }.orEmpty()
        val prefix = when {
            seg.size >= 2 && seg[0].equals("book", ignoreCase = true) -> "/${seg[0]}/${seg[1]}"
            seg.size >= 2 && seg[1].any(Char::isDigit) -> "/${seg[0]}/${seg[1]}"
            seg.isNotEmpty() -> "/${seg[0]}"
            else -> "/"
        }.lowercase()
        val key = seg
            .filter { it.any(Char::isDigit) }
            .sortedWith(compareByDescending<String> { it.count(Char::isDigit) }.thenByDescending { it.length })
            .firstOrNull()
            ?.lowercase()
        return CatalogScope(hostRoot = hostRoot, pathPrefix = prefix, bookKey = key)
    }

    private fun pathBelongsToBook(url: String, scope: CatalogScope): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (siteCore(parsed.host) != siteCore(scope.hostRoot)) return false
        val path = parsed.encodedPath.lowercase()
        val prefixMatched = scope.pathPrefix == "/" || path.startsWith(scope.pathPrefix)
        if (!scope.bookKey.isNullOrBlank()) {
            val joined = (parsed.host + path).lowercase()
            val keyMatched = joined.contains(scope.bookKey)
            if (!prefixMatched && !keyMatched) return false
            if (!keyMatched && scope.pathPrefix != "/") {
                // Guard against broad catalog pages pulling unrelated books.
                return false
            }
            return true
        }
        return prefixMatched
    }

    private fun rootDomain(host: String): String {
        val clean = host.lowercase().removePrefix("www.")
        val parts = clean.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else clean
    }

    private fun siteCore(host: String): String {
        val clean = host.lowercase().removePrefix("www.")
        val parts = clean.split('.')
        return if (parts.size >= 2) parts[parts.size - 2] else clean
    }

    private fun searchFromBingRss(query: String): List<NovelBook> {
        val encoded = java.net.URLEncoder.encode("$query 小说", "UTF-8")
        val url = "https://www.bing.com/search?q=$encoded&format=rss"
        val xml = fetchRaw(url)
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").mapNotNull { item ->
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            if (!link.startsWith("http")) return@mapNotNull null
            val title = item.selectFirst("title")?.text()?.trim().orEmpty().ifBlank { "网页小说结果" }
            val desc = item.selectFirst("description")?.text()?.trim()
            NovelBook(
                id = "bing:${link.hashCode()}",
                title = title.take(80),
                author = "网页结果",
                description = desc,
                textUrl = link,
                source = "Bing"
            )
        }.distinctBy { it.id }.take(30)
    }

    private fun searchFromBingHtml(query: String): List<NovelBook> {
        val encoded = java.net.URLEncoder.encode("$query 小说", "UTF-8")
        val url = "https://www.bing.com/search?q=$encoded&setlang=zh-cn"
        val html = fetchRaw(url)
        val doc = Jsoup.parse(html, url)
        val blocks = doc.select("li.b_algo, .b_algo, .b_ans")
        return blocks.mapNotNull { item ->
            val link = item.selectFirst("h2 a, a[href]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            if (href.isBlank() || !href.startsWith("http")) return@mapNotNull null
            val title = link.text().ifBlank { item.selectFirst("h2")?.text().orEmpty() }.ifBlank { "网页小说结果" }
            val desc = item.selectFirst(".b_caption p, p")?.text()
            NovelBook(
                id = "bing:${href.hashCode()}",
                title = title.take(80),
                author = "网页结果",
                description = desc,
                textUrl = href,
                source = "Bing"
            )
        }.distinctBy { it.id }.take(30)
    }

    private fun extractTextWithBreaks(element: org.jsoup.nodes.Element): String {
        val html = element.html()
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("(?i)</div>"), "\n")
        return Jsoup.parseBodyFragment(html).body().wholeText()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun fetchChapterWithPagination(startUrl: String, nextChapterUrl: String?): String {
        val visited = LinkedHashSet<String>()
        val merged = StringBuilder()
        var currentUrl: String? = startUrl
        var pageCount = 0
        val maxPagesPerChapter = 12

        while (!currentUrl.isNullOrBlank() && pageCount < maxPagesPerChapter) {
            if (!visited.add(currentUrl)) break
            val html = fetchRaw(currentUrl)
            val doc = Jsoup.parse(html, currentUrl)
            val pageText = extractReadableText(currentUrl, html)
            if (pageText.isNotBlank()) {
                if (merged.isNotEmpty() && !merged.endsWith("\n\n")) merged.append("\n\n")
                merged.append(pageText)
            }

            val nextPage = resolveNextPageUrl(doc, currentUrl, nextChapterUrl, visited)
            currentUrl = nextPage
            pageCount++
        }
        return merged.toString()
    }

    private fun resolveNextPageUrl(
        doc: Document,
        currentUrl: String,
        nextChapterUrl: String?,
        visited: Set<String>
    ): String? {
        val nextSelectors = listOf(
            "a#pb_next",
            "a#next",
            "a.next",
            "a[rel=next]",
            "a:matchesOwn(下一页|下页|下一頁|Next|next)"
        )
        val host = currentUrl.toHttpUrlOrNull()?.host
        val links = nextSelectors
            .flatMap { selector -> doc.select(selector) }
            .distinctBy { it.attr("href") + "|" + it.text() }

        for (link in links) {
            val text = link.text().trim()
            if (text.contains("下一章") || text.contains("目录") || text.contains("返回书页")) continue
            val abs = link.absUrl("href").trim()
            if (abs.isBlank() || !abs.startsWith("http")) continue
            if (abs == nextChapterUrl) continue
            if (visited.contains(abs)) continue
            val candidateHost = abs.toHttpUrlOrNull()?.host
            if (!host.isNullOrBlank() && !candidateHost.isNullOrBlank() && candidateHost != host) continue
            if (looksLikeCatalog(abs)) continue
            return abs
        }
        return null
    }

    private fun looksLikeCatalog(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("list") || lower.contains("index") || lower.contains("mulu") || lower.contains("catalog")
    }
}
