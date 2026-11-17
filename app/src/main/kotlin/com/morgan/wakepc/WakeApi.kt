package com.morgan.wakepc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class PingStats(
    val awake: Boolean,
    val lossPct: Double,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    /** Whether the relay's ready port answers; null when it has none to check. */
    val ready: Boolean? = null,
)

/** Reads a /status reply. A relay without a ready port sends no `ready`, so it stays null. */
internal fun parseStats(body: String): PingStats {
    val obj = JSONObject(body)
    val awake = obj.getBoolean("awake")
    return PingStats(
        awake = awake,
        lossPct = obj.optDouble("loss_pct", if (awake) 0.0 else FULL_LOSS_PCT),
        minMs = obj.optDouble("min_ms").takeIf { !it.isNaN() },
        avgMs = obj.optDouble("avg_ms").takeIf { !it.isNaN() },
        maxMs = obj.optDouble("max_ms").takeIf { !it.isNaN() },
        ready = if (obj.has("ready")) obj.getBoolean("ready") else null,
    )
}

private const val FULL_LOSS_PCT = 100.0

/** What a relay says about itself on `/health`. */
data class RelayHealth(
    val api: Int,
    val name: String,
    /** This phone's name on the tailnet, when the relay recognised it that way. */
    val you: String?,
    val machines: Int,
    /** Whether the relay lets a phone register machines to wake. */
    val phoneSetup: Boolean,
    /** Problems in plain words, ready to show as they are. */
    val warnings: List<String>,
)

/** A device the relay can see on its own network. */
data class LanDevice(
    val ip: String,
    val mac: String,
    val name: String?,
    /** Set when the relay already has this device as a machine. */
    val machineId: String?,
)

/** A machine as the relay stores it. Its wake button is the command `wake-<id>`. */
data class RelayMachine(
    val id: String,
    val name: String,
    val mac: String,
    val ip: String?,
    val ready: Int?,
) {
    val commandName: String get() = "wake-$id"
}

internal fun parseHealth(body: String): RelayHealth {
    val obj = JSONObject(body)
    return RelayHealth(
        api = obj.optInt("api", 1),
        name = obj.optString("name"),
        you = obj.optString("you").takeIf { it.isNotBlank() && !obj.isNull("you") },
        machines = obj.optInt("machines"),
        phoneSetup = obj.optBoolean("phone_setup", true),
        warnings = obj.optJSONArray("warnings").strings(),
    )
}

internal fun parseScan(body: String): List<LanDevice> {
    val array = JSONObject(body).optJSONArray("devices") ?: return emptyList()
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        LanDevice(
            ip = obj.getString("ip"),
            mac = obj.getString("mac"),
            name = obj.textOrNull("name"),
            machineId = obj.textOrNull("machine"),
        )
    }
}

internal fun parseMachine(body: String): RelayMachine {
    val obj = JSONObject(body)
    return RelayMachine(
        id = obj.getString("id"),
        name = obj.getString("name"),
        mac = obj.getString("mac"),
        ip = obj.textOrNull("ip"),
        ready = if (obj.isNull("ready")) null else obj.optInt("ready").takeIf { it > 0 },
    )
}

private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** Which link in the chain broke. The words shown to the user come from [failureText]. */
enum class FailureKind {
    /** The connection has no address at all. */
    NO_ADDRESS,

    /** The relay's name does not resolve. */
    BAD_NAME,

    /** Nothing answered: the relay is off, off the tailnet, or this phone is. */
    NO_ANSWER,

    /** The host answered, but nothing is listening: the service is down. */
    REFUSED,

    /** The relay answered and does not know this device. */
    NOT_RECOGNISED,
    WRONG_CODE,

    /** The relay answered that it has no such thing: a machine, a command, or the route itself. */
    NOT_FOUND,

    /** The relay is too old to know what was asked of it. */
    OLD_RELAY,

    /** The relay understood and said no, with a reason of its own. */
    REJECTED,
    OTHER,
    ;

    /** True when the relay itself could not be reached, whatever was asked. */
    val relayDown: Boolean get() = this == NO_ADDRESS || this == BAD_NAME || this == NO_ANSWER || this == REFUSED
}

/** A request that failed, already sorted into which link broke. */
class RelayException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

val Throwable?.failureKind: FailureKind get() = (this as? RelayException)?.kind ?: FailureKind.OTHER

/**
 * A failure in words that name the right device. The old message asked "is
 * the machine awake?" whenever anything timed out, which pointed at the PC
 * when it was the relay that could not be reached.
 */
