package it.pat.collettori

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun JSONObject.deleted() = optBoolean("deleted") || !optString("deleted_at").let { it.isBlank() || it == "null" }
fun JSONObject.available() = !deleted() && !optBoolean("archived")

/** Signed WGS84 coordinates; decimal comma is accepted, axes are never exchanged. */
fun coordinateInput(text:String,latitude:Boolean):Double {
    val value=text.trim().replace(',','.').toDoubleOrNull()
    require(value!=null&&value.isFinite()) { if(latitude)"Inserisci una latitudine valida" else "Inserisci una longitudine valida" }
    require(value in if(latitude)-90.0..90.0 else -180.0..180.0) { if(latitude)"Latitudine ammessa: da −90 a 90" else "Longitudine ammessa: da −180 a 180" }
    return value
}
fun manualPoint(initial:JSONObject,id:String,collectorId:String,code:String,lat:String,lon:String,sequence:String,branch:String,nextIds:List<String>,end:Boolean):JSONObject {
    require(code.trim().isNotBlank()) { "Inserisci il codice/numero del pozzetto" }
    require(id !in nextIds) { "Il pozzetto non può essere successivo a sé stesso" }
    require(!end||nextIds.isEmpty()) { "Un fine ramo non può avere collegamenti in uscita" }
    val result=JSONObject(initial.toString()).put("id",id).put("code",code.trim()).put("latitude",coordinateInput(lat,true)).put("longitude",coordinateInput(lon,false))
        .put("collectors",JSONArray((initial.memberships()+collectorId).distinct())).put("asset_type",initial.optString("asset_type","MANHOLE"))
        .put("under_asphalt",initial.optBoolean("under_asphalt")).put("uncertainty_m",initial.opt("uncertainty_m")?:JSONObject.NULL)
        .put("next_ids",JSONArray(nextIds)).put("topology_end",end).put("branch",branch.trim())
    if(sequence.isBlank())result.remove("sequence") else result.put("sequence",decimalItalian(sequence)?:error("Ordine: numero non negativo"))
    return result
}

fun frequencyLabel(c:JSONObject,now:Instant=Instant.now()):String {
    val field=if(now.atZone(ZoneId.of("Europe/Rome")).monthValue<=6)"visits_h1" else "visits_h2"
    val n=c.numberOrNull(field)?.takeIf{it>0}?:return "Frequenza ispezione non configurata"
    return "Frequenza ispezione: ogni ${kotlin.math.round(365.2425/2/n).toInt()} giorni (intervallo nominale)"
}
fun roleLabel(role:String?)=when(role){"admin"->"Amministratore";"inspector"->"Ispettore";else->"Non disponibile"}
fun lastRefreshLabel(value:String?):String = value?.let { runCatching { "(ultimo aggiornamento in data "+DateTimeFormatter.ofPattern("dd/MM/yyyy 'alle' HH:mm",Locale.ITALY).withZone(ZoneId.systemDefault()).format(Instant.parse(it))+")" }.getOrNull() }?:"(nessun aggiornamento completato)"
fun uploadCountLabel(count:Int)="$count ${if(count==1)"elemento" else "elementi"} in coda di caricamento"

/** Catalogue rows, inspection UUIDs, correction UUIDs and attachment UUIDs are logical units. */
fun queuedLogicalIds(queue:List<Pending>,largePayloads:Map<String,String> = emptyMap()):Set<String> = buildSet {
    queue.forEach { op ->
        val p=largePayloads[op.operationId]?.let(::JSONObject)?:JSONObject(op.body).optJSONObject("payload")?:JSONObject()
        when(op.kind){
            "catalog","catalog_chunk"->p.optJSONArray("items")?.objects().orEmpty().forEach { add("catalog:"+it.getJSONObject("data").getString("id")) }
            "catalog_delete"->add("catalog:"+p.getString("id"))
            "inspection","shared_inspection"->add("inspection:"+op.visitId)
            "cancel"->add("correction:"+p.optString("id",op.operationId))
            else->add("operation:"+op.operationId)
        }
    }
}

