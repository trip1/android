package com.zaneschepke.wireguardautotunnel.util.network

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * gofire Phase 38 admin API on the hub's directory listener: `/v1/mesh/admin/X` mirrors the operator `/v1/mesh/X`,
 * auth = this admin client's mesh source address + key (over the tunnel only). Responses may carry join codes:
 * never log them.
 */
object MeshAdmin {
    data class Client(
        val name: String,
        val kind: String,
        val meshIp: String,
        val peers: Boolean,
        val exit: Boolean,
        val lan: Boolean,
        val admin: Boolean,
        val groups: List<String>,
        val expiresAt: String?,
        val enrolled: Boolean,
    ) {
        val nebula: Boolean get() = kind == "nebula"
    }

    data class Rule(val id: String, val from: String, val to: String, val proto: String, val port: String)

    data class WolHost(val name: String, val mac: String)

    /** POST clients body. ttl "never" = no expiry (WireGuard: omitted; Nebula: "never", its default is 90d). */
    data class NewClient(
        val name: String,
        val nebula: Boolean = false,
        val lan: Boolean = false,
        val exit: Boolean = false,
        val peers: Boolean = false,
        val groups: List<String> = emptyList(),
        val ttl: String = "90d",
    ) {
        fun json(): String = buildJsonObject {
            put("name", name)
            put("kind", if (nebula) "nebula" else "wireguard")
            put("lan", lan)
            put("exit", exit)
            if (!nebula) put("peers", peers) // a nebula client has no peers flag (400)
            if (nebula && groups.isNotEmpty()) put("groups", JsonArray(groups.map(::JsonPrimitive)))
            if (ttl != "never" || nebula) put("ttl", ttl)
        }.toString()
    }

    val TTLS = listOf("24h", "7d", "30d", "90d", "never")

    /** Guest QR: expires in 24 h, no LAN, exit or peers. */
    fun guest(name: String, nebula: Boolean) = NewClient(name, nebula, ttl = "24h")

    fun patchJson(lan: Boolean, exit: Boolean, peers: Boolean?, groups: List<String>?): String = buildJsonObject {
        put("lan", lan)
        put("exit", exit)
        peers?.let { put("peers", it) }
        groups?.let { put("groups", JsonArray(it.map(::JsonPrimitive))) }
    }.toString()

    fun ruleJson(from: String, to: String, proto: String, port: String) = buildJsonObject {
        put("from_group", from); put("to_group", to); put("proto", proto.ifBlank { "any" }); put("port", port.ifBlank { "any" })
    }.toString()

    fun wolJson(mac: String) = buildJsonObject { put("mac", mac) }.toString()

    fun serviceJson(name: String, url: String) = buildJsonObject { put("name", name); put("url", url) }.toString()

    /** `/v1/mesh/admin/<segments>`, each segment URL-encoded so a name can't reach another route. */
    fun path(vararg segments: String) =
        "/v1/mesh/admin/" + segments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun list(json: String) = Json.parseToJsonElement(json).let { it as? JsonArray ?: JsonArray(emptyList()) }.map { it.jsonObject }

    fun parseClients(json: String) = list(json).map {
        Client(it.s("name").orEmpty(), it.s("kind").orEmpty().ifEmpty { "wireguard" }, it.s("mesh_ip").orEmpty(),
            it.b("peers"), it.b("exit"), it.b("lan"), it.b("admin"),
            (it["groups"] as? JsonArray)?.map { g -> g.jsonPrimitive.content }.orEmpty(), it.s("expires_at"),
            it.s("enrolled_at") != null)
    }.sortedBy { it.name }

    fun parseRules(json: String) = list(json).map {
        Rule(it.s("id").orEmpty(), it.s("from_group").orEmpty(), it.s("to_group").orEmpty(), it.s("proto").orEmpty(), it.s("port").orEmpty())
    }

    fun parseWol(json: String) = list(json).map { WolHost(it.s("name").orEmpty(), it.s("mac").orEmpty()) }

    /** Services: (service, removable = manual entry). */
    fun parseServices(json: String) = list(json).map {
        MeshDirectory.Service(it.s("name").orEmpty(), it.s("url").orEmpty(), it.s("kind").orEmpty()) to (it.s("source") == "manual")
    }

    /** The `transwarp://enroll?...` join code of a POST clients / renew response. */
    fun codeOf(json: String): String =
        Json.parseToJsonElement(json).jsonObject.s("code")?.takeIf { it.startsWith("transwarp://") } ?: error("no code in response")

    /** Blocking; throws "…: HTTP 4xx …" (403 not admin, 400 self/bad input, 429 rate-limited). */
    fun call(hub: String, method: String, path: String, json: String? = null): String =
        MeshDirectory.request(hub, method, path, json)
}
