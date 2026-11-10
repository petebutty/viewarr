package io.github.petebutty.viewarr.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface DispatcharrApi {
    @GET("api/core/version/")
    suspend fun version(): VersionDto

    @POST("api/accounts/token/")
    suspend fun token(@Body body: TokenRequest): TokenDto

    @GET("api/accounts/api-keys/")
    suspend fun apiKey(@Header("Authorization") bearer: String): ApiKeyDto

    @POST("api/accounts/api-keys/generate/")
    suspend fun generateApiKey(@Header("Authorization") bearer: String): ApiKeyDto

    @GET("api/accounts/users/me/")
    suspend fun me(): UserDto

    @GET("api/channels/channels/")
    suspend fun channels(): List<ChannelDto>

    @GET("api/channels/groups/")
    suspend fun groups(): List<GroupDto>

    @GET("api/vod/movies/")
    suspend fun vodMovies(@Query("page_size") pageSize: Int = 1): CountPage

    @GET("api/vod/series/")
    suspend fun vodSeries(@Query("page_size") pageSize: Int = 1): CountPage

    @Streaming
    @GET("api/epg/epgdata/")
    suspend fun epgData(): ResponseBody

    @GET("api/epg/programs/search/")
    suspend fun searchPrograms(
        @Query("tvg_id") tvgId: String,
        @Query("start_after") startAfter: String,
        @Query("start_before") startBefore: String,
        @Query("fields") fields: String = "title,sub_title,description,start_time,end_time,tvg_id,custom_properties",
        @Query("page_size") pageSize: Int = 500,
    ): ProgramSearchPage

    /** Catch-up search: programmes whose title matches [title] that started after [startAfter] and have ended. */
    @GET("api/epg/programs/search/")
    suspend fun searchPast(
        @Query("title") title: String,
        @Query("start_after") startAfter: String,
        @Query("end_before") endBefore: String,
        @Query("fields") fields: String = "title,sub_title,description,start_time,end_time,tvg_id,custom_properties",
        @Query("page_size") pageSize: Int = 100,
    ): ProgramSearchPage

    @GET
    suspend fun searchPage(@Url url: String): ProgramSearchPage

    @Streaming
    @GET("api/epg/grid/")
    suspend fun grid(@Query("start") start: String, @Query("end") end: String): ResponseBody
}

@Singleton
class ApiFactory @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    fun create(baseUrl: String, apiKey: String? = null, quick: Boolean = false): DispatcharrApi {
        val builder = client.newBuilder()
        if (quick) builder.callTimeout(6, TimeUnit.SECONDS)
        if (apiKey != null) {
            builder.addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-API-Key", apiKey).build())
            }
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(builder.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DispatcharrApi::class.java)
    }
}
