package it.pat.collettori

import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.*

data class Rule(val version: String, val radius: Double, val accuracy: Double, val age: Double, val timeout: Double, val mapUncertainty: Double, val earth: Double) {
    companion object { fun parse(p: JSONObject) = Rule(p.getString("version"), p.getDouble("radius_m"), p.getDouble("max_accuracy_m"), p.getDouble("max_age_s"), p.getDouble("timeout_s"), p.getDouble("max_map_uncertainty_m"), p.getDouble("earth_radius_m")) }
    fun json() = JSONObject().put("version",version).put("radius_m",radius).put("max_accuracy_m",accuracy).put("max_age_s",age).put("timeout_s",timeout).put("max_map_uncertainty_m",mapUncertainty).put("earth_radius_m",earth)
}
fun JSONObject.numberOrNull(key: String): Double? = optDouble(key, Double.NaN).takeIf { it.isFinite() }
fun validCoordinates(latitude: Double?, longitude: Double?) = latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0
fun JSONObject.boolOrNull(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

object GpsRule {
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double, radius: Double = 6371008.8): Double {
        val p1=Math.toRadians(lat1); val p2=Math.toRadians(lat2)
        val h=sin((p2-p1)/2).pow(2)+cos(p1)*cos(p2)*sin(Math.toRadians(lon2-lon1)/2).pow(2)
        return 2*radius*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
    fun decide(d: Double?, a: Double?, age: Double?, g: Double?, permission: String, mock: Boolean?, ambiguous: Boolean, error: String?, p: Rule): JSONObject {
        val reasons= mutableListOf<String>()
        fun result(state:String)=JSONObject().put("state",state).put("distance_m",d?:JSONObject.NULL).put("reasons",JSONArray(reasons))
        if(d==null || !error.isNullOrEmpty() || age==null || age<0 || age>p.age){ reasons.add(error?.takeIf{it.isNotEmpty()}?:"misura assente o troppo vecchia");return result("NON_DISPONIBILE") }
        if(permission!="PRECISE")reasons.add("permesso non preciso")
        if(a==null || a<0 || a>p.accuracy)reasons.add("accuratezza insufficiente")
        if(g==null)reasons.add("qualità cartografica da verificare") else if(g<0 || g>p.mapUncertainty)reasons.add("incertezza cartografica elevata")
        if(mock==true)reasons.add("posizione simulata segnalata")
        if(ambiguous)reasons.add("manufatti vicini: selezione da verificare")
        if(reasons.isNotEmpty())return result("INCERTA")
        val state=if(d+a!!+g!!<=p.radius)"COMPATIBILE" else if(d-a-g>p.radius)"NON_COMPATIBILE" else "INCERTA"
        if(state!="COMPATIBILE")reasons.add("margine di prossimità")
        return result(state)
    }
    fun evaluate(e: JSONObject, point: JSONObject, points: List<JSONObject>, p: Rule): JSONObject {
        val lat=e.numberOrNull("latitude");val lon=e.numberOrNull("longitude")
        val d=if(!validCoordinates(lat,lon))null else distance(lat!!,lon!!,point.getDouble("latitude"),point.getDouble("longitude"),p.earth)
        val ambiguous=d!=null && points.count{distance(lat!!,lon!!,it.getDouble("latitude"),it.getDouble("longitude"),p.earth)<=p.radius}>1
        return decide(d,e.numberOrNull("accuracy_m"),e.numberOrNull("age_s"),point.numberOrNull("uncertainty_m"),e.getString("permission"),e.boolOrNull("mock"),ambiguous,if(e.isNull("error"))null else e.optString("error"),p)
            .put("rule_version",p.version).put("parameters",p.json()).put("ambiguous",ambiguous)
    }
    fun label(state:String)=when(state){"COMPATIBILE"->"Prossimità GPS compatibile";"INCERTA"->"Prossimità GPS da verificare";"NON_COMPATIBILE"->"Posizione non compatibile con il pozzetto selezionato";else->"Posizione non disponibile"}
}

// Independent extension points; no scanner dependencies or inactive UI.
enum class SelectionMethod { MAP, LIST, FUTURE_QR, FUTURE_TAG_CODE, FUTURE_NFC }
data class AssetSelection(val manholeId:String,val method:SelectionMethod)
interface AssetIdentifier { suspend fun select():AssetSelection }
interface EvidenceCollector { suspend fun collect(inspection:JSONObject, rule:Rule):JSONObject }
