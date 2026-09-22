package it.pat.collettori

import org.json.JSONObject
import kotlin.math.*

data class GpsSample(val nanos:Long,val latitude:Double,val longitude:Double,val accuracy:Double?,val mock:Boolean=false)
object AcquisitionPolicy {
    const val METHOD="SPHERICAL_MEAN_MAX_ACCURACY_V1"
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
    private var lastSeen=start-1
    var failure:String?=null;private set
    fun add(sample:GpsSample,now:Long){
        if(failure!=null||sample.nanos<start||sample.nanos>start+AcquisitionPolicy.DURATION_NS||sample.nanos>now||sample.nanos<=lastSeen)return
        lastSeen=sample.nanos
        if(sample.accuracy?.let{it.isFinite()&&it>maxAccuracy}==true){failure=AcquisitionPolicy.validate(sample,now,maxAccuracy);return}
        if(AcquisitionPolicy.validate(sample,now,maxAccuracy)!=null)return
        samples.add(sample)
    }
    fun finish(now:Long):JSONObject{
        check(failure==null){failure!!}
        check(now>=start+AcquisitionPolicy.DURATION_NS){"Acquisizione non completata"}
        check(samples.size>=AcquisitionPolicy.MIN_SAMPLES&&samples.last().nanos-samples.first().nanos>=AcquisitionPolicy.MIN_SPAN_NS&&now-samples.last().nanos<=AcquisitionPolicy.LAST_MAX_AGE_NS){"Rilevazione non riuscita: campioni distinti insufficienti o non recenti. Riprova mantenendo fermo il dispositivo."}
        // Unit-vector mean on the WGS84 geographic sphere: handles longitude wrap correctly.
        val x=samples.sumOf{cos(Math.toRadians(it.latitude))*cos(Math.toRadians(it.longitude))}
        val y=samples.sumOf{cos(Math.toRadians(it.latitude))*sin(Math.toRadians(it.longitude))}
        val z=samples.sumOf{sin(Math.toRadians(it.latitude))}
        check(sqrt(x*x+y*y+z*z)>1e-8){"Campioni geografici incoerenti"}
        val lat=Math.toDegrees(atan2(z,sqrt(x*x+y*y)));val lon=Math.toDegrees(atan2(y,x))
        return JSONObject().put("latitude",lat).put("longitude",lon).put("accuracy_m",samples.maxOf{it.accuracy!!})
            .put("dispersion_m",samples.maxOf{GpsRule.distance(lat,lon,it.latitude,it.longitude)})
            .put("sample_count",samples.size).put("method",AcquisitionPolicy.METHOD).put("age_s",(now-samples.last().nanos)/1e9)
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
fun motivatedGpsException(event:JSONObject?)=accurateEvidence(event)&&event?.optString("method")==AcquisitionPolicy.METHOD&&
    event.optString("match_outcome")=="EXCEPTION"&&event.optString("exception_reason").isNotBlank()
fun usableInspectionGps(event:JSONObject?)=if(event?.optString("method")==AcquisitionPolicy.METHOD) accurateEvidence(event)&&(event.optString("match_outcome")=="VERIFIED"&&event.optJSONObject("local_evaluation")?.optString("state")=="COMPATIBILE"||motivatedGpsException(event)) else gpsQuality(event)=="RELIABLE"

/** Validates before any durable write, including a repeat after an exception dialog. */
fun validateNewEvidence(event:JSONObject,inspection:JSONObject){
    require(event.optString("inspection_id")==inspection.getString("id")&&event.optString("manhole_id")==inspection.getString("manhole_id")){"La rilevazione appartiene a un altro sopralluogo"}
    require(accurateEvidence(event)){"Accuratezza GPS non valida o misura non affidabile: nessuna eccezione ammessa"}
    require(event.optString("method")==AcquisitionPolicy.METHOD&&event.optInt("sample_count")>=AcquisitionPolicy.MIN_SAMPLES&&event.optLong("duration_ms")>=5000&&event.optLong("sample_span_ms")>=2000){"Acquisizione GPS incompleta"}
    val matched=event.getJSONObject("local_evaluation").getString("state")=="COMPATIBILE"
    require(if(matched)event.optString("match_outcome")=="VERIFIED" else motivatedGpsException(event)){"Indicare e confermare l’Eccezione GPS prima di registrare la posizione"}
}
