package it.pat.collettori

import org.json.JSONObject
import kotlin.math.*

data class GpsSample(val nanos:Long,val latitude:Double,val longitude:Double,val accuracy:Double?,val mock:Boolean=false)
object AcquisitionPolicy {
    const val LEGACY_METHOD="SPHERICAL_MEAN_MAX_ACCURACY_V1"
    const val METHOD="SPHERICAL_MEAN_MEAN_ACCURACY_V2"
    const val STABILIZATION_NS=3_000_000_000L
    const val TIMEOUT_NS=30_000_000_000L
    const val DURATION_NS=5_000_000_000L
    const val MIN_SAMPLES=3
    const val MIN_SPAN_NS=2_000_000_000L
    const val LAST_MAX_AGE_NS=2_000_000_000L
    const val PRECHECK_MAX_AGE_NS=10_000_000_000L
    fun fresh(sample:GpsSample?,now:Long)=sample!=null&&now-sample.nanos in 0..PRECHECK_MAX_AGE_NS
    fun validate(sample:GpsSample?,now:Long,limit:Double):String?=when{
        !fresh(sample,now)->"Nessuna misura GPS recente. Aggiorna la localizzazione e riprova."
        !validCoordinates(sample!!.latitude,sample.longitude)||sample.accuracy==null||!sample.accuracy.isFinite()||sample.accuracy<0->"Misura GPS non utilizzabile: coordinate o accuratezza assenti/non valide."
        sample.accuracy>limit->"Precisione GPS insufficiente: ±%.1f m. Limite impostato: %.1f m. Riprova con una localizzazione più precisa.".format(sample.accuracy,limit)
        sample.mock->"Posizione simulata: acquisisci una misura reale."
        else->null
    }
}

/** Pure acquisition window: elapsed realtime only, no wall-clock freshness or callback counting. */
class GpsWindow(val start:Long,private val maxAccuracy:Double){
    private val samples=mutableListOf<GpsSample>()
    private val seen=mutableSetOf<Long>()
    val failure:String? get()=null // Poor individual accuracy is never a terminal acquisition error.
    val count get()=samples.size
    val meanAccuracy get()=samples.mapNotNull{it.accuracy}.takeIf{it.isNotEmpty()}?.average()
    @Synchronized fun add(sample:GpsSample,now:Long){
        if(sample.nanos<start+AcquisitionPolicy.STABILIZATION_NS||sample.nanos>start+AcquisitionPolicy.TIMEOUT_NS||sample.nanos>now||now-sample.nanos>AcquisitionPolicy.LAST_MAX_AGE_NS||sample.nanos in seen)return
        if(sample.mock||!validCoordinates(sample.latitude,sample.longitude)||sample.accuracy==null||!sample.accuracy.isFinite()||sample.accuracy<0)return
        seen.add(sample.nanos);samples.add(sample);samples.sortBy{it.nanos}
    }
    @Synchronized fun centroid():Pair<Double,Double>?{
        if(samples.isEmpty())return null
        val x=samples.sumOf{cos(Math.toRadians(it.latitude))*cos(Math.toRadians(it.longitude))};val y=samples.sumOf{cos(Math.toRadians(it.latitude))*sin(Math.toRadians(it.longitude))};val z=samples.sumOf{sin(Math.toRadians(it.latitude))}
        return Math.toDegrees(atan2(z,sqrt(x*x+y*y))) to Math.toDegrees(atan2(y,x))
    }
    @Synchronized fun ready(now:Long)=now>=start+AcquisitionPolicy.STABILIZATION_NS+AcquisitionPolicy.DURATION_NS&&samples.size>=AcquisitionPolicy.MIN_SAMPLES&&samples.last().nanos-samples.first().nanos>=AcquisitionPolicy.MIN_SPAN_NS&&now-samples.last().nanos<=AcquisitionPolicy.LAST_MAX_AGE_NS
    @Synchronized fun finish(now:Long):JSONObject{
        check(ready(now)){"Rilevazione non riuscita: campioni distinti insufficienti o non recenti. Riprova oppure documenta il mancato rilievo GPS."}
        // Unit-vector mean on the WGS84 geographic sphere: handles longitude wrap correctly.
        val x=samples.sumOf{cos(Math.toRadians(it.latitude))*cos(Math.toRadians(it.longitude))}
        val y=samples.sumOf{cos(Math.toRadians(it.latitude))*sin(Math.toRadians(it.longitude))}
        val z=samples.sumOf{sin(Math.toRadians(it.latitude))}
        check(sqrt(x*x+y*y+z*z)>1e-8){"Campioni geografici incoerenti"}
        val lat=Math.toDegrees(atan2(z,sqrt(x*x+y*y)));val lon=Math.toDegrees(atan2(y,x))
        return JSONObject().put("latitude",lat).put("longitude",lon).put("accuracy_m",samples.map{it.accuracy!!}.average())
            .put("dispersion_m",samples.maxOf{GpsRule.distance(lat,lon,it.latitude,it.longitude)})
            .put("sample_count",samples.size).put("method",AcquisitionPolicy.METHOD).put("age_s",(now-samples.last().nanos)/1e9)
            .put("samples",org.json.JSONArray(samples.map{JSONObject().put("elapsed_ns",it.nanos).put("latitude",it.latitude).put("longitude",it.longitude).put("accuracy_m",it.accuracy).put("mock",it.mock)}))
            .put("stabilization_ms",3000).put("sampling_ms",(now-start-AcquisitionPolicy.STABILIZATION_NS)/1_000_000)
            .put("duration_ms",(now-start)/1_000_000).put("sample_span_ms",(samples.last().nanos-samples.first().nanos)/1_000_000)
            .put("started_elapsed_ns",start).put("ended_elapsed_ns",now).put("last_sample_elapsed_ns",samples.last().nanos)
    }
}

