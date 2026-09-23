package it.pat.collettori

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

const val REGISTERED_IDENTITY="@registered"
fun featureFingerprint(feature:ShapeFeature)=java.security.MessageDigest.getInstance("SHA-256").digest(canonicalJson(JSONObject().put("attributes",JSONObject(feature.fields)).put("geometry",feature.geometry)).toByteArray()).joinToString(""){"%02x".format(it)}
fun LayerMapping.recordKey(feature:ShapeFeature):String {
    val columns=listOf("scope","key","discriminator").mapNotNull{fields[it]?.takeIf{f->f.isNotBlank()}}.distinct()
    if(fields["key"]==REGISTERED_IDENTITY)return featureFingerprint(feature)
    val values=columns.map{feature.fields[it].orEmpty()}
    if(values.any{it.isBlank()}||values.isEmpty())return ""
    return if(values.size==1)values.single() else JSONArray(columns.zip(values).map{listOf(it.first,it.second)}).toString()
}
fun keyReport(layer:ShapeLayer,mapping:LayerMapping):KeyReport {
    val values=layer.features.map{mapping.recordKey(it)}
    val repeated=values.filter{it.isNotBlank()}.groupingBy{it}.eachCount().filterValues{it>1}
    return KeyReport(listOf("scope","key","discriminator").mapNotNull{mapping.fields[it]?.takeIf{f->f.isNotBlank()}}.joinToString(" + "),values.count{it.isBlank()},repeated.values.sumOf{it-1},values.mapIndexedNotNull{i,v->if(v.isBlank()||v in repeated)"Record ${i+1}: "+layer.features[i].fields.entries.take(4).joinToString{"${it.key}=${it.value}"}else null}.take(6))
}
fun sourceRecords(existing:List<CatalogItem>,source:String,layer:ShapeLayer)=existing.filter{it.kind==layer.kind&&JSONObject(it.body).let{b->b.optString("source")==source&&b.optString("source_layer")==layer.name}}
fun unresolvedFeatures(layer:ShapeLayer,mapping:LayerMapping,source:String,existing:List<CatalogItem>):List<ShapeFeature>{
    if(mapping.fields["key"]!=REGISTERED_IDENTITY)return emptyList()
    val prior=sourceRecords(existing,source,layer)
    if(prior.isEmpty())return emptyList()
    return layer.features.filter{f->val fingerprint=featureFingerprint(f);prior.none{JSONObject(it.body).optString("source_fingerprint")==fingerprint}&&mapping.fields["match:$fingerprint"].isNullOrBlank()}
}
fun registeredKey(feature:ShapeFeature,layer:ShapeLayer,mapping:LayerMapping,source:String,existing:List<CatalogItem>):String {
    if(mapping.fields["key"]!=REGISTERED_IDENTITY)return mapping.recordKey(feature)
    val fingerprint=featureFingerprint(feature);val prior=sourceRecords(existing,source,layer)
    val matched=prior.filter{JSONObject(it.body).optString("source_fingerprint")==fingerprint}
    require(matched.size<=1){"Corrispondenza registrata ambigua: verificare la sorgente"}
    matched.singleOrNull()?.let{return JSONObject(it.body).getString("source_key")}
    val choice=mapping.fields["match:$fingerprint"]
    require(prior.isEmpty()||choice!=null){"Record modificato o nuovo: scegli la corrispondenza nella riconciliazione guidata"}
    if(choice!=null&&choice!="@NEW"){
        val previous=prior.singleOrNull{it.id==choice}?:error("Corrispondenza scelta non disponibile")
        return JSONObject(previous.body).getString("source_key")
    }
    // Created once on explicit guided assignment, then persisted in source_identity on the server.
    return "assigned:"+UUID.nameUUIDFromBytes((source+"|"+layer.name+"|"+fingerprint).toByteArray())
}
fun fieldExample(layer:ShapeLayer,name:String):String = name+" · "+layer.features.map{it.fields[name].orEmpty().ifBlank{"(vuoto)"}}.distinct().take(3).joinToString(" / ").take(90)
