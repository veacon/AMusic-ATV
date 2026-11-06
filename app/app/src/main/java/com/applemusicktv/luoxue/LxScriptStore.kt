package com.applemusicktv.luoxue

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 落雪脚本仓库（filesDir/luoxue）。
 *
 * 解决三件事：
 *  1. **APK 升级能下发新脚本**：旧实现只在"文件不存在"时落盘，装新版 APK 也永远用第一版脚本，
 *     换音源只能 adb push。
 *  2. **用户导入的脚本不被覆盖**：manifest 记录每个文件是否仍由 assets 托管；
 *     一旦内容与记录的哈希不一致（用户改过/导入过），就转为 user 托管，同步时跳过。
 *  3. **可回滚**：resetToAssets() 清掉全部本地覆盖，回到随包版本。
 *
 * manifest.json 只记 name -> assetHash，不记时间戳，避免无意义 diff。
 */
class LxScriptStore(private val context: Context) {

    companion object {
        const val DIR = "luoxue"
        private const val MANIFEST = "manifest.json"
        private const val MAX_BYTES = 512 * 1024
    }

    data class Script(
        val name: String,
        /** asset=随包且未改动；user=用户导入或改过 */
        val origin: String,
        val size: Long,
        val updatedAt: Long,
    )

    fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun manifestFile() = File(dir(), MANIFEST)

    private fun readManifest(): JSONObject = runCatching {
        val f = manifestFile()
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun writeManifest(o: JSONObject) {
        runCatching { manifestFile().writeText(o.toString()) }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assetNames(): List<String> =
        runCatching { context.assets.list(DIR)?.toList() ?: emptyList() }.getOrDefault(emptyList())
            .filter { it.endsWith(".js", true) }

    private fun readAsset(name: String): ByteArray? = runCatching {
        context.assets.open("$DIR/$name").use { it.readBytes() }
    }.getOrNull()

    /**
     * 把 assets 同步到本地目录，返回发生变化的文件名。
     * 只在"本地确实是随包版本的副本"时才覆盖，用户脚本一律不碰。
     */
    fun syncFromAssets(): List<String> {
        val d = dir()
        val manifest = readManifest()
        val changed = mutableListOf<String>()
        for (name in assetNames()) {
            val bytes = readAsset(name) ?: continue
            val hash = sha1(bytes)
            val target = File(d, name)
            val recorded = manifest.optString(name, "")
            val localHash = if (target.exists() && target.length() > 0) sha1(target.readBytes()) else ""
            when {
                // 本地没有：直接落地
                localHash.isEmpty() -> {
                    runCatching { target.writeBytes(bytes) }
                    manifest.put(name, hash)
                    changed += name
                }
                // 本地与 assets 一致：无需动作
                localHash == hash -> manifest.put(name, hash)
                // 本地仍是上次记录的随包副本，而 assets 更新了：覆盖
                recorded.isNotEmpty() && localHash == recorded -> {
                    runCatching { target.writeBytes(bytes) }
                    manifest.put(name, hash)
                    changed += name
                }
                // 本地被用户改过/导入过：保留，并从托管名单里摘掉
                else -> if (manifest.has(name)) manifest.remove(name)
            }
        }
        // assets 里已经不存在的脚本：如果本地还是随包副本，删掉（随包脚本被淘汰）
        val known = manifest.keys().asSequence().toList()
        for (name in known) {
            if (name in assetNames()) continue
            val f = File(d, name)
            if (f.exists() && sha1(f.readBytes()) == manifest.optString(name, "")) {
                runCatching { f.delete() }
                changed += name
            }
            manifest.remove(name)
        }
        writeManifest(manifest)
        return changed
    }

    /** 目录里所有脚本（含用户导入），按名字排序。 */
    fun list(): List<Script> {
        val manifest = readManifest()
        return dir().listFiles { f -> f.isFile && f.name.endsWith(".js", true) }
            ?.sortedBy { it.name }
            ?.map {
                Script(
                    name = it.name,
                    origin = if (manifest.has(it.name)) "asset" else "user",
                    size = it.length(),
                    updatedAt = it.lastModified(),
                )
            } ?: emptyList()
    }

    fun isManagedByAsset(name: String): Boolean = readManifest().has(name)

    /**
     * 导入（或覆盖）一条脚本。名字非法/内容不像落雪脚本时抛异常，交给上层回显。
     * 导入后从托管名单摘除，之后 APK 升级也不会覆盖它。
     */
    fun import(name: String, text: String): File {
        val safe = name.trim().substringAfterLast('/').substringAfterLast('\\')
        require(safe.endsWith(".js", true)) { "文件名必须以 .js 结尾" }
        require(safe.length in 4..80) { "文件名长度不合法" }
        require(safe.none { it in "\\:*?\"<>|" }) { "文件名含非法字符" }
        val bytes = text.toByteArray()
        require(bytes.size <= MAX_BYTES) { "脚本超过 ${MAX_BYTES / 1024}KB" }
        // 落雪脚本的两个硬特征：导出 handler + musicUrl 实现
        val looksValid = text.contains("musicUrl") && (text.contains("EVENT_NAMES") || text.contains("on(EVENT_NAMES"))
        require(looksValid) { "不像落雪音源（缺少 musicUrl/handler）" }
        val target = File(dir(), safe)
        target.writeBytes(bytes)
        val manifest = readManifest().apply { remove(safe) }
        writeManifest(manifest)
        return target
    }

    fun delete(name: String): Boolean {
        val f = File(dir(), name)
        if (!f.exists()) return false
        val ok = f.delete()
        if (ok) writeManifest(readManifest().apply { remove(name) })
        return ok
    }

    /** 清空全部本地覆盖，下次启动重新从 assets 落地。 */
    fun resetToAssets(): Int {
        var n = 0
        for (f in dir().listFiles { f -> f.isFile && f.name.endsWith(".js", true) } ?: emptyArray()) {
            if (f.delete()) n++
        }
        writeManifest(JSONObject())
        return n
    }
}
