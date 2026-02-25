package com.example.novel.data

import android.content.Context
import java.io.File

class ChapterCache(context: Context) {
    private val root = File(context.cacheDir, "chapter_cache").apply { mkdirs() }
    data class CacheStat(
        val bookId: String,
        val title: String?,
        val chapterCount: Int,
        val totalBytes: Long
    )

    fun read(bookId: String, chapterIndex: Int): String? {
        val file = chapterFile(bookId, chapterIndex)
        if (!file.exists()) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun write(bookId: String, chapterIndex: Int, content: String, title: String? = null) {
        val file = chapterFile(bookId, chapterIndex)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            File(bookDir(bookId), "_book_id.txt").writeText(bookId, Charsets.UTF_8)
            if (!title.isNullOrBlank()) {
                File(bookDir(bookId), "_title.txt").writeText(title, Charsets.UTF_8)
            }
        }
    }

    fun clearBook(bookId: String) {
        runCatching { bookDir(bookId).deleteRecursively() }
    }

    fun clearAll() {
        runCatching {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
        }
    }

    fun listCacheStats(): List<CacheStat> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val files = dir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".txt") && it.name != "_book_id.txt" && it.name != "_title.txt"
            } ?: emptyList()
            if (files.isEmpty()) return@mapNotNull null
            val idFile = File(dir, "_book_id.txt")
            val titleFile = File(dir, "_title.txt")
            val bookId = runCatching { if (idFile.exists()) idFile.readText(Charsets.UTF_8).trim() else dir.name }.getOrDefault(dir.name)
            val title = runCatching {
                if (titleFile.exists()) titleFile.readText(Charsets.UTF_8).trim().takeIf { it.isNotBlank() } else null
            }.getOrNull()
            CacheStat(
                bookId = if (bookId.isBlank()) dir.name else bookId,
                title = title,
                chapterCount = files.size,
                totalBytes = files.sumOf { it.length() }
            )
        }.sortedByDescending { it.totalBytes }
    }

    private fun chapterFile(bookId: String, chapterIndex: Int): File {
        return File(bookDir(bookId), "$chapterIndex.txt")
    }

    private fun bookDir(bookId: String): File {
        val safe = bookId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(root, safe)
    }
}
