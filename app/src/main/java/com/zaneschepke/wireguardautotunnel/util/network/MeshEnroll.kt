package com.zaneschepke.wireguardautotunnel.util.network

import com.wgtunnel.parser.Config
import com.wgtunnel.parser.crypto.Key
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.OffsetDateTime
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Starfleet Provisioning: `transwarp://enroll?u=<https base>&t=<token>&ca=<sha256 hex of CA DER>[&k=nebula]`. */
object MeshEnroll {
    data class Code(val baseUrl: String, val token: String, val caSha256: String, val nebula: Boolean = false)

    /** [nebulaProfile] is set for Nebula codes; [config] is then a peerless placeholder (see [nebulaPlaceholder]). */
    data class Result(val config: String, val name: String?, val nebulaProfile: String = "")

    fun parseCode(raw: String): Code? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("transwarp", true) || !uri.host.equals("enroll", true)) return null
        val q =
            uri.rawQuery.orEmpty().split('&').mapNotNull {
                val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                k to URLDecoder.decode(v, "UTF-8")
            }.toMap()
        val u = q["u"]?.trimEnd('/') ?: return null
        val t = q["t"]?.takeIf { it.isNotBlank() } ?: return null
        val ca = q["ca"]?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        if (!u.startsWith("https://")) return null
        return Code(u, t, ca, q["k"].equals("nebula", true))
    }

    /**
     * Stored quickConfig of a Nebula tunnel: keeps the private key plus the Transwarp-Name / -Expires headers that
     * renew-in-place and the expiry warnings already read, and parses like any peerless conf.
     */
    fun nebulaPlaceholder(privateKey: String, ip: String, name: String?, expiresAt: String?,
        renewedAt: String = java.time.Instant.now().toString()): String =
        listOfNotNull(name?.let { "# Transwarp-Name = $it" }, expiresAt?.let { "# Transwarp-Expires = $it" },
            "# Transwarp-Renewed = $renewedAt")
            .plus(listOf("[Interface]", "PrivateKey = $privateKey", "Address = $ip"))
            .joinToString("\n")

    /** Server conf comes without a PrivateKey; ours goes right after `[Interface]`. */
    fun insertPrivateKey(conf: String, privateKey: String): String {
        val lines = conf.lines().toMutableList()
        val i = lines.indexOfFirst { it.trim().equals("[Interface]", true) }
        require(i >= 0) { "no [Interface] section" }
        lines.add(i + 1, "PrivateKey = $privateKey")
        return lines.joinToString("\n")
    }

    /** The parser drops comments inside `[Peer]`; keep gofire's `# Name = x` as a header `# Peer <key> = x`. */
    fun hoistPeerNames(conf: String): String {
        var name: String? = null
        val header = mutableListOf<String>()
        conf.lines().map { it.trim() }.forEach { l ->
            when {
                l.startsWith("[") -> name = null
                NAME_COMMENT.matches(l) -> name = NAME_COMMENT.find(l)!!.groupValues[1].trim()
                TRANSWARP_HEADER.matches(l) -> header += l
                l.startsWith("PublicKey", true) && name != null ->
                    header += "# Peer ${l.substringAfter('=').trim()} = $name"
            }
        }
        return (header + conf).joinToString("\n")
    }

    /** Peer name written by [hoistPeerNames], or null. */
    fun peerName(headerComments: List<String>, publicKey: String): String? =
        headerComments.firstNotNullOfOrNull { c ->
            PEER_NAME.matchEntire(c.trim())?.groupValues?.takeIf { it[1] == publicKey }?.get(2)?.trim()
        }

    /** gofire's `# Transwarp-<key> = value` (hoisted to the header), or null. */
    fun header(conf: String, key: String): String? =
        conf.lineSequence().firstNotNullOfOrNull { l ->
            TRANSWARP_HEADER.matchEntire(l.trim())?.groupValues?.takeIf { it[1].equals(key, true) }?.get(2)?.trim()
        }

    /** gofire's ordered `# Transwarp-Endpoints = a, b, wss://c`, or empty. */
    fun endpointLadder(conf: String): List<String> =
        header(conf, "Endpoints")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** Whole days until `# Transwarp-Expires` (negative = expired), or null when it never expires. */
    fun expiresInDays(conf: String, nowMs: Long = System.currentTimeMillis()): Long? =
        headerMs(conf, "Expires")?.let { exp -> if (exp <= nowMs) -1 else (exp - nowMs) / 86_400_000 }

    /** Epoch ms of an ISO-8601 `# Transwarp-<key>` header, or null. */
    fun headerMs(conf: String, key: String): Long? =
        header(conf, key)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /**
     * Blocking. Renews a Nebula cert over the tunnel (`POST /v1/mesh/nebula/renew`, the same key re-signed):
     * the new profile JSON and expires_at.
     */
    fun renewNebula(hub: String, publicKey: String): Pair<String, String?> {
        val body = buildJsonObject { put("nebula_public_key", publicKey) }.toString()
        val json = Json.parseToJsonElement(MeshDirectory.request(hub, "POST", "/v1/mesh/nebula/renew", body)).jsonObject
        val profile = Nebula.encode(Nebula.parse(json["nebula"]?.toString() ?: error("no nebula profile in renew response")))
        return profile to json["expires_at"]?.jsonPrimitive?.contentOrNull
    }

    /** Private routes other than the mesh range (10.89.0.0/16) in the hub's AllowedIPs: subnet (LAN) access. */
    fun lanRoutes(allowedIPs: String?): List<String> =
        allowedIPs.orEmpty().split(',').map { it.trim() }.filter {
            it.contains('/') && !it.startsWith("10.89.") &&
                (it.startsWith("10.") || it.startsWith("192.168.") ||
                    Regex("""172\.(1[6-9]|2\d|3[01])\..*""").matches(it))
        }

    private val TRANSWARP_HEADER = Regex("""#\s*Transwarp-([\w-]+)\s*=\s*(.+)""", RegexOption.IGNORE_CASE)
    private val NAME_COMMENT = Regex("""#\s*Name\s*=\s*(.+)""", RegexOption.IGNORE_CASE)
    private val PEER_NAME = Regex("""#\s*Peer\s+(\S+)\s+=\s*(.+)""")

    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /**
     * The chain is server-supplied, so merely containing the CA cert proves nothing (it's public). The cert
     * matching the pin becomes the only PKIX trust anchor and the leaf must chain to it by signature.
     */
    internal class PinnedTrustManager(private val caSha256: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client auth unsupported")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val ca =
                chain.orEmpty().firstOrNull { sha256Hex(it.encoded).equals(caSha256, true) }
                    ?: throw CertificateException("server CA does not match enrollment pin")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("ca", ca)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first().checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Blocking; call off the main thread. */
    fun enroll(code: Code): Result {
        val privateKey = Key.generatePrivateKey().toBase64()
        val publicKey = Config.generatePublicKeyFromPrivateKey(privateKey)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrustManager(code.caSha256)), null) }
        val conn = URL("${code.baseUrl}/v1/mesh/enroll").openConnection() as HttpsURLConnection
        try {
            conn.sslSocketFactory = ssl.socketFactory
            // Server is addressed by IP and pinned via the CA hash, so hostnames carry no extra trust.
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = buildJsonObject {
                put("token", code.token)
                // a Nebula X25519 public key is the same 32 bytes as a WireGuard one
                put(if (code.nebula) "nebula_public_key" else "wg_public_key", publicKey)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            // gofire's error code (e.g. nebula_unavailable: the token is still valid, retry later)
            if (conn.responseCode !in 200..299)
                error("HTTP ${conn.responseCode} ${conn.errorStream?.bufferedReader()?.readText()?.take(200).orEmpty()}".trim())
            val json = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            val name = json["name"]?.jsonPrimitive?.content
            if (code.nebula) {
                val profile = Nebula.encode(Nebula.parse(json["nebula"]?.toString() ?: error("no nebula profile in response")))
                val expires = json["expires_at"]?.jsonPrimitive?.content
                return Result(nebulaPlaceholder(privateKey, Nebula.parse(profile).ip, name, expires), name, profile)
            }
            val conf = json["config"]?.jsonPrimitive?.content ?: error("no config in response")
            return Result(hoistPeerNames(insertPrivateKey(conf, privateKey)), json["name"]?.jsonPrimitive?.content)
        } finally {
            conn.disconnect()
        }
    }
}
