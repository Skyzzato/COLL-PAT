package it.pat.collettori

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PilotApplication:Application(){
    lateinit var repository:Repository
    val startedAt=android.os.SystemClock.elapsedRealtime()
    var splashFinished=false
    override fun onCreate(){super.onCreate()
        org.maplibre.android.MapLibre.getInstance(this)
        // MapLibre owns the sole map cache; no second OkHttp disk cache.
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(okhttp3.OkHttpClient.Builder()
            .addInterceptor{chain->chain.proceed(chain.request().newBuilder().header("User-Agent","${AppSpec.NAME}/${AppSpec.version} (Android; https://github.com/Skyzzato/COLL-PAT)").build())}.build())
        org.maplibre.android.offline.OfflineManager.getInstance(this).setMaximumAmbientCacheSize(AppSpec.MAP_CACHE_BYTES,null)
        repository=Repository(this);repository.schedule()
        repository.writes.launch{withContext(Dispatchers.IO){java.io.File(cacheDir,"osm-http").deleteRecursively()}}
    }
}

class Repository(val context:Context){
    val db=Room.databaseBuilder(context,LocalDatabase::class.java,"pilot-v1.db").addMigrations(MIGRATION_1_2).build()
    val dao=db.dao();val store=SessionStore(context);val api=Api(store)
    val writes=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutation=Mutex()
    companion object{
        private val syncLock=Mutex()
        val observationKeys=listOf("cover","deposits","flow","walls","damage","closure","restored")
        fun defaultSheet():JSONObject=JSONObject().apply{
            put("accessible",true);put("opened",true);put("cleaning",false);put("unsafe",false);put("map_position_wrong",false)
            (observationKeys+externalKeys).forEach{put(it,"REGOLARE")}
            put("raise_needed",false);put("road_repair_needed",false)
            listOf("no_open_reason","anomaly_note","technical_value","notes","exception_reason","impediment_reason").forEach{put(it,"")}
            put("priority","MEDIA");put("technical_origin","NON_NOTO")
        }
    }
    fun owner()=store.get()?.let{if(it.optString("base")==DemoMode.base)DemoMode.owner else sessionOwner(it)}?:error("Accesso richiesto")
    fun project()=store.get()?.optString("project_id",AppSpec.LOCAL_PROJECT)?:AppSpec.LOCAL_PROJECT
    fun authenticated()=store.get()?.let{it.has("access_token")&&it.optInt("protocol")==AppSpec.PROTOCOL}==true
    fun checkOffline(){check(store.get()!=null){"Accesso richiesto"}}
    private suspend fun generation(account:String)=dao.settingValue(account,"generation")?.toLong()?:if(account==DemoMode.owner)0L else -1L
    suspend fun prepareDemo()=prepareWorkspace()
    suspend fun prepareWorkspace(){
        if(store.get()==null&&BuildConfig.DEMO)store.save(DemoMode.session())
        if(store.get()==null)return
        val account=owner()
        if(dao.settingValue(account,"catalog")==null){val rule=JSONObject(context.assets.open("gps-rule.json").bufferedReader().use{it.readText()});dao.setting(Setting(account,"catalog",DemoMode.catalog(rule).toString()))}
        if(BuildConfig.DEMO&&dao.settingValue(account,"seed-v013")==null){
            val text=context.assets.open("demo-package.json").bufferedReader().use{it.readText()};val data=JSONObject(text);DemoMode.validateDataset(data)
            db.withTransaction{
                if(dao.settingValue(account,"seed-v013")==null){
                    for((array,kind) in listOf("collectors" to "collector","points" to "point","segments" to "segment"))for(item in data.getJSONArray(array).objects())dao.putCatalog(CatalogItem(account,item.getString("id"),kind,item.toString(),"SEED_LOCAL"))
                    dao.setting(Setting(account,"seed-v013","done"));rebuildPackage(account)
                }
            }
        }
        // Never assign new reset generations to v0.12 data or outbox operations.
        if(dao.settingValue(account,"legacy-backed-up")==null){
            val old=dao.visitsNow(account).filter{!JSONObject(it.body).has("payload_version")}
            if(old.isNotEmpty())withContext(Dispatchers.IO){privateBackup("legacy-v012",JSONArray(old.map{JSONObject(it.body)}).toString())}
            dao.setting(Setting(account,"legacy-backed-up","done"))
        }
    }
    suspend fun rebuildPackage(account:String){
        val items=dao.catalogNow(account);val body=JSONObject().put("version",AppSpec.PACKAGE).put("area_id",project()).put("name",AppSpec.NAME).put("osm",true).put("basemap",JSONObject.NULL).put("attribution","© OpenStreetMap contributors · ODbL")
        listOf("collectors" to "collector","points" to "point","segments" to "segment").forEach{(key,kind)->body.put(key,JSONArray(items.filter{it.kind==kind}.map{JSONObject(it.body)}))}
        val raw=body.toString();dao.install(OfflinePackage(account,AppSpec.PACKAGE,project(),raw,sha256(raw.toByteArray()),raw.toByteArray().size.toLong(),Instant.now().toString(),true))
    }
    suspend fun localCatalog():JSONObject?=dao.settingValue(owner(),"catalog")?.let(::JSONObject)
    suspend fun snapshot(v:Visit):JSONObject=dao.settingValue(v.owner,"snapshot:"+v.id)?.let(::JSONObject)?:JSONObject(dao.pack(v.owner,v.datasetId)?.body?:error("Anagrafica originaria non disponibile"))
    suspend fun begin(point:JSONObject,pack:OfflinePackage,method:String):Visit=mutation.withLock{
        checkOffline();val account=owner();val id=UUID.randomUUID().toString();val s=store.get()!!
        var body=JSONObject().put("id",id).put("manhole_id",point.getString("id")).put("dataset_id",pack.id).put("device_id",store.deviceId)
            .put("user_id",s.getString("user_id")).put("selection_method",method).put("started_at",Instant.now().toString()).put("revision",1)
            .put("sheet_version","sheet-2").put("payload_version",AppSpec.PROTOCOL).put("app_version",AppSpec.version).put("project_id",project()).put("generation",generation(account))
            .put("model","ORDINARY").put("periodic_control",false).put("events",JSONArray()).put("sheet",defaultSheet())
        if(point.optString("asset_type","MANHOLE")!="MANHOLE")body=applyTemplate(body,"ASSET_EXTERNAL")
        check(body.getLong("generation")>=0){"Verificare il progetto online prima della prima ispezione; le successive sono disponibili offline"}
        val v=Visit(id,account,point.getString("id"),pack.id,body.toString())
        db.withTransaction{dao.save(v);dao.setting(Setting(account,"snapshot:$id",pack.body))};v
    }
    suspend fun saveDraft(id:String,body:JSONObject):Visit=mutation.withLock{db.withTransaction{
        val current=dao.visit(id,owner())?:error("Bozza assente")
        if(current.operational!="BOZZA"||isCancelled(current))return@withTransaction current
        val merged=JSONObject(body.toString()).put("events",JSONObject(current.body).getJSONArray("events"))
        val next=current.copy(body=merged.toString());dao.save(next);next
    }}
    suspend fun appendEvent(id:String,event:JSONObject):Visit=mutation.withLock{db.withTransaction{
        val v=dao.visit(id,owner())?:error("Bozza assente");check(v.operational=="BOZZA"&&!isCancelled(v)){"Scheda registrata: creare una nuova ispezione"}
        val body=JSONObject(v.body);body.getJSONArray("events").put(event);v.copy(body=body.toString()).also{dao.save(it)}
    }}
    private suspend fun enqueue(account:String,ref:String,kind:String,payload:JSONObject,gen:Long):Pending {
        val id=UUID.randomUUID().toString();val revision=(dao.pendingVisit(ref).maxOfOrNull{it.revision}?:0)+1
        val envelope=JSONObject().put("operation_id",id).put("project_id",project()).put("generation",gen).put("payload_version",AppSpec.PROTOCOL).put("kind",kind).put("payload",payload)
        return Pending(id,account,ref,revision,envelope.toString(),if(authenticated())"IN_ATTESA" else "AUTH_REQUIRED",null,project(),gen,AppSpec.PROTOCOL,kind).also{dao.enqueue(it)}
    }
    suspend fun complete(id:String,body:JSONObject,status:String):Visit=mutation.withLock{
        checkOffline();val account=owner()
        val next=db.withTransaction{
            val current=dao.visit(id,account)?:error("Bozza assente")
            if(current.operational!="BOZZA")return@withTransaction current
            check(!isCancelled(current));check(JSONObject(current.body).optInt("payload_version")==AppSpec.PROTOCOL){"Bozza precedente: esportare e creare una nuova ispezione"}
            val merged=JSONObject(body.toString()).put("events",JSONObject(current.body).getJSONArray("events"))
            validateInspection(merged,status)
            if(status=="IMPEDITO"){
                val s=merged.getJSONObject("sheet");s.put("opened",false).put("no_open_reason",s.getString("impediment_reason")).put("cleaning",JSONObject.NULL)
                (observationKeys+externalKeys).forEach{s.put(it,"NON_OSSERVABILE")}
            }
            merged.put("status",status).put("completed_at",Instant.now().toString()).put("periodic_control",status=="COMPLETO")
            merged.put("photos",JSONArray(PhotoRepository(this@Repository).list(current).map{p->JSONObject().put("id",p.getString("photoId")).put("created_at",p.getString("createdAt")).put("upload","SIMULATED_LOCAL_ONLY")}))
            merged.put("original_evidence",lastEvidence(merged)?.optString("id")?:JSONObject.NULL)
            val v=current.copy(body=merged.toString(),operational=status,sync="IN_ATTESA",error=null)
            dao.save(v);enqueue(account,id,"inspection",merged,merged.getLong("generation"));v
        };syncNow();next
    }
    suspend fun cancel(id:String,eventId:String?,reason:String)=mutation.withLock{db.withTransaction{
        require(reason.trim().length in 3..500){"Indicare una motivazione (3–500 caratteri)"}
        val account=owner();val v=dao.visit(id,account)?:error("Ispezione assente");val body=JSONObject(v.body)
        require(body.getString("user_id")==store.get()!!.getString("user_id")){"Puoi annullare soltanto i tuoi dati"}
        if(isCancelled(v))return@withTransaction
        val target=if(eventId==null)body else body.getJSONArray("events").objects().first{it.getString("id")==eventId}
        if(target.has("cancelled"))return@withTransaction
        val audit=Audit(UUID.randomUUID().toString(),account,id,eventId,body.getString("user_id"),Instant.now().toString(),reason.trim(),target.toString())
        dao.audit(audit)
        val payload=JSONObject().put("id",audit.id).put("inspection_id",id).put("event_id",eventId?:JSONObject.NULL).put("author",audit.author).put("at",audit.at).put("reason",audit.reason)
        target.put("cancelled",payload)
        val registered=v.operational!="BOZZA"&&body.optInt("payload_version")==AppSpec.PROTOCOL
        if(eventId!=null&&registered)body.put("evidence_rectified",true)
        dao.save(v.copy(body=body.toString(),sync=if(registered)"IN_ATTESA" else v.sync))
        if(registered)enqueue(account,id,"cancel",payload,body.getLong("generation"))
    };syncNow()}
    suspend fun setVisible(id:String,value:Boolean){dao.setting(Setting(owner(),"visible:$id",value.toString()))}
    suspend fun visibility():Set<String> = dao.catalogNow(owner()).filter{it.kind=="collector"&&dao.settingValue(owner(),"visible:${it.id}")=="false"}.map{it.id}.toSet()
    suspend fun saveCatalog(items:List<CatalogItem>,provenance:JSONObject?=null)=mutation.withLock{
        check(BuildConfig.DEV_ADMIN){"Dashboard di sviluppo disabilitata"}
        if(authenticated())check(store.get()!!.optString("role")=="admin"){"Amministratore del progetto richiesto"}
        val account=owner();val all=(dao.catalogNow(account).associateBy{it.id}+items.associateBy{it.id}).values
        val collectors=all.filter{it.kind=="collector"}.map{JSONObject(it.body)};collectors.forEach(::validateCollector)
        require(collectors.map{it.getString("code").lowercase()}.distinct().size==collectors.size){"Codice collettore già utilizzato"}
        val payload=JSONObject().put("items",JSONArray(items.map{JSONObject().put("kind",it.kind).put("data",JSONObject(it.body))})).put("provenance",provenance?:JSONObject.NULL)
        val bytes=payload.toString().toByteArray().size
        require(bytes<=40*1024*1024){"Importazione strutturata oltre 40 MiB: dividere il file; nessun dato salvato"}
        db.withTransaction{
            items.forEach{dao.putCatalog(it.copy(owner=account,sync="IN_ATTESA"))}
            val reference=UUID.randomUUID().toString();val gen=generation(account)
            if(bytes<=4*1024*1024)enqueue(account,reference,"catalog",payload,gen)
            else{
                val chunks=mutableListOf<List<JSONObject>>();var batch=mutableListOf<JSONObject>();var batchBytes=0
                for(item in payload.getJSONArray("items").objects()){
                    val size=item.toString().toByteArray().size;require(size<=4*1024*1024){"Singola geometria oltre 4 MiB: suddividerla con chiavi sorgente distinte"}
                    if(batch.isNotEmpty()&&batchBytes+size>1024*1024){chunks.add(batch);batch=mutableListOf();batchBytes=0}
                    batch.add(item);batchBytes+=size
                };if(batch.isNotEmpty())chunks.add(batch)
                val ids=chunks.mapIndexed{index,chunk->enqueue(account,reference,"catalog_chunk",JSONObject().put("batch_id",reference).put("index",index).put("items",JSONArray(chunk)),gen).operationId}
                val final=enqueue(account,reference,"catalog",JSONObject().put("batch_id",reference).put("chunks",JSONArray(ids)).put("provenance",provenance?:JSONObject.NULL),gen)
                dao.setting(Setting(account,"catalog-upload:"+final.operationId,payload.toString()))
            }
            if(provenance!=null)dao.saveImport(ImportRecord(provenance.getString("id"),account,provenance.getString("source"),provenance.getString("hash"),provenance.getJSONObject("mapping").toString(),provenance.getJSONObject("report").toString(),Instant.now().toString(),"IN_ATTESA"))
            rebuildPackage(account)
        };syncNow()
    }
    suspend fun archiveCollector(id:String){
        val c=dao.catalogNow(owner()).first{it.id==id&&it.kind=="collector"};val body=JSONObject(c.body).put("archived",true)
        saveCatalog(listOf(c.copy(body=body.toString())))
    }
    suspend fun reconcile():JSONObject{
        val account=owner();val s=api.rpc("coll_pat_status",JSONObject().put("p_project",project()),account)
        val gen=s.getLong("generation")
        db.withTransaction{
            dao.setting(Setting(account,"generation",gen.toString()))
            for(op in dao.allPending(account))if(op.generation!=gen)dao.updatePending(op.copy(state="RESET_OBSOLETE",error="Generazione precedente: recupero esplicito richiesto"))
            for(v in dao.visitsNow(account))if(JSONObject(v.body).optLong("generation",-1)!=gen)dao.save(v.copy(sync="RESET_OBSOLETE",error="Scheda precedente al reset; non verrà ripubblicata"))
        }
        val current=store.get();if(current!=null&&sessionOwner(current)==account)store.save(current.put("role",s.getString("role")))
        return s
    }
    suspend fun catalog():JSONObject{
        val account=owner();reconcile();val loaded=mutableListOf<CatalogItem>();var offset=0;var revision:Long?=null
        do{
            val page=api.rpc("coll_pat_catalog",JSONObject().put("p_project",project()).put("p_offset",offset).put("p_revision",revision?:JSONObject.NULL),account)
            if(revision==null)revision=page.getLong("revision")
            for(item in page.getJSONArray("items").objects())loaded.add(CatalogItem(account,item.getJSONObject("data").getString("id"),item.getString("kind"),item.getJSONObject("data").toString(),"RICEVUTO_SERVER"))
            offset+=page.getJSONArray("items").length()
            check(offset<=100000){"Catalogo troppo grande"}
        }while(page.getBoolean("has_more"))
        db.withTransaction{
            val pendingIds=dao.allPending(account).filter{it.kind.startsWith("catalog")}.flatMap{op->val p=dao.settingValue(account,"catalog-upload:"+op.operationId)?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload");p.optJSONArray("items")?.objects().orEmpty().map{i->i.getJSONObject("data").getString("id")}}.toSet()
            loaded.filter{it.id !in pendingIds}.forEach{dao.putCatalog(it)}
            dao.setting(Setting(account,"catalog-state","Completo dal server · ${Instant.now()}"));rebuildPackage(account)
        };return localCatalog()!!
    }
    suspend fun publishSeed(){
        check(authenticated()&&store.get()!!.optString("role")=="admin"){"Accedere come amministratore Auth"}
        catalog()
        val seed=dao.catalogNow(owner()).filter{it.sync=="SEED_LOCAL"}
        if(seed.isNotEmpty())saveCatalog(seed)
    }
    suspend fun downloadHistory(){
        val account=owner();reconcile();var offset=0
        do{
            val page=api.rpc("coll_pat_history",JSONObject().put("p_project",project()).put("p_offset",offset),account)
            db.withTransaction{for(row in page.getJSONArray("items").objects()){
                val id=row.getString("id");val current=dao.visit(id,account)
                if(current!=null&&(current.operational=="BOZZA"||dao.pendingVisit(id).isNotEmpty()||current.sync=="RESET_OBSOLETE"))continue
                val body=JSONObject(row.getJSONObject("original").toString())
                for(c in row.getJSONArray("corrections").objects()){
                    val cancellation=JSONObject().put("id",c.getString("id")).put("author",c.getString("author")).put("at",c.getString("at")).put("reason",c.getString("reason"))
                    if(c.isNull("event_id"))body.put("cancelled",cancellation)
                    else{body.getJSONArray("events").objects().find{it.getString("id")==c.getString("event_id")}?.put("cancelled",cancellation);body.put("evidence_rectified",true)}
                }
                dao.save(Visit(id,account,row.getString("manhole_id"),body.getString("dataset_id"),body.toString(),row.getString("status"),"RICEVUTO_SERVER",JSONObject().put("server_gps",row.getJSONObject("server_gps")).toString()))
            }}
            offset+=page.getJSONArray("items").length();check(offset<=100000)
        }while(page.getBoolean("has_more"))
    }
    suspend fun sync():Boolean=syncLock.withLock{
        if(!authenticated())return@withLock true
        val account=owner()
        try{reconcile()}catch(e:ApiError){if(e.code==401||e.code==403){dao.pauseAuth(account);return@withLock true}else throw e}
        for(op in dao.pending(account)){
            if(owner()!=account)return@withLock true
            // A terminal catalogue error blocks dependants until corrected; never send unknown assets.
            if(!op.kind.startsWith("catalog")&&dao.allPending(account).any{it.kind.startsWith("catalog")})continue
            if(op.kind=="catalog"&&dao.allPending(account).any{it.kind=="catalog_chunk"&&it.visitId==op.visitId})continue
            if(op.kind=="cancel"&&dao.allPending(account).any{it.visitId==op.visitId&&it.kind=="inspection"})continue
            try{
                dao.updatePending(op.copy(state="IN_CORSO"))
                val receipt=api.rpc("coll_pat_apply",JSONObject().put("operation",JSONObject(op.body)),account)
                check(receipt.getString("operation_id")==op.operationId&&receipt.getLong("generation")==op.generation&&receipt.getString("project_id")==op.project){"Ricevuta non corrispondente"}
                db.withTransaction{
                    dao.acknowledge(op.operationId)
                    dao.visit(op.visitId,account)?.let{latest->dao.save(latest.copy(sync=if(dao.pendingVisit(op.visitId).isEmpty())"RICEVUTO_SERVER" else "IN_ATTESA",receipt=receipt.toString(),error=null))}
                    if(op.kind=="catalog"){
                        val sent=dao.settingValue(account,"catalog-upload:"+op.operationId)?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload")
                        for(item in sent.getJSONArray("items").objects()){
                            val data=item.getJSONObject("data");val local=dao.catalogNow(account).find{it.id==data.getString("id")}
                            if(local!=null&&JSONObject(local.body).toString()==data.toString())dao.putCatalog(local.copy(sync="RICEVUTO_SERVER"))
                        }
                        sent.optJSONObject("provenance")?.let{p->dao.imports(account).find{it.id==p.getString("id")}?.let{dao.saveImport(it.copy(state="RICEVUTO_SERVER"))}}
                    }
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){
                val auth=e is ApiError&&e.code==401;val terminal=e is ApiError&&e.code in listOf(400,403,404,409,422)
                val state=if(auth)"AUTH_REQUIRED" else if(terminal)"CONFLICT" else "IN_ATTESA"
                db.withTransaction{dao.updatePending(op.copy(state=state,error=e.message));dao.visit(op.visitId,account)?.let{dao.save(it.copy(sync=state,error=e.message))}}
                if(auth){dao.pauseAuth(account);return@withLock true}
                if(!terminal)return@withLock false
                // Stop dependent cancellation operations behind a failed creation.
                return@withLock true
            }
        };true
    }
    fun syncNow(){WorkManager.getInstance(context).enqueueUniqueWork("coll-pat-send",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())}
    fun schedule(){val work=WorkManager.getInstance(context);work.cancelUniqueWork("periodic-sync");work.cancelUniqueWork("send-current-account");work.enqueueUniquePeriodicWork("coll-pat-periodic",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build());syncNow()}
    suspend fun recoveryBundle():String {
        val account=owner();return JSONObject().put("format","COLL-PAT-recovery-2").put("created_at",Instant.now()).put("scope",account)
            .put("visits",JSONArray(dao.visitsNow(account).map{JSONObject(it.body)})).put("operations",JSONArray(dao.allPending(account).map{JSONObject(it.body)}))
            .put("photos",JSONArray(dao.visitsNow(account).flatMap{PhotoRepository(this).list(it)}))
            .put("audit",JSONArray(dao.visitsNow(account).flatMap{dao.audits(account,it.id)}.map{JSONObject().put("id",it.id).put("original",JSONObject(it.original)).put("reason",it.reason).put("at",it.at).put("author",it.author)})).toString(2)
    }
    fun privateBackup(label:String,body:String):java.io.File {
        val dir=java.io.File(context.filesDir,"backups").apply{mkdirs()};val file=java.io.File(dir,"$label-${UUID.randomUUID()}.json")
        java.io.FileOutputStream(file).use{it.write(body.toByteArray());it.fd.sync()};return file
    }
    suspend fun reset(confirm:String):JSONObject=syncLock.withLock{mutation.withLock{
        require(confirm=="AZZERA");check(authenticated())
        val account=owner();val state=reconcile();check(state.getString("role")=="admin")
        val backup=api.rpc("coll_pat_backup",JSONObject().put("p_project",project()),account)
        val photos=dao.visitsNow(account).flatMap{PhotoRepository(this).list(it)}
        withContext(Dispatchers.IO){
            privateBackup("pre-reset-server",backup.toString());privateBackup("pre-reset-local",recoveryBundle())
            val photoBackup=java.io.File(context.filesDir,"backups/pre-reset-photos-${UUID.randomUUID()}.zip")
            java.io.FileOutputStream(photoBackup).use{file->
                java.util.zip.ZipOutputStream(file).use{zip->photos.forEach{photo->
                    zip.putNextEntry(java.util.zip.ZipEntry(photo.getString("photoId")+".jpg"))
                    context.contentResolver.openInputStream(android.net.Uri.parse(photo.getString("localUri")))?.use{it.copyTo(zip)}?:error("Foto non leggibile: backup incompleto, reset non eseguito")
                    zip.closeEntry()
                };zip.finish();file.fd.sync()}
            }
        }
        val result=api.rpc("coll_pat_reset",JSONObject().put("p_project",project()).put("p_generation",state.getLong("generation")).put("p_confirmation",confirm).put("p_backup_token",backup.getString("backup_token")),account)
        db.withTransaction{dao.clearInspectionQueue(account);dao.clearAudit(account);dao.clearVisits(account);dao.clearInspectionSettings(account);dao.setting(Setting(account,"generation",result.getLong("generation").toString()))}
        withContext(Dispatchers.IO){photos.forEach{photo->runCatching{context.contentResolver.delete(android.net.Uri.parse(photo.getString("localUri")),null,null)}}}
        result
    }}
}
fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result{
        val repo=(applicationContext as PilotApplication).repository
        if(repo.store.get()==null)return Result.success()
        return try{if(repo.sync())Result.success()else Result.retry()}catch(e:CancellationException){throw e}catch(e:Exception){Result.retry()}
    }
}
