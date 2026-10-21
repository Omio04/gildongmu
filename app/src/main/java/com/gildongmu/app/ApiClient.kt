package com.gildongmu.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Incoming message from a neighbour (a simulated one or a real person on a phone) or the public-data fallback. */
data class Inbound(
    val id: Int,
    val from: String,
    val text: String,
    val auto: Boolean,
    val coupon: String = "",
    val human: Boolean = false,
)

data class RestInfo(val name: String, val km: Double, val congestion: String)

/** One line of "what is ahead", built from what neighbours told us. kind: warning, info, cafe, food. */
data class SituationItem(val kind: String, val title: String, val detail: String)

/** Result of asking a question: null error means it went out. */
data class SendResult(val error: String?, val waitingHuman: Boolean = false, val preset: String = "", val code: Int = 200)

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
                o.optString("dest", ""), o.getDouble("distance_m").toInt(), o.getBoolean("same_dest"),
                o.optBoolean("live", false))
        }
    }

    fun inbox(after: Int): List<Inbound> {
        val arr = JSONArray(call("GET", "/api/inbox?vid=me&after=$after").second)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Inbound(o.getInt("id"), o.getString("from"), o.getString("text"), o.getBoolean("auto"),
                o.optString("coupon", ""), o.optBoolean("human", false))
        }
    }

    fun nextRest(): RestInfo? {
        val rest = JSONObject(call("GET", "/api/ahead?vid=me").second).getJSONArray("rest")
        if (rest.length() == 0) return null
        val o = rest.getJSONObject(0)
        return RestInfo(o.getString("name"), o.getDouble("km"), o.getString("congestion"))
    }

    fun situation(): List<SituationItem> {
        val arr = JSONArray(call("GET", "/api/situation").second)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SituationItem(o.getString("kind"), o.getString("title"), o.getString("detail"))
        }
    }

    /** [to] = one car; null = everyone around. */
    fun send(preset: String, arg: String?, speedKmh: Float, gear: String, to: String? = null): SendResult {
        val body = JSONObject().put("preset", preset).put("speed_kmh", speedKmh.toDouble()).put("gear", gear)
        if (arg != null) body.put("arg", arg)
        if (to != null) body.put("to", to)
        return post("/api/send", body)
    }

    /** A question said out loud; the server decides which of the fixed questions it means. */
    fun sendSpoken(spoken: String, arg: String?, gear: String): SendResult {
        val body = JSONObject().put("spoken", spoken).put("voice", true).put("gear", gear)
        if (arg != null) body.put("arg", arg)
        return post("/api/send", body)
    }

    private fun post(path: String, body: JSONObject): SendResult {
        val (code, text) = call("POST", path, body)
        val o = JSONObject(text)
        return if (code == 200) SendResult(null, o.optBoolean("waiting_human", false), o.optString("preset", ""))
        else SendResult(o.optString("error", "error $code"), false, "", code)
    }

    /** Report whoever wrote the message: they are blocked and their answers are no longer shown. */
    fun report(msgId: Int) {
        call("POST", "/api/report", JSONObject().put("msg_id", msgId).put("reason", "inappropriate"))
    }

    /** "고마워요" to whoever wrote the message. */
    fun thanks(msgId: Int) {
        call("POST", "/api/thanks", JSONObject().put("msg_id", msgId))
    }

    /** Tell the server where we are going so same-destination cars come first. */
    fun setDestination(dest: String) {
        call("POST", "/api/trip", JSONObject().put("dest", dest))
    }
}
