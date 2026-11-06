package com.applemusicktv.data.di

import com.applemusicktv.data.auth.withoutAppleCredentials
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.NetworkLog
import com.applemusicktv.data.network.DirectAppleApi
import com.applemusicktv.media.AppleDirectClient
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
        .addInterceptor(com.applemusicktv.data.auth.AppleAuthorizationInterceptor(
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
            chain.proceed(if (com.applemusicktv.data.auth.isAppleAuthorizationHost(request.url.host)) request
                else request.withoutAppleCredentials())
        }
        .build()

    @Provides @Singleton
    fun provideTokenVerifier(client: AppleDirectClient): com.applemusicktv.data.auth.TokenVerifier =
        com.applemusicktv.data.auth.AppleTokenVerifier(client)

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
