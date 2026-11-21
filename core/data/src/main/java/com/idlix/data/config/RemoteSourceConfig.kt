package com.idlix.data.config

import java.net.URI
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** JSON API routing configuration. Reserved HTML fields are never used for scraping. */
@Serializable
data class RemoteSourceConfig(
    val schemaVersion: Int,
    val version: Int,
    val baseUrl: String,
    // Required on the wire. The bundled value is the current TV UA; mobile may override it.
    val userAgent: String,
    val endpoints: Map<String, String>,
    val player: PlayerConfig,
    val selectors: ReservedSelectors? = null,
    val embedRegexes: List<String>? = null
) {
    @Serializable
    data class PlayerConfig(
        val apiPrefix: String,
        val watchPath: String,
        val redeemPath: String,
        val fallbackRedeemUrls: List<String>,
        val sourceHostSuffixes: List<String>,
        val gateOwnerRegex: String,
        val userAgent: String,
        val didCookieRegex: String
    )

    /** Reserved for a future schema; catalogue/episode selectors are NOT used by the JSON API. */
    @Serializable
    data class ReservedSelectors(
        val catalogue: Map<String, String>? = null,
        val episode: Map<String, String>? = null
    )

    fun validated(): RemoteSourceConfig = apply {
        require(schemaVersion == 1) { "Unsupported schemaVersion" }
        require(version > 0) { "version must be positive" }
        val base = requireHttpsUrl(baseUrl)
        require(base.rawQuery == null && base.rawFragment == null && baseUrl.endsWith('/')) {
            "baseUrl must end in / and have no query or fragment"
        }
        require(userAgent.length in 1..512 && userAgent.all { it.code in 32..126 }) {
            "userAgent must be a nonempty ASCII HTTP header value"
        }
        require(player.sourceHostSuffixes.size in 1..16) { "Expected 1..16 source host suffixes" }
        require(player.sourceHostSuffixes.distinct().size == player.sourceHostSuffixes.size)
        player.sourceHostSuffixes.forEach { require(validHostSuffix(it)) { "Invalid source host suffix" } }
        // Internal consistency only: the suffixes come from this same document. The actual guard
        // against a tampered file is effectiveConfig, which checks against the APK's own list.
        require(isSourceHost(requireNotNull(base.host) { "baseUrl needs a host" })) {
            "baseUrl host must be inside player.sourceHostSuffixes"
        }
        require(endpoints.keys == REQUIRED_ENDPOINT_KEYS) { "Missing or unknown endpoint keys" }
        endpoints.forEach { (key, value) ->
            val original = templateSegments(key)
            val mapped = templateSegments(value)
            require(placeholders(original).sorted() == placeholders(mapped).sorted()) {
                "Endpoint placeholders must match: $key"
            }
        }
        // Overlaps across different endpoints would make identity/auth matching ambiguous.
        val entries = endpoints.entries.toList()
        entries.forEachIndexed { index, first ->
            entries.drop(index + 1).forEach { second ->
                listOf(first.key, first.value).forEach { a ->
                    listOf(second.key, second.value).forEach { b ->
                        require(!templatesOverlap(a, b)) { "Ambiguous endpoint mappings" }
                    }
                }
            }
        }
        listOf(player.apiPrefix, player.watchPath, player.redeemPath).forEach {
            require(placeholders(templateSegments(it)).isEmpty()) { "Player paths must be literal" }
        }
        require(player.fallbackRedeemUrls.size in 1..8) { "Expected 1..8 fallback redeem URLs" }
        player.fallbackRedeemUrls.forEach(::requireHttpsUrl)
        validateRegex(player.gateOwnerRegex, requireCapture = true)
        validateRegex(player.didCookieRegex, requireCapture = true)
        require(player.userAgent.length in 1..512 && player.userAgent.all { it.code in 32..126 }) {
            "player.userAgent must be a nonempty ASCII HTTP header value"
        }
        // Reserved only: bounded and validated, but neither selectors nor embedRegexes is consumed.
        listOfNotNull(selectors?.catalogue, selectors?.episode).forEach { values ->
            require(values.size <= 32)
            values.forEach { (key, value) -> require(key.length in 1..128 && value.length in 1..2048) }
        }
        require((embedRegexes?.size ?: 0) <= 16)
        embedRegexes?.forEach { validateRegex(it) }
    }

    /** Return a relative path/template. Unknown keys are a programming error. */
    fun endpoint(key: String): String = requireNotNull(endpoints[key]) { "Unknown endpoint: $key" }

    /**
     * Match complete encoded segments, never substrings or decoded paths. Already-mapped paths
     * take precedence (idempotent), then legacy templates by literal count, length, and key.
     * Validation forbids cross-endpoint overlaps. Placeholder captures are substituted verbatim,
     * including percent escapes; queries belong to the URL builder and must not be passed here.
     * Unknown/unsafe paths are unchanged, and the caller's leading-slash style is preserved.
     */
    fun routePath(encodedPath: String): String {
        val segments = encodedSegments(encodedPath) ?: return encodedPath
        if (orderedEndpoints.any { match(it.value, segments) != null }) return encodedPath
        for ((key, mapped) in orderedEndpoints) {
            val captures = match(key, segments) ?: continue
            val routed = templateSegments(mapped).joinToString("/") { segment ->
                if (isPlaceholder(segment)) captures.getValue(segment) else segment
            }
            return if (encodedPath.startsWith('/')) "/$routed" else routed
        }
        return encodedPath
    }

    /** Accept both a Retrofit legacy template and its remote replacement, using full matches. */
    fun matchesEndpoint(encodedPath: String, key: String): Boolean {
        val mapped = endpoints[key] ?: return false
        val segments = encodedSegments(encodedPath) ?: return false
        return match(key, segments) != null || match(mapped, segments) != null
    }

    /** Domain boundary check: evilidlixku.com never matches idlixku.com. */
    fun isSourceHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).removeSuffix(".")
        if (!validHostSuffix(normalized)) return false
        return player.sourceHostSuffixes.any { normalized == it || normalized.endsWith(".$it") }
    }

    private val orderedEndpoints by lazy {
        endpoints.entries.sortedWith(
            compareByDescending<Map.Entry<String, String>> { entry ->
                templateSegments(entry.key).count { !isPlaceholder(it) }
            }.thenByDescending { templateSegments(it.key).size }.thenBy { it.key }
        )
    }

    companion object {
        const val MAX_CONFIG_BYTES = 256 * 1024

        val REQUIRED_ENDPOINT_KEYS: Set<String> = setOf(
            "api/movies", "api/series", "api/movies/{slug}", "api/series/{slug}",
            "api/series/{slug}/season/{season}/episode/{episode}", "api/genres", "api/search",
            "api/watch/play-info/movie/{id}", "api/watch/play-info/episode/{id}",
            "api/playback/resolve", "api/auth/login", "api/auth/me", "api/auth/refresh",
            "api/auth/logout", "api/auth/sessions", "api/auth/sessions/revoke",
            "api/auth/sessions/logout-others", "api/watch/session/claim"
        )

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            coerceInputValues = false
            allowSpecialFloatingPointValues = false
        }

        /** Pure, Android-independent parser. Invalid or incomplete documents throw. */
        fun parse(text: String): RemoteSourceConfig {
            require(text.length <= MAX_CONFIG_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) {
                "Configuration exceeds 256 KiB"
            }
            // kotlinx accepts quoted numbers ("1") for Int fields; the wire format does not.
            val root = json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("Configuration must be a JSON object")
            listOf("schemaVersion", "version").forEach { field ->
                val value = root[field] as? JsonPrimitive
                require(value != null && !value.isString) { "$field must be a JSON number" }
            }
            return json.decodeFromString<RemoteSourceConfig>(text).validated()
        }

        private fun isPlaceholder(segment: String): Boolean =
            segment.matches(Regex("\\{[A-Za-z][A-Za-z0-9_]*}"))

        private fun placeholders(segments: List<String>): List<String> = segments.filter(::isPlaceholder)

        private fun templateSegments(template: String): List<String> {
            require(template.length in 1..2048 && !template.startsWith('/') && !template.endsWith('/')) {
                "Expected a relative path without leading/trailing slash"
            }
            val segments = template.split('/')
            require(segments.size <= 32)
            segments.forEach { segment ->
                require(isPlaceholder(segment) ||
                    (segment.matches(Regex("[A-Za-z0-9._~-]+")) && segment != "." && segment != "..")) {
                    "Invalid relative path segment"
                }
            }
            require(placeholders(segments).distinct().size == placeholders(segments).size) {
                "Duplicate placeholder"
            }
            return segments
        }

        private fun encodedSegments(path: String): List<String>? {
            if (path.length !in 1..8192 || path.any { it.code <= 32 || it.code >= 127 || it in "?#\\{}" }) return null
            val segments = path.removePrefix("/").split('/')
            if (segments.any { it.isEmpty() }) return null
            for (segment in segments) {
                val dots = segment.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".")
                if (dots == "." || dots == "..") return null
                var i = 0
                while (i < segment.length) {
                    if (segment[i] == '%') {
                        if (i + 2 >= segment.length || segment[i + 1].digitToIntOrNull(16) == null ||
                            segment[i + 2].digitToIntOrNull(16) == null) return null
                        i += 3
                    } else i++
                }
            }
            return segments
        }

        private fun match(template: String, actual: List<String>): Map<String, String>? {
            val expected = templateSegments(template)
            if (expected.size != actual.size) return null
            val captures = mutableMapOf<String, String>()
            expected.zip(actual).forEach { (pattern, segment) ->
                if (isPlaceholder(pattern)) captures[pattern] = segment
                else if (pattern != segment) return null
            }
            return captures
        }

        private fun templatesOverlap(first: String, second: String): Boolean {
            val a = templateSegments(first)
            val b = templateSegments(second)
            return a.size == b.size && a.zip(b).all { (x, y) -> x == y || isPlaceholder(x) || isPlaceholder(y) }
        }

        internal fun requireHttpsUrl(value: String): URI {
            require(value.length in 1..2048 && value.none { it.code <= 32 || it.code >= 127 || it == '\\' }) {
                "Invalid HTTPS URL"
            }
            val uri = try {
                URI(value)
            } catch (invalid: java.net.URISyntaxException) {
                throw IllegalArgumentException("Invalid HTTPS URL", invalid)
            }
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
                "Only absolute HTTPS URLs without credentials or fragments are allowed"
            }
            return uri
        }

        private fun validHostSuffix(value: String): Boolean =
            value.length in 1..253 && value == value.lowercase(Locale.ROOT) && '.' in value &&
                value.split('.').all { label ->
                    label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                        label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
                } && value.any { it in 'a'..'z' }

        private fun validateRegex(pattern: String, requireCapture: Boolean = false) {
            require(pattern.length in 1..2048) { "Invalid regex length" }
            val compiled = Regex(pattern)
            require(!requireCapture || compiled.toPattern().matcher("").groupCount() >= 1) {
                "gateOwnerRegex requires a capture group"
            }
        }
    }
}

/**
 * Pure fallback selection; malformed/stale candidates cannot replace a valid bundled config.
 *
 * baseUrl and the redeem fallbacks receive login cookies and tokens, so a cached or remote
 * candidate is only adopted when its baseUrl host sits inside the source domains compiled into
 * this APK. A candidate cannot widen that trust by declaring its own sourceHostSuffixes. Moving
 * to a genuinely new domain therefore needs a new APK; mirrors under the existing domains do not.
 */
fun effectiveConfig(
    bundled: RemoteSourceConfig,
    cached: RemoteSourceConfig? = null,
    remote: RemoteSourceConfig? = null
): RemoteSourceConfig {
    val trusted = bundled.validated()
    var selected = trusted
    for (candidate in listOfNotNull(cached, remote)) {
        val valid = try { candidate.validated() } catch (_: IllegalArgumentException) { continue }
        val host = try { java.net.URI(valid.baseUrl).host } catch (_: Exception) { null } ?: continue
        if (!trusted.isSourceHost(host)) continue
        if (valid.version >= selected.version) selected = valid
    }
    return selected
}
