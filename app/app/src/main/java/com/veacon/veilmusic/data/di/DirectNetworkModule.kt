package com.veacon.veilmusic.data.di

import com.veacon.veilmusic.data.auth.withoutAppleCredentials
import com.veacon.veilmusic.data.MutPreferences
import com.veacon.veilmusic.data.NetworkLog
import com.veacon.veilmusic.data.network.DirectAppleApi
import com.veacon.veilmusic.media.AppleDirectClient
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DirectNetworkModule {

    @Provides
    @Singleton
    @Named("direct")
    fun provideDirectOkHttpClient(
        mutPrefs: MutPreferences,
        appleClient: AppleDirectClient,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(com.veacon.veilmusic.data.auth.AppleAuthorizationInterceptor(
            bearer = { runBlocking { appleClient.getBearer() } },
            refresh = { rejected -> runBlocking { appleClient.refreshBearer(rejected) } },
            userToken = mutPrefs::getMUT,
            state = appleClient.authorization,
        ))
        .addInterceptor { chain ->
            val request = chain.request()
            val start = System.currentTimeMillis()
            chain.proceed(request).also { response ->
                NetworkLog.add(request.method, request.url.toString(), response.code, System.currentTimeMillis() - start)
            }
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            chain.proceed(if (com.veacon.veilmusic.data.auth.isAppleAuthorizationHost(request.url.host)) request
                else request.withoutAppleCredentials())
        }
        .build()

    @Provides @Singleton
    fun provideTokenVerifier(client: AppleDirectClient): com.veacon.veilmusic.data.auth.TokenVerifier =
        com.veacon.veilmusic.data.auth.AppleTokenVerifier(client)

    @Provides
    @Singleton
    fun provideDirectAppleApi(
        @Named("direct") client: OkHttpClient,
        moshi: Moshi,
    ): DirectAppleApi = Retrofit.Builder()
        .baseUrl("https://amp-api-edge.music.apple.com/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(DirectAppleApi::class.java)
}
