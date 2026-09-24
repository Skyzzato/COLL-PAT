package it.pat.collettori

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

interface InspectionCapture {
    val status:String get()=""
    suspend fun acquire(inspection:JSONObject,rule:Rule,progress:(Int)->Unit):JSONObject
    fun cancel()
}
/** Foreground orientation request and cancellable inspection acquisition; no background tracking. */
class LocationCapture(private val context:Context):EvidenceCollector,InspectionCapture{
    private var requestCancellation:CancellationTokenSource?=null
    @Volatile private var userCancelled=false
    private var acquisitionJob:Job?=null
    override var status:String="";private set
    override fun cancel(){userCancelled=true;requestCancellation?.cancel();acquisitionJob?.cancel()}
    @Suppress("DEPRECATION")
    private fun Location.sample()=GpsSample(elapsedRealtimeNanos,latitude,longitude,if(hasAccuracy())accuracy.toDouble() else null,if(Build.VERSION.SDK_INT>=31)isMock else isFromMockProvider)
    @Suppress("MissingPermission")
    override suspend fun acquire(inspection:JSONObject,rule:Rule,progress:(Int)->Unit):JSONObject {
        check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){"Consenti la posizione precisa per registrare una rilevazione GPS."}
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        check(if(Build.VERSION.SDK_INT>=28)manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER)||manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){"Localizzazione disattivata. Attivala e riprova."}
        acquisitionJob=coroutineContext[Job]
        val client=LocationServices.getFusedLocationProviderClient(context)
        var callback:LocationCallback?=null
        try{
            userCancelled=false
            val start=SystemClock.elapsedRealtimeNanos();val wallStart=Instant.now().toString();val window=GpsWindow(start,rule.accuracy)
            callback=object:LocationCallback(){override fun onLocationResult(result:LocationResult){result.locations.forEach{window.add(it.sample(),SystemClock.elapsedRealtimeNanos())}}}
            val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,1000).setMinUpdateIntervalMillis(500).setMaxUpdateDelayMillis(0).setMaxUpdateAgeMillis(0).build()
            client.requestLocationUpdates(request,callback,Looper.getMainLooper()).await()
            while(!window.ready(SystemClock.elapsedRealtimeNanos())){
                ensureActiveCapture()
                val elapsed=SystemClock.elapsedRealtimeNanos()-start
                check(elapsed<AcquisitionPolicy.TIMEOUT_NS){"Timeout: nessun rilievo GPS utilizzabile. Riprova oppure scegli Non rilevare GPS."}
                status="${window.count} misure · accuratezza media "+(window.meanAccuracy?.let{"±%.1f m".format(it)}?:"in attesa")
                window.centroid()?.let{(lat,lon)->if(inspection.has("target_latitude"))status+=" · distanza %.1f m".format(GpsRule.distance(lat,lon,inspection.getDouble("target_latitude"),inspection.getDouble("target_longitude")))}
                progress(((elapsed)/1_000_000_000).toInt());delay(100)
            }
            ensureActiveCapture()
            return window.finish(SystemClock.elapsedRealtimeNanos()).put("id",UUID.randomUUID().toString())
                .put("inspection_id",inspection.getString("id")).put("manhole_id",inspection.getString("manhole_id"))
                .put("user_id",inspection.getString("user_id")).put("device_id",inspection.getString("device_id")).put("dataset_id",inspection.getString("dataset_id"))
                .put("requested_at",wallStart).put("acquisition_started_at",wallStart).put("acquired_at",Instant.now().toString())
                .put("acquisition_ended_at",Instant.now().toString()).put("permission","PRECISE").put("mock",false).put("error",JSONObject.NULL)
                .put("provider","fused").put("rule_version",rule.version).put("app_version",AppSpec.version).put("applied_limits",rule.json())
        }finally{
            callback?.let{withContext(NonCancellable){withTimeoutOrNull(2500){client.removeLocationUpdates(it).await()}}}
            acquisitionJob=null
        }
    }
    private suspend fun ensureActiveCapture(){coroutineContext.ensureActive();if(userCancelled)throw CancellationException("Rilevazione annullata")}
    @Suppress("MissingPermission", "DEPRECATION")
    override suspend fun collect(inspection:JSONObject,rule:Rule):JSONObject{
        val precise=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val coarse=context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val e=JSONObject().put("id",UUID.randomUUID().toString()).put("inspection_id",inspection.getString("id"))
            .put("manhole_id",inspection.getString("manhole_id")).put("user_id",inspection.getString("user_id")).put("device_id",inspection.getString("device_id"))
            .put("dataset_id",inspection.getString("dataset_id")).put("rule_version",rule.version).put("app_version",AppSpec.version)
            .put("requested_at",Instant.now().toString()).put("permission",if(precise)"PRECISE" else if(coarse)"APPROXIMATE" else "DENIED")
        listOf("latitude","longitude","accuracy_m","age_s","provider","mock","error").forEach{e.put(it,JSONObject.NULL)}
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled=if(Build.VERSION.SDK_INT>=28)manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER)||manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if(!coarse&&!precise)e.put("error","permesso negato")
        else if(!enabled)e.put("error","localizzazione disattivata")
        else{
            val cancellation=CancellationTokenSource()
            requestCancellation=cancellation
            try{
                val request=CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setMaxUpdateAgeMillis(0).setDurationMillis((rule.timeout*1000).toLong()).build()
                val location=withTimeoutOrNull((rule.timeout*1000).toLong()+1000){LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request,cancellation.token).await()}
                if(userCancelled)e.put("error","acquisizione annullata dall'operatore")
                else if(location==null)e.put("error","timeout o nessun risultato")
                else{
                    val age=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1_000_000_000.0
                    e.put("latitude",location.latitude).put("longitude",location.longitude).put("accuracy_m",if(location.hasAccuracy())location.accuracy.toDouble() else JSONObject.NULL)
                        .put("age_s",age.coerceAtLeast(0.0)).put("provider",location.provider?:JSONObject.NULL)
                        .put("mock",if(Build.VERSION.SDK_INT>=31)location.isMock else location.isFromMockProvider)
                    if(age<0)e.put("error","riferimento temporale incoerente")
                }
            }catch(e1:kotlinx.coroutines.CancellationException){
                if(userCancelled)e.put("error","acquisizione annullata dall'operatore") else throw e1
            }
            catch(e1:SecurityException){e.put("error","permesso revocato")}
            catch(e1:Exception){if(e1 is kotlinx.coroutines.CancellationException)throw e1;e.put("error","localizzazione non disponibile: "+e1.javaClass.simpleName)}
            finally{cancellation.cancel();requestCancellation=null}
        }
        return e.put("acquired_at",Instant.now().toString())
    }
}
