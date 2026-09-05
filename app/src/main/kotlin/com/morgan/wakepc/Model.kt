package com.morgan.wakepc

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")
private val STATE = stringPreferencesKey("state_v3")
private val whitespace = Regex("\\s+")

internal const val DEFAULT_CONNECTION_COLOR: Long = 0xFFFF5D49

// Pastes drag invisible extras into single-line fields; keep the plausible part.
// Also absorb address shorthand: "homepi", "100.64.0.2:8787" and full URLs all work —
// a missing scheme becomes http:// and a bare host gets the default port.
fun cleanUrl(raw: String): String {
    val trimmed = raw.trim().takeWhile { !it.isWhitespace() }.trimEnd('/')
    if (trimmed.isEmpty() || "://" in trimmed) return trimmed
    val withPort = if (Regex(":\\d+$").containsMatchIn(trimmed)) trimmed else "$trimmed:8787"
    return "http://$withPort"
}

private val plainWord = Regex("^[a-z0-9]+$")

/**
 * Accepts a token however it arrives.
 *
 * Word passphrases are hyphenated, but typing them with spaces is the natural
 * thing to do, so several plain words become one hyphenated token. A pasted
 * label ("Token: 1234") is dropped first, and anything else keeps the old
 * behaviour of taking the last chunk, which is what rescues a paste that
 * dragged extra text along with it.
 */
fun cleanToken(raw: String): String {
    val parts = raw.trim().split(whitespace).filter { it.isNotBlank() }
    if (parts.size <= 1) return parts.firstOrNull().orEmpty()

    val withoutLabel = if (parts.first().endsWith(":")) parts.drop(1) else parts
    if (withoutLabel.isEmpty()) return ""
    return if (withoutLabel.size > 1 && withoutLabel.all { plainWord.matches(it) }) {
        withoutLabel.joinToString("-")
    } else {
        withoutLabel.last()
    }
}

data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val color: Long = DEFAULT_CONNECTION_COLOR,
    val baseUrl: String = "",
    val fallbackUrl: String = "",
    val token: String = "",
)

data class CommandRef(
    val name: String,
    val ping: Boolean,
    /**
     * Which connection serves this command. Null means the machine's own —
     * how every command looked before a machine could draw from more than
     * one relay, e.g. wake via the Pi and shutdown from the PC itself.
     */
    val connectionId: String? = null,
)

data class Machine(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val connectionId: String = "",
    val commands: List<CommandRef> = emptyList(),
)

enum class HeroStyle { BANNER, DIAL, MINI }

data class ButtonRef(
    val machineId: String,
    val command: String,
)

data class AppState(
    val connections: List<Connection> = emptyList(),
    val machines: List<Machine> = emptyList(),
    val hero: ButtonRef? = null,
    val heroStyle: HeroStyle = HeroStyle.BANNER,
    val tile: ButtonRef? = null,
) {
    fun connection(id: String?): Connection? = connections.firstOrNull { it.id == id }

    fun machine(id: String?): Machine? = machines.firstOrNull { it.id == id }

    fun resolve(ref: ButtonRef?): ResolvedButton? {
        val machine = machine(ref?.machineId) ?: return null
        val command = machine.commands.firstOrNull { it.name == ref?.command } ?: return null
        val connection = connectionFor(machine, command) ?: return null
        return ResolvedButton(machine, connection, command)
    }

    /** The relay that runs this command: its own, else the machine's. */
    fun connectionFor(
        machine: Machine,
        command: CommandRef,
    ): Connection? = connection(command.connectionId ?: machine.connectionId)
}

data class ResolvedButton(
    val machine: Machine,
    val connection: Connection,
    val command: CommandRef,
)

class Store(
    private val context: Context,
) {
    val state: Flow<AppState> =
        context.dataStore.data.map { prefs ->
            prefs[STATE]?.let(::decodeState) ?: AppState()
        }

    suspend fun current(): AppState = state.first()

    suspend fun update(transform: (AppState) -> AppState) {
        context.dataStore.edit { prefs ->
            val old = prefs[STATE]?.let(::decodeState) ?: AppState()
            prefs[STATE] = encodeState(transform(old))
        }
    }
}

// Encode/decode live outside Store so they're testable on the JVM: org.json is
// stubbed in unit tests, but no DataStore or Context is involved.
internal fun encodeState(state: AppState): String {
    val root = JSONObject()
    root.put(
        "connections",
        JSONArray().also { arr ->
            state.connections.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("color", c.color)
                        .put("baseUrl", c.baseUrl)
                        .put("fallbackUrl", c.fallbackUrl)
                        .put("token", c.token),
                )
            }
        },
    )
    root.put(
        "machines",
        JSONArray().also { arr ->
            state.machines.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("name", m.name)
                        .put("connectionId", m.connectionId)
                        .put(
                            "commands",
                            JSONArray().also { cs ->
                                m.commands.forEach { c ->
                                    cs.put(
                                        JSONObject()
                                            .put("name", c.name)
                                            .put("ping", c.ping)
                                            .put("connectionId", c.connectionId ?: JSONObject.NULL),
                                    )
                                }
                            },
                        ),
                )
            }
        },
    )
    state.hero?.let { root.put("hero", refJson(it)) }
    state.tile?.let { root.put("tile", refJson(it)) }
    root.put("heroStyle", state.heroStyle.name)
    return root.toString()
}

/** Anything unparsable falls back to empty state rather than crashing on launch. */
internal fun decodeState(json: String): AppState =
    runCatching {
        val root = JSONObject(json)
        AppState(
            connections =
                root.optJSONArray("connections").toObjectList { o ->
                    Connection(
                        id = o.getString("id"),
                        name = o.optString("name"),
                        color = o.optLong("color", DEFAULT_CONNECTION_COLOR),
                        baseUrl = o.optString("baseUrl"),
                        fallbackUrl = o.optString("fallbackUrl"),
                        token = o.optString("token"),
                    )
                },
            machines =
                root.optJSONArray("machines").toObjectList { o ->
                    Machine(
                        id = o.getString("id"),
                        name = o.optString("name"),
                        connectionId = o.optString("connectionId"),
                        commands =
                            o.optJSONArray("commands").toObjectList { c ->
                                CommandRef(
                                    name = c.getString("name"),
                                    ping = c.optBoolean("ping"),
                                    connectionId = c.optString("connectionId").takeIf { it.isNotBlank() },
                                )
                            },
                    )
                },
            hero = root.optJSONObject("hero")?.toRef(),
            heroStyle =
                runCatching { HeroStyle.valueOf(root.optString("heroStyle")) }
                    .getOrDefault(HeroStyle.BANNER),
            tile = root.optJSONObject("tile")?.toRef(),
        )
    }.getOrDefault(AppState())

private fun refJson(ref: ButtonRef) = JSONObject().put("machineId", ref.machineId).put("command", ref.command)

private fun JSONObject.toRef() = ButtonRef(optString("machineId"), optString("command"))

private fun <T> JSONArray?.toObjectList(map: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { map(getJSONObject(it)) }
