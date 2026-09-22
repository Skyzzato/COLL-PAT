package it.pat.collettori

import org.json.JSONObject
import java.time.Instant

enum class IdentificationMethod { GPS, NFC_HF, EXTERNAL_RFID, QR, MANUAL }
data class TagAssociation(val uid:String, val type:String, val associatedAt:String, val status:String, val replacedBy:String?=null)
data class IdentificationResult(val state:String, val candidates:List<String>, val distances:Map<String,Double>)
interface ManholeIdentificationService {
    val method:IdentificationMethod
    fun identify(points:List<JSONObject>, position:JSONObject?):IdentificationResult
}

/** Suggestion only: the operator always confirms the physical asset. */
object GpsIdentification:ManholeIdentificationService {
    override val method=IdentificationMethod.GPS
    override fun identify(points:List<JSONObject>, position:JSONObject?)=identify(points,position,FieldSettings())
    fun identify(points:List<JSONObject>, position:JSONObject?,settings:FieldSettings):IdentificationResult {
        fun empty(state:String)=IdentificationResult(state,emptyList(),emptyMap())
        val lat=position?.numberOrNull("latitude")?:return empty("Posizione non disponibile")
        val lon=position.numberOrNull("longitude")?:return empty("Posizione non disponibile")
        if(!validCoordinates(lat,lon))return empty("Posizione non disponibile")
        val elapsed=try{java.time.Duration.between(Instant.parse(position.getString("acquired_at")),Instant.now()).seconds}catch(_:Exception){return empty("Posizione da aggiornare")}
        if(elapsed !in 0..60 || (position.numberOrNull("age_s")?:61.0)+elapsed>60) return empty("Posizione da aggiornare")
        if(!position.isNull("error") || position.optBoolean("mock",false))return empty("Posizione non affidabile")
        val distances=points.associate{it.getString("id") to GpsRule.distance(lat,lon,it.getDouble("latitude"),it.getDouble("longitude"))}
        val accuracy=position.numberOrNull("accuracy_m")
        if(accuracy==null || !accuracy.isFinite() || accuracy<0 || accuracy>settings.maxAccuracy || position.optString("permission")!="PRECISE")return IdentificationResult("Posizione troppo imprecisa",emptyList(),distances)
        val radius=settings.maxDistance
        val candidates=distances.filterValues{it<=radius}.toList().sortedBy{it.second}.map{it.first}
        val state=when {
            candidates.isEmpty()->"Nessun pozzetto compatibile"
            candidates.size>1->"Più pozzetti compatibili: verifica sulla mappa"
            accuracy<=10 && distances.getValue(candidates.first())+accuracy<=15->"Identificazione molto probabile · da confermare"
            else->"Possibile identificazione · da confermare"
        }
        return IdentificationResult(state,candidates,distances)
    }
}

fun precisionText(position:JSONObject?):String=position?.numberOrNull("accuracy_m")?.takeIf{it>=0}?.let{"Precisione posizione: ± %.0f m".format(it)}?:"Precisione non ancora disponibile"
