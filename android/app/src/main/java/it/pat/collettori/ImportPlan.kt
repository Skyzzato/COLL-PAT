package it.pat.collettori

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LayerMapping(val layer:String,val fields:Map<String,String>) {
    fun value(f:ShapeFeature,key:String)=fields[key]?.takeIf{it.isNotBlank()}?.let{f.fields[it]}?:""
    fun json()=JSONObject(fields).put("layer",layer)
}
data class ImportCounts(val inserted:Int,val updated:Int,val unchanged:Int,val missing:Int){
    fun json()=JSONObject().put("inserted",inserted).put("updated",updated).put("unchanged",unchanged).put("missing",missing)
}
data class KeyReport(val field:String,val empty:Int,val duplicates:Int,val examples:List<String>){val valid get()=field.isNotBlank()&&empty==0&&duplicates==0}
fun keyReport(layer:ShapeLayer,field:String):KeyReport{
    val values=layer.features.map{it.fields[field].orEmpty()}
    val repeated=values.filter{it.isNotBlank()}.groupingBy{it}.eachCount().filterValues{it>1}
    return KeyReport(field,values.count{it.isBlank()},repeated.values.sumOf{it-1},values.mapIndexedNotNull{i,v->
        if(v.isBlank()||v in repeated)"Record ${i+1}: ${if(v.isBlank())"vuoto" else v} · "+layer.features[i].fields.entries.take(4).joinToString{"${it.key}=${it.value}"} else null
    }.take(5))
}
data class ImportPlan(val items:List<CatalogItem>,val preview:JSONObject,val provenance:JSONObject,val warnings:List<String>,val inserted:Int,val updated:Int,val unchanged:Int,val missing:List<CatalogItem>,val counts:Map<String,ImportCounts>)

