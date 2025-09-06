package com.romm.android.network

import com.romm.android.data.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import androidx.documentfile.provider.DocumentFile
import android.content.Context
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
        @Query("search_term") searchTerm: String? = null,
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
    
    @GET("api/saves")
    suspend fun getSaves(
        @Query("rom_id") romId: Int? = null,
        @Query("platform_id") platformId: Int? = null
    ): List<SaveFile>
    
    @GET("api/saves/{id}")
    suspend fun getSave(@Path("id") id: Int): SaveFile
    
    @Streaming
    @GET("api/raw/assets/{path}")
    suspend fun downloadAsset(
        @Path("path", encoded = true) path: String
    ): Response<ResponseBody>
    
    @GET("api/states")
    suspend fun getStates(
        @Query("rom_id") romId: Int? = null,
        @Query("platform_id") platformId: Int? = null
    ): List<SaveState>
    
    @GET("api/states/{id}")
    suspend fun getState(@Path("id") id: Int): SaveState
    
    @Multipart
    @POST("api/saves")
    suspend fun uploadSaveFile(
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String?,
        @Part file: MultipartBody.Part
    ): SaveFile
    
    @Multipart
    @POST("api/states") 
    suspend fun uploadSaveState(
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String?,
        @Part file: MultipartBody.Part
    ): SaveState
    
    @Multipart
    @PUT("api/saves/{id}")
    suspend fun updateSaveFile(
        @Path("id") id: Int,
        @Part file: MultipartBody.Part
    ): SaveFile
    
    @Multipart
    @PUT("api/states/{id}")
    suspend fun updateSaveState(
        @Path("id") id: Int,
        @Part file: MultipartBody.Part
    ): SaveState
    
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
    private val settingsRepository: SettingsRepository,
    private val applicationContext: Context
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
            val request = if (settings.username.isNotEmpty() || settings.password.isNotEmpty()) {
                val credential = Credentials.basic(settings.username, settings.password)
                chain.request().newBuilder()
                    .header("Authorization", credential)
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        
        // Add logging interceptor to see what's happening (but limit body logging for downloads)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
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
    
    suspend fun getGames(
        platformId: Int? = null, 
        collectionId: Int? = null,
        searchTerm: String? = null,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<Game> {
        val allGames = mutableListOf<Game>()
        var offset = 0
        val limit = 1000
        
        do {
            val response = getApi().getRoms(
                platformId = platformId, 
                collectionId = collectionId,
                searchTerm = searchTerm,
                limit = limit,
                offset = offset
            )
            allGames.addAll(response.items)
            offset += response.items.size
            
            // Report progress
            onProgress?.invoke(allGames.size, response.total)
            
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
    
    suspend fun getSaves(
        romId: Int? = null,
        platformId: Int? = null,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<SaveFile> {
        val saves = getApi().getSaves(
            romId = romId,
            platformId = platformId
        )
        onProgress?.invoke(saves.size, saves.size)
        return saves
    }
    
    suspend fun getSave(id: Int): SaveFile {
        return getApi().getSave(id)
    }
    
    suspend fun downloadSaveFile(saveFile: com.romm.android.data.SaveFile): Response<ResponseBody> {
        // The download_path already includes /api/raw/assets/, so we need to strip that prefix
        val assetPath = saveFile.download_path?.removePrefix("/api/raw/assets/") 
            ?: saveFile.file_path
        return getApi().downloadAsset(assetPath)
    }
    
    suspend fun getStates(
        romId: Int? = null,
        platformId: Int? = null,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<SaveState> {
        val states = getApi().getStates(
            romId = romId,
            platformId = platformId
        )
        onProgress?.invoke(states.size, states.size)
        return states
    }
    
    suspend fun getState(id: Int): SaveState {
        return getApi().getState(id)
    }
    
    suspend fun downloadSaveState(saveState: com.romm.android.data.SaveState): Response<ResponseBody> {
        // The download_path already includes /api/raw/assets/, so we need to strip that prefix
        val assetPath = saveState.download_path?.removePrefix("/api/raw/assets/") 
            ?: saveState.file_path
        return getApi().downloadAsset(assetPath)
    }
    
    suspend fun uploadSaveFile(
        romId: Int,
        emulator: String?,
        documentFile: DocumentFile
    ): SaveFile {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        android.util.Log.d("RomMApiService", "Attempting to upload save file: $fileName")
        android.util.Log.d("RomMApiService", "DocumentFile URI: ${documentFile.uri}")
        android.util.Log.d("RomMApiService", "DocumentFile exists: ${documentFile.exists()}")
        android.util.Log.d("RomMApiService", "DocumentFile isFile: ${documentFile.isFile}")
        android.util.Log.d("RomMApiService", "DocumentFile canRead: ${documentFile.canRead()}")
        android.util.Log.d("RomMApiService", "DocumentFile length: ${documentFile.length()}")
        android.util.Log.d("RomMApiService", "DocumentFile type: ${documentFile.type}")
        
        // Try to get more info about URI permissions
        val uriString = documentFile.uri.toString()
        android.util.Log.d("RomMApiService", "URI string: $uriString")
        android.util.Log.d("RomMApiService", "URI scheme: ${documentFile.uri.scheme}")
        android.util.Log.d("RomMApiService", "URI authority: ${documentFile.uri.authority}")
        android.util.Log.d("RomMApiService", "URI path: ${documentFile.uri.path}")
        
        // Check if we have permissions to read this URI
        val persistedUris = applicationContext.contentResolver.persistedUriPermissions
        android.util.Log.d("RomMApiService", "Persisted URI permissions: ${persistedUris.size}")
        persistedUris.forEach { permission ->
            android.util.Log.d("RomMApiService", "  Permission: ${permission.uri} (read: ${permission.isReadPermission}, write: ${permission.isWritePermission})")
        }
        
        // Try multiple approaches to read the file content
        val bytes = try {
            // First approach: Direct content resolver access
            android.util.Log.d("RomMApiService", "Attempting direct ContentResolver access...")
            val inputStream = applicationContext.contentResolver.openInputStream(documentFile.uri)
            
            if (inputStream == null) {
                android.util.Log.e("RomMApiService", "Input stream is null for ${documentFile.uri}")
                throw IllegalArgumentException("Cannot open file input stream for ${documentFile.uri}")
            }
            
            val data = inputStream.use { stream ->
                android.util.Log.d("RomMApiService", "Reading bytes from input stream...")
                val result = try {
                    stream.readBytes()
                } catch (e: Exception) {
                    android.util.Log.e("RomMApiService", "Error reading bytes from stream", e)
                    throw IllegalArgumentException("Error reading file content: ${e.message}")
                }
                
                android.util.Log.d("RomMApiService", "Read ${result.size} bytes from stream")
                
                // Log first few bytes to debug what we're actually reading
                if (result.size > 0) {
                    val preview = result.take(50).joinToString(" ") { byte -> "%02x".format(byte) }
                    android.util.Log.d("RomMApiService", "First 50 bytes (hex): $preview")
                    
                    // Also check if it's text (like JSON error)
                    val text = try {
                        String(result.take(100).toByteArray(), Charsets.UTF_8)
                    } catch (e: Exception) {
                        "binary data"
                    }
                    android.util.Log.d("RomMApiService", "Content preview (as text): $text")
                }
                
                result
            }
            
            // Validate that we didn't get an error response
            if (data.isNotEmpty()) {
                val content = try {
                    String(data, Charsets.UTF_8)
                } catch (e: Exception) {
                    null
                }
                
                if (content != null && content.contains("status_code") && content.contains("Asset not found")) {
                    android.util.Log.e("RomMApiService", "Got JSON error response instead of file content: $content")
                    throw IllegalArgumentException("Storage Access Framework returned JSON error instead of file content")
                }
            }
            
            data
            
        } catch (e: Exception) {
            android.util.Log.e("RomMApiService", "Primary file access failed: ${e.message}")
            
            // Second approach: Try alternative URI construction
            android.util.Log.d("RomMApiService", "Attempting alternative URI construction...")
            try {
                // Try to construct a direct document URI instead of using the tree-based navigation
                val uriString = documentFile.uri.toString()
                android.util.Log.d("RomMApiService", "Original URI: $uriString")
                
                // Convert tree-based URI to document URI if needed
                val alternativeUri = if (uriString.contains("/tree/") && uriString.contains("/document/")) {
                    // Extract the document part and create a direct document URI
                    val documentPart = uriString.substringAfter("/document/")
                    val authority = documentFile.uri.authority
                    val directUri = android.net.Uri.parse("content://$authority/document/$documentPart")
                    android.util.Log.d("RomMApiService", "Trying direct document URI: $directUri")
                    directUri
                } else {
                    documentFile.uri
                }
                
                val alternativeInputStream = applicationContext.contentResolver.openInputStream(alternativeUri)
                if (alternativeInputStream == null) {
                    android.util.Log.e("RomMApiService", "Alternative input stream is also null")
                    throw IllegalArgumentException("Cannot open file input stream with alternative approach")
                }
                
                val alternativeData = alternativeInputStream.use { stream ->
                    android.util.Log.d("RomMApiService", "Reading bytes from alternative input stream...")
                    stream.readBytes()
                }
                
                android.util.Log.d("RomMApiService", "Alternative approach succeeded, read ${alternativeData.size} bytes")
                alternativeData
                
            } catch (alternativeException: Exception) {
                android.util.Log.e("RomMApiService", "Alternative approach also failed: ${alternativeException.message}")
                throw IllegalArgumentException("Both primary and alternative file access methods failed. Original error: ${e.message}, Alternative error: ${alternativeException.message}")
            }
        }
        
        android.util.Log.d("RomMApiService", "Final byte array size: ${bytes.size}")
        
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        
        val multipartBody = MultipartBody.Part.createFormData(
            "file",
            fileName,
            requestBody
        )
        
        android.util.Log.d("RomMApiService", "Created multipart body with name 'file' for $fileName")
        
        return getApi().uploadSaveFile(romId, emulator, multipartBody)
    }
    
    suspend fun uploadSaveState(
        romId: Int,
        emulator: String?,
        documentFile: DocumentFile
    ): SaveState {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        val inputStream = applicationContext.contentResolver.openInputStream(documentFile.uri)
            ?: throw IllegalArgumentException("Cannot open file input stream")
        
        val bytes = inputStream.use { it.readBytes() }
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        
        val multipartBody = MultipartBody.Part.createFormData(
            "file",
            fileName,
            requestBody
        )
        
        return getApi().uploadSaveState(romId, emulator, multipartBody)
    }
    
    suspend fun updateSaveFile(
        saveFileId: Int,
        documentFile: DocumentFile
    ): SaveFile {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        val inputStream = applicationContext.contentResolver.openInputStream(documentFile.uri)
            ?: throw IllegalArgumentException("Cannot open file input stream")
        
        val bytes = inputStream.use { it.readBytes() }
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        
        val multipartBody = MultipartBody.Part.createFormData(
            "file",
            fileName,
            requestBody
        )
        
        return getApi().updateSaveFile(saveFileId, multipartBody)
    }
    
    suspend fun updateSaveState(
        saveStateId: Int,
        documentFile: DocumentFile
    ): SaveState {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        val inputStream = applicationContext.contentResolver.openInputStream(documentFile.uri)
            ?: throw IllegalArgumentException("Cannot open file input stream")
        
        val bytes = inputStream.use { it.readBytes() }
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        
        val multipartBody = MultipartBody.Part.createFormData(
            "file",
            fileName,
            requestBody
        )
        
        return getApi().updateSaveState(saveStateId, multipartBody)
    }
}
