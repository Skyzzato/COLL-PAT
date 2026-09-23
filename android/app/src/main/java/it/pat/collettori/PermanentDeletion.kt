package it.pat.collettori

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

suspend fun Repository.localDeletionScope(kind:String,id:String):JSONObject{
    val items=dao.catalogNow(owner());val visits=dao.visitsNow(owner())
    val removed=items.filter{c->val b=JSONObject(c.body);c.id==id&&c.kind==kind||kind=="collector"&&c.kind!="collector"&&b.memberships()==listOf(id)||kind=="point"&&c.kind=="segment"&&(b.optString("from_id")==id||b.optString("to_id")==id)}.map{it.id}.toSet()
    val shared=items.filter{it.kind!="collector"&&kind=="collector"&&id in JSONObject(it.body).memberships()&&it.id !in removed}.map{it.id}.toSet()
    val affected=visits.filter{it.manholeId in removed||kind=="inspection"&&it.id==id}
    val photos=affected.flatMap{v->PhotoRepository(this).list(v)+dao.settingsNow(owner()).filter{it.key.startsWith("photo-revision:"+v.id)}.flatMap{runCatching{JSONArray(it.value).objects()}.getOrDefault(emptyList())}}.map{it.getString("photoId")}.distinct()
    return JSONObject().put("id",id).put("kind",kind).put("removed",JSONArray(removed.sorted())).put("points",JSONArray(items.filter{it.id in removed&&it.kind=="point"}.map{it.id}.sorted())).put("segments",JSONArray(items.filter{it.id in removed&&it.kind=="segment"}.map{it.id}.sorted())).put("shared",JSONArray(shared.sorted())).put("inspections",JSONArray(affected.map{it.id}.sorted())).put("photos",JSONArray(photos.sorted())).put("verified",false)
}
fun scrubDeleted(value:Any?,ids:Set<String>):Any?=when(value){
    is JSONObject->if(listOf("id","inspection_id","inspectionId","manhole_id","photoId").any{value.optString(it) in ids})null else JSONObject().apply{value.keys().forEach{k->scrubDeleted(value.opt(k),ids)?.let{put(k,it)}}}
    is JSONArray->JSONArray((0 until value.length()).mapNotNull{scrubDeleted(value.get(it),ids)})
    is String->value.takeUnless{it in ids}
    else->value
}
suspend fun Repository.purgeLocal(scope:JSONObject){
    val account=owner();val removed=scope.getJSONArray("removed").strings().toSet();val deletedVisits=scope.getJSONArray("inspections").strings().toSet();val photoIds=scope.getJSONArray("photos").strings().toSet();val ids=removed+deletedVisits+photoIds
    val shared=scope.getJSONArray("shared").strings().toSet();val target=scope.getString("id")
    val sources=dao.catalogNow(account).filter{it.id in removed}.map{JSONObject(it.body).optString("source")}.filter{it.isNotBlank()}.toSet()
    // Freeze file cleanup tasks before removing references, so interruption cannot orphan content.
    for(v in dao.visitsNow(account).filter{it.id in deletedVisits})for(photo in PhotoRepository(this).list(v)+dao.settingsNow(account).filter{it.key.startsWith("photo-revision:"+v.id)}.flatMap{runCatching{JSONArray(it.value).objects()}.getOrDefault(emptyList())}){
        val uri=photo.optString("localUri");if(uri.isNotBlank())dao.setting(Setting(account,"purge-file:"+photo.getString("photoId"),uri))
    }
    for(op in dao.allPending(account)){
        if(op.kind=="permanent_delete")continue
        val full=dao.settingValue(account,"catalog-upload:"+op.operationId)?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload")
        if(op.visitId in ids){dao.acknowledge(op.operationId);continue}
        if(op.kind in listOf("catalog","catalog_chunk")){
            val rows=full.optJSONArray("items")?.objects().orEmpty()
            if(rows.none{it.getJSONObject("data").getString("id") in removed+shared})continue
            if(op.kind=="catalog_chunk")continue // The final operation owns the complete payload.
            val keep=rows.filter{it.getJSONObject("data").getString("id") !in removed}.mapNotNull{scrubDeleted(it,ids) as? JSONObject}.onEach{row->val data=row.getJSONObject("data");if(data.getString("id") in shared)data.put("collectors",JSONArray(data.memberships()-target))}
            dao.pendingVisit(op.visitId,account).forEach{dao.acknowledge(it.operationId)};dao.removeSetting(account,"catalog-upload:"+op.operationId)
            if(keep.isNotEmpty())enqueueCatalog(account,JSONObject().put("items",JSONArray(keep)))
        }
    }
    for(id in deletedVisits){dao.removeAudit(account,id);dao.removeVisit(account,id)}
    for(id in removed)dao.removeCatalog(account,id)
    for(item in dao.catalogNow(account)){
        val data=JSONObject(item.body);val next=scrubDeleted(data,ids) as? JSONObject?:continue
        if(item.id in shared)next.put("collectors",JSONArray(next.memberships()-target))
        if(canonicalJson(next)!=canonicalJson(data))dao.putCatalog(item.copy(body=next.toString()))
    }
    for(setting in dao.settingsNow(account)){
        if(setting.key.startsWith("purge-file:")||setting.key.startsWith("deleted:")||setting.key.startsWith("deletion-state:"))continue
        if(ids.any{setting.key.substringAfter(':',"")==it}){dao.removeSetting(account,setting.key);continue}
        if(setting.value.startsWith("{")||setting.value.startsWith("[")){
            val json=runCatching{org.json.JSONTokener(setting.value).nextValue()}.getOrNull()?:continue
            val clean=scrubDeleted(json,ids)
            if(clean==null)dao.removeSetting(account,setting.key)else if(canonicalJson(clean)!=canonicalJson(json))dao.setting(setting.copy(value=clean.toString()))
        }
    }
    // Import reports may include private attribute examples; counts/mappings are not a content archive.
    for(record in dao.imports(account).filter{it.source in sources})dao.saveImport(record.copy(report="{}"))
    for(id in ids)dao.setting(Setting(account,"deleted:$id",JSONObject().put("id",id).put("deleted",true).toString()))
    dao.setting(Setting(account,"purge-backup-ids",JSONArray((dao.settingValue(account,"purge-backup-ids")?.let{JSONArray(it).strings()}.orEmpty()+ids).distinct()).toString()))
    for(pack in dao.packagesNow(account)){
        val clean=scrubDeleted(JSONObject(pack.body),ids) as? JSONObject?:JSONObject()
        val raw=clean.toString();dao.install(pack.copy(body=raw,checksum=sha256(raw.toByteArray()),bytes=raw.toByteArray().size.toLong()))
    }
    rebuildPackage(account)
}
suspend fun Repository.purgeRemoteMarkers(markers:List<JSONObject>){
    val account=owner();val all=markers.map{it.getString("id")}.toSet()
    val visits=dao.visitsNow(account).filter{it.id in all||it.manholeId in all}
    val photoIds=visits.flatMap{PhotoRepository(this).list(it)}.map{it.getString("photoId")}
    purgeLocal(JSONObject().put("id","").put("removed",JSONArray(markers.filter{it.optString("kind")!="inspection"}.map{it.getString("id")})).put("shared",JSONArray()).put("inspections",JSONArray((visits.map{it.id}+markers.filter{it.optString("kind")=="inspection"}.map{it.getString("id")}).distinct())).put("photos",JSONArray(photoIds)))
}
suspend fun Repository.purgeLocalFiles(){
    val account=owner()
    for(setting in dao.settingsNow(account).filter{it.key.startsWith("purge-file:")}){
        context.contentResolver.delete(android.net.Uri.parse(setting.value),null,null);dao.removeSetting(account,setting.key)
    }
    val ids=dao.settingValue(account,"purge-backup-ids")?.let{JSONArray(it).strings().toSet()}.orEmpty()
    if(ids.isEmpty())return
    // Staging copies have no ongoing role after import; old versions retained them indefinitely.
    File(context.filesDir,"import-originals").listFiles().orEmpty().filter{it.isFile}.forEach{check(it.delete())}
    val dir=File(context.filesDir,"backups")
    for(file in dir.listFiles().orEmpty()){
        if(file.extension=="json"){
            val value=org.json.JSONTokener(file.readText()).nextValue();val clean=scrubDeleted(value,ids)
            if(clean==null){check(file.delete())}else if(canonicalJson(value)!=canonicalJson(clean))file.writeText(clean.toString())
        }else if(file.extension=="zip"){
            val temp=File(dir,file.name+".purging")
            java.util.zip.ZipFile(file).use{zip->java.util.zip.ZipOutputStream(temp.outputStream()).use{out->zip.entries().asSequence().filter{it.name.substringBeforeLast('.') !in ids}.forEach{entry->out.putNextEntry(java.util.zip.ZipEntry(entry.name));zip.getInputStream(entry).use{it.copyTo(out)};out.closeEntry()}}}
            check(file.delete());check(temp.renameTo(file))
        }
    }
    dao.removeSetting(account,"purge-backup-ids")
}
suspend fun Repository.syncStorageDeletions(account:String){
    val rows=api.rpc("coll_pat_storage_pending",JSONObject().put("p_project",project()),account).getJSONArray("items").objects()
    for(row in rows){
        val path=row.getString("path");require(path.startsWith(project()+"/"))
        api.deleteStorage(path,account)
        api.rpc("coll_pat_storage_confirm",JSONObject().put("p_project",project()).put("p_photo",row.getString("photo_id")),account)
    }
    val remaining=api.rpc("coll_pat_storage_pending",JSONObject().put("p_project",project()),account).getJSONArray("items").objects().map{it.getString("operation_id")}.toSet()
    for(setting in dao.settingsNow(account).filter{it.key.startsWith("deletion-operation:")&&it.value !in remaining})dao.setting(Setting(account,"deletion-state:"+setting.key.substringAfter(':'),"Eliminazione definitiva confermata dal server"))
    purgeLocalFiles()
}
