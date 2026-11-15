package com.forbiddenstreams.vod.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.forbiddenstreams.vod.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A newer build published on the repo's GitHub Releases page. */
data class UpdateInfo(
    val build: Int,
    val title: String,
    val notes: String?,
    val apkUrl: String,
    val sizeBytes: Long?,
)

/**
 * Checks GitHub Releases for a newer APK, downloads it, and hands it to the system installer.
 * CI publishes every build as the "latest" release named "... (build N)".
 */
class UpdateManager(context: Context, private val http: OkHttpClient) {

    private val appContext = context.applicationContext

    val currentBuild: Int = BuildConfig.VERSION_CODE
    val currentVersionName: String = BuildConfig.VERSION_NAME

    /** Returns the latest release if it's newer than this install, otherwise null. */
    suspend fun checkForUpdate(): UpdateInfo? = latestRelease()?.takeIf { it.build > currentBuild }

    suspend fun latestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return@use null // no releases yet
                if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
                val root = Json.parseToJsonElement(response.body?.string().orEmpty()).obj()
                    ?: throw IOException("Unexpected response from GitHub")
                val title = root.str("name") ?: root.str("tag_name").orEmpty()
                val build = BUILD_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: return@use null
                val apk = root["assets"].list()
                    .mapNotNull { it.obj() }
                    .firstOrNull { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
                    ?: return@use null
                val url = apk.str("browser_download_url") ?: return@use null
                UpdateInfo(build, title, root.str("body"), url, apk.long("size"))
            }
        } catch (e: IOException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw IOException("Unexpected response from GitHub", e)
        }
    }

    /** Downloads the APK into the app cache. [onProgress] gets 0..1, or null if the size is unknown. */
    suspend fun download(update: UpdateInfo, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "ForbiddenStreamsVOD.apk")
        val partial = File(dir, "download.part")
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", USER_AGENT).build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Download failed (empty response)")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            onProgress(if (total != null) 0f else null)
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }
        target.delete()
        if (!partial.renameTo(target)) throw IOException("Couldn't save the update")
        target
    }

    /** Android 8+ needs the user to allow this app to install other apps once. */
    fun canInstallPackages(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        /** Where builds are published. */
        const val REPO = "jaytatro/ForbiddenStreamsVOD"
        private val BUILD_REGEX = Regex("""build\s+(\d+)""", RegexOption.IGNORE_CASE)
    }
}