fun accurateEvidence(event:JSONObject?):Boolean {
    if(event==null)return false
    val limits=event.optJSONObject("applied_limits")?:return false
    return acceptableMeasure(event.numberOrNull("accuracy_m"),limits.optDouble("max_accuracy_m",Double.NaN))&&
        validCoordinates(event.numberOrNull("latitude"),event.numberOrNull("longitude"))&&event.optString("permission")=="PRECISE"&&
        !event.optBoolean("mock")&&(event.isNull("error")||event.optString("error").isBlank())&&acceptableMeasure(event.numberOrNull("age_s"),10.0)
}
fun motivatedGpsException(event:JSONObject?)=accurateEvidence(event)&&event?.optString("method")==AcquisitionPolicy.LEGACY_METHOD&&
    event.optString("match_outcome")=="EXCEPTION"&&event.optString("exception_reason").isNotBlank()
fun usableInspectionGps(event:JSONObject?)=when(event?.optString("method")){
    AcquisitionPolicy.METHOD->accurateEvidence(event)&&event.optString("match_outcome")=="VERIFIED"&&event.optJSONObject("local_evaluation")?.optString("state")=="COMPATIBILE"
    AcquisitionPolicy.LEGACY_METHOD->accurateEvidence(event)&&(event.optString("match_outcome")=="VERIFIED"&&event.optJSONObject("local_evaluation")?.optString("state")=="COMPATIBILE"||motivatedGpsException(event))
    else->gpsQuality(event)=="RELIABLE"
}
fun noGpsConfirmed(body:JSONObject)=body.optJSONObject("gps_state")?.let{it.optString("status")=="NOT_RECORDED_WITH_REASON"&&it.optString("reason").isNotBlank()&&it.optString("author").isNotBlank()}==true&&activeEvents(body).isEmpty()
fun inspectionLocationReady(body:JSONObject)=noGpsConfirmed(body)||usableInspectionGps(lastEvidence(body))

/** New writes always use the verified v2 contract; old evidence remains readable. */
fun validateNewEvidence(event:JSONObject,inspection:JSONObject){
    require(event.optString("inspection_id")==inspection.getString("id")&&event.optString("manhole_id")==inspection.getString("manhole_id")){"La rilevazione appartiene a un altro sopralluogo"}
    require(accurateEvidence(event)){"Accuratezza GPS insufficiente: riprova oppure documenta il mancato rilievo"}
    val limits=event.getJSONObject("applied_limits")
    require(limits.getDouble("max_accuracy_m") in ACCURACY_STEPS&&limits.getDouble("radius_m") in DISTANCE_STEPS){"Soglie GPS non ammesse"}
    require(event.optString("method")==AcquisitionPolicy.METHOD&&event.optInt("sample_count")>=3&&event.optLong("duration_ms")>=8000&&event.optLong("sampling_ms")>=5000&&event.optLong("stabilization_ms")==3000L){"Acquisizione GPS incompleta"}
    val start=event.getLong("started_elapsed_ns");val end=event.getLong("ended_elapsed_ns")
    val window=GpsWindow(start,limits.getDouble("max_accuracy_m"))
    val samples=event.getJSONArray("samples").objects()
    require(samples.size==event.getInt("sample_count")&&samples.map{it.getLong("elapsed_ns")}.distinct().size==samples.size)
    samples.forEach{s->val t=s.getLong("elapsed_ns");require(t>=start+AcquisitionPolicy.STABILIZATION_NS);window.add(GpsSample(t,s.getDouble("latitude"),s.getDouble("longitude"),s.getDouble("accuracy_m"),s.getBoolean("mock")),t)}
    val actual=window.finish(end)
    require(window.count==samples.size&&end-start in 8_000_000_000L..31_000_000_000L)
    listOf("duration_ms","sampling_ms","sample_span_ms","last_sample_elapsed_ns").forEach{k->require(actual.getLong(k)==event.getLong(k)){"Tempi GPS incoerenti"}}
    require(kotlin.math.abs(actual.getDouble("dispersion_m")-event.getDouble("dispersion_m"))<0.1&&kotlin.math.abs(actual.getDouble("age_s")-event.getDouble("age_s"))<0.001){"Dispersione o età GPS incoerenti"}
    require(kotlin.math.abs(actual.getDouble("accuracy_m")-event.getDouble("accuracy_m"))<0.001&&GpsRule.distance(actual.getDouble("latitude"),actual.getDouble("longitude"),event.getDouble("latitude"),event.getDouble("longitude"))<0.1){"Media GPS incoerente"}
    require(event.optString("match_outcome")=="VERIFIED"&&event.getJSONObject("local_evaluation").getString("state")=="COMPATIBILE"){"Corrispondenza GPS non verificata: riprova o documenta il mancato rilievo"}
}
