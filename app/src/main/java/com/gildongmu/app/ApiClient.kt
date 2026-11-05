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
    val tag: String = "",   // the cause the answering car named (accident, work, ...)
)

data class RestInfo(val name: String, val km: Double, val congestion: String)

/** One line of "what is ahead", built from what neighbours told us. kind: warning, info, cafe, food. */
data class SituationItem(val kind: String, val title: String, val detail: String)

/** One report on the road notice board. [votes] counts the taps: confirm / thanks / passed. [myVote] is ours, or null. */
data class BoardPost(
    val id: Int, val kind: String, val title: String, val urgent: Boolean, val by: String, val mine: Boolean,
    val age: String, val votes: Map<String, Int>, val myVote: String?,
)

/** Our standing: points earned from other cars' taps, and the grade they add up to. */
data class Standing(val points: Int, val grade: String, val next: Int?, val posts: Int)

data class BoardView(val posts: List<BoardPost>, val me: Standing)

/** What a neighbour answered to a public question (everyone sees these). */
data class QAnswer(val by: String, val text: String, val mine: Boolean)

/** A question somebody asked: [direct] = asked to us alone, otherwise to everyone around. */
data class QuestionItem(
    val id: Int, val from: String, val text: String, val age: String,
    val answers: List<QAnswer>, val answered: Boolean, val open: Boolean,
    val direct: Boolean = false,
)

/** The car thinks it sees [title] ahead; the driver decides whether to report it. [left] = seconds before it goes away. */
data class HazardProposal(val id: Int, val kind: String, val title: String, val left: Int)

/** The answer to a confirmed report: a ticket number and an honest status (접수됨 = received by this service). */
data class HazardTicket(val ticket: String, val title: String, val status: String, val located: Boolean, val photo: Boolean = false)