object ImportPlanner {
    fun propose(layer:ShapeLayer):LayerMapping {
        val aliases=mapOf("key" to listOf("source_id","id","fid","uuid"),"code" to listOf("code","codice","cod","name"),"description" to listOf("description","descrizion","descr","nome"),"collector" to listOf("collector","collettore","coll_cod"),"asset_type" to listOf("tipo","type","asset_type"),"under_asphalt" to listOf("under_asph","asfalto","sotto_asf"),"sequence" to listOf("sequence","sequenza","ordine"),"chainage" to listOf("progressiv","chainage","prog_m"),"branch" to listOf("ramo","branch"),"from" to listOf("from_id","da","inizio"),"to" to listOf("to_id","a","fine"),"previous" to listOf("prev_id","precedente"),"next" to listOf("next_id","successivo"))
        val fields=aliases.mapValues{(_,options)->layer.fields.firstOrNull{it.lowercase() in options}?:""}.toMutableMap()
        fields["key"]=listOf("source_id","uuid","id","code","codice").firstNotNullOfOrNull{name->layer.fields.firstOrNull{it.equals(name,true)&&keyReport(layer,it).valid}}?:""
        return LayerMapping(layer.name,fields)
    }
    fun plan(archive:ShapeArchive,mappings:List<LayerMapping>,source:String,fallbackCollector:String,mode:String,orderConfirmed:Boolean,allowNewKeys:Boolean,tolerance:Double,existing:List<CatalogItem>,owner:String):ImportPlan {
        require(source.trim().length>=3){"Indicare un nome sorgente stabile (almeno 3 caratteri)"};require(tolerance.isFinite()&&tolerance in 0.0..20.0){"Tolleranza estremi: 0–20 metri"}
        require(mode in listOf("LINES","ORDERED","ISOLATED"));if(mode=="ORDERED")require(orderConfirmed){"Confermare l'ordine e l'origine dei rami nell'anteprima"}
        require(!allowNewKeys){"Ogni layer richiede un identificativo stabile: non vengono generati identificativi sostitutivi per chiavi mancanti."}
        archive.layers.forEach{layer->val report=keyReport(layer,mappings.first{it.layer==layer.name}.fields["key"].orEmpty())
            require(report.valid){"${layer.name}: campo identificativo '${report.field}', ${report.empty} vuoti, ${report.duplicates} duplicati. Scegliere una chiave stabile univoca o correggere il file sorgente. "+report.examples.joinToString("; ")}
        }
        val warnings=mutableListOf<String>();val staged=linkedMapOf<String,CatalogItem>();val all=existing.associateBy{it.id}.toMutableMap();val sourceKeys=mutableSetOf<String>()
        fun identity(kind:String,layer:String,key:String):String {
            require(key.isNotBlank()){"$layer: chiave sorgente assente"}
            val src="$source|$layer|$kind|$key";require(sourceKeys.add(src)){"Chiave sorgente duplicata in $layer: $key"}
            return existing.firstOrNull{it.kind==kind&&JSONObject(it.body).optString("source_identity")==src}?.id?:UUID.randomUUID().toString()
        }
        fun put(kind:String,data:JSONObject){val id=data.getString("id");val item=CatalogItem(owner,id,kind,data.toString());staged[id]=item;all[id]=item}
        fun collectors(f:ShapeFeature,m:LayerMapping):List<String>{
            val code=m.value(f,"collector")
            if(code.isBlank()){val selected=all[fallbackCollector];require(selected?.kind=="collector"){"Scegliere il collettore per i record senza campo collettore"};require(!JSONObject(selected.body).optBoolean("archived")){"Il collettore scelto è archiviato: ripristinarlo prima dell'importazione"};return listOf(fallbackCollector)}
            // The administrator explicitly maps this field to catalogue codes; no asset matching by code.
            val found=all.values.firstOrNull{it.kind=="collector"&&JSONObject(it.body).getString("code").equals(code,ignoreCase=true)}
            if(found!=null){require(!JSONObject(found.body).optBoolean("archived")){"Il collettore $code è archiviato: ripristinarlo prima dell'importazione"};return listOf(found.id)}
            val id=UUID.randomUUID().toString();put("collector",collectorDefaults(id,code,code+" — importato").put("synthetic",false));return listOf(id)
        }
        val keyToPoint=mutableMapOf<String,MutableList<String>>();val rawPoints=mutableMapOf<String,Pair<ShapeFeature,LayerMapping>>()
        val run=UUID.randomUUID().toString()
        for(layer in archive.layers.filter{it.kind=="point"}){
            val map=mappings.first{it.layer==layer.name}
            for(feature in layer.features){
                val key=map.value(feature,"key");val id=identity("point",layer.name,key);val old=existing.find{it.id==id}?.let{JSONObject(it.body)}
                val code=map.value(feature,"code");require(code.isNotBlank()){"${layer.name}: codice obbligatorio; gli zeri iniziali sono conservati"}
                val coord=feature.geometry.getJSONArray("coordinates");val members=collectors(feature,map)
                val p=JSONObject().put("id",id).put("code",code).put("description",map.value(feature,"description")).put("latitude",coord.getDouble(1)).put("longitude",coord.getDouble(0))
                    .put("collectors",JSONArray((old?.memberships().orEmpty()+members).distinct())).put("asset_type",map.value(feature,"asset_type").ifBlank{"UNKNOWN"}).put("synthetic",false).put("uncertainty_m",JSONObject.NULL)
                    .put("source_identity",if(key.isBlank())"$source|${layer.name}|point|new:$run:$id" else "$source|${layer.name}|point|$key").put("source_key",key).put("source",source).put("source_layer",layer.name)
                val asphalt=map.value(feature,"under_asphalt").trim().lowercase()
                p.put("under_asphalt",if(asphalt.isBlank())old?.optBoolean("under_asphalt")?:false else when(asphalt){"1","true","si","sì","yes"->true;"0","false","no"->false;else->error("Campo sotto asfalto non valido: usare sì/no o 1/0")})
                // Preserve established topology when a partial file supplies no replacements.
                listOf("previous_id","previous_distance_m","chainage_m","chainage_source","origin_id","branch","gis_chainage_m","sequence").forEach{k->old?.opt(k)?.let{p.put(k,it)}}
                map.value(feature,"chainage").takeIf{it.isNotBlank()}?.let{p.put("gis_chainage_m",decimalItalian(it)?:error("Progressiva GIS non valida"))}
                put("point",p);rawPoints[id]=feature to map
                if(key.isNotBlank())keyToPoint.getOrPut(key){mutableListOf()}.add(id)
            }
        }
        require(rawPoints.isNotEmpty()||mode=="LINES"){"Nessun layer di punti"}
        fun endpoint(key:String,coordinate:JSONArray):String {
            if(key.isNotBlank())return (keyToPoint[key].orEmpty()+existing.filter{it.kind=="point"&&JSONObject(it.body).optString("source")==source&&JSONObject(it.body).optString("source_key")==key}.map{it.id}).distinct().singleOrNull()?:error("Estremo sconosciuto o ambiguo: $key")
            require(tolerance>0){"Estremi mancanti: mappare from/to o scegliere tolleranza esplicita"}
            val matches=all.values.filter{it.kind=="point"}.map{JSONObject(it.body)}.filter{GpsRule.distance(coordinate.getDouble(1),coordinate.getDouble(0),it.getDouble("latitude"),it.getDouble("longitude"))<=tolerance}
            return matches.singleOrNull()?.getString("id")?:error("Estremo senza candidato univoco entro $tolerance m")
        }
        if(mode=="LINES"){
            require(archive.layers.any{it.kind=="segment"}){"Modalità punti e linee: manca un layer lineare"}
            for(layer in archive.layers.filter{it.kind=="segment"}){val map=mappings.first{it.layer==layer.name}
                for(feature in layer.features){
                    val key=map.value(feature,"key");val id=identity("segment",layer.name,key);val geom=feature.geometry
                    require(geom.getString("type")=="LineString"){"Polilinea multipart: separare i rami in oggetti con chiavi distinte"}
                    val coords=geom.getJSONArray("coordinates");val from=endpoint(map.value(feature,"from"),coords.getJSONArray(0));val to=endpoint(map.value(feature,"to"),coords.getJSONArray(coords.length()-1));require(from!=to){"Tratto con estremi identici"}
                    val members=collectors(feature,map)
                    for((pointId,coordinate) in listOf(from to coords.getJSONArray(0),to to coords.getJSONArray(coords.length()-1))){val ref=JSONObject(all[pointId]!!.body);val gap=GpsRule.distance(coordinate.getDouble(1),coordinate.getDouble(0),ref.getDouble("latitude"),ref.getDouble("longitude"));if(gap>maxOf(.5,tolerance))warnings.add("Discontinuità estremo ${ref.getString("code")}: %.1f m; associazione per chiave esplicita".format(gap))}
                    for(pointId in listOf(from,to)){val p=JSONObject(all[pointId]!!.body);p.put("collectors",JSONArray((p.memberships()+members).distinct()));put("point",p)}
                    put("segment",JSONObject().put("id",id).put("code",map.value(feature,"code")).put("collectors",JSONArray(members)).put("from_id",from).put("to_id",to).put("geometry",geom).put("length_m",geometryLength(geom)).put("schematic",false)
                        .put("source_identity",if(key.isBlank())"$source|${layer.name}|segment|new:$run:$id" else "$source|${layer.name}|segment|$key").put("source",source))
                }
            }
            if(tolerance>0)warnings.add("Associazione degli estremi entro $tolerance m: verificare i collegamenti nell'anteprima")
            val incoming=all.values.filter{it.kind=="segment"}.map{JSONObject(it.body)}.groupBy{it.optString("to_id")}
            val outgoing=all.values.filter{it.kind=="segment"}.map{JSONObject(it.body)}.groupBy{it.optString("from_id")}
            for(id in rawPoints.keys){
                val p=JSONObject(all[id]!!.body);val inc=incoming[id].orEmpty();val out=outgoing[id].orEmpty()
                if(inc.size>1||out.size>1){warnings.add("Biforcazione ${p.getString("code")}: nessun precedente/progressiva univoci");listOf("previous_id","previous_distance_m","chainage_m","origin_id").forEach{p.remove(it)}}
                else if(inc.size==1){p.put("previous_id",inc.single().getString("from_id")).put("previous_distance_m",geometryLength(inc.single().getJSONObject("geometry")))}
                // A documented directed origin exists only along a continuous, non-branching path.
                var origin=id;var distance=0.0;val walked=mutableSetOf<String>();var reliable=true
                while(true){
                    if(!walked.add(origin)){reliable=false;break}
                    val before=incoming[origin].orEmpty();if(before.size>1||outgoing[origin].orEmpty().size>1){reliable=false;break}
                    if(before.isEmpty())break
                    distance+=geometryLength(before.single().getJSONObject("geometry"));origin=before.single().getString("from_id")
                }
                if(reliable&&(inc.isNotEmpty()||out.isNotEmpty()))p.put("origin_id",origin).put("chainage_m",distance).put("chainage_source","CALCULATED_LINE_DIRECTION")
                else{p.remove("origin_id");p.remove("chainage_m")}
                if(inc.isEmpty()&&out.isEmpty())warnings.add("Punto isolato ${p.getString("code")}")
                put("point",p)
            }
        }else if(mode=="ORDERED"){
            require(archive.layers.none{it.kind=="segment"}){"Selezionare punti e linee per importare i layer lineari"}
            val groups=rawPoints.keys.groupBy{id->val(f,m)=rawPoints[id]!!;JSONObject(all[id]!!.body).memberships().sorted().joinToString()+"|"+m.value(f,"branch")+"|"+m.layer}
            for((_,ids) in groups){
                val sample=rawPoints[ids.first()]!!;val mapping=sample.second
                val ordered=if(!mapping.fields["previous"].isNullOrBlank()||!mapping.fields["next"].isNullOrBlank()){
                    val links=mutableMapOf<String,String>();val incoming=mutableMapOf<String,String>()
                    fun link(a:String,b:String){require(a in ids&&b in ids&&a!=b){"Collegamento tra rami o estremo sconosciuto"};require(links[a]==null||links[a]==b);require(incoming[b]==null||incoming[b]==a){"Precedenti ambigui"};links[a]=b;incoming[b]=a}
                    for(id in ids){val(f,m)=rawPoints[id]!!;val prev=m.value(f,"previous");val next=m.value(f,"next");if(prev.isNotBlank())link(keyToPoint[prev]?.singleOrNull()?:error("Precedente ambiguo: $prev"),id);if(next.isNotBlank())link(id,keyToPoint[next]?.singleOrNull()?:error("Successivo ambiguo: $next"))}
                    val root=ids.filter{it !in incoming}.singleOrNull()?:error("Ordine ciclico o più origini: separare i rami")
                    val path=mutableListOf<String>();var at:String?=root;while(at!=null){require(at !in path){"Ciclo nell'ordine"};path.add(at);at=links[at]};require(path.size==ids.size){"Ordine discontinuo: separare i rami"};path
                }else{
                    val useSequence=!mapping.fields["sequence"].isNullOrBlank();require(useSequence||!mapping.fields["chainage"].isNullOrBlank()){"Ordine affidabile assente: mappare sequenza, progressiva oppure precedente/successivo"}
                    val values=ids.associateWith{id->val(f,m)=rawPoints[id]!!;decimalItalian(m.value(f,if(useSequence)"sequence" else "chainage"))?:error("Ordine mancante/non numerico")}
                    require(values.values.distinct().size==values.size){"Ordine duplicato: specificare un campo ramo"};ids.sortedBy{values[it]}
                }
                var chain=0.0
                for((i,id) in ordered.withIndex()){
                    val p=JSONObject(all[id]!!.body);val(f,m)=rawPoints[id]!!;p.put("branch",m.value(f,"branch")).put("sequence",i).put("origin_id",ordered.first()).put("chainage_source","CALCULATED_SCHEMATIC")
                    if(i==0){p.remove("previous_id");p.remove("previous_distance_m")}else{
                        val prev=JSONObject(all[ordered[i-1]]!!.body);val geom=JSONObject().put("type","LineString").put("coordinates",JSONArray(listOf(listOf(prev.getDouble("longitude"),prev.getDouble("latitude")),listOf(p.getDouble("longitude"),p.getDouble("latitude")))))
                        val len=geometryLength(geom);chain+=len;p.put("previous_id",prev.getString("id")).put("previous_distance_m",len)
                        val key="${prev.getString("id")}>$id";val sid=identity("segment","schematic",key)
                        put("segment",JSONObject().put("id",sid).put("collectors",p.getJSONArray("collectors")).put("from_id",prev.getString("id")).put("to_id",id).put("geometry",geom).put("length_m",len).put("schematic",true).put("source_identity","$source|schematic|segment|$key").put("source",source))
                    };p.put("chainage_m",chain);put("point",p)
                }
            };warnings.add("Collegamenti schematici: lunghezze e progressive stimate; l'origine è il primo punto di ogni ramo confermato")
        }else{
            require(archive.layers.none{it.kind=="segment"}){"Per importare linee selezionare la modalità punti e linee"}
            warnings.add("Punti senza ordine: nessuna linea o progressiva calcolata; tipo manufatto conservato")
        }
        val touched=staged.values.filter{it.kind=="point"||it.kind=="segment"}.flatMap{JSONObject(it.body).memberships()}.toSet()
        for(cid in touched){val c=JSONObject(all[cid]?.body?:error("Collettore assente"));if(c.optString("length_source")=="DECLARED"){warnings.add("${c.getString("code")}: lunghezza dichiarata preservata");continue}
            val segments=all.values.filter{it.kind=="segment"&&cid in JSONObject(it.body).memberships()}.map{JSONObject(it.body)}.distinctBy{it.getString("id")}
            c.put("length_m",if(segments.isEmpty())JSONObject.NULL else segments.sumOf{geometryLength(it.getJSONObject("geometry"))}).put("length_source",if(segments.isEmpty())"UNAVAILABLE" else if(segments.any{it.optBoolean("schematic")})"ESTIMATED" else "MEASURED").put("length_complete",false);put("collector",c)
        }
        val before=existing.associateBy{it.id}
        val inserted=staged.keys.count{it !in before};val unchanged=staged.values.count{s->before[s.id]?.let{canonicalJson(JSONObject(it.body))==canonicalJson(JSONObject(s.body))}==true};val updated=staged.size-inserted-unchanged
        // Compare only layers supplied by this source. An omitted layer may be a partial update.
        val layerKinds=archive.layers.map{it.name to it.kind}.toMutableSet()
        if(mode=="ORDERED")layerKinds.add("schematic" to "segment")
        val pointLayers=archive.layers.filter{it.kind=="point"}.map{it.name}.toSet()
        val scopePoints=existing.filter{it.kind=="point"&&JSONObject(it.body).let{p->p.optString("source")==source&&p.optString("source_layer") in pointLayers}}.map{it.id}.toSet()+rawPoints.keys
        val missing=existing.filter{item->
            val data=JSONObject(item.body)
            item.id !in staged && layerKinds.any{(layer,kind)->item.kind==kind && data.optString("source_identity").startsWith("$source|$layer|$kind|") && (layer!="schematic"||data.optString("from_id") in scopePoints&&data.optString("to_id") in scopePoints)}
        }
        val counts=listOf("collector","point","segment").associateWith{kind->
            val items=staged.values.filter{it.kind==kind}
            val fresh=items.count{it.id !in before}
            val same=items.count{s->before[s.id]?.let{canonicalJson(JSONObject(it.body))==canonicalJson(JSONObject(s.body))}==true}
            ImportCounts(fresh,items.size-fresh-same,same,missing.count{it.kind==kind})
        }
        if(missing.isNotEmpty())warnings.add("${missing.size} elementi assenti dal nuovo file: conservati, senza archiviazione automatica")
        val report=JSONObject().put("inserted",inserted).put("updated",updated).put("unchanged",unchanged).put("missing",missing.size).put("by_kind",JSONObject().apply{counts.forEach{(kind,count)->this.put(kind,count.json())}}).put("warnings",JSONArray(warnings.distinct())).put("errors",0)
        val provenance=JSONObject().put("id",run).put("source",source).put("hash",archive.hash).put("mapping",JSONObject().put("layers",JSONArray(mappings.map{it.json()})).put("encodings",org.json.JSONObject().apply{archive.layers.forEach{this.put(it.name,org.json.JSONObject().put("choice",it.encodingChoice).put("applied",it.encoding).put("basis",it.encodingNote))}}).put("mode",mode).put("fallback_collector",fallbackCollector).put("tolerance_m",tolerance).put("order_confirmed",orderConfirmed)).put("report",report)
        val preview=JSONObject().put("osm",true).put("basemap",JSONObject.NULL).put("collectors",JSONArray(all.values.filter{it.kind=="collector"&&it.id in touched}.map{JSONObject(it.body)})).put("points",JSONArray(staged.values.filter{it.kind=="point"}.map{JSONObject(it.body)})).put("segments",JSONArray(staged.values.filter{it.kind=="segment"}.map{JSONObject(it.body)}))
        return ImportPlan(staged.values.toList(),preview,provenance,warnings.distinct(),inserted,updated,unchanged,missing,counts)
    }
}