fun nextPointLabel(p:JSONObject,points:List<JSONObject>,segments:List<JSONObject>):String {
    val id=p.getString("id");val byId=points.filter{it.available()}.associateBy{it.getString("id")}
    val outgoing=segments.filter{it.available()&&it.optString("from_id")==id&&it.memberships().any{c->c in p.memberships()}}
    val explicit=p.optJSONArray("next_ids")?.strings().orEmpty()
    val previousLinks=points.filter{it.available()&&it.optString("previous_id")==id&&it.memberships().any{c->c in p.memberships()}}.map{it.getString("id")}
    val next=(outgoing.map{it.optString("to_id")}+explicit+previousLinks).filter{it.isNotBlank()}.distinct().toMutableList()
    if(next.isEmpty()&&!p.optBoolean("topology_end")&&p.has("sequence")){
        val peers=points.filter{it.available()&&it.memberships().intersect(p.memberships().toSet()).isNotEmpty()&&it.optString("branch")==p.optString("branch")&&it.numberOrNull("sequence")!=null}
        val candidates=peers.filter{it.getDouble("sequence")>p.getDouble("sequence")}
        val n=candidates.minOfOrNull{it.getDouble("sequence")}
        val nearest=candidates.filter{it.getDouble("sequence")==n}
        if(nearest.size==1&&peers.count{it.getDouble("sequence")==p.getDouble("sequence")}==1)next.add(nearest.single().getString("id"))
    }
    if(next.isEmpty())return if(p.optBoolean("topology_end")||p.optString("chainage_source").startsWith("CALCULATED_")&&segments.any{it.optString("to_id")==id})"Fine collettore / ramo" else "Pozzetto successivo non definito"
    return next.joinToString("\n") { nid ->
        val target=byId[nid];val code=target?.optString("code")?:nid
        val lines=outgoing.filter{it.optString("to_id")==nid}
        if(lines.size>1)"Al successivo #$code: più tronchi in uscita · "+lines.joinToString(" / "){s->s.numberOrNull("length_m")?.let{"%.1f m".format(Locale.ITALY,it)}?:"distanza non disponibile"}
        else {
            val segment=lines.singleOrNull();val schematic=segment?.optBoolean("schematic")==true
            val distance=segment?.let{s->s.optJSONObject("geometry")?.let{runCatching{geometryLength(it)}.getOrNull()}?:s.numberOrNull("length_m")}
            val estimate=if(segment==null&&target!=null&&validCoordinates(p.numberOrNull("latitude"),p.numberOrNull("longitude"))&&validCoordinates(target.numberOrNull("latitude"),target.numberOrNull("longitude")))GpsRule.distance(p.getDouble("latitude"),p.getDouble("longitude"),target.getDouble("latitude"),target.getDouble("longitude")) else null
            "Al successivo #$code: "+((distance?:estimate)?.let{"%.1f m".format(Locale.ITALY,it)+(if(estimate!=null||schematic)" (stima in linea d’aria)" else "")}?:"distanza non disponibile")
        }
    }
}

data class CollectorDeletion(val id:String,val removed:Set<String>,val shared:Set<String>,val points:Int,val segments:Int,val inspections:Int,val photos:Int)
fun deletionImpact(id:String,items:List<CatalogItem>,visits:List<Visit>,photoCount:Int=0):CollectorDeletion {
    val children=items.filter{it.kind!="collector"&&id in JSONObject(it.body).memberships()}
    val exclusive=children.filter{JSONObject(it.body).memberships().all{c->c==id}}.map{it.id}.toSet()
    return CollectorDeletion(id,exclusive+id,children.map{it.id}.toSet()-exclusive,children.count{it.kind=="point"},children.count{it.kind=="segment"},visits.count{it.manholeId in children.map{c->c.id}},photoCount)
}
