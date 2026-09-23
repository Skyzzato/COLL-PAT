package it.pat.collettori

import org.json.JSONObject

/** Created after the Room transaction; the user closes the summary explicitly. */
data class SaveFeedback(val id:String,val edit:Long,val draft:Boolean,val savedAt:Long){
    fun serverConfirmed(visit:Visit)=visit.id==id&&visit.sync=="RICEVUTO_SERVER"&&JSONObject(visit.body).optLong("local_edit")==edit&&visit.receipt!=null
    fun title(visit:Visit)=if(draft)"Bozza salvata" else if(serverConfirmed(visit))"Ispezione salvata sul server" else "Ispezione salvata in locale — in attesa di sincronizzazione"
    fun detail(visit:Visit)=if(!draft)"" else if(serverConfirmed(visit))"Sul server — disponibile agli utenti autorizzati" else "Sul dispositivo — in attesa di sincronizzazione"
    fun mayReturn(visit:Visit)=visit.id==id&&visit.sync !in setOf("CONFLICT","RESET_OBSOLETE")
}
