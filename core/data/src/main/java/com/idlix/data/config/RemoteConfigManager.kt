package com.idlix.data.config

import android.content.Context
import com.idlix.data.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resumeWithException

/**
 * Startup-only, last-known-good config. Does not depend on ApiClient, its cookies, auth, or DID.
 * The embedded JSON is generated from root remote-source.json, so current also works before
 * Android startup (including plain JVM model/API tests). initialize is coalesced and retryable
 * after cancellation; it publishes one validated final snapshot before callers build clients.
 */
object RemoteConfigManager {
    private const val PREFS_NAME = "remote_source_config"
    private const val CACHE_KEY = "validated_json_v1"
    private val startupMutex = Mutex()
    private val bundled = RemoteSourceConfig.parse(BuildConfig.BUNDLED_REMOTE_SOURCE_JSON)

    @Volatile
    private var snapshot: RemoteSourceConfig = bundled

    @Volatile
    private var initialized = false

    val current: RemoteSourceConfig get() = snapshot
    val currentOrNull: RemoteSourceConfig? get() = if (initialized) snapshot else null

    // Dedicated client: no app interceptors, authenticators, cookie persistence, or redirects.
    // Redirects are rejected rather than risking an HTTPS-to-HTTP downgrade or another origin.
    private val configClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    suspend fun initialize(context: Context): RemoteSourceConfig = startupMutex.withLock {
        if (initialized) return@withLock snapshot
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext ?: context
            val preferences = fallbackOnFailure {
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            val cachedText = fallbackOnFailure { preferences?.getString(CACHE_KEY, null) }
            val cached = parseOrNull(cachedText)
            val local = effectiveConfig(bundled, cached)
            val remoteText = fallbackOnFailure { fetchConfig(BuildConfig.REMOTE_SOURCE_CONFIG_URL) }
            val remote = parseOrNull(remoteText)
            val selected = effectiveConfig(local, remote = remote)
            currentCoroutineContext().ensureActive()
            if (remote != null && selected === remote && remoteText != null) {
                // Persist only after full validation. Cache I/O failure must not block startup.
                fallbackOnFailure { preferences?.edit()?.putString(CACHE_KEY, remoteText)?.commit() }
            }
            currentCoroutineContext().ensureActive()
            snapshot = selected
            initialized = true
            selected
        }
    }

    private suspend fun fetchConfig(url: String): String {
        val uri = RemoteSourceConfig.requireHttpsUrl(url)
        require(uri.rawQuery == null) { "Config URL must not contain query credentials" }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        configClient.newCall(request).awaitResponse().use { response ->
            require(response.isSuccessful) { "Config fetch failed" }
            // This client never follows redirects; also verify the final request defensively.
            require(response.request.url.isHttps) { "Config response must use HTTPS" }
            val body = requireNotNull(response.body) { "Missing config response body" }
            require(body.contentLength() <= RemoteSourceConfig.MAX_CONFIG_BYTES) { "Config too large" }
            return body.byteStream().use(::readBoundedUtf8)
        }
    }

    private fun parseOrNull(text: String?): RemoteSourceConfig? =
        if (text == null) null else fallbackOnFailure { RemoteSourceConfig.parse(text) }

    /** No runCatching: coroutine cancellation is never converted into offline success. */
    private inline fun <T> fallbackOnFailure(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Close even if cancellation wins the race with resuming the IO dispatcher.
                continuation.resume(response) { response.close() }
            }
        })
    }
}

/** Testable, bounded streaming read; content-length is not trusted (chunked/gzip also bounded). */
internal fun readBoundedUtf8(input: InputStream): String {
    val limit = RemoteSourceConfig.MAX_CONFIG_BYTES
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (read == -1) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        require(output.size() <= limit) { "Configuration exceeds 256 KiB" }
    }
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray()))
        .toString()
}
