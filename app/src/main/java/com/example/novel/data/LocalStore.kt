package com.example.novel.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "novel_store")

class LocalStore(private val context: Context) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val bookshelfKey = stringPreferencesKey("bookshelf")
    private val styleKey = stringPreferencesKey("reader_style")
    private val progressKey = stringPreferencesKey("reading_progress")
    private val catalogSyncedKey = stringPreferencesKey("catalog_synced_books")

    val bookshelfFlow: Flow<List<NovelBook>> = context.dataStore.data.map { pref ->
        val raw = pref[bookshelfKey] ?: return@map emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(NovelBook.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    val styleFlow: Flow<ReaderStyle> = context.dataStore.data.map { pref ->
        val raw = pref[styleKey] ?: return@map ReaderStyle()
        runCatching {
            json.decodeFromString(ReaderStyle.serializer(), raw)
        }.getOrDefault(ReaderStyle())
    }

    val progressFlow: Flow<Map<String, ReadingProgress>> = context.dataStore.data.map { pref ->
        val raw = pref[progressKey] ?: return@map emptyMap()
        runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), ReadingProgress.serializer()),
                raw
            )
        }.getOrDefault(emptyMap())
    }

    val catalogSyncedFlow: Flow<Set<String>> = context.dataStore.data.map { pref ->
        val raw = pref[catalogSyncedKey] ?: return@map emptySet()
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw).toSet()
        }.getOrDefault(emptySet())
    }

    suspend fun saveBookshelf(books: List<NovelBook>) {
        context.dataStore.edit { pref ->
            pref[bookshelfKey] = json.encodeToString(ListSerializer(NovelBook.serializer()), books)
        }
    }

    suspend fun saveReaderStyle(style: ReaderStyle) {
        context.dataStore.edit { pref ->
            pref[styleKey] = json.encodeToString(ReaderStyle.serializer(), style)
        }
    }

    suspend fun saveProgress(progress: Map<String, ReadingProgress>) {
        context.dataStore.edit { pref ->
            pref[progressKey] = json.encodeToString(
                MapSerializer(String.serializer(), ReadingProgress.serializer()),
                progress
            )
        }
    }

    suspend fun saveCatalogSyncedBooks(bookIds: Set<String>) {
        context.dataStore.edit { pref ->
            pref[catalogSyncedKey] = json.encodeToString(ListSerializer(String.serializer()), bookIds.toList())
        }
    }
}
