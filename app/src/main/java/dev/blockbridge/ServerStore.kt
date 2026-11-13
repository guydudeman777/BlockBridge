package dev.blockbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 19132,
)

/** Saved server list, kept in SharedPreferences as JSON. */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun load(): List<Server> {
        val raw = prefs.getString("list", null) ?: return defaults()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Server(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"))
            }
        }.getOrElse { defaults() }
    }

    fun save(list: List<Server>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun defaults() = listOf(
        Server(name = "The Hive", host = "geo.hivebedrock.network"),
        Server(name = "CubeCraft", host = "play.cubecraft.net"),
    )
}
