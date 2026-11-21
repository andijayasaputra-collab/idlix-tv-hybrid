package com.idlix.data.config

import com.idlix.data.BuildConfig
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure JVM tests for the remote source model, routing and fallback selection. Nothing here
 * performs network I/O; all inputs are the bundled JSON (generated from root remote-source.json
 * into BuildConfig) or small in-memory mutations of it.
 */
class RemoteSourceConfigTest {

    // ---------------------------------------------------------------- helpers

    private val bundledText: String get() = BuildConfig.BUNDLED_REMOTE_SOURCE_JSON
    private fun base(): RemoteSourceConfig = RemoteSourceConfig.parse(bundledText)

    /** Anything rejected by the model is an IllegalArgumentException (SerializationException included). */
    private fun assertInvalid(what: String, block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("Expected IllegalArgumentException: $what")
    }

    private fun assertInvalidConfig(what: String, config: RemoteSourceConfig) =
        assertInvalid(what) { config.validated() }

    private fun mutated(edit: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(bundledText).jsonObject.toMutableMap()
        edit(root)
        return JsonObject(root).toString()
    }

    private fun mutatedPlayer(edit: (MutableMap<String, JsonElement>) -> Unit): String = mutated { root ->
        val player = root.getValue("player").jsonObject.toMutableMap()
        edit(player)
        root["player"] = JsonObject(player)
    }

    private fun withEndpoints(vararg changes: Pair<String, String>): RemoteSourceConfig =
        base().let { it.copy(endpoints = it.endpoints + changes) }

    private fun withPlayer(edit: (RemoteSourceConfig.PlayerConfig) -> RemoteSourceConfig.PlayerConfig) =
        base().let { it.copy(player = edit(it.player)) }

    /** Every endpoint moved from api/... to v2/... with the same placeholders. */
    private fun remapped(): RemoteSourceConfig = base().let { b ->
        b.copy(endpoints = b.endpoints.mapValues { (key, _) -> "v2/" + key.removePrefix("api/") }).validated()
    }

    private fun sample(template: String, capture: String = "a%2Fb"): String =
        template.split('/').joinToString("/") { if (it.startsWith("{")) capture else it }

    private fun versioned(version: Int, baseUrl: String = "https://z3.idlixku.com/") =
        base().copy(version = version, baseUrl = baseUrl)

    // ------------------------------------------------------- parsing: valid

    @Test
    fun bundledDefaultParsesAndMatchesExpectedShape() {
        val config = base()
        assertEquals(1, config.schemaVersion)
        assertTrue(config.version > 0)
        assertEquals("https://z3.idlixku.com/", config.baseUrl)
        assertEquals(RemoteSourceConfig.REQUIRED_ENDPOINT_KEYS, config.endpoints.keys)
        assertEquals(18, config.endpoints.size)
        assertEquals(listOf("idlixku.com"), config.player.sourceHostSuffixes)
    }

    @Test
    fun bundledDefaultIsIdentityMappingAndUsesLiteralPlayerPaths() {
        val config = base()
        config.endpoints.forEach { (key, value) -> assertEquals(key, value) }
        assertEquals("api", config.player.apiPrefix)
        assertEquals("watch", config.player.watchPath)
        assertEquals("api/watch/session/redeem", config.player.redeemPath)
    }

    @Test
    fun bundledDefaultReservedFieldsAreEmptyAndPlayerRegexesCapture() {
        val config = base()
        assertNull(config.selectors?.catalogue)
        assertNull(config.selectors?.episode)
        assertEquals(emptyList<String>(), config.embedRegexes)
        assertEquals("abc123", Regex(config.player.gateOwnerRegex).find("{\"owner\":\"d:abc123\"}")?.groupValues?.get(1))
        val did = "0123456789abcdef0123"
        assertEquals(did, Regex(config.player.didCookieRegex).find("did=$did; Path=/")?.groupValues?.get(1))
    }

    @Test
    fun bundledJsonIsWellInsideSizeLimit() {
        assertTrue(bundledText.toByteArray(Charsets.UTF_8).size < RemoteSourceConfig.MAX_CONFIG_BYTES)
    }

    @Test
    fun validatedReturnsSameInstanceAndIsIdempotent() {
        val config = base()
        assertSame(config, config.validated())
        assertSame(config, config.validated().validated())
    }

    @Test
    fun unknownTopLevelAndNestedKeysAreIgnored() {
        val text = mutated { it["futureField"] = JsonPrimitive("x") }
        assertEquals(base(), RemoteSourceConfig.parse(text))
        val nested = mutatedPlayer { it["futurePlayerField"] = JsonArray(listOf(JsonPrimitive(1))) }
        assertEquals(base(), RemoteSourceConfig.parse(nested))
    }

    @Test
    fun selectorsAndEmbedRegexesMayBeAbsentOrNull() {
        val absent = mutated { it.remove("selectors"); it.remove("embedRegexes") }
        val parsed = RemoteSourceConfig.parse(absent)
        assertNull(parsed.selectors)
        assertNull(parsed.embedRegexes)
        val nulls = mutated { it["selectors"] = JsonNull; it["embedRegexes"] = JsonNull }
        assertNull(RemoteSourceConfig.parse(nulls).embedRegexes)
    }

    @Test
    fun baseUrlWithPathAndPortIsAccepted() {
        base().copy(baseUrl = "https://z3.idlixku.com/v1/").validated()
        base().copy(baseUrl = "https://z3.idlixku.com:8443/").validated()
    }

    @Test
    fun endpointLookupReturnsMappedTemplateAndRejectsUnknownKey() {
        val config = remapped()
        assertEquals("v2/movies/{slug}", config.endpoint("api/movies/{slug}"))
        assertInvalid("unknown endpoint key") { config.endpoint("api/unknown") }
    }

    // --------------------------------------------- parsing: missing / invalid

