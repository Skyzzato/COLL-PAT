package it.pat.collettori

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

data class ExportPeriod(val start:Instant?,val end:Instant?,val label:String,val filenamePart:String) {
    fun contains(at:Instant)=(start==null||!at.isBefore(start))&&(end==null||at.isBefore(end))
    companion object {
        val rome:ZoneId=ZoneId.of("Europe/Rome")
        private fun dates(start:LocalDate,end:LocalDate)=ExportPeriod(start.atStartOfDay(rome).toInstant(),end.atStartOfDay(rome).toInstant(),
            "$start – ${end.minusDays(1)} (Europe/Rome)","${start}_${end.minusDays(1)}")
        fun lastCompletedQuarter(now:Instant):ExportPeriod {
            val today=now.atZone(rome).toLocalDate();val end=LocalDate.of(today.year,(today.monthValue-1)/3*3+1,1)
            return dates(end.minusMonths(3),end)
        }
        fun year(year:Int):ExportPeriod {require(year in 1900..9998);return dates(LocalDate.of(year,1,1),LocalDate.of(year+1,1,1))}
        fun all()=ExportPeriod(null,null,"Tutto lo storico disponibile nel progetto","tutto_storico")
    }
}

fun exportRows(visits:List<Visit>,period:ExportPeriod):List<Visit> = visits.groupBy{it.id}.values.map{versions->
    versions.maxWith(compareBy<Visit>{JSONObject(it.body).optLong("server_revision")}.thenBy{JSONObject(it.body).optLong("local_edit")})
}.filter{it.operational in setOf("COMPLETO","IMPEDITO")&&!isCancelled(it)&&it.sync!="RESET_OBSOLETE"&&inspectionInstant(it)?.let(period::contains)==true}
    .sortedWith(compareBy<Visit>{inspectionInstant(it)}.thenBy{it.id})

/** Export downloads full rows independently of the UI and draft editor caches. */
suspend fun Repository.exportHistory(period:ExportPeriod):List<Visit> {
    val account=owner();reconcile();val rows=mutableListOf<Visit>();var after:String?=null;var snapshot:String?=null
    do{
        val page=api.rpc("coll_pat_export_history",JSONObject().put("p_project",project()).put("p_after",after?:JSONObject.NULL)
            .put("p_snapshot",snapshot?:JSONObject.NULL).put("p_from",period.start?.toString()?:JSONObject.NULL).put("p_until",period.end?.toString()?:JSONObject.NULL),account)
        if(snapshot==null)snapshot=page.getString("snapshot")
        for(row in page.getJSONArray("items").objects()){
            val body=JSONObject(row.getJSONObject("original").toString()).put("server_revision",row.getInt("revision")).put("local_edit",0)
            for(k in listOf("created_by","created_at","updated_by","updated_at","submitted_by","submitted_at"))if(!row.isNull(k))body.put(k,row.get(k))
            if(row.optBoolean("cancelled"))body.put("cancelled",JSONObject())
            row.optJSONArray("corrections")?.objects().orEmpty().forEach{correction->if(correction.isNull("event_id"))body.put("cancelled",correction)else body.getJSONArray("events").objects().find{it.optString("id")==correction.optString("event_id")}?.put("cancelled",correction)}
            rows.add(Visit(row.getString("id"),account,row.getString("manhole_id"),body.getString("dataset_id"),body.toString(),row.getString("status"),"RICEVUTO_SERVER"))
        }
        val next=page.optString("next").takeUnless{it.isBlank()||it=="null"};check(next==null||next!=after){"Cursore dello storico non valido"};after=next
    }while(after!=null)
    val local=dao.visitsNow(account).filter{it.sync!="RICEVUTO_SERVER"&&it.sync!="RESET_OBSOLETE"}
    return exportRows(rows+local,period)
}
