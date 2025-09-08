package com.romm.android.network

import com.romm.android.data.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
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
    
    @Streaming
    @GET("{path}")
    suspend fun downloadFromPath(
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
        @Part saveFile: MultipartBody.Part
    ): SaveFile
    
    @Multipart
    @POST("api/states") 
    suspend fun uploadSaveState(
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String?,
        @Part stateFile: MultipartBody.Part
    ): SaveState
    
    @PUT("api/saves/{id}")
    suspend fun updateSaveFile(
        @Path("id") id: Int,
        @Body file: RequestBody
    ): SaveFile
    
    @PUT("api/states/{id}")
    suspend fun updateSaveState(
        @Path("id") id: Int,
        @Body file: RequestBody
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
        // Use the download_path directly as provided by the server metadata
        val downloadPath = saveFile.download_path ?: "/api/raw/assets/${saveFile.file_path}"
        android.util.Log.d("RomMApiService", "Downloading save file from: $downloadPath")
        return getApi().downloadFromPath(downloadPath)
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
        // Use the download_path directly as provided by the server metadata
        val downloadPath = saveState.download_path ?: "/api/raw/assets/${saveState.file_path}"
        android.util.Log.d("RomMApiService", "Downloading save state from: $downloadPath")
        return getApi().downloadFromPath(downloadPath)
    }
    
    suspend fun uploadSaveFile(
        romId: Int,
        emulator: String?,
        documentFile: DocumentFile
    ): SaveFile {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        android.util.Log.d("RomMApiService", "Attempting to upload save file: $fileName")
        android.util.Log.d("RomMApiService", "DocumentFile URI: ${documentFile.uri}")
        android.util.Log.d("RomMApiService", "ROM ID: $romId")
        android.util.Log.d("RomMApiService", "Emulator: $emulator")
        
        // Use standard Storage Access Framework approach
        val bytes = try {
            applicationContext.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                inputStream.readBytes()
            } ?: throw IllegalArgumentException("Could not open input stream")
        } catch (e: Exception) {
            android.util.Log.e("RomMApiService", "Failed to read file: ${e.message}")
            throw IllegalArgumentException("Failed to read file: ${e.message}")
        }
        
        android.util.Log.d("RomMApiService", "Read ${bytes.size} bytes from file")
        
        // Debug: Check if we're getting actual binary data or JSON error
        if (bytes.size > 10) {
            val preview = bytes.take(20).joinToString(" ") { byte -> "%02x".format(byte) }
            android.util.Log.d("RomMApiService", "First 20 bytes (hex): $preview")
            
            val text = try {
                String(bytes.take(100).toByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                "binary data"
            }
            android.util.Log.d("RomMApiService", "Content preview (first 100 chars): $text")
            
            // Check if we're still getting JSON error
            if (text.contains("status_code") && text.contains("Asset not found")) {
                android.util.Log.e("RomMApiService", "File content is JSON error instead of save data!")
                throw IllegalArgumentException("File contains error response instead of save data: $text")
            }
        }
        
        // Create filename with file's last modified timestamp
        val baseNameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val lastModifiedTime = documentFile.lastModified()
        val timestamp = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(lastModifiedTime),
            java.time.ZoneId.systemDefault()
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS"))
        val timestampedFileName = "$baseNameWithoutExt [$timestamp]${if (extension.isNotEmpty()) ".$extension" else ""}"
        
        // Create multipart body with 'saveFile' field name (matching Python code)
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        val multipartBody = MultipartBody.Part.createFormData(
            name = "saveFile",
            filename = timestampedFileName,
            body = requestBody
        )
        
        android.util.Log.d("RomMApiService", "Created multipart body:")
        android.util.Log.d("RomMApiService", "  - Field name: saveFile")
        android.util.Log.d("RomMApiService", "  - Original filename: $fileName")
        android.util.Log.d("RomMApiService", "  - Timestamped filename: $timestampedFileName")
        android.util.Log.d("RomMApiService", "  - Content type: application/octet-stream")
        android.util.Log.d("RomMApiService", "  - Content length: ${bytes.size} bytes")
        
        // Check API endpoint parameters
        android.util.Log.d("RomMApiService", "API call parameters:")
        android.util.Log.d("RomMApiService", "  - rom_id: $romId")
        android.util.Log.d("RomMApiService", "  - emulator: $emulator")
        
        return getApi().uploadSaveFile(romId, emulator, multipartBody)
    }
    
    suspend fun uploadSaveState(
        romId: Int,
        emulator: String?,
        documentFile: DocumentFile
    ): SaveState {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        // Use ParcelFileDescriptor approach for consistency with uploadSaveFile
        val bytes = try {
            val pfd = applicationContext.contentResolver.openFileDescriptor(documentFile.uri, "r")
                ?: throw IllegalArgumentException("Cannot open file descriptor")
            
            pfd.use { fileDescriptor ->
                val fileInputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
                fileInputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
            android.util.Log.e("RomMApiService", "Failed to read save state file: ${e.message}")
            throw IllegalArgumentException("Failed to read save state file: ${e.message}")
        }
        
        // Create filename with file's last modified timestamp
        val baseNameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val lastModifiedTime = documentFile.lastModified()
        val timestamp = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(lastModifiedTime),
            java.time.ZoneId.systemDefault()
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS"))
        val timestampedFileName = "$baseNameWithoutExt [$timestamp]${if (extension.isNotEmpty()) ".$extension" else ""}"
        
        // Create multipart body with 'stateFile' field name (matching Python code)
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        val multipartBody = MultipartBody.Part.createFormData(
            name = "stateFile",
            filename = timestampedFileName,
            body = requestBody
        )
        
        return getApi().uploadSaveState(romId, emulator, multipartBody)
    }
    
    suspend fun updateSaveFile(
        saveFileId: Int,
        documentFile: DocumentFile
    ): SaveFile {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        // Use ParcelFileDescriptor approach for consistency
        val bytes = try {
            val pfd = applicationContext.contentResolver.openFileDescriptor(documentFile.uri, "r")
                ?: throw IllegalArgumentException("Cannot open file descriptor")
            
            pfd.use { fileDescriptor ->
                val fileInputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
                fileInputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
            android.util.Log.e("RomMApiService", "Failed to read save file for update: ${e.message}")
            throw IllegalArgumentException("Failed to read save file for update: ${e.message}")
        }
        
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        
        return getApi().updateSaveFile(saveFileId, requestBody)
    }
    
    suspend fun updateSaveState(
        saveStateId: Int,
        documentFile: DocumentFile
    ): SaveState {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        // Use ParcelFileDescriptor approach for consistency
        val bytes = try {
            val pfd = applicationContext.contentResolver.openFileDescriptor(documentFile.uri, "r")
                ?: throw IllegalArgumentException("Cannot open file descriptor")
            
            pfd.use { fileDescriptor ->
                val fileInputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
                fileInputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
            android.util.Log.e("RomMApiService", "Failed to read save state for update: ${e.message}")
            throw IllegalArgumentException("Failed to read save state for update: ${e.message}")
        }
        
        val requestBody = bytes.toRequestBody("application/octet-stream".toMediaType())
        
        return getApi().updateSaveState(saveStateId, requestBody)
    }
    
}
