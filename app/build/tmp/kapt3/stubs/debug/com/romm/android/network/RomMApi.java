package com.romm.android.network;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000P\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\bf\u0018\u00002\u00020\u0001J(\u0010\u0002\u001a\b\u0012\u0004\u0012\u00020\u00040\u00032\b\b\u0001\u0010\u0005\u001a\u00020\u00062\b\b\u0001\u0010\u0007\u001a\u00020\bH\u00a7@\u00a2\u0006\u0002\u0010\tJ4\u0010\n\u001a\b\u0012\u0004\u0012\u00020\u00040\u00032\b\b\u0001\u0010\u0005\u001a\u00020\u00062\b\b\u0001\u0010\u0007\u001a\u00020\b2\n\b\u0003\u0010\u000b\u001a\u0004\u0018\u00010\bH\u00a7@\u00a2\u0006\u0002\u0010\fJ\u0014\u0010\r\u001a\b\u0012\u0004\u0012\u00020\u000f0\u000eH\u00a7@\u00a2\u0006\u0002\u0010\u0010J\u001e\u0010\u0011\u001a\b\u0012\u0004\u0012\u00020\u00120\u000e2\b\b\u0001\u0010\u0013\u001a\u00020\u0006H\u00a7@\u00a2\u0006\u0002\u0010\u0014J\u0018\u0010\u0015\u001a\u00020\u00162\b\b\u0001\u0010\u0005\u001a\u00020\u0006H\u00a7@\u00a2\u0006\u0002\u0010\u0014J\u0014\u0010\u0017\u001a\b\u0012\u0004\u0012\u00020\u00180\u000eH\u00a7@\u00a2\u0006\u0002\u0010\u0010J0\u0010\u0019\u001a\u00020\u001a2\n\b\u0003\u0010\u0013\u001a\u0004\u0018\u00010\u00062\n\b\u0003\u0010\u001b\u001a\u0004\u0018\u00010\u00062\b\b\u0003\u0010\u001c\u001a\u00020\u0006H\u00a7@\u00a2\u0006\u0002\u0010\u001dJ\u000e\u0010\u001e\u001a\u00020\u001fH\u00a7@\u00a2\u0006\u0002\u0010\u0010\u00a8\u0006 "}, d2 = {"Lcom/romm/android/network/RomMApi;", "", "downloadFirmware", "Lretrofit2/Response;", "Lokhttp3/ResponseBody;", "id", "", "fileName", "", "(ILjava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "downloadGame", "fileIds", "(ILjava/lang/String;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getCollections", "", "Lcom/romm/android/data/Collection;", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getFirmware", "Lcom/romm/android/data/Firmware;", "platformId", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", "getGame", "Lcom/romm/android/data/Game;", "getPlatforms", "Lcom/romm/android/data/Platform;", "getRoms", "Lcom/romm/android/network/GameResponse;", "collectionId", "limit", "(Ljava/lang/Integer;Ljava/lang/Integer;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", "login", "Lcom/romm/android/network/LoginResponse;", "app_debug"})
public abstract interface RomMApi {
    
    @retrofit2.http.GET(value = "api/platforms")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getPlatforms(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.romm.android.data.Platform>> $completion);
    
    @retrofit2.http.GET(value = "api/collections")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getCollections(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.romm.android.data.Collection>> $completion);
    
    @retrofit2.http.GET(value = "api/roms")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getRoms(@retrofit2.http.Query(value = "platform_id")
    @org.jetbrains.annotations.Nullable()
    java.lang.Integer platformId, @retrofit2.http.Query(value = "collection_id")
    @org.jetbrains.annotations.Nullable()
    java.lang.Integer collectionId, @retrofit2.http.Query(value = "limit")
    int limit, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.romm.android.network.GameResponse> $completion);
    
    @retrofit2.http.GET(value = "api/roms/{id}")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getGame(@retrofit2.http.Path(value = "id")
    int id, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.romm.android.data.Game> $completion);
    
    @retrofit2.http.Streaming()
    @retrofit2.http.GET(value = "api/roms/{id}/content/{fileName}")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object downloadGame(@retrofit2.http.Path(value = "id")
    int id, @retrofit2.http.Path(value = "fileName")
    @org.jetbrains.annotations.NotNull()
    java.lang.String fileName, @retrofit2.http.Query(value = "file_ids")
    @org.jetbrains.annotations.Nullable()
    java.lang.String fileIds, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super retrofit2.Response<okhttp3.ResponseBody>> $completion);
    
    @retrofit2.http.GET(value = "api/firmware")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object getFirmware(@retrofit2.http.Query(value = "platform_id")
    int platformId, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super java.util.List<com.romm.android.data.Firmware>> $completion);
    
    @retrofit2.http.Streaming()
    @retrofit2.http.GET(value = "api/firmware/{id}/content/{fileName}")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object downloadFirmware(@retrofit2.http.Path(value = "id")
    int id, @retrofit2.http.Path(value = "fileName")
    @org.jetbrains.annotations.NotNull()
    java.lang.String fileName, @org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super retrofit2.Response<okhttp3.ResponseBody>> $completion);
    
    @retrofit2.http.POST(value = "api/login")
    @org.jetbrains.annotations.Nullable()
    public abstract java.lang.Object login(@org.jetbrains.annotations.NotNull()
    kotlin.coroutines.Continuation<? super com.romm.android.network.LoginResponse> $completion);
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 3, xi = 48)
    public static final class DefaultImpls {
    }
}