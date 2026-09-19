package it.pat.collettori
import org.json.JSONObject
import org.json.JSONArray

/** Separate APK sandbox; never an operational identity or a server receipt. */
object DemoMode {
    const val base = "demo://local"
    const val user = "00000000-0000-4000-8000-000000000001"
    const val owner = "$base#$user"
    fun validateDataset(p:JSONObject) {
        require(p.getBoolean("synthetic")){"Dataset dimostrativo non riconosciuto"}
        val points=p.getJSONArray("points").objects()
        require(points.size==10){"Dataset incompleto: previsti 10 pozzetti"}
        require(points.all{validCoordinates(it.numberOrNull("latitude"),it.numberOrNull("longitude"))}){"Coordinate demo non valide"}
        val ids=points.map{it.getString("id")}.toSet()
        require(ids.size==points.size){"Identificativi pozzetti duplicati"}
        val segments=p.getJSONArray("segments").objects()
        require(segments.size==9){"Tracciato demo incompleto"}
        for(segment in segments) {
            require(segment.getString("from_id") in ids && segment.getString("to_id") in ids){"Riferimento del tracciato non valido"}
            val geometry=segment.getJSONObject("geometry")
            require(geometry.getString("type")=="LineString"){"Geometria del tracciato non valida"}
            val coordinates=geometry.getJSONArray("coordinates")
            require(coordinates.length()>=2){"Segmento senza geometria"}
            for(i in 0 until coordinates.length()) {
                val coordinate=coordinates.getJSONArray(i)
                require(validCoordinates(coordinate.optDouble(1),coordinate.optDouble(0))){"Coordinate segmento non valide"}
            }
        }
    }
    fun session() = JSONObject().put("base",base).put("user_id",user)
        .put("username","Operatore demo").put("role","DEMO LOCALE")
        .put("offline_until","2099-01-01T00:00:00Z")
    fun catalog(rule:JSONObject) = JSONObject().put("rule",rule)
        .put("datasets",JSONArray()).put("deadlines",JSONArray())
    fun export(visits:List<JSONObject>) = JSONObject().put("format","collettori-demo-1")
        .put("synthetic",true).put("server_received",false).put("controls",JSONArray(visits))
}
