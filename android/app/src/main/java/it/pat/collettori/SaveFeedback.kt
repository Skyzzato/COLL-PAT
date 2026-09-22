package it.pat.collettori

import org.json.JSONObject

/** Created only after the Room transaction commits; server updates never reset its deadline. */
data class SaveFeedback(val id:String,val edit:Long,val draft:Boolean,val savedAt:Long){
    companion object{const val DISPLAY_MS=2000L}
    fun remaining(now:Long)=(savedAt+DISPLAY_MS-now).coerceAtLeast(0)
    fun serverConfirmed(visit:Visit)=visit.id==id&&visit.sync=="RICEVUTO_SERVER"&&JSONObject(visit.body).optLong("local_edit")==edit&&visit.receipt!=null
    fun title(visit:Visit,demo:Boolean)=if(draft)"Bozza salvata" else if(serverConfirmed(visit))"Ispezione salvata sul server" else if(demo)"Ispezione salvata in locale" else "Ispezione salvata in locale — in attesa di sincronizzazione"
    fun detail(visit:Visit,demo:Boolean)=if(!draft)"" else if(serverConfirmed(visit))"Sul server — disponibile agli utenti autorizzati" else if(demo)"Sul dispositivo — demo offline" else "Sul dispositivo — in attesa di sincronizzazione"
    fun mayReturn(visit:Visit)=visit.id==id&&visit.sync !in setOf("CONFLICT","RESET_OBSOLETE")
}
