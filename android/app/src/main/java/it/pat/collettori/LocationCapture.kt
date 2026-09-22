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

/** One foreground request. No background location and no route recording. */
class LocationCapture(private val context:Context):EvidenceCollector{
    private var requestCancellation:CancellationTokenSource?=null
    @Volatile private var userCancelled=false
    fun cancel(){userCancelled=true;requestCancellation?.cancel()}
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
            catch(e1:Exception){e.put("error","localizzazione non disponibile: "+e1.javaClass.simpleName)}
            finally{cancellation.cancel();requestCancellation=null}
        }
        return e.put("acquired_at",Instant.now().toString())
    }
}