    @Test
    fun missingRequiredTopLevelFieldIsInvalid() {
        listOf("schemaVersion", "version", "baseUrl", "userAgent", "endpoints", "player").forEach { field ->
            assertInvalid("missing $field") { RemoteSourceConfig.parse(mutated { it.remove(field) }) }
        }
    }

    @Test
    fun missingRequiredPlayerFieldIsInvalid() {
        listOf(
            "apiPrefix", "watchPath", "redeemPath", "fallbackRedeemUrls", "sourceHostSuffixes",
            "gateOwnerRegex", "userAgent", "didCookieRegex"
        ).forEach { field ->
            assertInvalid("missing player.$field") { RemoteSourceConfig.parse(mutatedPlayer { it.remove(field) }) }
        }
    }

    @Test
    fun nullOrWrongTypedRequiredFieldsAreInvalid() {
        assertInvalid("null baseUrl") { RemoteSourceConfig.parse(mutated { it["baseUrl"] = JsonNull }) }
        assertInvalid("string schemaVersion") {
            RemoteSourceConfig.parse(mutated { it["schemaVersion"] = JsonPrimitive("1") })
        }
        assertInvalid("endpoints as array") {
            RemoteSourceConfig.parse(mutated { it["endpoints"] = JsonArray(emptyList()) })
        }
        assertInvalid("non-string endpoint value") {
            RemoteSourceConfig.parse(mutated { root ->
                root["endpoints"] = JsonObject(root.getValue("endpoints").jsonObject + ("api/genres" to JsonPrimitive(7)))
            })
        }
        assertInvalid("null endpoint value") {
            RemoteSourceConfig.parse(mutated { root ->
                root["endpoints"] = JsonObject(root.getValue("endpoints").jsonObject + ("api/genres" to JsonNull))
            })
        }
    }

    @Test
    fun invalidJsonDocumentsAreRejected() {
        listOf("", "   ", "{", "}", "not json", "null", "[]", "42", "\"text\"", "{\"schemaVersion\":1}").forEach {
            assertInvalid("json: '$it'") { RemoteSourceConfig.parse(it) }
        }
        assertInvalid("trailing garbage") { RemoteSourceConfig.parse(bundledText + " trailing") }
        assertInvalid("truncated document") { RemoteSourceConfig.parse(bundledText.dropLast(5)) }
    }

    @Test
    fun unsupportedSchemaVersionIsRejected() {
        listOf(0, 2, 99, -1).forEach { schema ->
            assertInvalidConfig("schemaVersion $schema", base().copy(schemaVersion = schema))
            assertInvalid("parse schemaVersion $schema") {
                RemoteSourceConfig.parse(mutated { it["schemaVersion"] = JsonPrimitive(schema) })
            }
        }
    }

    @Test
    fun nonPositiveVersionIsRejected() {
        listOf(0, -1, Int.MIN_VALUE).forEach { assertInvalidConfig("version $it", base().copy(version = it)) }
        base().copy(version = Int.MAX_VALUE).validated()
    }

    // ------------------------------------------------------------ unsafe URLs

    @Test
    fun unsafeBaseUrlsAreRejected() {
        listOf(
            "http://z3.idlixku.com/",
            "//z3.idlixku.com/",
            "z3.idlixku.com/",
            "/api/",
            "ftp://z3.idlixku.com/",
            "file:///etc/passwd/",
            "javascript:alert(1)/",
            "https://z3.idlixku.com",
            "https://z3.idlixku.com/?token=abc/",
            "https://z3.idlixku.com/#frag/",
            "https://user:secret@z3.idlixku.com/",
            "https://user@z3.idlixku.com/",
            "https://z3.idlixku.com\\@evil.example/",
            "https://z3.idlixku.com/ ",
            "https://z3.idlixku.com/é/",
            "https://z3.idlixku.com:0/",
            "https://z3.idlixku.com:99999/",
            "https:///path/",
            "https://",
            "",
            "https://z3.idlixku.com/" + "a".repeat(2100) + "/"
        ).forEach { assertInvalidConfig("baseUrl '$it'", base().copy(baseUrl = it)) }
    }

    @Test
    fun baseUrlOutsideSourceHostSuffixesIsRejected() {
        listOf(
            "https://evil.example/",
            "https://evilidlixku.com/",
            "https://idlixku.com.evil.example/",
            "https://attacker.example:8443/"
        ).forEach { assertInvalidConfig("baseUrl '$it'", base().copy(baseUrl = it)) }
    }

    @Test
    fun baseUrlInsideSourceHostSuffixesIsAccepted() {
        listOf("https://idlixku.com/", "https://z9.idlixku.com/", "https://a.b.idlixku.com:8443/v1/").forEach {
            base().copy(baseUrl = it).validated()
        }
    }

    @Test
    fun effectiveConfigRejectsCandidateThatDeclaresItsOwnForeignDomain() {
        val bundled = versioned(1)
        val selfDeclared = base().copy(
            version = 99,
            baseUrl = "https://evil.example/",
            player = base().player.copy(sourceHostSuffixes = listOf("evil.example"))
        )
        selfDeclared.validated() // internally consistent, so only the APK-side check can stop it
        assertSame(bundled, effectiveConfig(bundled, remote = selfDeclared))
        assertSame(bundled, effectiveConfig(bundled, cached = selfDeclared))
    }

    @Test
    fun effectiveConfigAcceptsMirrorInsideTheApkTrustedDomain() {
        val bundled = versioned(1)
        val mirror = versioned(2, "https://z9.idlixku.com/")
        assertSame(mirror, effectiveConfig(bundled, remote = mirror))
    }

    @Test
    fun baseUrlOnANewDomainNeedsThatDomainDeclaredInTheSameDocument() {
        val moved = base().copy(
            baseUrl = "https://idlix-new.example/",
            player = base().player.copy(sourceHostSuffixes = listOf("idlixku.com", "idlix-new.example"))
        )
        moved.validated()
    }

