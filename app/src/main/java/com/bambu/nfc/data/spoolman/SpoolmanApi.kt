package com.bambu.nfc.data.spoolman

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Spoolman REST API v1 — no auth (the homelab instance is LAN-only by design). */
interface SpoolmanApi {
    @GET("api/v1/info")
    suspend fun info(): JsonObject

    @GET("api/v1/field/{entity}")
    suspend fun fields(@Path("entity") entity: String): List<ExtraField>

    /** Adds or updates one extra field; returns the entity's full field list. */
    @POST("api/v1/field/{entity}/{key}")
    suspend fun addField(
        @Path("entity") entity: String,
        @Path("key") key: String,
        @Body params: ExtraFieldParams,
    ): List<ExtraField>

    @GET("api/v1/vendor")
    suspend fun vendors(): List<Vendor>

    @POST("api/v1/vendor")
    suspend fun createVendor(@Body body: VendorCreate): Vendor

    @GET("api/v1/filament")
    suspend fun filaments(): List<Filament>

    @POST("api/v1/filament")
    suspend fun createFilament(@Body body: FilamentCreate): Filament

    @GET("api/v1/filament/{id}")
    suspend fun filament(@Path("id") id: Long): Filament

    @PATCH("api/v1/filament/{id}")
    suspend fun patchFilament(@Path("id") id: Long, @Body body: FilamentPatch): Filament

    @GET("api/v1/spool")
    suspend fun spools(@Query("allow_archived") allowArchived: Boolean = true): List<Spool>

    @GET("api/v1/spool/{id}")
    suspend fun spool(@Path("id") id: Long): Spool

    @POST("api/v1/spool")
    suspend fun createSpool(@Body body: SpoolCreate): Spool

    @PATCH("api/v1/spool/{id}")
    suspend fun patchSpool(@Path("id") id: Long, @Body body: SpoolPatch): Spool

    @PUT("api/v1/spool/{id}/use")
    suspend fun use(@Path("id") id: Long, @Body body: SpoolUse): Spool

    @PUT("api/v1/spool/{id}/measure")
    suspend fun measure(@Path("id") id: Long, @Body body: SpoolMeasure): Spool
}

@OptIn(ExperimentalSerializationApi::class)
val SpoolmanJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false // omit nulls so PATCH only touches the fields we set
    encodeDefaults = true
    coerceInputValues = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/** Where [SpoolmanRepository] gets its API from; a fake in-memory Spoolman in tests. */
interface SpoolmanApiSource {
    fun api(): SpoolmanApi
    fun currentBaseUrl(): String
}

/** Builds (and caches) a Retrofit client for whatever Spoolman base URL is configured right now. */
class SpoolmanApiProvider(
    private val okHttp: OkHttpClient,
    private val baseUrl: () -> String,
) : SpoolmanApiSource {
    private var cached: Pair<String, SpoolmanApi>? = null

    @Synchronized
    override fun api(): SpoolmanApi {
        val url = normalize(baseUrl())
        cached?.let { (u, api) -> if (u == url) return api }
        val api = Retrofit.Builder()
            .baseUrl(url)
            .client(okHttp)
            .addConverterFactory(SpoolmanJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpoolmanApi::class.java)
        cached = url to api
        return api
    }

    override fun currentBaseUrl(): String = normalize(baseUrl())

    companion object {
        fun normalize(url: String): String {
            val trimmed = url.trim().removeSuffix("/api/v1").removeSuffix("/api/v1/")
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }
    }
}
