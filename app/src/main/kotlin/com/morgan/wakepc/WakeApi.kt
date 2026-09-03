package com.morgan.wakepc

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object WakeApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchCommands(connection: Connection): Result<List<CommandRef>> =
        request(connection, "commands", post = false).map { body ->
            val array = JSONObject(body).getJSONArray("commands")
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CommandRef(obj.getString("name"), obj.optBoolean("ping"))
            }
        }

    suspend fun run(connection: Connection, command: String): Result<Unit> =
        request(connection, "run/$command", post = true).map { }

    suspend fun status(connection: Connection, command: String): Result<Boolean> =
        request(connection, "status/$command", post = false)
            .map { JSONObject(it).getBoolean("awake") }

    /** Tries the primary address, then the fallback. */
    private suspend fun request(connection: Connection, path: String, post: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            val bases = listOf(connection.baseUrl, connection.fallbackUrl).filter { it.isNotBlank() }
            if (bases.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("no address configured"))
            }
            var failure: Throwable? = null
            for (base in bases) {
                val attempt = runCatching {
                    val builder = Request.Builder()
                        .url("$base/$path")
                        .header("Authorization", "Bearer ${connection.token}")
                    if (post) builder.post(ByteArray(0).toRequestBody())
                    client.newCall(builder.build()).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        response.body.string()
                    }
                }
                if (attempt.isSuccess) return@withContext attempt
                failure = attempt.exceptionOrNull()
            }
            Result.failure(failure ?: IllegalStateException("unreachable"))
        }
}
