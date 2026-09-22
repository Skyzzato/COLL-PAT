package it.pat.collettori

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

object AppSpec {
    const val NAME="COLL-PAT"
    const val MAP_CACHE_BYTES=209_715_200L
    const val SPLASH_MILLIS=3000L
    const val LOCAL_PROJECT="00000000-0000-4000-8000-000000000013"
    const val PACKAGE="00000000-0000-4000-8000-000000000013"
    const val PROTOCOL=2
    val version get()=BuildConfig.VERSION_NAME
}

fun JSONArray.strings()=(0 until length()).map{getString(it)}
fun canonicalJson(value:Any?):String=when(value){is JSONObject->value.keys().asSequence().toList().sorted().joinToString(prefix="{",postfix="}"){JSONObject.quote(it)+":"+canonicalJson(value.get(it))};is JSONArray->(0 until value.length()).joinToString(prefix="[",postfix="]"){canonicalJson(value.get(it))};is String->JSONObject.quote(value);else->value.toString()}
fun JSONObject.memberships():List<String> = (optJSONArray("collectors")?:JSONArray()).strings()
fun activeEvents(body:JSONObject)=body.getJSONArray("events").objects().filter{!it.has("cancelled")}.sortedWith(compareBy({it.optString("acquired_at")},{it.optString("id")}))
fun lastEvidence(body:JSONObject)=activeEvents(body).lastOrNull()
fun isCancelled(v:Visit)=JSONObject(v.body).has("cancelled")
fun periodic(v:Visit)=!isCancelled(v)&&v.operational=="COMPLETO"&&JSONObject(v.body).optBoolean("periodic_control")
fun visitDay(v:Visit)=Instant.parse(JSONObject(v.body).optString("completed_at",JSONObject(v.body).getString("started_at"))).atZone(ZoneId.of("Europe/Rome")).toLocalDate()
fun semester(instant:Instant):String {val d=instant.atZone(ZoneId.of("Europe/Rome"));return "${d.year}-S${if(d.monthValue<=6)1 else 2}"}
fun mayManageCatalog(session:JSONObject?,localAdminEnabled:Boolean):Boolean = when {
    session==null->false
    session.optString("base")==DemoMode.base->localAdminEnabled
    else->session.has("access_token")&&session.optInt("protocol")==AppSpec.PROTOCOL&&session.optString("role")=="admin"
}
fun hasAnomaly(body:JSONObject)=body.getJSONObject("sheet").let{s->s.optString("anomaly_note").isNotBlank()||(Repository.observationKeys+externalKeys).any{s.optString(it)=="ANOMALO"}||s.optBoolean("raise_needed")||s.optBoolean("road_repair_needed")}
val externalKeys=listOf("surface","subsidence")
val collectorTypes=listOf("CV","CZI","CR","BOE'","opere accessorie")

fun collectorDefaults(id:String,code:String,description:String)=JSONObject().put("id",id).put("code",code).put("description",description)
    .put("display_color",DEFAULT_COLLECTOR_COLOR).put("type","CV").put("visits_h1",2).put("visits_h2",2).put("hours_km_visit",2.0)
    .put("length_m",JSONObject.NULL).put("length_source","UNAVAILABLE").put("length_complete",false).put("archived",false)
