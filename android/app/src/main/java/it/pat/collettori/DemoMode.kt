package it.pat.collettori
import org.json.JSONObject
import org.json.JSONArray

/** Separate APK sandbox; never an operational identity or a server receipt. */
object DemoMode {
    const val base = "demo://local"
    const val user = "00000000-0000-4000-8000-000000000001"
    const val owner = "$base#$user"
    fun session() = JSONObject().put("base",base).put("user_id",user)
        .put("username","Operatore demo").put("role","DEMO LOCALE")
        .put("offline_until","2099-01-01T00:00:00Z")
    fun catalog(rule:JSONObject) = JSONObject().put("rule",rule)
        .put("datasets",JSONArray()).put("deadlines",JSONArray())
    fun export(visits:List<JSONObject>) = JSONObject().put("format","collettori-demo-1")
        .put("synthetic",true).put("server_received",false).put("controls",JSONArray(visits))
}
