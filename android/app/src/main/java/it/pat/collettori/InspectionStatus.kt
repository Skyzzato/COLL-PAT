package it.pat.collettori

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.Duration

enum class InspectionState(val label:String,val color:String) {
    INSPECTED("Ispezionato", "#197548"), DUE("Da ispezionare", "#E4A900"),
    ANOMALY_RECENT("Con anomalie · ispezionato recentemente", "#CD5700"),
    ANOMALY_DUE("Con anomalie · da ispezionare", "#B31237")
}
data class InspectionStatus(val inspectionUpToDate:Boolean,val latestInspection:Visit?,val latestInspectionHasAnomaly:Boolean,val calculatedStatus:InspectionState,val targetDays:Double)
fun inspectionInstant(v:Visit):Instant?=runCatching{val b=JSONObject(v.body);Instant.parse(b.optString("completed_at",b.optString("started_at")))}.getOrNull()
fun visitsPerSemester(collector:JSONObject,now:Instant):Int=collector.optInt(if(now.atZone(ZoneId.of("Europe/Rome")).monthValue<=6)"visits_h1" else "visits_h2",1)
/** One index for an entire screen/map. Drafts, impediments, cancelled and obsolete records never count. */
fun latestInspections(inspections:List<Visit>,now:Instant=Instant.now()):Map<String,Visit> = inspections.asSequence()
    .filter{periodic(it)&&it.sync!="RESET_OBSOLETE"&&inspectionInstant(it)?.let{date->!date.isAfter(now)}==true}
    .groupBy{it.manholeId}.mapValues{(_,rows)->rows.maxWith(compareBy<Visit>{inspectionInstant(it)}.thenBy{it.id})}
fun getInspectionStatus(manhole:JSONObject,collector:JSONObject,inspections:List<Visit>,now:Instant=Instant.now()):InspectionStatus =
    inspectionStatus(latestInspections(inspections,now)[manhole.getString("id")],visitsPerSemester(collector,now),now)
fun inspectionStatus(latest:Visit?,visits:Int,now:Instant):InspectionStatus {
    // Average Gregorian half-year: frequency may be any positive integer, not only divisors of six.
    val days=if(visits>0)365.2425/2/visits else Double.POSITIVE_INFINITY
    val recent=visits>0&&latest?.let{inspectionInstant(it)?.let{at->!at.isAfter(now)&&Duration.between(at,now).seconds<=days*86400}}==true
    val anomaly=latest?.let{hasAnomaly(JSONObject(it.body))}==true
    val state=when{anomaly&&recent->InspectionState.ANOMALY_RECENT;anomaly->InspectionState.ANOMALY_DUE;recent->InspectionState.INSPECTED;else->InspectionState.DUE}
    return InspectionStatus(recent,latest,anomaly,state,days)
}
fun statusIndex(points:List<JSONObject>,collectors:List<JSONObject>,visits:List<Visit>,now:Instant):Map<String,InspectionStatus>{
    val latest=latestInspections(visits,now);val byId=collectors.filter{!it.optBoolean("archived")}.associateBy{it.getString("id")}
    return points.associate{p->val frequency=p.memberships().mapNotNull{byId[it]}.maxOfOrNull{visitsPerSemester(it,now)}?:1
        p.getString("id") to inspectionStatus(latest[p.getString("id")],frequency,now)}
}
const val DEFAULT_COLLECTOR_COLOR="#176D73"
fun collectorColor(c:JSONObject?):String=c?.optString("display_color")?.takeIf{it.matches(Regex("#[0-9a-fA-F]{6}"))}?:DEFAULT_COLLECTOR_COLOR

/** Numeric semantic version comparison, tolerant of historical build suffixes. */
fun compareVersions(a:String,b:String):Int {
    fun parts(v:String):List<Int>{val core=v.removeSuffix("-demo").substringBefore('+').substringBefore('-');require(core.matches(Regex("[0-9]+(\\.[0-9]+){1,2}")));return core.split('.').map{it.toInt()}}
    // Published names are historical: 0.2 is release/build 17, after 0.16.
    // Keep numeric ordering for every other version and the same registry in SQL.
    fun ordered(v:String)=parts(v).let{if(it[0]==0&&it[1]==2)listOf(0,17,it.getOrElse(2){0})else it}
    val x=ordered(a);val y=ordered(b)
    for(i in 0..2){val n=(x.getOrElse(i){0}).compareTo(y.getOrElse(i){0});if(n!=0)return n}
    val ap=a.removeSuffix("-demo").substringBefore('+').substringAfter('-',"");val bp=b.removeSuffix("-demo").substringBefore('+').substringAfter('-',"")
    if(ap==bp)return 0;if(ap.isEmpty())return 1;if(bp.isEmpty())return -1
    val aa=ap.split('.');val bb=bp.split('.')
    for(i in 0 until maxOf(aa.size,bb.size)){if(i>=aa.size)return -1;if(i>=bb.size)return 1;val an=aa[i].toIntOrNull();val bn=bb[i].toIntOrNull();val n=when{an!=null&&bn!=null->an.compareTo(bn);an!=null->-1;bn!=null->1;else->aa[i].compareTo(bb[i])};if(n!=0)return n};return 0
}

