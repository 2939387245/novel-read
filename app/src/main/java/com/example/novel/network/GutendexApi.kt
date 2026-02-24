package com.example.novel.network

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

interface GutendexApi {
    @GET("books")
    suspend fun search(
        @Query("search") query: String
    ): GutendexResponse
}

data class GutendexResponse(
    @Json(name = "results")
    val results: List<GutendexBook> = emptyList()
)

data class GutendexBook(
    @Json(name = "id")
    val id: Int,
    @Json(name = "title")
    val title: String,
    @Json(name = "authors")
    val authors: List<GutendexAuthor> = emptyList(),
    @Json(name = "summaries")
    val summaries: List<String> = emptyList(),
    @Json(name = "formats")
    val formats: Map<String, String> = emptyMap()
)

data class GutendexAuthor(
    @Json(name = "name")
    val name: String
)
