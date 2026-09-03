package com.morgan.wakepc

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "settings")
private val STATE = stringPreferencesKey("state_v3")
private val whitespace = Regex("\\s+")

// Pastes drag invisible extras into single-line fields; keep the plausible part.
// Also absorb address shorthand: "homepi", "100.64.0.2:8787" and full URLs all work —
// a missing scheme becomes http:// and a bare host gets the default port.
fun cleanUrl(raw: String): String {
    val trimmed = raw.trim().takeWhile { !it.isWhitespace() }.trimEnd('/')
    if (trimmed.isEmpty() || "://" in trimmed) return trimmed
    val withPort = if (Regex(":\\d+$").containsMatchIn(trimmed)) trimmed else "$trimmed:8787"
    return "http://$withPort"
}
fun cleanToken(raw: String): String = raw.trim().split(whitespace).lastOrNull().orEmpty()

data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val color: Long = 0xFFFF5D49,
    val baseUrl: String = "",
    val fallbackUrl: String = "",
    val token: String = "",
)

data class CommandRef(val name: String, val ping: Boolean)

data class Machine(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val connectionId: String = "",
    val commands: List<CommandRef> = emptyList(),
)

enum class HeroStyle { BANNER, DIAL, MINI }

data class ButtonRef(val machineId: String, val command: String)

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
        val connection = connection(machine.connectionId) ?: return null
        val command = machine.commands.firstOrNull { it.name == ref?.command } ?: return null
        return ResolvedButton(machine, connection, command)
    }
}

data class ResolvedButton(val machine: Machine, val connection: Connection, val command: CommandRef)

class Store(private val context: Context) {

    val state: Flow<AppState> = context.dataStore.data.map { prefs ->
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
                        .put("id", c.id).put("name", c.name).put("color", c.color)
                        .put("baseUrl", c.baseUrl).put("fallbackUrl", c.fallbackUrl)
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
                        .put("id", m.id).put("name", m.name).put("connectionId", m.connectionId)
                        .put(
                            "commands",
                            JSONArray().also { cs ->
                                m.commands.forEach { c ->
                                    cs.put(JSONObject().put("name", c.name).put("ping", c.ping))
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
internal fun decodeState(json: String): AppState = runCatching {
    val root = JSONObject(json)
    AppState(
        connections = root.optJSONArray("connections").toObjectList { o ->
            Connection(
                id = o.getString("id"), name = o.optString("name"),
                color = o.optLong("color", 0xFFFF5D49), baseUrl = o.optString("baseUrl"),
                fallbackUrl = o.optString("fallbackUrl"), token = o.optString("token"),
            )
        },
        machines = root.optJSONArray("machines").toObjectList { o ->
            Machine(
                id = o.getString("id"), name = o.optString("name"),
                connectionId = o.optString("connectionId"),
                commands = o.optJSONArray("commands").toObjectList { c ->
                    CommandRef(c.getString("name"), c.optBoolean("ping"))
                },
            )
        },
        hero = root.optJSONObject("hero")?.toRef(),
        heroStyle = runCatching { HeroStyle.valueOf(root.optString("heroStyle")) }
            .getOrDefault(HeroStyle.BANNER),
        tile = root.optJSONObject("tile")?.toRef(),
    )
}.getOrDefault(AppState())

private fun refJson(ref: ButtonRef) =
    JSONObject().put("machineId", ref.machineId).put("command", ref.command)

private fun JSONObject.toRef() = ButtonRef(optString("machineId"), optString("command"))

private fun <T> JSONArray?.toObjectList(map: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { map(getJSONObject(it)) }