/** A short sentence a neighbour said to everyone around. */
data class RoadMessage(val id: Int, val from: String, val text: String, val age: String)

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
                o.optBoolean("live", false), if (o.isNull("say")) null else o.optString("say", "").ifBlank { null },
                o.optInt("ahead_m", 0), o.optInt("lateral_m", 0))
        }
    }

    /** The partner shops near this position, as they registered themselves (inside the server's range). */
    fun shopsNear(lat: Double, lon: Double, heading: Double? = null, radiusKm: Double? = null): ShopsView {
        val h = (if (heading != null) "&heading=$heading" else "") + (if (radiusKm != null) "&radius=$radiusKm" else "")   // heading only while moving
        val (code, body) = call("GET", "/api/shops?lat=$lat&lon=$lon$h")
        if (code != 200) throw java.io.IOException("shops $code")
        val o = JSONObject(body)
        val arr = o.getJSONArray("shops")
        val shops = (0 until arr.length()).map {
            val s = arr.getJSONObject(it)
            val m = s.getJSONArray("menu")
            Shop(s.getString("id"), s.getString("name"), s.optString("brand", s.getString("name")), s.getString("place"),
                s.getDouble("km"), s.getInt("eta_min"), s.getInt("wait_min"), s.getInt("cook_in_min"), s.getString("phone"),
                (0 until m.length()).map { i -> m.getJSONObject(i).let { x -> MenuItem(x.getString("name"), x.getInt("price"), x.getString("kind"), x.optBoolean("simple", true)) } },
                s.optDouble("lat", 0.0), s.optDouble("lon", 0.0))
        }
        val n = if (o.isNull("nearest")) null else o.getJSONObject("nearest")
        return ShopsView(o.getDouble("range_km"), shops, n?.getString("name"), n?.optDouble("km", 0.0) ?: 0.0, n?.optString("place"))
    }

    /** Pay for one item: the server records the order for the shop (a mock payment) and answers with a receipt, or null. */
    fun orderShop(shopId: String, item: String, lat: Double, lon: Double, key: String, paymentToken: String): OrderReceipt? {
        val body = JSONObject().put("shop_id", shopId).put("item", item).put("lat", lat).put("lon", lon).put("key", key)
            .put("payment_token", paymentToken)   // what the payment provider gave back when the card was registered, never the card number
        val (code, text) = call("POST", "/api/shops/order", body)
        if (code != 200) return null
        return parseOrder(JSONObject(text))
    }

    private fun parseOrder(o: JSONObject) = OrderReceipt(
        o.getString("receipt"), o.getString("shop"), o.getString("item"), o.getInt("price"),
        o.getInt("eta_min"), o.getInt("cook_in_min"), o.getString("status"), o.optString("status_label", ""),
        o.optInt("delay_min", 0), o.optBoolean("car_can_cancel", false), o.optString("payment", ""), o.optString("phone", ""),
        o.optDouble("shop_lat", 0.0), o.optDouble("shop_lon", 0.0))

    /** How the order is going now; the car's position lets the server move the arrival time if the car is held up. */
    fun trackOrder(receipt: String, lat: Double?, lon: Double?, routeEtaMin: Int? = null): OrderReceipt? {
        val pos = (if (lat != null && lon != null) "&lat=$lat&lon=$lon" else "") + (if (routeEtaMin != null) "&eta=$routeEtaMin" else "")
        val (code, text) = call("GET", "/api/shops/order?receipt=$receipt$pos")
        return if (code == 200) parseOrder(JSONObject(text)) else null
    }

    /** This car's own orders, newest first. */
    fun myOrders(): List<OrderRow> {
        val (code, text) = call("GET", "/api/shops/mine")
        if (code != 200) return emptyList()
        val arr = JSONObject(text).getJSONArray("items")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            OrderRow(o.getString("receipt"), o.getString("shop"), o.getString("item"), o.getInt("price"), o.getString("status"),
                o.getString("status_label"), o.optString("payment", ""), o.optBoolean("refunded", false), o.getDouble("created_at"), o.optBoolean("live", false))
        }
    }

    /** The car cancels an order the shop has not started cooking. */
    fun cancelOrderOnServer(receipt: String): OrderReceipt? {
        val (code, text) = call("POST", "/api/shops/order/cancel", JSONObject().put("receipt", receipt))
        return if (code == 200) parseOrder(JSONObject(text)) else null
    }

    fun inbox(after: Int): List<Inbound> {
        val arr = JSONArray(call("GET", "/api/inbox?vid=me&after=$after").second)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Inbound(o.getInt("id"), o.getString("from"), o.getString("text"), o.getBoolean("auto"),
                o.optString("coupon", ""), o.optBoolean("human", false), o.optString("tag", ""))
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

    private fun parseQuestions(arr: JSONArray) = (0 until arr.length()).map {
        val q = arr.getJSONObject(it)
        val a = q.getJSONArray("answers")
        QuestionItem(
            q.getInt("id"), q.getString("from"), q.getString("text"), q.getString("age"),
            (0 until a.length()).map { i -> a.getJSONObject(i).let { x -> QAnswer(x.getString("by"), x.getString("text"), x.getBoolean("mine")) } },
            q.getBoolean("answered"), q.getBoolean("open"), q.optBoolean("direct", false),
        )
    }

    /** This car's own profile, picked from the lists: the name other cars see, the colour, and the model (if shared). */
    fun setMyProfile(adjective: String, animal: String, color: String, model: String, showModel: Boolean): Boolean {
        val body = JSONObject().put("adjective", adjective).put("animal", animal).put("color", color)
            .put("model", model).put("show_model", showModel)
        return call("POST", "/api/me/profile", body).first == 200
    }

    /** A spoken question the driver confirmed: to everyone around, or to the one car [to]. */
    fun ask(text: String, to: String?): SendResult {
        val body = JSONObject().put("text", text)
        if (to != null) body.put("to", to)
        return post("/api/ask", body)
    }

    /** Report a car picked on the road: it is blocked, and what it said or posted leaves the road. */
    fun reportCar(vid: String, reason: String): Boolean =
        call("POST", "/api/report_car", JSONObject().put("vid", vid).put("reason", reason)).first == 200

    /** Questions asked to us alone, then the public ones. */
    fun questions(): List<QuestionItem> = parseQuestions(JSONObject(call("GET", "/api/questions?vid=me").second).getJSONArray("items"))

    /** [reply]: "yes" / "no" / "unknown", or the sentence the driver said and confirmed. */
    fun answerQuestion(qid: Int, reply: String): Boolean =
        call("POST", "/api/questions/answer", JSONObject().put("qid", qid).put("reply", reply)).first == 200

    fun board(): BoardView {
        val o = JSONObject(call("GET", "/api/board").second)
        val arr = o.getJSONArray("posts")
        val posts = (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            val v = p.getJSONObject("votes")
            BoardPost(
                p.getInt("id"), p.getString("kind"), p.getString("title"), p.getBoolean("urgent"), p.getString("by"),
                p.getBoolean("mine"), p.getString("age"),
                mapOf("confirm" to v.optInt("confirm"), "thanks" to v.optInt("thanks"), "passed" to v.optInt("passed")),
                if (p.isNull("my_vote")) null else p.getString("my_vote"),
            )
        }
        val m = o.getJSONObject("me")
        return BoardView(posts, Standing(m.getInt("points"), m.getString("grade"), if (m.isNull("next")) null else m.getInt("next"), m.getInt("posts")))
    }

    /** One of three taps on someone's report: confirm, thanks, passed. */
    fun react(post: Int, what: String): Boolean =
        call("POST", "/api/board/react", JSONObject().put("post", post).put("what", what)).first == 200

    /** The one question: why is the road stuck? Everyone around gets it. */
    fun send(preset: String, arg: String?, speedKmh: Float, gear: String, restricted: Boolean = false): SendResult {
        val body = JSONObject().put("preset", preset).put("speed_kmh", speedKmh.toDouble()).put("gear", gear).put("restricted", restricted)
        if (arg != null) body.put("arg", arg)
        return post("/api/send", body)
    }

    /**
     * Something said out loud and confirmed by the driver. If it is the question ("왜 막혀?") the server asks it;
     * anything else goes out as a short message to everyone around (never to one car), and comes back with
     * an empty [SendResult.preset].
     */
    fun sendSpoken(spoken: String, arg: String?, gear: String): SendResult {
        val body = JSONObject().put("spoken", spoken).put("voice", true).put("gear", gear)
        if (arg != null) body.put("arg", arg)
        return post("/api/send", body)
    }

    fun hazardProposal(): HazardProposal? {
        val o = JSONObject(call("GET", "/api/hazard/proposal").second)
        if (o.isNull("proposal")) return null
        val p = o.getJSONObject("proposal")
        return HazardProposal(p.getInt("id"), p.getString("kind"), p.getString("title"), p.getInt("left"))
    }

    /** One tap: the report is stored and a ticket comes back. Null if it could not be filed. */
    fun hazardReport(kind: String, proposalId: Int?, place: Place?, photo: ByteArray? = null,
                     key: String? = null, seenAtMs: Long? = null): HazardTicket? {
        val body = JSONObject().put("kind", kind)
        if (key != null) body.put("key", key)                              // our id for this report: a retry is not a second report
        if (seenAtMs != null) body.put("event_at", seenAtMs / 1000.0)      // when the driver saw it; the server notes when it arrived
        if (proposalId != null) body.put("proposal_id", proposalId)
        if (place != null) body.put("place", JSONObject().put("lat", place.lat).put("lon", place.lon).put("heading", place.heading).put("address", place.address))
        if (photo != null) body.put("photo", android.util.Base64.encodeToString(photo, android.util.Base64.NO_WRAP))
        val (code, text) = call("POST", "/api/hazard/report", body)
        if (code != 200) return null
        val o = JSONObject(text)
        return HazardTicket(o.getString("ticket"), o.getString("title"), o.getString("status"), o.optBoolean("located", false), o.optBoolean("photo", false))
    }

    /** The navigation says a hazard zone is [metres] ahead: the server turns it into a proposal for the driver. */
    fun hazardDetected(kind: String, source: String) {
        call("POST", "/api/hazard/detected", JSONObject().put("kind", kind).put("confidence", 0.8).put("source", source))
    }

    fun hazardDecline(proposalId: Int) {
        call("POST", "/api/hazard/decline", JSONObject().put("proposal_id", proposalId))
    }

    /** What others said lately, newest first. */
    fun roadMessages(): List<RoadMessage> {
        val arr = JSONObject(call("GET", "/api/chat?vid=me").second).getJSONArray("items")
        return (0 until arr.length()).map {
            val m = arr.getJSONObject(it)
            RoadMessage(m.getInt("id"), m.getString("from"), m.getString("text"), m.getString("age"))
        }
    }

    /** Report a message: whoever said it is gone from the road. */
    /** Report a strange question: whoever asked it is blocked and what they said or posted leaves the road. */
    fun reportQuestion(qid: Int, reason: String) {
        call("POST", "/api/questions/report", JSONObject().put("qid", qid).put("reason", reason))
    }

    fun reportMessage(id: Int, reason: String) {
        call("POST", "/api/chat/report", JSONObject().put("id", id).put("reason", reason))
    }

    private fun post(path: String, body: JSONObject): SendResult {
        val (code, text) = call("POST", path, body)
        val o = JSONObject(text)
        return if (code == 200) SendResult(null, o.optBoolean("waiting_human", false), o.optString("preset", ""))
        else SendResult(o.optString("error", "error $code"), false, "", code)
    }

    /** Report whoever wrote the message: they are blocked and their answers are no longer shown. */
    fun report(msgId: Int, reason: String) {
        call("POST", "/api/report", JSONObject().put("msg_id", msgId).put("reason", reason))
    }

    /** "고마워요" to whoever wrote the message. */
    /** Returns the points the answering neighbour earned from this thanks (0 for a simulated car). */
    fun thanks(msgId: Int): Int {
        val (code, text) = call("POST", "/api/thanks", JSONObject().put("msg_id", msgId))
        return if (code == 200) JSONObject(text).optInt("points", 0) else 0
    }

    /** "알리기": tell the road what you see, without being asked. kind: accident, work, clearing, ok. */
    fun inform(kind: String): Boolean = call("POST", "/api/inform", JSONObject().put("kind", kind)).first == 200

    /** "한마디": one of the fixed feelings as a bubble over our car; null clears it. */
    fun setMood(key: String?): Boolean =
        call("POST", "/api/mood", JSONObject().put("mood", key ?: JSONObject.NULL)).first == 200

    /** The bubble text over our own car right now, or null. */
    /** Tell the server where we are going so same-destination cars come first. */
    /** Where this car is, from the navigation. The server measures every distance from the positions the apps report. */
    fun postPosition(place: Place) {
        call("POST", "/api/pos", JSONObject().put("lat", place.lat).put("lon", place.lon).put("heading", place.heading))
    }

    fun setDestination(dest: String) {
        call("POST", "/api/trip", JSONObject().put("dest", dest))
    }
}
