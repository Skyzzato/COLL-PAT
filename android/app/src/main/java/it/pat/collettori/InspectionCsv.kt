package it.pat.collettori

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object InspectionCsv {
    fun quarter(at:Instant):String {val date=at.atZone(ZoneId.of("Europe/Rome"));return "${date.year}-T${(date.monthValue-1)/3+1}"}
    fun filename(now:Instant)="COLL-PAT_ispezioni_${quarter(now).replace('-','_')}.csv"
    fun cell(value:Any?)="\""+(value?.toString()?:"").replace("\"","\"\"")+"\""
    fun rows(visits:List<Visit>,now:Instant)=visits.filter{it.operational in listOf("COMPLETO","IMPEDITO")&&!isCancelled(it)&&it.sync!="RESET_OBSOLETE"&&inspectionInstant(it)?.let{at->quarter(at)==quarter(now)&&!at.isAfter(now)}==true}.sortedByDescending{inspectionInstant(it)}
    fun export(visits:List<Visit>,points:List<JSONObject>,collectors:List<JSONObject>,photoIds:Set<String>,now:Instant):String {
        return exportPeriod(visits,points,collectors,photoIds,ExportPeriod(Instant.EPOCH,null,"Compatibilità","compat"),false,rows(visits,now))
    }
    fun exportPeriod(visits:List<Visit>,points:List<JSONObject>,collectors:List<JSONObject>,photoIds:Set<String>,period:ExportPeriod,localOnly:Boolean=false,selected:List<Visit> = exportRows(visits,period)):String {
        return java.io.StringWriter().also{writePeriod(it,visits,points,collectors,photoIds,period,localOnly,selected)}.toString()
    }
    fun writePeriod(writer:java.io.Writer,visits:List<Visit>,points:List<JSONObject>,collectors:List<JSONObject>,photoIds:Set<String>,period:ExportPeriod,localOnly:Boolean=false,selected:List<Visit> = exportRows(visits,period)) {
        val ps=points.associateBy{it.getString("id")};val cs=collectors.associateBy{it.getString("id")}
        val controls=Repository.observationKeys+externalKeys+listOf("accessible","opened","cleaning","unsafe","raise_needed","road_repair_needed","impediment_reason","exception_reason")
        val headers=listOf("ID ispezione","Data","Ora","Collettore","Tipologia collettore","Pozzetto","ID pozzetto","Operatore creatore","Ultimo modificatore","Operatore invio","Esito")+controls.map{fieldLabels[it]?:it}+listOf("Anomalie","Note","Latitudine","Longitudine","Accuratezza GPS (m)","Distanza dal pozzetto (m)","Limite accuratezza (m)","Limite distanza (m)","Qualità GPS","Motivazione non rilievo GPS","Foto presenti","Creata il","Inviata il","Stato sincronizzazione","Revisione server","Modifica locale","Dati sintetici","Ambito esportazione")
        fun writeRow(row:List<Any?>){writer.write(row.joinToString(";"){cell(it)}+"\r\n")}
        writer.write("\uFEFF");writeRow(headers)
        selected.forEach{v->val b=JSONObject(v.body);val p=ps[v.manholeId];val c=p?.memberships()?.mapNotNull{cs[it]}.orEmpty();val s=b.getJSONObject("sheet");val e=lastEvidence(b);val at=inspectionInstant(v)!!.atZone(ZoneId.of("Europe/Rome"));val limits=e?.optJSONObject("applied_limits")
            writeRow((listOf(v.id,at.toLocalDate().toString(),at.format(DateTimeFormatter.ofPattern("HH:mm:ss")),c.joinToString(" | "){it.optString("description")},c.joinToString(" | "){it.optString("type")},p?.optString("code")?:"",v.manholeId,b.optString("created_by",b.optString("user_id")),b.optString("updated_by"),b.optString("submitted_by",b.optString("user_id")),operational(v.operational))+controls.map{s.opt(it)?.takeUnless{it==JSONObject.NULL}?.toString()?:""}+listOf(s.optString("anomaly_note"),s.optString("notes"),e?.numberOrNull("latitude"),e?.numberOrNull("longitude"),e?.numberOrNull("accuracy_m"),e?.optJSONObject("local_evaluation")?.numberOrNull("distance_m"),limits?.numberOrNull("max_accuracy_m"),limits?.numberOrNull("radius_m"),if(noGpsConfirmed(b))"GPS non rilevato" else when(gpsQuality(e)){"RELIABLE"->"Affidabile";"IMPRECISE"->"GPS impreciso";else->"GPS non affidabile"},b.optJSONObject("gps_state")?.optString("reason").orEmpty(),if(v.id in photoIds||(b.optJSONArray("photos")?.length()?:0)>0)"SI" else "NO",b.optString("created_at",b.optString("started_at")),b.optString("submitted_at",b.optString("completed_at")),syncLabel(v.sync),b.optLong("server_revision"),b.optLong("local_edit"),if(p?.optBoolean("synthetic")==true||c.any{it.optBoolean("synthetic")})"SI" else "NO",(if(localOnly)"SOLO DATI LOCALI · " else "STORICO SERVER E DEFINITIVE LOCALI · ")+period.label)).map{it?.toString()?:""})
        }
        writer.flush()
    }
}
