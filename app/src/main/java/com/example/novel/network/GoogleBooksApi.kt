package com.example.novel.network

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

interface GoogleBooksApi {
    @GET("volumes")
    suspend fun searchZh(
        @Query("q") query: String,
        @Query("langRestrict") langRestrict: String = "zh",
        @Query("maxResults") maxResults: Int = 20,
        @Query("printType") printType: String = "books"
    ): GoogleBooksResponse
}

data class GoogleBooksResponse(
    @Json(name = "items")
    val items: List<GoogleBookItem> = emptyList()
)

data class GoogleBookItem(
    @Json(name = "id")
    val id: String,
    @Json(name = "volumeInfo")
    val volumeInfo: GoogleVolumeInfo
)

data class GoogleVolumeInfo(
    @Json(name = "title")
    val title: String? = null,
    @Json(name = "authors")
    val authors: List<String>? = null,
    @Json(name = "description")
    val description: String? = null,
    @Json(name = "imageLinks")
    val imageLinks: GoogleImageLinks? = null,
    @Json(name = "previewLink")
    val previewLink: String? = null
)

data class GoogleImageLinks(
    @Json(name = "thumbnail")
    val thumbnail: String? = null
)
