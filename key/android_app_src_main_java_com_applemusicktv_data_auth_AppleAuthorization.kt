package com.applemusicktv.data.auth

import com.applemusicktv.media.AppleDirectClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

enum class TokenVerification { Valid, Invalid, Unavailable }
enum class AuthorizationIssue { DeveloperUnavailable, UserRejected }

@Singleton
class AppleAuthState @Inject constructor() {
    private val mutable = MutableStateFlow<AuthorizationIssue?>(null)
    val issue = mutable.asStateFlow()
    fun developerUnavailable() { mutable.value = AuthorizationIssue.DeveloperUnavailable }
    fun userRejected() { mutable.value = AuthorizationIssue.UserRejected }
    fun recovered() { mutable.value = null }
}

fun interface TokenVerifier { suspend fun verify(token: String): TokenVerification }

@Singleton
class AppleTokenVerifier @Inject constructor(private val client: AppleDirectClient) : TokenVerifier {
    override suspend fun verify(token: String) = kotlinx.coroutines.withTimeoutOrNull(20_000) {
        client.validateUserToken(token)
    } ?: TokenVerification.Unavailable
}

internal fun isAppleAuthorizationHost(host: String) = host in setOf(
    "api.music.apple.com", "amp-api-edge.music.apple.com", "amp-api.music.apple.com", "play.itunes.apple.com",
)

/** Also used at the network boundary: cross-host redirects cannot retain custom credential headers. */
internal fun Request.withoutAppleCredentials(): Request = newBuilder()
    .removeHeader("Authorization").removeHeader("Media-User-Token").removeHeader("Music-User-Token")
    .removeHeader("X-Music-User-Token").removeHeader("Cookie").build()

internal class AppleAuthorizationInterceptor(
    private val bearer: () -> String,
    private val refresh: (String) -> String,
    private val userToken: () -> String,
    private val state: AppleAuthState,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isAppleAuthorizationHost(original.url.host)) return chain.proceed(original.withoutAppleCredentials())
        val token = original.header("Media-User-Token") ?: original.header("Music-User-Token") ?: userToken()
        fun request(dev: String) = original.newBuilder()
            .header("Authorization", "Bearer $dev")
            .apply { if (token.isNotEmpty()) header("Media-User-Token", token) }
            .header("Origin", "https://music.apple.com")
            .header("User-Agent", original.header("User-Agent") ?: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
            .build()
        val first = bearer()
        var response = chain.proceed(request(first))
        if (response.code == 401) {
            response.close()
            val next = refresh(first)
            response = chain.proceed(request(next)) // Exactly one retry; never recurse.
        }
        // Optional avatar endpoints must never produce global login warnings.
        if (original.url.encodedPath in setOf("/v1/me/account", "/v1/me/social/profile")) return response
        if (response.code == 401) state.developerUnavailable()
        // Catalog/territorial 403 is not proof the user's token is invalid.
        else if (response.code == 403 && original.url.encodedPath.startsWith("/v1/me/") && token.isNotEmpty() && token == userToken()) state.userRejected()
        else if (response.isSuccessful && original.url.encodedPath.startsWith("/v1/me/") && token.isNotEmpty() && token == userToken()) state.recovered()
        else if (response.isSuccessful && state.issue.value == AuthorizationIssue.DeveloperUnavailable) state.recovered()
        return response
    }
}
