package com.morgan.wakepc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PingStats(
    val awake: Boolean,
    val lossPct: Double,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
)

/** The network seam: everything the app asks of a connection. Faked in tests. */
interface WakeRepository {
    suspend fun fetchCommands(connection: Connection): Result<List<CommandRef>>

    suspend fun run(
        connection: Connection,
        command: String,
    ): Result<Unit>

    suspend fun status(
        connection: Connection,
        command: String,
    ): Result<Boolean>

    suspend fun stats(
        connection: Connection,
        command: String,
        count: Int = 1,
    ): Result<PingStats>
}

object WakeApi : WakeRepository {
    private const val FULL_LOSS_PCT = 100.0

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    // Multi-ping probes take count seconds server-side; give them room.
    private val probeClient =
        client
            .newBuilder()
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    override suspend fun fetchCommands(connection: Connection): Result<List<CommandRef>> =
        request(connection, "commands", post = false).map { body ->
            val array = JSONObject(body).getJSONArray("commands")
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CommandRef(obj.getString("name"), obj.optBoolean("ping"))
            }
        }

    override suspend fun run(
        connection: Connection,
        command: String,
    ): Result<Unit> = request(connection, "run/$command", post = true).map { }

    override suspend fun status(
        connection: Connection,
        command: String,
    ): Result<Boolean> = stats(connection, command, count = 1).map { it.awake }

    override suspend fun stats(
        connection: Connection,
        command: String,
        count: Int,
    ): Result<PingStats> =
        request(connection, "status/$command?count=$count", post = false, probe = count > 1)
            .map { body ->
                val obj = JSONObject(body)
                val awake = obj.getBoolean("awake")
                PingStats(
                    awake = awake,
                    lossPct = obj.optDouble("loss_pct", if (awake) 0.0 else FULL_LOSS_PCT),
                    minMs = obj.optDouble("min_ms").takeIf { !it.isNaN() },
                    avgMs = obj.optDouble("avg_ms").takeIf { !it.isNaN() },
                    maxMs = obj.optDouble("max_ms").takeIf { !it.isNaN() },
                )
            }

    /** Tries the primary address, then the fallback; logs the outcome to the console. */
    private suspend fun request(
        connection: Connection,
        path: String,
        post: Boolean,
        probe: Boolean = false,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val method = if (post) "POST" else "GET"
            val label = "$method /${path.substringBefore('?')} @ ${connection.name.ifBlank { "?" }}"
            val bases = listOf(connection.baseUrl, connection.fallbackUrl).filter { it.isNotBlank() }
            if (bases.isEmpty()) {
                AppLog.log("$label · no address configured", ok = false)
                return@withContext Result.failure(IllegalStateException("no address configured"))
            }
            var failure: Throwable? = null
            for (base in bases) {
                val startedAt = System.currentTimeMillis()
                val attempt =
                    runCatching {
                        val builder =
                            Request
                                .Builder()
                                .url("$base/$path")
                                .header("Authorization", "Bearer ${connection.token}")
                        if (post) builder.post(ByteArray(0).toRequestBody())
                        (if (probe) probeClient else client).newCall(builder.build()).execute().use { response ->
                            check(response.isSuccessful) { "HTTP ${response.code}" }
                            response.body.string()
                        }
                    }
                if (attempt.isSuccess) {
                    AppLog.log("$label · ${System.currentTimeMillis() - startedAt}ms", ok = true)
                    return@withContext attempt
                }
                failure = attempt.exceptionOrNull()
            }
            val reason = describe(failure)
            AppLog.log("$label · $reason", ok = false)
            Result.failure(failure ?: IllegalStateException(reason))
        }
}

/**
 * Android does not apply Tailscale's DNS search domain to app lookups, so a
 * bare MagicDNS name like "pi-nd" resolves from a desktop but never from the
 * phone. Say so, instead of surfacing a raw resolver exception.
 */
private fun describe(failure: Throwable?): String =
    when (failure) {
        is java.net.UnknownHostException -> {
            "can't resolve that name — use the full name (host.tailnet.ts.net) or the IP"
        }

        is java.net.SocketTimeoutException -> {
            "timed out — is the machine awake and on the tailnet?"
        }

        null -> {
            "failed"
        }

        else -> {
            failure.message ?: failure::class.simpleName ?: "failed"
        }
    }
