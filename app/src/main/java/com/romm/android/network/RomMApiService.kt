package com.romm.android.network

import com.romm.android.data.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface RomMApi {
    @GET("api/platforms")
    suspend fun getPlatforms(): List<Platform>
    
    @GET("api/collections")
    suspend fun getCollections(): List<com.romm.android.data.Collection>
    
    @GET("api/roms")
    suspend fun getRoms(
        @Query("platform_id") platformId: Int? = null,
        @Query("collection_id") collectionId: Int? = null,
        @Query("limit") limit: Int = 1000,
        @Query("offset") offset: Int = 0
    ): GameResponse
    
    @GET("api/roms/{id}")
    suspend fun getGame(@Path("id") id: Int): Game
    
    @Streaming
    @GET("api/roms/{id}/content/{fileName}")
    suspend fun downloadGame(
        @Path("id") id: Int,
        @Path("fileName") fileName: String,
        @Query("file_ids") fileIds: String? = null
    ): Response<ResponseBody>
    
    @GET("api/firmware")
    suspend fun getFirmware(@Query("platform_id") platformId: Int): List<Firmware>
    
    @Streaming
    @GET("api/firmware/{id}/content/{fileName}")
    suspend fun downloadFirmware(
        @Path("id") id: Int,
        @Path("fileName") fileName: String
    ): Response<ResponseBody>
    
    @POST("api/login")
    suspend fun login(): LoginResponse
    
    @GET("api/raw/assets/{path}")
    suspend fun getCoverImage(@Path("path", encoded = true) path: String): Response<ResponseBody>
}

data class GameResponse(
    val items: List<Game>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class LoginResponse(
    val msg: String
)

@Singleton
class RomMApiService @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private var api: RomMApi? = null
    
    private suspend fun getApi(): RomMApi {
        val settings = settingsRepository.getCurrentSettings()
        
        if (api == null || shouldRecreateApi(settings)) {
            api = createApi(settings)
        }
        
        return api!!
    }
    
    private var lastSettings: AppSettings? = null
    
    private fun shouldRecreateApi(settings: AppSettings): Boolean {
        return lastSettings != settings
    }
    
    private fun createApi(settings: AppSettings): RomMApi {
        lastSettings = settings
        
        val authInterceptor = Interceptor { chain ->
            val credential = Credentials.basic(settings.username, settings.password)
            val request = chain.request().newBuilder()
                .header("Authorization", credential)
                .build()
            chain.proceed(request)
        }
        
        // Add logging interceptor to see what's happening (but limit body logging for downloads)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        
        // Create a custom connection spec that allows cleartext
        val clearTextConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.CLEARTEXT)
            .build()
        
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES) // Increased for large downloads
            .writeTimeout(5, TimeUnit.MINUTES) // Increased for large downloads
            .callTimeout(30, TimeUnit.MINUTES) // Added overall call timeout
            // Explicitly allow cleartext connections
            .connectionSpecs(listOf(clearTextConnectionSpec, ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            // Force allow all hostnames and certificates (for HTTP)
            .hostnameVerifier { _, _ -> true }
            // Configure connection pool for better memory management
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
        
        // Use the host as-is (user provides full URL with protocol)
        val baseUrl = if (settings.host.endsWith("/")) {
            settings.host
        } else {
            "${settings.host}/"
        }
        
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        return retrofit.create(RomMApi::class.java)
    }
    
    suspend fun getPlatforms(): List<Platform> {
        return getApi().getPlatforms()
    }
    
    suspend fun getCollections(): List<com.romm.android.data.Collection> {
        return getApi().getCollections()
    }
    
    suspend fun getGames(platformId: Int? = null, collectionId: Int? = null): List<Game> {
        val allGames = mutableListOf<Game>()
        var offset = 0
        val limit = 1000
        
        do {
            val response = getApi().getRoms(
                platformId = platformId, 
                collectionId = collectionId,
                limit = limit,
                offset = offset
            )
            allGames.addAll(response.items)
            offset += response.items.size
        } while (response.items.size == limit && offset < response.total)
        
        return allGames
    }
    
    suspend fun getGame(id: Int): Game {
        return getApi().getGame(id)
    }
    
    suspend fun downloadGame(
        id: Int,
        fileName: String,
        fileIds: String? = null
    ): Response<ResponseBody> {
        return getApi().downloadGame(id, fileName, fileIds)
    }
    
    suspend fun getFirmware(platformId: Int): List<Firmware> {
        return getApi().getFirmware(platformId)
    }
    
    suspend fun downloadFirmware(id: Int, fileName: String): Response<ResponseBody> {
        return getApi().downloadFirmware(id, fileName)
    }
    
    suspend fun getCoverImage(path: String): Response<ResponseBody> {
        return getApi().getCoverImage(path)
    }
}
