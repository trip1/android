package com.zaneschepke.wireguardautotunnel.util.network

import com.wgtunnel.parser.Config
import com.wgtunnel.parser.crypto.Key
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Starfleet Provisioning: `transwarp://enroll?u=<https base>&t=<token>&ca=<sha256 hex of CA DER>`. */
object MeshEnroll {
    data class Code(val baseUrl: String, val token: String, val caSha256: String)

    data class Result(val config: String, val name: String?)

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
        return Code(u, t, ca)
    }

    /** Server conf comes without a PrivateKey; ours goes right after `[Interface]`. */
    fun insertPrivateKey(conf: String, privateKey: String): String {
        val lines = conf.lines().toMutableList()
        val i = lines.indexOfFirst { it.trim().equals("[Interface]", true) }
        require(i >= 0) { "no [Interface] section" }
        lines.add(i + 1, "PrivateKey = $privateKey")
        return lines.joinToString("\n")
    }

    /** Trusts the server only if some cert in its chain has sha256(DER) == [caSha256]. Never trust-all. */
    private class PinnedTrustManager(private val caSha256: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client auth unsupported")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val sha = MessageDigest.getInstance("SHA-256")
            val ok = chain.orEmpty().any { c ->
                sha.digest(c.encoded).joinToString("") { "%02x".format(it) }.equals(caSha256, true)
            }
            if (!ok) throw CertificateException("server CA does not match enrollment pin")
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
                put("wg_public_key", publicKey)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) error("enroll failed: HTTP ${conn.responseCode}")
            val json = Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            val conf = json["config"]?.jsonPrimitive?.content ?: error("no config in response")
            return Result(insertPrivateKey(conf, privateKey), json["name"]?.jsonPrimitive?.content)
        } finally {
            conn.disconnect()
        }
    }
}