    @Test
    fun unsafeFallbackRedeemUrlsAreRejected() {
        listOf(
            "http://e2e.majorplay.net/api/play",
            "https://user:pw@e2e.majorplay.net/api/play",
            "https://e2e.majorplay.net/api/play#frag",
            "//e2e.majorplay.net/api/play",
            "/api/play",
            "javascript:alert(1)",
            "https://e2e.majorplay.net/api play",
            ""
        ).forEach { url ->
            assertInvalidConfig("fallback '$url'", withPlayer { it.copy(fallbackRedeemUrls = listOf(url)) })
        }
    }

    @Test
    fun fallbackRedeemUrlCountMustBeOneToEight() {
        fun urls(n: Int) = (1..n).map { "https://e$it.majorplay.net/api/play" }
        assertInvalidConfig("0 urls", withPlayer { it.copy(fallbackRedeemUrls = emptyList()) })
        assertInvalidConfig("9 urls", withPlayer { it.copy(fallbackRedeemUrls = urls(9)) })
        withPlayer { it.copy(fallbackRedeemUrls = urls(1)) }.validated()
        withPlayer { it.copy(fallbackRedeemUrls = urls(8)) }.validated()
    }

    @Test
    fun oneBadFallbackUrlAmongGoodOnesInvalidatesConfig() {
        val mixed = listOf("https://e2e.majorplay.net/api/play", "http://downgrade.example/api/play")
        assertInvalidConfig("mixed urls", withPlayer { it.copy(fallbackRedeemUrls = mixed) })
    }

    @Test
    fun userAgentsMustBeNonEmptyBoundedAscii() {
        listOf("", "x".repeat(513), "café", "line\nbreak", "tab\there", "nul\u0000", "del\u007f").forEach {
            assertInvalidConfig("userAgent '$it'", base().copy(userAgent = it))
            assertInvalidConfig("player.userAgent '$it'", withPlayer { p -> p.copy(userAgent = it) })
        }
        base().copy(userAgent = "x".repeat(512)).validated()
        base().copy(userAgent = "x").validated()
    }

    // ---------------------------------------------------------- regex checks

    @Test
    fun badRegexesAreRejected() {
        listOf("(", "[", "*abc", "(?<", "\\", "").forEach {
            assertInvalidConfig("gate '$it'", withPlayer { p -> p.copy(gateOwnerRegex = it) })
            assertInvalidConfig("did '$it'", withPlayer { p -> p.copy(didCookieRegex = it) })
            assertInvalidConfig("embed '$it'", base().copy(embedRegexes = listOf(it)))
        }
        val oversized = "a".repeat(2049)
        assertInvalidConfig("oversized gate", withPlayer { it.copy(gateOwnerRegex = "($oversized)") })
        assertInvalidConfig("oversized embed", base().copy(embedRegexes = listOf(oversized)))
    }

    @Test
    fun playerRegexesRequireACaptureGroup() {
        listOf("owner", "(?:owner)", "\\(literal\\)", "[(]").forEach {
            assertInvalidConfig("gate no capture '$it'", withPlayer { p -> p.copy(gateOwnerRegex = it) })
            assertInvalidConfig("did no capture '$it'", withPlayer { p -> p.copy(didCookieRegex = it) })
        }
        withPlayer { it.copy(gateOwnerRegex = "(?<owner>[a-z]+)") }.validated()
        withPlayer { it.copy(didCookieRegex = "did=([0-9a-f]+)") }.validated()
    }

    @Test
    fun embedRegexesNeedNoCaptureButAreBoundedToSixteen() {
        base().copy(embedRegexes = listOf("https://[a-z]+\\.example/embed")).validated()
        base().copy(embedRegexes = (1..16).map { "x$it" }).validated()
        assertInvalidConfig("17 embed regexes", base().copy(embedRegexes = (1..17).map { "x$it" }))
        base().copy(embedRegexes = emptyList()).validated()
    }

    // ------------------------------------------------ unknown/missing endpoints

    @Test
    fun missingEndpointIsRejectedForEveryRequiredKey() {
        RemoteSourceConfig.REQUIRED_ENDPOINT_KEYS.forEach { key ->
            val config = base().let { it.copy(endpoints = it.endpoints - key) }
            assertInvalidConfig("missing endpoint $key", config)
        }
    }

    @Test
    fun unknownEndpointKeyIsRejected() {
        assertInvalidConfig("extra key", withEndpoints("api/extra" to "api/extra"))
        assertInvalidConfig("extra placeholder key", withEndpoints("api/extra/{id}" to "api/extra/{id}"))
        val renamed = base().let { it.copy(endpoints = (it.endpoints - "api/genres") + ("api/genre" to "api/genre")) }
        assertInvalidConfig("renamed key", renamed)
        assertInvalidConfig("no endpoints", base().copy(endpoints = emptyMap()))
    }

    @Test
    fun endpointKeysAreMatchedExactlyNotCaseInsensitively() {
        val config = base().let { it.copy(endpoints = (it.endpoints - "api/genres") + ("API/genres" to "api/genres")) }
        assertInvalidConfig("case-changed key", config)
    }

    // ----------------------------------------------- no path traversal / syntax

    @Test
    fun unsafeEndpointValuesAreRejected() {
        listOf(
            "/api/genres", "api/genres/", "", "api//genres", "../genres", "api/../genres", "api/./genres",
            ".", "..", "api/%2e%2e/genres", "api/%2Fgenres", "api/genres?x=1", "api/genres#x",
            "api\\genres", "api/gen res", "https://evil.example/api/genres", "//evil.example/genres",
            "api/genrés", "api/{", "api/{}", "api/{1x}", "api/{a-b}", "api/genres\n", "api/ge:nres",
            "a/".repeat(40) + "genres", "g".repeat(2049)
        ).forEach { assertInvalidConfig("endpoint value '$it'", withEndpoints("api/genres" to it)) }
    }

