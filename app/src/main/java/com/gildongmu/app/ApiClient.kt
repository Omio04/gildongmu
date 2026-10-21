package com.gildongmu.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Incoming message from a neighbour or the public-data fallback. */
data class Inbound(val id: Int, val from: String, val text: String, val auto: Boolean, val coupon: String = "")

data class RestInfo(val name: String, val km: Double, val congestion: String)

/** Thin blocking client for server/app_server.py. Call off the main thread. */
class ApiClient(private val base: String = "http://10.0.2.2:8080") {

    private fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 2000
        c.readTimeout = 3000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    fun nearby(): List<Neighbor> {
        val arr = JSONArray(call("GET", "/api/nearby?vid=me").second)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Neighbor(o.getString("id"), o.getString("nickname"), o.getString("model"),
                o.optString("dest", ""), o.getDouble("distance_m").toInt(), o.getBoolean("same_dest"))
        }
    }

    fun inbox(after: Int): List<Inbound> {
        val arr = JSONArray(call("GET", "/api/inbox?vid=me&after=$after").second)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Inbound(o.getInt("id"), o.getString("from"), o.getString("text"), o.getBoolean("auto"), o.optString("coupon", ""))
        }
    }

    fun nextRest(): RestInfo? {
        val rest = JSONObject(call("GET", "/api/ahead?vid=me").second).getJSONArray("rest")
        if (rest.length() == 0) return null
        val o = rest.getJSONObject(0)
        return RestInfo(o.getString("name"), o.getDouble("km"), o.getString("congestion"))
    }

    /** Returns null on success, otherwise the reason the server refused. */
    fun send(preset: String, arg: String?, speedKmh: Float, gear: String): String? {
        val body = JSONObject().put("preset", preset).put("speed_kmh", speedKmh.toDouble()).put("gear", gear)
        if (arg != null) body.put("arg", arg)
        val (code, text) = call("POST", "/api/send", body)
        return if (code == 200) null else JSONObject(text).optString("error", "error $code")
    }

    /** Tell the server where we are going so same-destination cars come first. */
    fun setDestination(dest: String) {
        call("POST", "/api/trip", JSONObject().put("dest", dest))
    }
}
