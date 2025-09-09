package com.romm.android.network

import com.romm.android.data.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okio.source
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import androidx.documentfile.provider.DocumentFile
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    
    @GET("api/roms/{id}")
    suspend fun getDetailedGame(@Path("id") id: Int): DetailedGame
    
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
    
    @Streaming
    @GET
    suspend fun downloadFromUrl(
        @Url url: String
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
    
    @Multipart
    @PUT("api/saves/{id}")
    suspend fun updateSaveFileMultipart(
        @Path("id") id: Int,
        @Part saveFile: MultipartBody.Part
    ): SaveFile
    
    @PUT("api/states/{id}")
    suspend fun updateSaveState(
        @Path("id") id: Int,
        @Body file: RequestBody
    ): SaveState
    
    @Multipart
    @PUT("api/states/{id}")
    suspend fun updateSaveStateMultipart(
        @Path("id") id: Int,
        @Part stateFile: MultipartBody.Part
    ): SaveState
    
    @POST("api/saves/delete")
    suspend fun deleteSaves(@Body request: DeleteSavesRequest): Response<ResponseBody>
    
    @POST("api/states/delete")
    suspend fun deleteSaveStates(@Body request: DeleteStatesRequest): Response<ResponseBody>
    
    @Multipart
    @POST("api/screenshots")
    suspend fun uploadScreenshot(
        @Query("rom_id") romId: Int,
        @Query("state_id") stateId: Int,
        @Part screenshotFile: MultipartBody.Part
    ): Screenshot
    
    @GET("api/screenshots")
    suspend fun getScreenshots(
        @Query("rom_id") romId: Int? = null,
        @Query("state_id") stateId: Int? = null
    ): List<Screenshot>
    
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

data class DeleteSavesRequest(
    val saves: List<Int>
)

data class DeleteStatesRequest(
    val states: List<Int>
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
        
        // Add logging interceptor - use HEADERS level to avoid logging large upload bodies
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
        val rawDownloadPath = saveFile.download_path ?: "/api/raw/assets/${saveFile.file_path}"
        
        // URL encode the path properly
        val encodedPath = encodeDownloadPath(rawDownloadPath)
        
        // Get the base URL from current settings to construct full URL
        val settings = settingsRepository.getCurrentSettings()
        val baseUrl = if (settings.host.endsWith("/")) settings.host.dropLast(1) else settings.host
        val fullUrl = "$baseUrl$encodedPath"
        
        android.util.Log.d("RomMApiService", "Raw download path: $rawDownloadPath")
        android.util.Log.d("RomMApiService", "Encoded download path: $encodedPath")
        android.util.Log.d("RomMApiService", "Full URL: $fullUrl")
        
        return getApi().downloadFromUrl(fullUrl)
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
        val rawDownloadPath = saveState.download_path ?: "/api/raw/assets/${saveState.file_path}"
        
        // URL encode the path properly
        val encodedPath = encodeDownloadPath(rawDownloadPath)
        
        // Get the base URL from current settings to construct full URL
        val settings = settingsRepository.getCurrentSettings()
        val baseUrl = if (settings.host.endsWith("/")) settings.host.dropLast(1) else settings.host
        val fullUrl = "$baseUrl$encodedPath"
        
        android.util.Log.d("RomMApiService", "Raw download path: $rawDownloadPath")
        android.util.Log.d("RomMApiService", "Encoded download path: $encodedPath")
        android.util.Log.d("RomMApiService", "Full URL: $fullUrl")
        
        return getApi().downloadFromUrl(fullUrl)
    }
    
    suspend fun downloadSaveFileFromRomMetadata(romId: Int, fileExtension: String): Response<ResponseBody>? {
        android.util.Log.d("RomMApiService", "Getting ROM details for ROM ID: $romId")
        val detailedGame = getApi().getDetailedGame(romId)
        
        // Find matching save file by extension
        val matchingFiles = detailedGame.user_saves?.filter { saveFile ->
            saveFile.file_name.lowercase().endsWith(fileExtension.lowercase())
        } ?: emptyList()
        
        if (matchingFiles.isEmpty()) {
            android.util.Log.w("RomMApiService", "No save files with extension $fileExtension found for ROM $romId")
            return null
        }
        
        // Sort by filename (later timestamps last) and pick the most recent
        val latestFile = matchingFiles.sortedByDescending { it.file_name }.first()
        android.util.Log.d("RomMApiService", "Latest save file: ${latestFile.file_name}")
        android.util.Log.d("RomMApiService", "Expected size: ${latestFile.file_size_bytes} bytes")
        
        // Use download_path from metadata
        val downloadPath = latestFile.download_path ?: "/api/raw/assets/${latestFile.file_path}"
        android.util.Log.d("RomMApiService", "Downloading from: $downloadPath")
        
        return getApi().downloadFromPath(downloadPath)
    }
    
    suspend fun downloadSaveStateFromRomMetadata(romId: Int, fileExtension: String): Response<ResponseBody>? {
        android.util.Log.d("RomMApiService", "Getting ROM details for ROM ID: $romId")
        val detailedGame = getApi().getDetailedGame(romId)
        
        // Find matching save state by extension
        val matchingFiles = detailedGame.user_states?.filter { saveState ->
            saveState.file_name.lowercase().endsWith(fileExtension.lowercase())
        } ?: emptyList()
        
        if (matchingFiles.isEmpty()) {
            android.util.Log.w("RomMApiService", "No save states with extension $fileExtension found for ROM $romId")
            return null
        }
        
        // Sort by filename (later timestamps last) and pick the most recent
        val latestFile = matchingFiles.sortedByDescending { it.file_name }.first()
        android.util.Log.d("RomMApiService", "Latest save state: ${latestFile.file_name}")
        android.util.Log.d("RomMApiService", "Expected size: ${latestFile.file_size_bytes} bytes")
        
        // Use download_path from metadata
        val downloadPath = latestFile.download_path ?: "/api/raw/assets/${latestFile.file_path}"
        android.util.Log.d("RomMApiService", "Downloading from: $downloadPath")
        
        return getApi().downloadFromPath(downloadPath)
    }
    
    suspend fun uploadSaveFile(
        romId: Int,
        emulator: String?,
        documentFile: DocumentFile,
        onProgress: ((Long, Long) -> Unit)? = null
    ): SaveFile {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        android.util.Log.d("RomMApiService", "Uploading save file: $fileName (${documentFile.length()} bytes)")
        
        // Create streaming request body with progress callback
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            
            override fun contentLength() = documentFile.length()
            
            override fun writeTo(sink: okio.BufferedSink) {
                applicationContext.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                    val totalBytes = contentLength()
                    var bytesWritten = 0L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    onProgress?.invoke(0, totalBytes)
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead
                        onProgress?.invoke(bytesWritten, totalBytes)
                    }
                } ?: throw IllegalArgumentException("Could not open input stream")
            }
        }
        
        // Create filename with android-sync- prefix and timestamp
        val baseNameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val lastModifiedTime = documentFile.lastModified()
        val timestamp = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(lastModifiedTime),
            java.time.ZoneId.systemDefault()
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS"))
        val timestampedFileName = "android-sync-$baseNameWithoutExt [$timestamp]${if (extension.isNotEmpty()) ".$extension" else ""}"
        
        // Create multipart body with 'saveFile' field name (matching Python code)
        val multipartBody = MultipartBody.Part.createFormData(
            name = "saveFile",
            filename = timestampedFileName,
            body = requestBody
        )
        
        android.util.Log.d("RomMApiService", "Created multipart body: $timestampedFileName")
        
        android.util.Log.d("RomMApiService", "API call parameters:")
        android.util.Log.d("RomMApiService", "  - rom_id: $romId")
        android.util.Log.d("RomMApiService", "  - emulator: $emulator")
        android.util.Log.d("RomMApiService", "  - filename (with android-sync- prefix): $timestampedFileName")
        
        return getApi().uploadSaveFile(romId, emulator, multipartBody)
    }
    
    suspend fun uploadSaveState(
        romId: Int,
        emulator: String?,
        documentFile: DocumentFile,
        onProgress: ((Long, Long) -> Unit)? = null
    ): SaveState {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        android.util.Log.d("RomMApiService", "Uploading save state: $fileName (${documentFile.length()} bytes)")
        
        // Create streaming request body with progress callback
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            
            override fun contentLength() = documentFile.length()
            
            override fun writeTo(sink: okio.BufferedSink) {
                applicationContext.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                    val totalBytes = contentLength()
                    var bytesWritten = 0L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    onProgress?.invoke(0, totalBytes)
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead
                        onProgress?.invoke(bytesWritten, totalBytes)
                    }
                } ?: throw IllegalArgumentException("Could not open input stream")
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
        val timestampedFileName = "android-sync-$baseNameWithoutExt [$timestamp]${if (extension.isNotEmpty()) ".$extension" else ""}"
        
        // Create multipart body with 'stateFile' field name (matching Python code)
        val multipartBody = MultipartBody.Part.createFormData(
            name = "stateFile",
            filename = timestampedFileName,
            body = requestBody
        )
        
        android.util.Log.d("RomMApiService", "Save state upload:")
        android.util.Log.d("RomMApiService", "  - emulator: $emulator")
        android.util.Log.d("RomMApiService", "  - filename (with android-sync- prefix): $timestampedFileName")
        
        return getApi().uploadSaveState(romId, emulator, multipartBody)
    }
    
    suspend fun updateSaveFile(
        saveFileId: Int,
        documentFile: DocumentFile
    ): SaveFile {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        android.util.Log.d("RomMApiService", "Updating save file: $fileName (${documentFile.length()} bytes)")
        
        // Create streaming request body
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            
            override fun contentLength() = documentFile.length()
            
            override fun writeTo(sink: okio.BufferedSink) {
                applicationContext.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                    val totalBytes = contentLength()
                    var bytesWritten = 0L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead
                    }
                } ?: throw IllegalArgumentException("Could not open input stream")
            }
        }
        
        // Create filename with android-sync- prefix and timestamp (same as upload)
        val baseNameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val lastModifiedTime = documentFile.lastModified()
        val timestamp = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(lastModifiedTime),
            java.time.ZoneId.systemDefault()
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS"))
        val timestampedFileName = "android-sync-$baseNameWithoutExt [$timestamp]${if (extension.isNotEmpty()) ".$extension" else ""}"
        
        // Use multipart format like upload, but for update endpoint
        val multipartBody = MultipartBody.Part.createFormData(
            name = "saveFile",
            filename = timestampedFileName,
            body = requestBody
        )
        
        android.util.Log.d("RomMApiService", "Update save file with multipart body: $timestampedFileName")
        
        return getApi().updateSaveFileMultipart(saveFileId, multipartBody)
    }
    
    suspend fun updateSaveState(
        saveStateId: Int,
        documentFile: DocumentFile
    ): SaveState {
        val fileName = documentFile.name ?: throw IllegalArgumentException("File name is required")
        
        android.util.Log.d("RomMApiService", "Updating save state: $fileName (${documentFile.length()} bytes)")
        
        // Create streaming request body
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            
            override fun contentLength() = documentFile.length()
            
            override fun writeTo(sink: okio.BufferedSink) {
                applicationContext.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                    val totalBytes = contentLength()
                    var bytesWritten = 0L
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead
                    }
                } ?: throw IllegalArgumentException("Could not open input stream")
            }
        }
        
        // Create filename with android-sync- prefix and timestamp (same as upload)
        val baseNameWithoutExt = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val lastModifiedTime = documentFile.lastModified()
        val timestamp = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(lastModifiedTime),
            java.time.ZoneId.systemDefault()
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS"))
        val timestampedFileName = "android-sync-$baseNameWithoutExt [$timestamp]${if (extension.isNotEmpty()) ".$extension" else ""}"
        
        // Use multipart format like upload, but for update endpoint
        val multipartBody = MultipartBody.Part.createFormData(
            name = "stateFile",
            filename = timestampedFileName,
            body = requestBody
        )
        
        android.util.Log.d("RomMApiService", "Update save state with multipart body: $timestampedFileName")
        
        return getApi().updateSaveStateMultipart(saveStateId, multipartBody)
    }
    
    suspend fun uploadScreenshot(
        romId: Int,
        stateId: Int,
        documentFile: DocumentFile,
        originalSaveStateFileName: String
    ): Screenshot? {
        return try {
            val fileName = documentFile.name ?: throw IllegalArgumentException("Screenshot file name is required")
            
            android.util.Log.d("RomMApiService", "Uploading screenshot: $fileName for save state: $originalSaveStateFileName")
            
            // Extract timestamp from save state filename using improved regex
            val timestamp = extractTimestampFromSaveStateFileName(originalSaveStateFileName)
            if (timestamp == null) {
                android.util.Log.w("RomMApiService", "Could not extract timestamp from save state filename: $originalSaveStateFileName")
                // Use current timestamp as fallback
                val now = java.time.LocalDateTime.now()
                val fallbackTimestamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS"))
                android.util.Log.d("RomMApiService", "Using fallback timestamp: $fallbackTimestamp")
                return uploadScreenshotWithTimestamp(romId, stateId, documentFile, originalSaveStateFileName, fallbackTimestamp)
            }
            
            return uploadScreenshotWithTimestamp(romId, stateId, documentFile, originalSaveStateFileName, timestamp)
            
        } catch (e: Exception) {
            android.util.Log.e("RomMApiService", "Failed to upload screenshot", e)
            null
        }
    }
    
    private suspend fun uploadScreenshotWithTimestamp(
        romId: Int,
        stateId: Int,
        documentFile: DocumentFile,
        originalSaveStateFileName: String,
        timestamp: String
    ): Screenshot? {
        return try {
            android.util.Log.d("RomMApiService", "🕐 Extracted timestamp from save state: $timestamp")
            
            // Extract base name from save state filename (improved logic)
            val baseName = extractBaseNameFromSaveStateFileName(originalSaveStateFileName)
            
            // Create screenshot filename with matching timestamp AND android-sync- prefix
            val screenshotFileName = "android-sync-$baseName [$timestamp].png"
            
            android.util.Log.d("RomMApiService", "📸 Screenshot filename with matching timestamp: $screenshotFileName")
            android.util.Log.d("RomMApiService", "🔗 Linking to save state ID: $stateId")
            
            // Create streaming request body
            val requestBody = object : RequestBody() {
                override fun contentType() = "image/png".toMediaType()
                
                override fun contentLength() = documentFile.length()
                
                override fun writeTo(sink: okio.BufferedSink) {
                    applicationContext.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            sink.write(buffer, 0, bytesRead)
                        }
                    } ?: throw IllegalArgumentException("Could not open input stream")
                }
            }
            
            // Create multipart request with proper form data (matching Python implementation)
            val multipartBodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            
            // Add the screenshot file
            multipartBodyBuilder.addFormDataPart(
                "screenshotFile",
                screenshotFileName,
                requestBody
            )
            
            // Add form data fields (matching Python implementation)
            multipartBodyBuilder.addFormDataPart("rom_id", romId.toString())
            multipartBodyBuilder.addFormDataPart("state_id", stateId.toString())
            multipartBodyBuilder.addFormDataPart("filename", screenshotFileName)
            multipartBodyBuilder.addFormDataPart("file_name", screenshotFileName)
            
            val multipartBody = multipartBodyBuilder.build()
            
            android.util.Log.d("RomMApiService", "📡 Uploading screenshot with parameters:")
            android.util.Log.d("RomMApiService", "  - rom_id: $romId")
            android.util.Log.d("RomMApiService", "  - state_id: $stateId")
            android.util.Log.d("RomMApiService", "  - filename: $screenshotFileName")
            android.util.Log.d("RomMApiService", "  - file_name: $screenshotFileName")
            
            // Use direct HTTP call since we need to send multipart form data differently
            uploadScreenshotMultipart(romId, stateId, multipartBody)
            
        } catch (e: Exception) {
            android.util.Log.e("RomMApiService", "Failed to upload screenshot with timestamp", e)
            null
        }
    }
    
    private suspend fun uploadScreenshotMultipart(
        romId: Int,
        stateId: Int,
        multipartBody: MultipartBody
    ): Screenshot = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        val baseUrl = if (settings.host.endsWith("/")) settings.host.dropLast(1) else settings.host
        val url = "$baseUrl/api/screenshots?rom_id=$romId&state_id=$stateId"
        
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(multipartBody)
            // Add authentication if needed
            .apply {
                if (settings.username.isNotEmpty() || settings.password.isNotEmpty()) {
                    val credential = Credentials.basic(settings.username, settings.password)
                    header("Authorization", credential)
                }
            }
            .build()
        
        // Create a new OkHttpClient with same configuration as the API
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        
        if (settings.username.isNotEmpty() || settings.password.isNotEmpty()) {
            val authInterceptor = Interceptor { chain ->
                val credential = Credentials.basic(settings.username, settings.password)
                val authenticatedRequest = chain.request().newBuilder()
                    .header("Authorization", credential)
                    .build()
                chain.proceed(authenticatedRequest)
            }
            clientBuilder.addInterceptor(authInterceptor)
        }
        
        val client = clientBuilder.build()
        val response = client.newCall(request).execute()
        
        response.use {
            if (it.isSuccessful) {
                android.util.Log.d("RomMApiService", "🎉 Screenshot with matching timestamp uploaded!")
                val responseBody = it.body?.string() ?: ""
                android.util.Log.d("RomMApiService", "📡 Screenshot upload response: ${it.code}")
                android.util.Log.d("RomMApiService", "   Response: ${responseBody.take(200)}")
                
                // Parse response to Screenshot object
                val gson = com.google.gson.Gson()
                gson.fromJson(responseBody, Screenshot::class.java)
            } else {
                val errorBody = it.body?.string() ?: ""
                android.util.Log.e("RomMApiService", "❌ Screenshot upload failed: ${it.code} - ${errorBody.take(200)}")
                throw Exception("Screenshot upload failed with code: ${it.code}")
            }
        }
    }
    
    private fun extractTimestampFromSaveStateFileName(fileName: String): String? {
        // Extract timestamp using improved regex pattern (matching Python implementation)
        // Matches patterns like [YYYY-MM-DD HH-MM-SS-mmm] including colons and various separators
        val timestampPattern = Regex("""\[([0-9\-\s:]+)\]""")
        val matchResult = timestampPattern.find(fileName)
        return matchResult?.groupValues?.get(1)
    }
    
    private fun extractBaseNameFromSaveStateFileName(fileName: String): String {
        // Extract base name (remove timestamp and extension) - matching Python implementation
        
        // First, try to match the pattern before the first bracket (timestamp)
        val baseNamePattern = Regex("""^(.+?)\s*\[""")
        val baseNameMatch = baseNamePattern.find(fileName)
        
        val baseName = if (baseNameMatch != null) {
            baseNameMatch.groupValues[1].trim()
        } else {
            // Fallback: use stem of original filename and remove any existing timestamps
            val withoutExtension = fileName.substringBeforeLast(".")
            // Remove any existing timestamp patterns
            val timestampPattern = Regex("""\s*\[.*?\]""")
            withoutExtension.replace(timestampPattern, "")
        }
        
        // Remove android-sync- prefix if present
        return if (baseName.startsWith("android-sync-")) {
            baseName.removePrefix("android-sync-")
        } else {
            baseName
        }.trim()
    }
    
    suspend fun getScreenshotsForSaveState(stateId: Int): List<Screenshot> {
        return try {
            val screenshots = getApi().getScreenshots(stateId = stateId)
            android.util.Log.d("RomMApiService", "Found ${screenshots.size} screenshots for save state ID: $stateId")
            screenshots
        } catch (e: Exception) {
            android.util.Log.w("RomMApiService", "Failed to get screenshots for save state $stateId", e)
            emptyList()
        }
    }
    
    suspend fun downloadScreenshot(screenshot: Screenshot): Response<ResponseBody> {
        // Use the download_path directly as provided by the server metadata
        val rawDownloadPath = screenshot.download_path ?: "/api/raw/assets/${screenshot.file_path}"
        
        // URL encode the path properly
        val encodedPath = encodeDownloadPath(rawDownloadPath)
        
        // Get the base URL from current settings to construct full URL
        val settings = settingsRepository.getCurrentSettings()
        val baseUrl = if (settings.host.endsWith("/")) settings.host.dropLast(1) else settings.host
        val fullUrl = "$baseUrl$encodedPath"
        
        android.util.Log.d("RomMApiService", "Raw screenshot download path: $rawDownloadPath")
        android.util.Log.d("RomMApiService", "Encoded screenshot download path: $encodedPath")
        android.util.Log.d("RomMApiService", "Full screenshot URL: $fullUrl")
        
        return getApi().downloadFromUrl(fullUrl)
    }
    
    suspend fun deleteSave(id: Int): Boolean {
        android.util.Log.d("RomMApiService", "Deleting save file with ID: $id")
        val request = DeleteSavesRequest(saves = listOf(id))
        val response = getApi().deleteSaves(request)
        
        // For error responses, read from errorBody instead of body
        val responseBody = if (response.isSuccessful) {
            response.body()?.string() ?: ""
        } else {
            response.errorBody()?.string() ?: ""
        }
        
        android.util.Log.d("RomMApiService", "Delete save response code: ${response.code()}, body: '$responseBody'")
        return response.isSuccessful
    }
    
    suspend fun deleteSaveState(id: Int): Boolean {
        android.util.Log.d("RomMApiService", "Deleting save state with ID: $id")
        val request = DeleteStatesRequest(states = listOf(id))
        val response = getApi().deleteSaveStates(request)
        
        // For error responses, read from errorBody instead of body
        val responseBody = if (response.isSuccessful) {
            response.body()?.string() ?: ""
        } else {
            response.errorBody()?.string() ?: ""
        }
        
        android.util.Log.d("RomMApiService", "Delete save state response code: ${response.code()}, body: '$responseBody'")
        return response.isSuccessful
    }
    
    private fun encodeDownloadPath(rawPath: String): String {
        // Split the path and query parameters
        val parts = rawPath.split("?")
        val path = parts[0]
        val queryString = if (parts.size > 1) parts[1] else null
        
        // Split path into segments and encode each segment individually
        val pathSegments = path.split("/")
        val encodedSegments = pathSegments.map { segment ->
            if (segment.isEmpty()) {
                segment
            } else {
                // URL encode the segment, but be careful with already-encoded characters
                java.net.URLEncoder.encode(segment, "UTF-8")
                    .replace("+", "%20") // URLEncoder uses + for spaces, but we want %20
            }
        }
        
        // Reconstruct the path
        val encodedPath = encodedSegments.joinToString("/")
        
        // Add back query string if it exists (encode query parameters too)
        return if (queryString != null) {
            val encodedQuery = queryString.split("&").joinToString("&") { param ->
                val keyValue = param.split("=")
                if (keyValue.size == 2) {
                    val key = keyValue[0]
                    val value = java.net.URLEncoder.encode(keyValue[1], "UTF-8")
                        .replace("+", "%20")
                    "$key=$value"
                } else {
                    param
                }
            }
            "$encodedPath?$encodedQuery"
        } else {
            encodedPath
        }
    }
    
}
