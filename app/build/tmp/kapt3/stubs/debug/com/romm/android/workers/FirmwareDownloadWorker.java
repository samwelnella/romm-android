package com.romm.android.workers;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000`\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\b\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0005\b\u0007\u0018\u00002\u00020\u0001B#\b\u0007\u0012\b\b\u0001\u0010\u0002\u001a\u00020\u0003\u0012\b\b\u0001\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007\u00a2\u0006\u0002\u0010\bJ\u001e\u0010\t\u001a\u00020\n2\u0006\u0010\u000b\u001a\u00020\f2\u0006\u0010\r\u001a\u00020\u000eH\u0082@\u00a2\u0006\u0002\u0010\u000fJ\u001a\u0010\u0010\u001a\u0004\u0018\u00010\u00112\u0006\u0010\u0012\u001a\u00020\u00112\u0006\u0010\u0013\u001a\u00020\fH\u0002J\u000e\u0010\u0014\u001a\u00020\u0015H\u0096@\u00a2\u0006\u0002\u0010\u0016JJ\u0010\u0017\u001a\u00020\u00182\u0006\u0010\u0019\u001a\u00020\u001a2\u0006\u0010\u001b\u001a\u00020\u00112\u0006\u0010\u000b\u001a\u00020\f2\"\u0010\u001c\u001a\u001e\b\u0001\u0012\u0004\u0012\u00020\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00180\u001e\u0012\u0006\u0012\u0004\u0018\u00010\u001f0\u001dH\u0082@\u00a2\u0006\u0002\u0010 J\u0012\u0010!\u001a\u0004\u0018\u00010\u00112\u0006\u0010\"\u001a\u00020\u0011H\u0002J\u0012\u0010#\u001a\u0004\u0018\u00010\u00112\u0006\u0010\"\u001a\u00020\u0011H\u0002R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006$"}, d2 = {"Lcom/romm/android/workers/FirmwareDownloadWorker;", "Landroidx/work/CoroutineWorker;", "context", "Landroid/content/Context;", "workerParams", "Landroidx/work/WorkerParameters;", "apiService", "Lcom/romm/android/network/RomMApiService;", "(Landroid/content/Context;Landroidx/work/WorkerParameters;Lcom/romm/android/network/RomMApiService;)V", "createForegroundInfo", "Landroidx/work/ForegroundInfo;", "title", "", "progress", "", "(Ljava/lang/String;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", "createOrReplaceFile", "Landroidx/documentfile/provider/DocumentFile;", "directory", "fileName", "doWork", "Landroidx/work/ListenableWorker$Result;", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "downloadFileOptimized", "", "body", "Lokhttp3/ResponseBody;", "outputFile", "onProgress", "Lkotlin/Function2;", "Lkotlin/coroutines/Continuation;", "", "(Lokhttp3/ResponseBody;Landroidx/documentfile/provider/DocumentFile;Ljava/lang/String;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "findAnyFirmwareDirectory", "baseDir", "getOrCreateFirmwareDirectory", "app_debug"})
@androidx.hilt.work.HiltWorker()
public final class FirmwareDownloadWorker extends androidx.work.CoroutineWorker {
    @org.jetbrains.annotations.NotNull()
    private final com.romm.android.network.RomMApiService apiService = null;
    
    @dagger.assisted.AssistedInject()
    public FirmwareDownloadWorker(@dagger.assisted.Assisted()
    @org.jetbrains.annotations.NotNull()
    android.content.Context context, @dagger.assisted.Assisted()
    @org.jetbrains.annotations.NotNull()
    androidx.work.WorkerParameters workerParams, @org.jetbrains.annotations.NotNull()
    com.romm.android.network.RomMApiService apiService) {
        super(null, null);
    }
    
    @java.lang.Override()
    @org.jetbrains.annotations.Nullable()
    public java.lang.Object doWork(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super androidx.work.ListenableWorker.Result> $completion) {
        return null;
    }
    
    /**
     * Simplified firmware directory creation that handles race conditions gracefully
     */
    private final androidx.documentfile.provider.DocumentFile getOrCreateFirmwareDirectory(androidx.documentfile.provider.DocumentFile baseDir) {
        return null;
    }
    
    /**
     * Find any existing firmware directory (including numbered variants)
     */
    private final androidx.documentfile.provider.DocumentFile findAnyFirmwareDirectory(androidx.documentfile.provider.DocumentFile baseDir) {
        return null;
    }
    
    /**
     * Create a file, overwriting if it already exists
     */
    private final androidx.documentfile.provider.DocumentFile createOrReplaceFile(androidx.documentfile.provider.DocumentFile directory, java.lang.String fileName) {
        return null;
    }
    
    private final java.lang.Object createForegroundInfo(java.lang.String title, int progress, kotlin.coroutines.Continuation<? super androidx.work.ForegroundInfo> $completion) {
        return null;
    }
    
    /**
     * Optimized file download with memory management
     */
    private final java.lang.Object downloadFileOptimized(okhttp3.ResponseBody body, androidx.documentfile.provider.DocumentFile outputFile, java.lang.String title, kotlin.jvm.functions.Function2<? super java.lang.Integer, ? super kotlin.coroutines.Continuation<? super kotlin.Unit>, ? extends java.lang.Object> onProgress, kotlin.coroutines.Continuation<? super kotlin.Unit> $completion) {
        return null;
    }
}