    @Test
    fun pathTraversalInDynamicEndpointValuesIsRejected() {
        assertInvalidConfig("dotdot dynamic", withEndpoints("api/movies/{slug}" to "api/../{slug}"))
        assertInvalidConfig("dot dynamic", withEndpoints("api/movies/{slug}" to "./movies/{slug}"))
        assertInvalidConfig("encoded dot dynamic", withEndpoints("api/movies/{slug}" to "api/%2e%2e/{slug}"))
    }

    @Test
    fun playerPathsMustBeLiteralRelativeSafePaths() {
        listOf("api/{x}", "{x}", "/api", "api/", "", "../api", "api/../x", "api?x=1", "api//x").forEach {
            assertInvalidConfig("apiPrefix '$it'", withPlayer { p -> p.copy(apiPrefix = it) })
            assertInvalidConfig("watchPath '$it'", withPlayer { p -> p.copy(watchPath = it) })
            assertInvalidConfig("redeemPath '$it'", withPlayer { p -> p.copy(redeemPath = it) })
        }
    }

    // ------------------------------------------------------ placeholder parity

    @Test
    fun renamedPlaceholderIsRejected() {
        assertInvalidConfig("slug to id", withEndpoints("api/movies/{slug}" to "api/movies/{id}"))
    }

    @Test
    fun droppedOrAddedPlaceholderIsRejected() {
        assertInvalidConfig("dropped", withEndpoints("api/movies/{slug}" to "api/movies/fixed"))
        assertInvalidConfig("added", withEndpoints("api/genres" to "api/genres/{extra}"))
        assertInvalidConfig(
            "episode loses a placeholder",
            withEndpoints(
                "api/series/{slug}/season/{season}/episode/{episode}" to "api/series/{slug}/season/{season}/episode/1"
            )
        )
    }

    @Test
    fun duplicatePlaceholderInMappedValueIsRejected() {
        assertInvalidConfig(
            "duplicate in mapped value",
            withEndpoints(
                "api/series/{slug}/season/{season}/episode/{episode}" to "api/series/{slug}/season/{slug}/episode/{episode}"
            )
        )
    }

    @Test
    fun reorderedPlaceholdersWithSameNamesAreAccepted() {
        val episode = "api/series/{slug}/season/{season}/episode/{episode}"
        val config = withEndpoints(episode to "v3/{episode}/e/{season}/s/{slug}").validated()
        assertEquals("/v3/9/e/2/s/a%2Fb", config.routePath("/api/series/a%2Fb/season/2/episode/9"))
    }

    // ----------------------------------------------------------------- overlaps

    @Test
    fun staticMappingOverlappingAnotherDynamicEndpointIsRejected() {
        assertInvalidConfig("genres onto movies/{slug}", withEndpoints("api/genres" to "api/movies/popular"))
    }

    @Test
    fun dynamicMappingOverlappingAnotherEndpointIsRejected() {
        assertInvalidConfig("movies/{slug} onto series/{slug}", withEndpoints("api/movies/{slug}" to "api/series/{slug}"))
    }

    @Test
    fun twoEndpointsMappedToTheSameTargetAreRejected() {
        assertInvalidConfig("search onto me", withEndpoints("api/search" to "api/auth/me"))
        assertInvalidConfig(
            "login and logout share target",
            withEndpoints("api/auth/login" to "api/auth/x", "api/auth/logout" to "api/auth/x")
        )
    }

    @Test
    fun mappingOntoAnotherEndpointsLegacyKeyIsRejected() {
        assertInvalidConfig("me onto refresh", withEndpoints("api/auth/me" to "api/auth/refresh"))
    }

    @Test
    fun placeholderOverlapAtDifferentPositionsIsRejected() {
        // v2/{slug}/x overlaps v2/y/{slug} even though neither is a prefix of the other.
        val config = base().let {
            it.copy(
                endpoints = it.endpoints +
                    ("api/movies/{slug}" to "v2/{slug}/x") +
                    ("api/series/{slug}" to "v2/y/{slug}")
            )
        }
        assertInvalidConfig("crossed placeholder overlap", config)
    }

    @Test
    fun disjointWholesaleRemapIsAccepted() {
        val config = remapped()
        assertEquals("v2/auth/me", config.endpoint("api/auth/me"))
        assertEquals(18, config.endpoints.size)
    }

    // ------------------------------------------------- source host suffix rules

    @Test
    fun invalidSourceHostSuffixesAreRejected() {
        listOf(
            "", "com", "localhost", ".idlixku.com", "idlixku.com.", "idlixku..com", "*.idlixku.com",
            "IDLIXKU.com", "https://idlixku.com", "idlixku.com/path", "idlixku.com:443", "user@idlixku.com",
            "127.0.0.1", "-idlixku.com", "idlixku-.com", "idlix_ku.com", "idlixku.com ", "idlixku.cöm",
            "a".repeat(64) + ".com", "a.".repeat(130) + "com"
        ).forEach {
            assertInvalidConfig("suffix '$it'", withPlayer { p -> p.copy(sourceHostSuffixes = listOf(it)) })
        }
    }

    @Test
    fun sourceHostSuffixCountAndUniquenessAreEnforced() {
        fun suffixes(n: Int) = (1..n).map { "h$it.example.com" }
        assertInvalidConfig("empty", withPlayer { it.copy(sourceHostSuffixes = emptyList()) })
        assertInvalidConfig("17", withPlayer { it.copy(sourceHostSuffixes = suffixes(17)) })
        assertInvalidConfig(
            "duplicates",
            withPlayer { it.copy(sourceHostSuffixes = listOf("idlixku.com", "idlixku.com")) }
        )
        // The limit is 16 and baseUrl's own domain must be one of them.
        withPlayer { it.copy(sourceHostSuffixes = listOf("idlixku.com") + suffixes(15)) }.validated()
        assertInvalidConfig("16 without the baseUrl domain", withPlayer { it.copy(sourceHostSuffixes = suffixes(16)) })
    }

