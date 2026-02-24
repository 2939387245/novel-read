package com.example.novel.network

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenLibraryApi {
    @GET("search.json")
    suspend fun search(
        @Query("q") query: String,
        @Query("limit") limit: Int = 20
    ): OpenLibraryResponse
}

data class OpenLibraryResponse(
    @Json(name = "docs")
    val docs: List<OpenLibraryDoc> = emptyList()
)

data class OpenLibraryDoc(
    @Json(name = "key")
    val key: String? = null,
    @Json(name = "title")
    val title: String? = null,
    @Json(name = "author_name")
    val authorNames: List<String>? = null,
    @Json(name = "first_sentence")
    val firstSentence: List<String>? = null,
    @Json(name = "cover_i")
    val coverId: Int? = null
)
