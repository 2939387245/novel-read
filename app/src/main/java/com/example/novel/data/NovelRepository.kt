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

class NovelRepository {
    suspend fun search(query: String): List<NovelBook> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val fromRss = runCatching { searchFromBingRss(query) }.getOrDefault(emptyList())
        if (fromRss.isNotEmpty()) return@withContext fromRss
        runCatching { searchFromBingHtml(query) }.getOrDefault(emptyList())
    }

    suspend fun importBookFromUrl(url: String): NovelBook? = withContext(Dispatchers.IO) {
        runCatching {
            val html = fetchRaw(url)
            val doc = Jsoup.parse(html, url)
            val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            val source = when {
                host.contains("biqu") -> "笔趣阁"
                host.contains("81zw") || host.contains("zw81") || host.contains("81") -> "八一中文网"
                host.contains("aisiluoke") || host.contains("siluke") -> "爱思路客"
                else -> "网页导入"
            }

            val chapters = extractChapters(doc, url)
            val title = resolveTitle(doc).ifBlank {
                chapters.firstOrNull()?.title?.substringBefore("第")?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "网页小说"
            }

            NovelBook(
                id = "web:${url.hashCode()}",
                title = title,
                author = resolveAuthor(doc),
                description = doc.select("meta[name=description]").attr("content").takeIf { it.isNotBlank() },
                textUrl = if (chapters.isEmpty()) url else chapters.first().url,
                catalogUrl = if (chapters.isNotEmpty()) url else null,
                chapters = chapters,
                source = source
            )
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
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) NovelReader/1.0")
            .build()
        return NetworkModule.client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
    }

    private fun extractReadableText(url: String, html: String): String {
        val doc = Jsoup.parse(html, url)
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val candidates = when {
            host.contains("biqu") -> listOf("#content", "#chaptercontent", ".content", "#booktxt")
            host.contains("81zw") || host.contains("zw81") || host.contains("81") -> listOf("#content", ".article-content", ".content", "#txt")
            host.contains("aisiluoke") || host.contains("siluke") -> listOf("#chaptercontent", "#content", ".txt", ".content")
            else -> listOf("article", "#content", ".content", ".article", ".chapter", "main")
        }
        val text = candidates.asSequence()
            .mapNotNull { selector -> doc.select(selector).firstOrNull()?.let { extractTextWithBreaks(it) } }
            .firstOrNull { it.isNotBlank() }
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

    private fun extractChapters(doc: Document, baseUrl: String): List<BookChapter> {
        val baseHost = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
        val links = doc.select("a[href]")
        val chapters = mutableListOf<BookChapter>()
        links.forEach { a ->
            val title = a.text().trim()
            if (title.length < 2 || title.length > 40) return@forEach
            if (!isLikelyChapterTitle(title)) return@forEach
            val abs = a.absUrl("href").trim()
            if (abs.isBlank() || !abs.startsWith("http")) return@forEach
            val host = abs.toHttpUrlOrNull()?.host.orEmpty()
            if (baseHost.isNotBlank() && host.isNotBlank() && !host.contains(baseHost.removePrefix("www."))) return@forEach
            chapters += BookChapter(title = title, url = abs)
        }
        return chapters
            .distinctBy { it.url }
            .take(3000)
            .let { list -> if (list.size >= 6) list else emptyList() }
    }

    private fun isLikelyChapterTitle(title: String): Boolean {
        val cleaned = title.replace(" ", "")
        return cleaned.contains("第") && (cleaned.contains("章") || cleaned.contains("节") || cleaned.contains("回")) ||
            Regex("^Chapter\\s*[\\p{L}\\p{N}IVXLC]+", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
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