data class FieldSettings(val minZoomPozzetti:Float=14f,val iconSize:Float=7f,val symbol:String="CIRCLE",val asphalt:Boolean=true,val maxDistance:Double=20.0,val maxAccuracy:Double=20.0,val lineColor:String=DEFAULT_COLLECTOR_COLOR,val lineWidth:Int=4){
    fun json()=JSONObject().put("zoom",minZoomPozzetti.toDouble()).put("size",iconSize.toDouble()).put("symbol",symbol).put("asphalt",asphalt).put("distance",maxDistance).put("accuracy",maxAccuracy).put("line_color",lineColor).put("line_width",lineWidth)
    companion object{fun parse(b:JSONObject)=FieldSettings(b.optDouble("zoom",14.0).coerceIn(8.0,20.0).toFloat(),b.optDouble("size",7.0).coerceIn(4.0,14.0).toFloat(),ManholeSymbol.fromId(b.optString("symbol","CIRCLE")).name,b.optBoolean("asphalt",true),normalizeThreshold(b.optDouble("distance",20.0),DISTANCE_STEPS),normalizeThreshold(b.optDouble("accuracy",20.0),ACCURACY_STEPS),b.optString("line_color",DEFAULT_COLLECTOR_COLOR).takeIf{it.matches(Regex("#[0-9a-fA-F]{6}"))}?:DEFAULT_COLLECTOR_COLOR,b.optInt("line_width",4).coerceIn(1,10))}
}
fun acceptableMeasure(value:Double?,limit:Double)=value!=null&&value.isFinite()&&value>=0&&value<=limit
fun gpsQuality(event:JSONObject?):String {
    if(event==null)return "UNAVAILABLE"
    val limits=event.optJSONObject("applied_limits")?:event.optJSONObject("local_evaluation")?.optJSONObject("parameters")
    if(!acceptableMeasure(event.numberOrNull("accuracy_m"),limits?.optDouble("max_accuracy_m",15.0)?:15.0))return "IMPRECISE"
    if(!event.isNull("error")&&event.optString("error").isNotBlank())return "UNRELIABLE"
    if(!validCoordinates(event.numberOrNull("latitude"),event.numberOrNull("longitude"))||event.optBoolean("mock")||event.optString("permission")!="PRECISE"||!acceptableMeasure(event.numberOrNull("age_s"),10.0))return "UNRELIABLE"
    if(!acceptableMeasure(event.optJSONObject("local_evaluation")?.numberOrNull("distance_m"),limits?.optDouble("radius_m",20.0)?:20.0))return "UNRELIABLE"
    return "RELIABLE"
}
val ACCURACY_STEPS=listOf(20.0,40.0,60.0,80.0,100.0,120.0,140.0,150.0)
val DISTANCE_STEPS=listOf(5.0,10.0,15.0,20.0,25.0,30.0)
fun normalizeThreshold(value:Double,steps:List<Double>)=steps.lastOrNull{it<=value}?:steps.first()
fun friendlyError(e:Exception):String=when{
    e is ApiError->when{
        e.code==401->"Sessione scaduta. Accedi nuovamente; i dati locali sono conservati."
        e.code==403->"Operazione non autorizzata per questo account/progetto."
        e.serverCode in setOf("PGRST202","PGRST204","42883","42P01")||e.code==404->"Contratto server o risorsa non disponibile. Verificare le migrazioni del progetto."
        e.code==400||e.code==422->"Il server ha rifiutato i dati. Verificare la scheda e il contratto server."
        e.code==409->"Conflitto: dati o ambito di eliminazione cambiati. Le operazioni pendenti richiedono una verifica."
        e.code==426->"Versione dell’app non più supportata. Installa l’aggiornamento."
        e.code==429->"Limite di richieste raggiunto. Riprova più tardi."
        e.code==408->"Tempo di risposta scaduto. La ricevuta verrà verificata al prossimo invio."
        e.code>=500->"Errore del server. Il lavoro resta in coda; riprova più tardi."
        else->"Richiesta rifiutata dal server."
    }+" ("+e.diagnostic()+")"
    e is java.net.SocketTimeoutException->"Tempo di risposta scaduto. Il lavoro resta sul dispositivo."
    e is java.net.UnknownHostException||e is java.net.ConnectException->"Connessione non disponibile. Il lavoro resta sul dispositivo."
    e is java.io.IOException->"Collegamento interrotto. La ricevuta verrà verificata al prossimo invio."
    e is IllegalArgumentException||e is IllegalStateException->e.message?:"Verifica i dati inseriti."
    else->"Operazione non riuscita. I dati già salvati sono conservati."
}