fun failureText(
    kind: FailureKind,
    relay: String,
    detail: String = "",
    vpnOff: Boolean = false,
): String {
    val name = relay.ifBlank { "the relay" }
    return when (kind) {
        FailureKind.NO_ADDRESS -> {
            "no address set for $name"
        }

        FailureKind.BAD_NAME -> {
            "can't find $name by that name: check it, or use its tailscale IP"
        }

        FailureKind.NO_ANSWER -> {
            if (vpnOff) {
                "tailscale looks off on this phone, so $name can't be reached"
            } else {
                "no answer from $name: is it switched on and on tailscale?"
            }
        }

        FailureKind.REFUSED -> {
            "$name is on, but wakepc is not running on it"
        }

        FailureKind.NOT_RECOGNISED -> {
            "$name does not recognise this phone: same tailscale account?"
        }

        FailureKind.WRONG_CODE -> {
            "wrong confirmation code"
        }

        FailureKind.OLD_RELAY -> {
            "$name runs an older wakepc: update it to use this"
        }

        FailureKind.NOT_FOUND, FailureKind.REJECTED, FailureKind.OTHER -> {
            detail.ifBlank { "$name could not do that" }
        }
    }
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/** The relay answered with an error: sort it, keeping any reason it gave. */
internal fun httpFailure(
    code: Int,
    body: String,
): RelayException {
    val said = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
    return when (code) {
        HTTP_UNAUTHORIZED -> {
            RelayException(FailureKind.NOT_RECOGNISED, said)
        }

        // 403 is a wrong code, except when the relay has phone setup switched off.
        HTTP_FORBIDDEN -> {
            if ("phone setup" in said) RelayException(FailureKind.REJECTED, said) else RelayException(FailureKind.WRONG_CODE, said)
        }

        HTTP_NOT_FOUND -> {
            RelayException(FailureKind.NOT_FOUND, said.ifBlank { "not found" })
        }

        HTTP_BAD_REQUEST -> {
            RelayException(FailureKind.REJECTED, said.ifBlank { "the relay refused that" })
        }

        else -> {
            RelayException(FailureKind.OTHER, said.ifBlank { "HTTP $code" })
        }
    }
}

/** Sort a transport failure. A relay that never answered is not a relay that refused. */
internal fun transportFailure(failure: Throwable): RelayException =
    when (failure) {
        is RelayException -> {
            failure
        }

        is UnknownHostException -> {
            RelayException(FailureKind.BAD_NAME, "unknown host", failure)
        }

        is SocketTimeoutException -> {
            RelayException(FailureKind.NO_ANSWER, "timed out", failure)
        }

        is ConnectException -> {
            if (failure.message.orEmpty().contains("refused", ignoreCase = true)) {
                RelayException(FailureKind.REFUSED, "connection refused", failure)
            } else {
                RelayException(FailureKind.NO_ANSWER, failure.message ?: "unreachable", failure)
            }
        }

        // OkHttp's whole-call timeout arrives as a bare InterruptedIOException.
        is InterruptedIOException -> {
            RelayException(FailureKind.NO_ANSWER, "timed out", failure)
        }

        else -> {
            RelayException(FailureKind.OTHER, failure.message ?: failure::class.simpleName ?: "failed", failure)
        }
    }

/** The network seam: everything the app asks of a connection. Faked in tests. */
interface WakeRepository {
    suspend fun fetchCommands(connection: Connection): Result<List<CommandRef>>

    suspend fun run(
        connection: Connection,
        command: String,
        confirmCode: String = "",
    ): Result<Unit>

    suspend fun stats(
        connection: Connection,
        command: String,
        count: Int = 1,
    ): Result<PingStats>

    /** How the relay is, or [FailureKind.OLD_RELAY] from one that predates `/health`. */
    suspend fun health(connection: Connection): Result<RelayHealth> = Result.failure(RelayException(FailureKind.OLD_RELAY, "no health"))

    /** The devices on the relay's network. Slow: the relay sweeps its subnet first. */
    suspend fun scan(connection: Connection): Result<List<LanDevice>> = Result.failure(RelayException(FailureKind.OLD_RELAY, "no scan"))

    /** Register a machine to wake. The relay picks its ready port while the machine is still on. */
    suspend fun addMachine(
        connection: Connection,
        name: String,
        mac: String,
        ip: String?,
    ): Result<RelayMachine> = Result.failure(RelayException(FailureKind.OLD_RELAY, "no machines"))

    suspend fun removeMachine(
        connection: Connection,
        id: String,
    ): Result<Unit> = Result.failure(RelayException(FailureKind.OLD_RELAY, "no machines"))
}

object WakeApi : WakeRepository {
    // Set once from Application; without it we simply resolve as before.
    @Volatile private var appContext: android.content.Context? = null

    fun attach(context: android.content.Context) {
        appContext = context
    }

    /** False only when we can see that no VPN is up, so Tailscale cannot be connected. */
    fun vpnLooksOff(): Boolean = appContext?.let(::vpnActive) == false

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .dns(SearchDomainDns({ appContext?.let(::networkSearchDomains).orEmpty() }))
            .build()

    // Multi-ping probes take count seconds server-side, and a scan sweeps the
    // relay's subnet before it answers; give them room.
    private val slowClient =
        client
            .newBuilder()
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    override suspend fun fetchCommands(connection: Connection): Result<List<CommandRef>> =
        request(connection, "commands").map { body ->
            val array = JSONObject(body).getJSONArray("commands")
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                CommandRef(
                    name = name,
                    ping = obj.optBoolean("ping"),
                    // The relay knows what the command actually runs, so it
                    // decides. Only guess from the name for older relays that
                    // do not say, and guess towards the safer answer.
                    elevated = if (obj.has("elevated")) obj.getBoolean("elevated") else looksDisruptive(name),
                )
            }
        }

    override suspend fun run(
        connection: Connection,
        command: String,
        confirmCode: String,
    ): Result<Unit> = request(connection, "run/$command", method = "POST", extras = Extras(confirmCode = confirmCode)).map { }

    override suspend fun stats(
        connection: Connection,
        command: String,
        count: Int,
    ): Result<PingStats> =
        request(connection, "status/$command?count=$count", extras = Extras(slow = count > 1))
            .map(::parseStats)

    override suspend fun health(connection: Connection): Result<RelayHealth> =
        request(connection, "health").map(::parseHealth).notFoundMeansOld()

    override suspend fun scan(connection: Connection): Result<List<LanDevice>> =
        request(connection, "scan", extras = Extras(slow = true)).map(::parseScan).notFoundMeansOld()

    override suspend fun addMachine(
        connection: Connection,
        name: String,
        mac: String,
        ip: String?,
    ): Result<RelayMachine> {
        val body = JSONObject().put("name", name).put("mac", mac)
        // "auto": the machine is on right now, so the relay can see which
        // port it answers on and wait for that one after a wake.
        if (ip != null) body.put("ip", ip).put("ready", "auto")
        return request(
            connection,
            "machines",
            method = "POST",
            extras = Extras(body = body.toString()),
        ).map(::parseMachine).notFoundMeansOld()
    }

    override suspend fun removeMachine(
        connection: Connection,
        id: String,
    ): Result<Unit> = request(connection, "machines/$id", method = "DELETE").map { }

    /** A relay from before machines answers 404 to these routes. */
    private fun <T> Result<T>.notFoundMeansOld(): Result<T> =
        recoverCatching { failure ->
            throw if (failure.failureKind == FailureKind.NOT_FOUND) RelayException(FailureKind.OLD_RELAY, "older relay") else failure
        }

    /** The parts of a request most calls leave alone. */
    private data class Extras(
        /** Allow the long timeout: multi-ping probes and scans take seconds on the relay. */
        val slow: Boolean = false,
        val confirmCode: String = "",
        val body: String = "",
    )

    /** Tries the primary address, then the fallback; logs the outcome to the console. */
    private suspend fun request(
        connection: Connection,
        path: String,
        method: String = "GET",
        extras: Extras = Extras(),
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val (slow, confirmCode, body) = extras
            val label = "$method /${path.substringBefore('?')} @ ${connection.name.ifBlank { "?" }}"
            val bases = listOf(connection.baseUrl, connection.fallbackUrl).filter { it.isNotBlank() }
            var failure = RelayException(FailureKind.NO_ADDRESS, "no address configured")
            for (base in bases) {
                val startedAt = System.currentTimeMillis()
                val attempt =
                    runCatching {
                        val builder = Request.Builder().url("$base/$path")
                        // Usually absent: a relay on the tailnet identifies us
                        // by our peer address and needs no credential at all.
                        if (connection.token.isNotBlank()) {
                            builder.header("Authorization", "Bearer ${connection.token}")
                        }
                        if (confirmCode.isNotBlank()) {
                            builder.header("X-WakePC-Confirm", confirmCode)
                        }
                        if (method != "GET") builder.method(method, body.toByteArray().toRequestBody())
                        (if (slow) slowClient else client).newCall(builder.build()).execute().use { response ->
                            val text = response.body.string()
                            if (!response.isSuccessful) throw httpFailure(response.code, text)
                            text
                        }
                    }
                if (attempt.isSuccess) {
                    AppLog.log("$label · ${System.currentTimeMillis() - startedAt}ms", ok = true)
                    return@withContext attempt
                }
                val cause = attempt.exceptionOrNull()
                failure = transportFailure(cause ?: IllegalStateException("failed"))
                // The relay answered, so the fallback address has nothing to
                // add, and repeating a POST there could do the thing twice.
                if (cause is RelayException) break
            }
            val text = failureText(failure.kind, connection.name, failure.message.orEmpty(), vpnLooksOff())
            AppLog.log("$label · $text", ok = false)
            Result.failure(RelayException(failure.kind, text, failure))
        }
}

/** Whether any VPN is up. Tailscale is one on Android, so without it the tailnet is out of reach. */
private fun vpnActive(context: android.content.Context): Boolean? =
    runCatching {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        manager.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }.getOrNull()
