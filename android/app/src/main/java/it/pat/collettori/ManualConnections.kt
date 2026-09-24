package it.pat.collettori

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A manual connection is a schematic edge, never a guess at hydraulic flow. */
fun manualConnectionId(collector:String,a:String,b:String)=UUID.nameUUIDFromBytes(
    ("coll-pat:manual-edge:"+collector+":"+listOf(a,b).sorted().joinToString(":" )).toByteArray(Charsets.UTF_8)).toString()

fun schematicConnection(collector:String,a:JSONObject,b:JSONObject):JSONObject {
    val aid=a.getString("id");val bid=b.getString("id")
    require(aid!=bid){"Auto-collegamento non consentito"}
    require(a.available()&&b.available()&&collector in a.memberships()&&collector in b.memberships()){"Estremi non disponibili in questo collettore"}
    val geometry=JSONObject().put("type","LineString").put("coordinates",JSONArray(listOf(
        listOf(a.getDouble("longitude"),a.getDouble("latitude")),listOf(b.getDouble("longitude"),b.getDouble("latitude")))))
    val length=geometryLength(geometry);require(length>0.01){"Pozzetti coincidenti: verificare la posizione"}
    return JSONObject().put("id",manualConnectionId(collector,aid,bid)).put("collectors",JSONArray(listOf(collector)))
        .put("from_id",aid).put("to_id",bid).put("geometry",geometry).put("length_m",length)
        .put("schematic",true).put("length_source","ESTIMATED").put("manual_connection",true).put("flow_direction","UNSPECIFIED")
}

fun nearbyPoints(point:JSONObject,collector:String,points:List<JSONObject>,query:String=""):List<Pair<JSONObject,Double>> =
    points.filter{it.available()&&it.getString("id")!=point.getString("id")&&collector in it.memberships()&&it.optString("code").contains(query,true)}
        .map{it to GpsRule.distance(point.getDouble("latitude"),point.getDouble("longitude"),it.getDouble("latitude"),it.getDouble("longitude"))}
        .sortedWith(compareBy({it.second},{it.first.optString("code")}))

/** Called within the catalogue mutation lock. Entire graph and outbox are committed in one Room transaction. */
fun materializeManualConnections(input:List<CatalogItem>,existing:List<CatalogItem>):List<CatalogItem> {
    val all=(existing+input).associateBy{it.id}
    val segments=all.values.filter{it.kind=="segment"}.map{JSONObject(it.body)}.toMutableList()
    val added=mutableListOf<CatalogItem>()
    val clean=input.map{item->
        val p=JSONObject(item.body)
        if(item.kind=="point"&&p.has("manual_link_ids")){
            val cid=p.getString("manual_link_collector")
            require(all[cid]?.let{it.kind=="collector"&&JSONObject(it.body).available()}==true){"Collettore non disponibile"}
            p.getJSONArray("manual_link_ids").strings().distinct().forEach{target->
                val b=all[target]?.takeIf{it.kind=="point"}?.let{JSONObject(it.body)}?:error("Pozzetto collegato non disponibile")
                val edge=schematicConnection(cid,p,b)
                val pair=setOf(p.getString("id"),target)
                if(segments.none{it.available()&&cid in it.memberships()&&setOf(it.optString("from_id"),it.optString("to_id"))==pair}){
                    require(edge.getString("id") !in all){"Identità del collegamento già utilizzata"}
                    segments.add(edge);added.add(CatalogItem(item.owner,edge.getString("id"),"segment",edge.toString()))
                }
            }
            p.remove("manual_link_ids");p.remove("manual_link_collector")
        }
        item.copy(body=p.toString())
    }
    return updateSchematicSegments(clean+added,existing)
}

fun positioningData(collector:JSONObject,points:List<JSONObject>,segments:List<JSONObject>,proposed:JSONObject,links:List<String>):JSONObject {
    val cid=collector.getString("id");val visible=points.filter{it.available()&&cid in it.memberships()&&it.getString("id")!=proposed.getString("id")}
    val lines=segments.filter{it.available()&&cid in it.memberships()}.toMutableList()
    for(id in links.distinct())visible.find{it.getString("id")==id}?.let{b->
        if(lines.none{setOf(it.optString("from_id"),it.optString("to_id"))==setOf(proposed.getString("id"),id)})
            runCatching{schematicConnection(cid,proposed,b)}.getOrNull()?.let{lines.add(it)}
    }
    val moved=updateSchematicSegments(listOf(CatalogItem("",proposed.getString("id"),"point",proposed.toString())),
        visible.map{CatalogItem("",it.getString("id"),"point",it.toString())}+lines.map{CatalogItem("",it.getString("id"),"segment",it.toString())})
    val adjusted=moved.filter{it.kind=="segment"}.associateBy{it.id}
    return JSONObject().put("osm",true).put("basemap",JSONObject.NULL).put("context_collector",cid)
        .put("collectors",JSONArray(listOf(collector))).put("points",JSONArray(visible))
        .put("segments",JSONArray(lines.map{adjusted[it.getString("id")]?.let{r->JSONObject(r.body)}?:it})).put("proposed_point",proposed)
}