    // ------------------------------------------------------ reserved selectors

    @Test
    fun reservedSelectorsAreBoundedButOtherwiseUnused() {
        fun selectors(n: Int) = (1..n).associate { "k$it" to "v$it" }
        base().copy(selectors = RemoteSourceConfig.ReservedSelectors(selectors(32), selectors(32))).validated()
        assertInvalidConfig(
            "33 catalogue",
            base().copy(selectors = RemoteSourceConfig.ReservedSelectors(catalogue = selectors(33)))
        )
        assertInvalidConfig(
            "33 episode",
            base().copy(selectors = RemoteSourceConfig.ReservedSelectors(episode = selectors(33)))
        )
        assertInvalidConfig(
            "empty key",
            base().copy(selectors = RemoteSourceConfig.ReservedSelectors(catalogue = mapOf("" to "v")))
        )
        assertInvalidConfig(
            "empty value",
            base().copy(selectors = RemoteSourceConfig.ReservedSelectors(episode = mapOf("k" to "")))
        )
        assertInvalidConfig(
            "oversized value",
            base().copy(selectors = RemoteSourceConfig.ReservedSelectors(episode = mapOf("k" to "v".repeat(2049))))
        )
        assertInvalidConfig(
            "oversized key",
            base().copy(selectors = RemoteSourceConfig.ReservedSelectors(catalogue = mapOf("k".repeat(129) to "v")))
        )
    }

    // -------------------------------------------------------------- size bounds