fun validateCollector(c:JSONObject){
    java.util.UUID.fromString(c.getString("id"))
    require(c.getString("code").isNotBlank()&&c.getString("description").isNotBlank()){"Codice e descrizione obbligatori"}
    require(c.getString("type") in collectorTypes){"Tipologia non supportata"}
    listOf("visits_h1","visits_h2").forEach{require(c.getDouble(it)>=0&&c.getDouble(it)%1==0.0){"Visite: intero non negativo"}}
    require(c.numberOrNull("hours_km_visit")?.let{it>=0}==true){"Ore per km per visita: numero non negativo"}
    require(c.isNull("length_m")||c.numberOrNull("length_m")?.let{it>=0}==true){"Lunghezza non valida"}
    require(c.getString("length_source") in listOf("MEASURED","ESTIMATED","DECLARED","UNAVAILABLE"))
    require((c.getString("length_source")=="UNAVAILABLE")==c.isNull("length_m")){"Specificare origine e lunghezza coerenti"}
}
fun decimalItalian(text:String)=text.trim().replace(',','.').toDoubleOrNull()?.takeIf{it.isFinite()&&it>=0}
fun lengthLabel(c:JSONObject):String {
    val n=c.numberOrNull("length_m")?:return "Lunghezza non disponibile / non pertinente"
    val source=when(c.optString("length_source")){"MEASURED"->"misurata";"ESTIMATED"->"stimata su collegamenti schematici";else->"dichiarata"}
    return "${if(c.optBoolean("length_complete"))"Lunghezza" else "Subtotale noto (rete incompleta)"}: %.1f m · %s".format(n,source)
}
fun geometryLength(geometry:JSONObject):Double {
    val lines=when(geometry.getString("type")){"LineString"->listOf(geometry.getJSONArray("coordinates"));"MultiLineString"->geometry.getJSONArray("coordinates").let{a->(0 until a.length()).map{a.getJSONArray(it)}};else->error("Geometria lineare richiesta")}
    return lines.sumOf{line->(1 until line.length()).sumOf{i->val a=line.getJSONArray(i-1);val b=line.getJSONArray(i);GpsRule.distance(a.getDouble(1),a.getDouble(0),b.getDouble(1),b.getDouble(0))}}
}

/** Switching a template replaces observations, while notes and photo metadata remain intact. */
fun applyTemplate(body:JSONObject,model:String):JSONObject {
    require(model in listOf("ORDINARY","ASPHALT_EXTERNAL","ASSET_EXTERNAL"))
    val next=JSONObject(body.toString());val old=next.getJSONObject("sheet");val s=Repository.defaultSheet()
    listOf("notes","anomaly_note","exception_reason","technical_value","technical_origin").forEach{s.put(it,old.opt(it)?:"")}
    if(model!="ORDINARY"){
        s.put("opened",false).put("no_open_reason",if(model=="ASPHALT_EXTERNAL")"Pozzetto sotto asfalto" else "Verifica esterna del manufatto")
        Repository.observationKeys.forEach{s.put(it,if(it in listOf("closure","restored"))"NON_APPLICABILE" else "NON_OSSERVABILE")}
        s.put("cleaning",JSONObject.NULL)
    }
    return next.put("model",model).put("sheet",s)
}
fun validateInspection(body:JSONObject,status:String){
    require(status in listOf("COMPLETO","IMPEDITO")){"Esito non ammesso per nuove registrazioni"}
    val s=body.getJSONObject("sheet");val model=body.getString("model")
    if(status=="IMPEDITO")require(s.optString("impediment_reason").isNotBlank()){"Indicare il motivo dell'impedimento"}
    else {
        require(s.optBoolean("accessible")&&!s.optBoolean("unsafe")){"Registrare l'impedimento se il controllo non è eseguibile"}
        if(model=="ORDINARY")require(s.optBoolean("opened")&&Repository.observationKeys.all{s.optString(it) in listOf("REGOLARE","ANOMALO","NON_APPLICABILE")}){"Completare i controlli pertinenti o registrare impedimento"}
        else require(!s.optBoolean("opened")&&s.optString("no_open_reason").isNotBlank()&&listOf("deposits","flow","walls","damage").all{s.optString(it)=="NON_OSSERVABILE"}){"I controlli interni non sono osservabili"}
        require(!hasAnomaly(body)||s.optString("anomaly_note").isNotBlank()){"Descrivere l'anomalia o la necessità di intervento"}
    }
    // An impediment documents the failed visit, without inventing a failed position event.
    if(status=="IMPEDITO"&&activeEvents(body).isEmpty())return
    require(activeEvents(body).isNotEmpty()){"Rilevare una posizione valida prima di completare l’ispezione"}
    if(lastEvidence(body)?.optJSONObject("local_evaluation")?.optString("state")!="COMPATIBILE")require(s.optString("exception_reason").isNotBlank()){"Motivare l'eccezione GPS"}
}
