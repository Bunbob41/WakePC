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
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun wake(settings: WakeSettings): Result<Unit> =
        request(settings, "wake", post = true).map { }

    /** True once the Pi can ping the PC. */
    suspend fun status(settings: WakeSettings): Result<Boolean> =
        request(settings, "status", post = false).map { JSONObject(it).getBoolean("awake") }

    private suspend fun request(settings: WakeSettings, path: String, post: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder()
                    .url("${settings.baseUrl}/$path")
                    .header("Authorization", "Bearer ${settings.token}")
                if (post) builder.post(ByteArray(0).toRequestBody())
                client.newCall(builder.build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    response.body.string()
                }
            }
        }
}