    @Test
    fun documentExactlyAtByteLimitParsesAndOneByteMoreFails() {
        val padding = RemoteSourceConfig.MAX_CONFIG_BYTES - bundledText.toByteArray(Charsets.UTF_8).size
        assertTrue(padding > 0)
        assertEquals(base(), RemoteSourceConfig.parse(bundledText + " ".repeat(padding)))
        try {
            RemoteSourceConfig.parse(bundledText + " ".repeat(padding + 1))
            fail("expected size rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("256 KiB"))
        }
    }

    @Test
    fun byteLimitCountsUtf8BytesNotCharacters() {
        // 140k two-byte characters: under the limit as chars, over it as bytes.
        val over = mutated { it["ignored"] = JsonPrimitive("é".repeat(140_000)) }
        assertTrue(over.length <= RemoteSourceConfig.MAX_CONFIG_BYTES)
        try {
            RemoteSourceConfig.parse(over)
            fail("expected byte-size rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("256 KiB"))
        }
        val under = mutated { it["ignored"] = JsonPrimitive("é".repeat(100_000)) }
        assertEquals(base(), RemoteSourceConfig.parse(under))
    }

    // ------------------------------------------------ routePath: bundled/remap

    @Test
    fun routePathIsIdentityForBundledConfigAndPreservesLeadingSlashStyle() {
        val config = base()
        config.endpoints.keys.forEach { key ->
            listOf("/" + sample(key), sample(key)).forEach { path -> assertEquals(path, config.routePath(path)) }
        }
    }

    @Test
    fun routePathRemapsEveryEndpointAndIsIdempotent() {
        val config = remapped()
        config.endpoints.forEach { (legacy, mapped) ->
            listOf("a%2Fb", "plain", "x%20y", "caf%C3%A9").forEach { capture ->
                val oldPath = "/" + sample(legacy, capture)
                val newPath = "/" + sample(mapped, capture)
                assertEquals(newPath, config.routePath(oldPath))
                assertEquals(newPath, config.routePath(newPath))
                assertEquals(newPath.removePrefix("/"), config.routePath(oldPath.removePrefix("/")))
                assertEquals(newPath.removePrefix("/"), config.routePath(newPath.removePrefix("/")))
            }
        }
    }

    @Test
    fun routePathPreservesEncodedSlashAsSingleSegmentWithoutDecoding() {
        val config = remapped()
        assertEquals("/v2/movies/foo%2Fbar", config.routePath("/api/movies/foo%2Fbar"))
        assertEquals("/v2/movies/foo%2fbar", config.routePath("/api/movies/foo%2fbar"))
        assertEquals("/v2/movies/a%252Fb", config.routePath("/api/movies/a%252Fb"))
        assertEquals("/v2/movies/%2F", config.routePath("/api/movies/%2F"))
        assertEquals("/v2/series/a%2Fb/season/1%2F2/episode/3", config.routePath("/api/series/a%2Fb/season/1%2F2/episode/3"))
        // An encoded slash must not turn one segment into two and so must not match a shorter template.
        assertEquals("/api/movies%2Fx", config.routePath("/api/movies%2Fx"))
    }

    @Test
    fun routePathDistinguishesStaticFromDynamicTemplates() {
        val config = remapped()
        assertEquals("/v2/movies", config.routePath("/api/movies"))
        assertEquals("/v2/movies/popular", config.routePath("/api/movies/popular"))
        assertEquals("/v2/series/season", config.routePath("/api/series/season"))
        assertEquals("/v2/auth/sessions", config.routePath("/api/auth/sessions"))
        assertEquals("/v2/auth/sessions/revoke", config.routePath("/api/auth/sessions/revoke"))
        assertEquals("/v2/movies", config.routePath("/v2/movies"))
        assertEquals("/v2/movies/popular", config.routePath("/v2/movies/popular"))
    }

    @Test
    fun routePathLeavesUnknownAndNearMissPathsUnchanged() {
        val config = remapped()
        listOf(
            "/api/unknown", "/api", "/api/", "/xapi/movies", "/api/moviesx", "/API/movies", "/api/movies/a/b",
            "/prefix/api/movies", "/v3/movies", "/api/auth/me/extra", "/api/auth", "/watch/x"
        ).forEach { assertEquals(it, config.routePath(it)) }
    }

    @Test
    fun routePathReturnsTraversalAndMalformedPathsUnchanged() {
        val config = remapped()
        listOf(
            "", "/", "//", "/api//movies", "/api/movies/", "/api/movies//x",
            "/api/movies/..", "/api/movies/.", "/api/../api/movies", "/api/movies/../auth/me",
            "/api/movies/%2e%2e", "/api/movies/%2E%2E", "/api/movies/%2e", "/api/%2e%2e/auth/me",
            "/api/movies/%", "/api/movies/%2", "/api/movies/%zz", "/api/movies/%2g", "/api/movies/a%",
            "/api/movies?x=1", "/api/movies#x", "/api/movies/a?x=1", "/api\\movies", "/api/movies\\x",
            "/api/movies/ x", "/api/movies/\tx", "/api/movies/\nx", "/api/movies/é", "/api/movies/\u007f",
            "/api/movies/{slug}", "/api/{x}", "/api/movies/a{b}", "http://evil.example/api/movies",
            "/" + "a/".repeat(5000)
        ).forEach { assertEquals("unchanged: $it", it, config.routePath(it)) }
    }

    @Test
    fun routePathRemapsPlaceholderCapturesVerbatimIncludingReorder() {
        val episode = "api/series/{slug}/season/{season}/episode/{episode}"
        val config = withEndpoints(episode to "v3/{episode}/e/{season}/s/{slug}").validated()
        assertEquals("/v3/9/e/2/s/a%2Fb", config.routePath("/api/series/a%2Fb/season/2/episode/9"))
        assertEquals("v3/9%2F1/e/2/s/a", config.routePath("api/series/a/season/2/episode/9%2F1"))
        assertEquals("/v3/9/e/2/s/a%2Fb", config.routePath("/v3/9/e/2/s/a%2Fb"))
    }

    @Test
    fun routePathDoesNotMutateTheConfig() {
        val config = remapped()
        val before = config.endpoints.toMap()
        repeat(3) { config.routePath("/api/movies/foo%2Fbar") }
        assertEquals(before, config.endpoints)
        assertEquals(config.routePath("/api/movies/x"), config.routePath("/api/movies/x"))
    }

    // ----------------------------------------------------------- matchesEndpoint

    @Test
    fun matchesEndpointAcceptsLegacyAndRemappedForms() {
        val config = remapped()
        assertTrue(config.matchesEndpoint("/api/auth/me", "api/auth/me"))
        assertTrue(config.matchesEndpoint("/v2/auth/me", "api/auth/me"))
        assertTrue(config.matchesEndpoint("v2/auth/me", "api/auth/me"))
        assertTrue(config.matchesEndpoint("api/auth/me", "api/auth/me"))
        assertTrue(config.matchesEndpoint("/api/auth/login", "api/auth/login"))
        assertTrue(config.matchesEndpoint("/v2/auth/login", "api/auth/login"))
        assertTrue(config.matchesEndpoint("/api/auth/refresh", "api/auth/refresh"))
        assertTrue(config.matchesEndpoint("/v2/auth/logout", "api/auth/logout"))
        assertTrue(config.matchesEndpoint("/v2/watch/session/claim", "api/watch/session/claim"))
    }

    @Test
    fun matchesEndpointWorksForBundledIdentityMapping() {
        val config = base()
        config.endpoints.keys.forEach { key ->
            assertTrue(key, config.matchesEndpoint("/" + sample(key), key))
            assertTrue(key, config.matchesEndpoint(sample(key), key))
        }
    }

    @Test
    fun matchesEndpointDoesNotCrossBetweenAuthEndpoints() {
        val config = remapped()
        val auth = listOf(
            "api/auth/login", "api/auth/me", "api/auth/refresh", "api/auth/logout",
            "api/auth/sessions", "api/auth/sessions/revoke", "api/auth/sessions/logout-others"
        )
        auth.forEach { path ->
            auth.filter { it != path }.forEach { other ->
                assertFalse("$path vs $other", config.matchesEndpoint("/$path", other))
                assertFalse("v2 $path vs $other", config.matchesEndpoint("/v2/" + path.removePrefix("api/"), other))
            }
        }
    }

    @Test
    fun matchesEndpointRequiresFullSegmentMatchesOnly() {
        val config = remapped()
        listOf(
            "/api/auth/mex", "/api/auth/me/", "/api/auth/me/extra", "/xapi/auth/me", "/api/auth",
            "/api/auth/me?x=1", "/api/auth/me#x", "/v2/auth/me/", "/v2/auth/mex", "/api/AUTH/me",
            "/api/auth//me", "", "/"
        ).forEach { assertFalse(it, config.matchesEndpoint(it, "api/auth/me")) }
    }

    @Test
    fun matchesEndpointRejectsUnknownKeysAndPlaceholderLiterals() {
        val config = remapped()
        assertFalse(config.matchesEndpoint("/api/auth/me", "api/auth/unknown"))
        assertFalse(config.matchesEndpoint("/api/auth/me", ""))
        assertFalse(config.matchesEndpoint("/v2/auth/me", "v2/auth/me"))
        assertFalse(config.matchesEndpoint("/api/movies/{slug}", "api/movies/{slug}"))
    }

    @Test
    fun matchesEndpointHandlesDynamicSegmentsAndEncodedSlash() {
        val config = remapped()
        assertTrue(config.matchesEndpoint("/api/movies/a%2Fb", "api/movies/{slug}"))
        assertTrue(config.matchesEndpoint("/v2/movies/a%2Fb", "api/movies/{slug}"))
        assertFalse(config.matchesEndpoint("/api/movies/a/b", "api/movies/{slug}"))
        assertFalse(config.matchesEndpoint("/v2/movies/a/b", "api/movies/{slug}"))
        assertFalse(config.matchesEndpoint("/api/movies", "api/movies/{slug}"))
        assertFalse(config.matchesEndpoint("/v2/movies/x", "api/movies"))
        assertTrue(config.matchesEndpoint("/v2/movies", "api/movies"))
        assertTrue(config.matchesEndpoint("/v2/series/a/season/1/episode/2", "api/series/{slug}/season/{season}/episode/{episode}"))
    }

    @Test
    fun matchesEndpointRejectsTraversalAndMalformedEncodings() {
        val config = remapped()
        listOf(
            "/api/movies/..", "/api/movies/%2e%2e", "/api/movies/%2E", "/api/movies/%", "/api/movies/%zz",
            "/v2/movies/..", "/v2/movies/%2e%2e", "/v2/movies/ x", "/v2/movies/é"
        ).forEach { assertFalse(it, config.matchesEndpoint(it, "api/movies/{slug}")) }
    }

    // -------------------------------------------------------------- host boundary

    @Test
    fun isSourceHostAcceptsExactAndSubdomainsCaseInsensitively() {
        val config = base()
        listOf("idlixku.com", "z3.idlixku.com", "a.b.idlixku.com", "IDLIXKU.COM", "Z3.IdlixKu.Com", "idlixku.com.", "z3.idlixku.com.")
            .forEach { assertTrue(it, config.isSourceHost(it)) }
    }

    @Test
    fun isSourceHostRejectsLookalikesAndMaliciousHosts() {
        val config = base()
        listOf(
            "evilidlixku.com", "xidlixku.com", "idlixku.com.evil.com", "idlixku.com.evil", "idlixku.co",
            "idlixku.comx", "idlixku.org", "evil.com", "com", "", ".", "..", ".idlixku.com", "a..idlixku.com",
            "idlixku.com..", "-idlixku.com", "idlixku.com@evil.com", "evil.com@idlixku.com",
            "evil.com/idlixku.com", "idlixku.com/evil", "idlixku.com:443", "idlixku.com?x", "idlixku.com#x",
            "idlixku.com\\evil.com", "idlixku.com evil.com", " idlixku.com", "idlixku.com ", "idlixku.com\n",
            "idlixku.com\u0000.evil.com", "idlixku.com․evil.com", "ıdlixku.com", "idlïxku.com",
            "xn--idlixku-3ya.com", "idlixku_com.evil.com", "127.0.0.1", "[::1]", "idlixku.com%2eevil.com"
        ).forEach { assertFalse("'$it'", config.isSourceHost(it)) }
    }

    @Test
    fun isSourceHostSupportsMultipleSuffixesWithSameBoundaryRules() {
        val config = withPlayer { it.copy(sourceHostSuffixes = listOf("idlixku.com", "mirror.example")) }.validated()
        assertTrue(config.isSourceHost("mirror.example"))
        assertTrue(config.isSourceHost("cdn.mirror.example"))
        assertTrue(config.isSourceHost("z3.idlixku.com"))
        assertFalse(config.isSourceHost("notmirror.example"))
        assertFalse(config.isSourceHost("example"))
        assertFalse(config.isSourceHost("mirror.example.evil.com"))
    }

    // -------------------------------------------------------------- effectiveConfig

    @Test
    fun effectiveConfigReturnsBundledWhenNoCandidates() {
        val bundled = versioned(1)
        assertSame(bundled, effectiveConfig(bundled))
        assertSame(bundled, effectiveConfig(bundled, null, null))
    }

    @Test
    fun effectiveConfigPicksHighestValidVersionAcrossCachedAndRemote() {
        val bundled = versioned(1)
        val cached = versioned(3, "https://cached.idlixku.com/")
        val remote = versioned(5, "https://remote.idlixku.com/")
        assertSame(remote, effectiveConfig(bundled, cached, remote))
        assertSame(cached, effectiveConfig(bundled, cached, versioned(2, "https://remote.idlixku.com/")))
        assertSame(cached, effectiveConfig(bundled, cached = cached))
        assertSame(remote, effectiveConfig(bundled, remote = remote))
    }

    @Test
    fun effectiveConfigOrderOfCachedAndRemoteDoesNotChangeHigherVersionWinner() {
        val bundled = versioned(1)
        val low = versioned(2, "https://low.idlixku.com/")
        val high = versioned(4, "https://high.idlixku.com/")
        assertSame(high, effectiveConfig(bundled, cached = low, remote = high))
        assertSame(high, effectiveConfig(bundled, cached = high, remote = low))
    }

    @Test
    fun effectiveConfigNeverSelectsOlderThanBundled() {
        val bundled = versioned(5)
        assertSame(
            bundled,
            effectiveConfig(bundled, versioned(4, "https://cached.idlixku.com/"), versioned(1, "https://remote.idlixku.com/"))
        )
    }

    @Test
    fun effectiveConfigTiePrefersRemoteOverCachedOverBundled() {
        val bundled = versioned(2, "https://bundled.idlixku.com/")
        val cached = versioned(2, "https://cached.idlixku.com/")
        val remote = versioned(2, "https://remote.idlixku.com/")
        assertSame(cached, effectiveConfig(bundled, cached = cached))
        assertSame(remote, effectiveConfig(bundled, remote = remote))
        assertSame(remote, effectiveConfig(bundled, cached, remote))
    }

    @Test
    fun effectiveConfigSkipsInvalidCandidatesWithoutThrowing() {
        val bundled = versioned(1)
        val badSchema = versioned(99, "https://bad.idlixku.com/").copy(schemaVersion = 2)
        val badUrl = versioned(99, "http://downgrade.example/")
        val badEndpoints = versioned(99, "https://bad.idlixku.com/").let { it.copy(endpoints = it.endpoints - "api/auth/me") }
        val badRegex = versioned(99, "https://bad.idlixku.com/").let { it.copy(player = it.player.copy(gateOwnerRegex = "(")) }
        val goodRemote = versioned(2, "https://good.idlixku.com/")
        listOf(badSchema, badUrl, badEndpoints, badRegex).forEach { bad ->
            assertSame(bundled, effectiveConfig(bundled, cached = bad))
            assertSame(bundled, effectiveConfig(bundled, remote = bad))
            assertSame(bundled, effectiveConfig(bundled, bad, bad))
            assertSame(goodRemote, effectiveConfig(bundled, cached = bad, remote = goodRemote))
            assertSame(goodRemote, effectiveConfig(bundled, cached = goodRemote, remote = bad))
        }
    }

    @Test
    fun effectiveConfigFallsBackToCachedWhenRemoteIsInvalidAndNewer() {
        val bundled = versioned(1)
        val cached = versioned(3, "https://cached.idlixku.com/")
        val badRemote = versioned(10, "https://remote.idlixku.com/").copy(schemaVersion = 7)
        assertSame(cached, effectiveConfig(bundled, cached, badRemote))
    }

    @Test
    fun effectiveConfigRejectsInvalidBundledBaseline() {
        assertInvalid("invalid bundled") { effectiveConfig(base().copy(schemaVersion = 2)) }
    }

    @Test
    fun effectiveConfigIsPureAndDoesNotMutateInputs() {
        val bundled = versioned(1)
        val cached = versioned(2, "https://cached.idlixku.com/")
        val remote = versioned(3, "https://remote.idlixku.com/")
        val snapshot = listOf(bundled.copy(), cached.copy(), remote.copy())
        val first = effectiveConfig(bundled, cached, remote)
        val second = effectiveConfig(bundled, cached, remote)
        assertEquals(first, second)
        assertEquals(snapshot, listOf(bundled, cached, remote))
    }

    @Test
    fun effectiveConfigCanComposeLikeStartupDoes() {
        // RemoteConfigManager calls effectiveConfig(bundled, cached) then effectiveConfig(local, remote = remote).
        val bundled = versioned(1)
        val local = effectiveConfig(bundled, cached = versioned(4, "https://cached.idlixku.com/"))
        assertEquals("https://cached.idlixku.com/", local.baseUrl)
        assertSame(local, effectiveConfig(local, remote = versioned(3, "https://remote.idlixku.com/")))
        val upgraded = versioned(6, "https://remote.idlixku.com/")
        assertSame(upgraded, effectiveConfig(local, remote = upgraded))
    }

    // ---------------------------------------------------- bounded UTF-8 input

    private val limit get() = RemoteSourceConfig.MAX_CONFIG_BYTES

    @Test
    fun readBoundedUtf8ReadsEmptyAsciiAndMultibyteText() {
        assertEquals("", readBoundedUtf8(ByteArrayInputStream(ByteArray(0))))
        assertEquals("hello", readBoundedUtf8(ByteArrayInputStream("hello".toByteArray())))
        val text = "héllo ✓ 😀 日本語"
        assertEquals(text, readBoundedUtf8(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun readBoundedUtf8HandlesMultibyteCharactersAcrossInternalBufferBoundaries() {
        val text = "é".repeat(10_001) + "✓".repeat(3_001)
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, readBoundedUtf8(ByteArrayInputStream(bytes)))
        // One byte per read forces every multibyte sequence to be split across reads.
        assertEquals(text, readBoundedUtf8(OneByteAtATime(bytes)))
    }

    @Test
    fun readBoundedUtf8AcceptsExactlyTheLimitAndRejectsOneMoreByte() {
        val atLimit = ByteArray(limit) { 'a'.code.toByte() }
        assertEquals(limit, readBoundedUtf8(ByteArrayInputStream(atLimit)).length)
        val over = ByteArray(limit + 1) { 'a'.code.toByte() }
        try {
            readBoundedUtf8(ByteArrayInputStream(over))
            fail("expected size rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("256 KiB"))
        }
    }

    @Test
    fun readBoundedUtf8StopsReadingEndlessStreamsAtLimitPlusOne() {
        val endless = EndlessStream()
        assertInvalid("endless stream") { readBoundedUtf8(endless) }
        assertTrue("read ${endless.total} bytes", endless.total <= limit + 1L)
    }

    @Test
    fun readBoundedUtf8CountsBytesNotChars() {
        // 140k two-byte characters = 280k bytes > limit, but only 140k chars.
        val bytes = "é".repeat(140_000).toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > limit)
        assertInvalid("multibyte over limit") { readBoundedUtf8(ByteArrayInputStream(bytes)) }
        assertInvalid("multibyte over limit, one byte per read") { readBoundedUtf8(OneByteAtATime(bytes)) }
    }

    @Test
    fun readBoundedUtf8RejectsMalformedUtf8() {
        listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()),
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf('a'.code.toByte(), 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte())
        ).forEach { bytes ->
            try {
                readBoundedUtf8(ByteArrayInputStream(bytes))
                fail("expected CharacterCodingException for ${bytes.joinToString { "%02x".format(it) }}")
            } catch (expected: CharacterCodingException) {
                assertNotNull(expected)
            }
        }
    }

    @Test
    fun boundedStreamOfBundledJsonRoundTripsThroughParse() {
        val text = readBoundedUtf8(ByteArrayInputStream(bundledText.toByteArray(Charsets.UTF_8)))
        assertEquals(bundledText, text)
        assertEquals(base(), RemoteSourceConfig.parse(text))
    }

    private class OneByteAtATime(private val bytes: ByteArray) : InputStream() {
        private var index = 0
        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val next = read()
            if (next == -1) return -1
            b[off] = next.toByte()
            return 1
        }
    }

    private class EndlessStream : InputStream() {
        var total = 0L
        override fun read(): Int {
            total++
            return 'a'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte())
            total += len
            return len
        }
    }
}
