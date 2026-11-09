package io.github.petebutty.viewarr.data.remote.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

data class JsonResponse(val code: Int, val body: JsonElement?) {
    val ok get() = code in 200..299
}

/** Small JSON-over-HTTP helper for the third-party services (each has its own auth headers). */
@Singleton
class HttpJson @Inject constructor(private val client: OkHttpClient, val json: Json) {

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JsonResponse =
        call(Request.Builder().url(url).get(), headers)

    suspend fun post(url: String, body: JsonElement? = null, headers: Map<String, String> = emptyMap()): JsonResponse {
        val payload = (body?.toString() ?: "").toRequestBody(JSON_TYPE)
        return call(Request.Builder().url(url).post(payload), headers)
    }

    suspend fun put(url: String, body: JsonElement, headers: Map<String, String> = emptyMap()): JsonResponse =
        call(Request.Builder().url(url).put(body.toString().toRequestBody(JSON_TYPE)), headers)

    suspend fun delete(url: String, headers: Map<String, String> = emptyMap()): JsonResponse =
        call(Request.Builder().url(url).delete(), headers)

    private suspend fun call(builder: Request.Builder, headers: Map<String, String>): JsonResponse = withContext(Dispatchers.IO) {
        headers.forEach { (k, v) -> builder.header(k, v) }
        if ("Accept" !in headers) builder.header("Accept", "application/json")
        builder.header("User-Agent", "Viewarr/0.7")
        client.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            JsonResponse(r.code, runCatching { json.parseToJsonElement(text) }.getOrNull())
        }
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
fun JsonElement?.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
fun JsonElement?.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull
fun JsonElement?.child(key: String): JsonElement? = (this as? JsonObject)?.get(key)
fun JsonElement?.array(key: String): List<JsonElement> = runCatching { (this as JsonObject)[key]!!.jsonArray.toList() }.getOrDefault(emptyList())
fun JsonElement?.asArray(): List<JsonElement> = runCatching { this!!.jsonArray.toList() }.getOrDefault(emptyList())
fun JsonElement?.objOrEmpty(): JsonObject = runCatching { this!!.jsonObject }.getOrDefault(JsonObject(emptyMap()))
