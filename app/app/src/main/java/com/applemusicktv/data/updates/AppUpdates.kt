package com.applemusicktv.data.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.applemusicktv.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class UpdateInfo(val version: String, val code: Int, val url: String, val size: Long, val sha256: String, val minSdk: Int, val notes: String)

class AppUpdates(private val context: Context, private val manifestUrl: String = BuildConfig.UPDATE_BASE_URL + "/update.json") {
    private val client = updateHttpClient(manifestUrl)
    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        val response = client.newCall(Request.Builder().url(manifestUrl).cacheControl(CacheControl.FORCE_NETWORK).build()).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val body = it.body ?: throw IOException("Empty update response")
            if (body.contentLength() > 32768) throw IOException("Update response too large")
            val bytes = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (bytes.size() + n > 32768) throw IOException("Update response too large")
                    bytes.write(buffer, 0, n)
                }
            }
            val raw = bytes.toString("UTF-8")
            parse(raw)
        }
    }
    internal fun parse(raw: String): UpdateInfo {
        val j = JSONObject(raw)
        require(j.getString("channel") == "user")
        require(j.getString("packageName") == context.packageName)
        val url = j.getString("apkUrl").toHttpUrlOrNull() ?: error("Invalid APK URL")
        val metadataHost = manifestUrl.toHttpUrlOrNull()?.host ?: error("Invalid update source")
        require(url.isHttps && url.host == metadataHost && url.port == 443 && url.encodedPath.startsWith("/downloads/") && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null)
        val result = UpdateInfo(j.getString("versionName"), j.getInt("versionCode"), url.toString(), j.getLong("sizeBytes"), j.getString("sha256").lowercase(), j.getInt("minSdk"), j.optString("notes", ""))
        require(result.version.length in 1..64 && result.code > 0 && result.size in 1..150_000_000 && result.sha256.matches(Regex("[0-9a-f]{64}")) && result.minSdk in 1..100 && result.notes.length <= 3000)
        return result
    }
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(dir, "download.part"); val dest = File(dir, "update.apk")
        part.delete(); dest.delete()
        try {
            client.newCall(Request.Builder().url(info.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty download")
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input -> part.outputStream().use { output ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n; if (total > info.size) throw IOException("Download size mismatch")
                        md.update(buf, 0, n); output.write(buf, 0, n); onProgress((100 * total / info.size).toInt())
                    }
                    output.fd.sync()
                } }
                if (total != info.size || md.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != info.sha256) throw IOException("Download checksum mismatch")
            }
            validateApk(part, info)
            if (!part.renameTo(dest)) throw IOException("Could not save APK")
            dest
        } catch (e: Exception) { part.delete(); throw e }
    }
    @Suppress("DEPRECATION")
    private fun validateApk(file: File, info: UpdateInfo) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Invalid APK")
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        if (candidate.packageName != context.packageName || candidate.versionName != info.version || code != info.code.toLong() || code <= BuildConfig.VERSION_CODE) throw IOException("APK version or package mismatch")
        @Suppress("DEPRECATION") fun certs(p: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
            return signatures.orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }.toSet()
        }
        if (certs(installed).isEmpty() || certs(candidate) != certs(installed)) throw IOException("APK signing certificate mismatch")
    }
    fun install(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
}
