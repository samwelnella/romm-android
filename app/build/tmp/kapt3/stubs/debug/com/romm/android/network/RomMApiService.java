package com.romm.android.network;

@javax.inject.Singleton()
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000^\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0007\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0000\b\u0007\u0018\u00002\u00020\u0001B\u000f\b\u0007\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\u0010\u0010\t\u001a\u00020\u00062\u0006\u0010\n\u001a\u00020\bH\u0002J$\u0010\u000b\u001a\b\u0012\u0004\u0012\u00020\r0\f2\u0006\u0010\u000e\u001a\u00020\u000f2\u0006\u0010\u0010\u001a\u00020\u0011H\u0086@\u00a2\u0006\u0002\u0010\u0012J0\u0010\u0013\u001a\b\u0012\u0004\u0012\u00020\r0\f2\u0006\u0010\u000e\u001a\u00020\u000f2\u0006\u0010\u0010\u001a\u00020\u00112\n\b\u0002\u0010\u0014\u001a\u0004\u0018\u00010\u0011H\u0086@\u00a2\u0006\u0002\u0010\u0015J\u000e\u0010\u0016\u001a\u00020\u0006H\u0082@\u00a2\u0006\u0002\u0010\u0017J\u0014\u0010\u0018\u001a\b\u0012\u0004\u0012\u00020\u001a0\u0019H\u0086@\u00a2\u0006\u0002\u0010\u0017J\u001c\u0010\u001b\u001a\b\u0012\u0004\u0012\u00020\u001c0\u00192\u0006\u0010\u001d\u001a\u00020\u000fH\u0086@\u00a2\u0006\u0002\u0010\u001eJ\u0016\u0010\u001f\u001a\u00020 2\u0006\u0010\u000e\u001a\u00020\u000fH\u0086@\u00a2\u0006\u0002\u0010\u001eJ,\u0010!\u001a\b\u0012\u0004\u0012\u00020 0\u00192\n\b\u0002\u0010\u001d\u001a\u0004\u0018\u00010\u000f2\n\b\u0002\u0010\"\u001a\u0004\u0018\u00010\u000fH\u0086@\u00a2\u0006\u0002\u0010#J\u0014\u0010$\u001a\b\u0012\u0004\u0012\u00020%0\u0019H\u0086@\u00a2\u0006\u0002\u0010\u0017J\u0010\u0010&\u001a\u00020\'2\u0006\u0010\n\u001a\u00020\bH\u0002R\u0010\u0010\u0005\u001a\u0004\u0018\u00010\u0006X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0007\u001a\u0004\u0018\u00010\bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006("}, d2 = {"Lcom/romm/android/network/RomMApiService;", "", "settingsRepository", "Lcom/romm/android/data/SettingsRepository;", "(Lcom/romm/android/data/SettingsRepository;)V", "api", "Lcom/romm/android/network/RomMApi;", "lastSettings", "Lcom/romm/android/data/AppSettings;", "createApi", "settings", "downloadFirmware", "Lretrofit2/Response;", "Lokhttp3/ResponseBody;", "id", "", "fileName", "", "(ILjava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "downloadGame", "fileIds", "(ILjava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getApi", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getCollections", "", "Lcom/romm/android/data/Collection;", "getFirmware", "Lcom/romm/android/data/Firmware;", "platformId", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getGame", "Lcom/romm/android/data/Game;", "getGames", "collectionId", "(Ljava/lang/Integer;Ljava/lang/Integer;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getPlatforms", "Lcom/romm/android/data/Platform;", "shouldRecreateApi", "", "app_debug"})
public final class RomMApiService {
    @org.jetbrains.annotations.NotNull()
    private final com.romm.android.data.SettingsRepository settingsRepository = null;
    @org.jetbrains.annotations.Nullable()
    private com.romm.android.network.RomMApi api;
    @org.jetbrains.annotations.Nullable()
    private com.romm.android.data.AppSettings lastSettings;
    
    @javax.inject.Inject()
    public RomMApiService(@org.jetbrains.annotations.NotNull()
    com.romm.android.data.SettingsRepository settingsRepository) {
        super();
    }
    
    private final java.lang.Object getApi(kotlin.coroutines.Continuation<? super com.romm.android.network.RomMApi> $completion) {
        return null;
    }
    
    private final boolean shouldRecreateApi(com.romm.android.data.AppSettings settings) {
        return false;
    }
    
    private final com.romm.android.network.RomMApi createApi(com.romm.android.data.AppSettings settings) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getPlatforms(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.romm.android.data.Platform>> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getCollections(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.romm.android.data.Collection>> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getGames(@org.jetbrains.annotations.Nullable()
    java.lang.Integer platformId, @org.jetbrains.annotations.Nullable()
    java.lang.Integer collectionId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.romm.android.data.Game>> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getGame(int id, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.romm.android.data.Game> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object downloadGame(int id, @org.jetbrains.annotations.NotNull()
    java.lang.String fileName, @org.jetbrains.annotations.Nullable()
    java.lang.String fileIds, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super retrofit2.Response<okhttp3.ResponseBody>> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object getFirmware(int platformId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.romm.android.data.Firmware>> $completion) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.Object downloadFirmware(int id, @org.jetbrains.annotations.NotNull()
    java.lang.String fileName, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super retrofit2.Response<okhttp3.ResponseBody>> $completion) {
        return null;
    }
}