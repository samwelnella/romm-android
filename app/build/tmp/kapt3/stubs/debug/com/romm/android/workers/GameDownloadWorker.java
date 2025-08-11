package com.romm.android.workers;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000j\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\b\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0010\u0002\n\u0002\u0010\u0000\n\u0002\b\r\b\u0007\u0018\u00002\u00020\u0001B+\b\u0007\u0012\b\b\u0001\u0010\u0002\u001a\u00020\u0003\u0012\b\b\u0001\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007\u0012\u0006\u0010\b\u001a\u00020\t\u00a2\u0006\u0002\u0010\nJ\u001e\u0010\u000b\u001a\u00020\f2\u0006\u0010\r\u001a\u00020\u000e2\u0006\u0010\u000f\u001a\u00020\u0010H\u0082@\u00a2\u0006\u0002\u0010\u0011J\u001a\u0010\u0012\u001a\u0004\u0018\u00010\u00132\u0006\u0010\u0014\u001a\u00020\u00132\u0006\u0010\u0015\u001a\u00020\u000eH\u0002J\u0010\u0010\u0016\u001a\u00020\u00172\u0006\u0010\u0014\u001a\u00020\u0013H\u0002J\u000e\u0010\u0018\u001a\u00020\u0019H\u0096@\u00a2\u0006\u0002\u0010\u001aJJ\u0010\u001b\u001a\u00020\u00102\u0006\u0010\u001c\u001a\u00020\u001d2\u0006\u0010\u001e\u001a\u00020\u00132\u0006\u0010\r\u001a\u00020\u000e2\"\u0010\u001f\u001a\u001e\b\u0001\u0012\u0004\u0012\u00020\u0010\u0012\n\u0012\b\u0012\u0004\u0012\u00020\"0!\u0012\u0006\u0012\u0004\u0018\u00010#0 H\u0082@\u00a2\u0006\u0002\u0010$J\u001e\u0010%\u001a\u00020\"2\u0006\u0010&\u001a\u00020\u00132\u0006\u0010\'\u001a\u00020\u0013H\u0082@\u00a2\u0006\u0002\u0010(J\u001a\u0010)\u001a\u0004\u0018\u00010\u00132\u0006\u0010*\u001a\u00020\u00132\u0006\u0010+\u001a\u00020\u000eH\u0002J\u001a\u0010,\u001a\u0004\u0018\u00010\u00132\u0006\u0010*\u001a\u00020\u00132\u0006\u0010+\u001a\u00020\u000eH\u0002J\u0010\u0010-\u001a\u00020\"2\u0006\u0010.\u001a\u00020\u000eH\u0002J\u0010\u0010/\u001a\u00020\"2\u0006\u0010.\u001a\u00020\u000eH\u0002R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\tX\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u00060"}, d2 = {"Lcom/romm/android/workers/GameDownloadWorker;", "Landroidx/work/CoroutineWorker;", "context", "Landroid/content/Context;", "workerParams", "Landroidx/work/WorkerParameters;", "apiService", "Lcom/romm/android/network/RomMApiService;", "downloadManager", "Lcom/romm/android/utils/DownloadManager;", "(Landroid/content/Context;Landroidx/work/WorkerParameters;Lcom/romm/android/network/RomMApiService;Lcom/romm/android/utils/DownloadManager;)V", "createForegroundInfo", "Landroidx/work/ForegroundInfo;", "gameName", "", "progress", "", "(Ljava/lang/String;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", "createOrReplaceFile", "Landroidx/documentfile/provider/DocumentFile;", "directory", "fileName", "deleteDirectoryRecursively", "", "doWork", "Landroidx/work/ListenableWorker$Result;", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "downloadFileOptimized", "body", "Lokhttp3/ResponseBody;", "outputFile", "onProgress", "Lkotlin/Function2;", "Lkotlin/coroutines/Continuation;", "", "", "(Lokhttp3/ResponseBody;Landroidx/documentfile/provider/DocumentFile;Ljava/lang/String;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "extractZipFileOptimized", "zipFile", "destinationDir", "(Landroidx/documentfile/provider/DocumentFile;Landroidx/documentfile/provider/DocumentFile;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "findAnyPlatformDirectory", "baseDir", "platformSlug", "getOrCreatePlatformDirectory", "showErrorNotification", "message", "showSuccessNotification", "app_debug"})
@androidx.hilt.work.HiltWorker()
public final class GameDownloadWorker extends androidx.work.CoroutineWorker {
    @org.jetbrains.annotations.NotNull()
    private final com.romm.android.network.RomMApiService apiService = null;
    @org.jetbrains.annotations.NotNull()
    private final com.romm.android.utils.DownloadManager downloadManager = null;
    
    @dagger.assisted.AssistedInject()
    public GameDownloadWorker(@dagger.assisted.Assisted()
    @org.jetbrains.annotations.NotNull()
    android.content.Context context, @dagger.assisted.Assisted()
    @org.jetbrains.annotations.NotNull()
    androidx.work.WorkerParameters workerParams, @org.jetbrains.annotations.NotNull()
    com.romm.android.network.RomMApiService apiService, @org.jetbrains.annotations.NotNull()
    com.romm.android.utils.DownloadManager downloadManager) {
        super(null, null);
    }
    
    @java.lang.Override()
    @org.jetbrains.annotations.Nullable()
    public java.lang.Object doWork(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super androidx.work.ListenableWorker.Result> $completion) {
        return null;
    }
    
    /**
     * Simplified directory creation that handles race conditions gracefully
     */
    private final androidx.documentfile.provider.DocumentFile getOrCreatePlatformDirectory(androidx.documentfile.provider.DocumentFile baseDir, java.lang.String platformSlug) {
        return null;
    }
    
    /**
     * Find any existing platform directory (including numbered variants)
     */
    private final androidx.documentfile.provider.DocumentFile findAnyPlatformDirectory(androidx.documentfile.provider.DocumentFile baseDir, java.lang.String platformSlug) {
        return null;
    }
    
    /**
     * Create a file, overwriting if it already exists
     */
    private final androidx.documentfile.provider.DocumentFile createOrReplaceFile(androidx.documentfile.provider.DocumentFile directory, java.lang.String fileName) {
        return null;
    }
    
    /**
     * Recursively delete a directory and its contents
     */
    private final boolean deleteDirectoryRecursively(androidx.documentfile.provider.DocumentFile directory) {
        return false;
    }
    
    private final java.lang.Object createForegroundInfo(java.lang.String gameName, int progress, kotlin.coroutines.Continuation<? super androidx.work.ForegroundInfo> $completion) {
        return null;
    }
    
    private final void showErrorNotification(java.lang.String message) {
    }
    
    private final void showSuccessNotification(java.lang.String message) {
    }
    
    /**
     * Optimized file download with memory management
     */
    private final java.lang.Object downloadFileOptimized(okhttp3.ResponseBody body, androidx.documentfile.provider.DocumentFile outputFile, java.lang.String gameName, kotlin.jvm.functions.Function2<? super java.lang.Integer, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends java.lang.Object> onProgress, kotlin.coroutines.Continuation<? super java.lang.Integer> $completion) {
        return null;
    }
    
    /**
     * Optimized ZIP extraction with memory management
     */
    private final java.lang.Object extractZipFileOptimized(androidx.documentfile.provider.DocumentFile zipFile, androidx.documentfile.provider.DocumentFile destinationDir, kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
}