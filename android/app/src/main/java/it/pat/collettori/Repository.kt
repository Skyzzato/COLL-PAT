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

class Repository(val context:Context,databaseName:String="pilot-v1.db",sessionPreferenceName:String="session",httpClient:okhttp3.OkHttpClient?=null){
    val db=Room.databaseBuilder(context,LocalDatabase::class.java,databaseName).addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
    val dao=db.dao();val store=SessionStore(context,sessionPreferenceName);val api=httpClient?.let{Api(store,it)}?:Api(store)
    val writes=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutation=Mutex()
    private val refreshLock=Mutex()
    val refreshing=kotlinx.coroutines.flow.MutableStateFlow(false)
    val editing=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
    fun owner()=store.get()?.let(::sessionOwner)?:error("Accesso richiesto")
    fun project()=store.get()?.optString("project_id",AppSpec.LOCAL_PROJECT)?:AppSpec.LOCAL_PROJECT
    fun authenticated()=store.get()?.let{it.has("access_token")&&it.optInt("protocol")==AppSpec.PROTOCOL}==true
    fun canManageCatalog()=mayManageCatalog(store.get())
    fun checkOffline(){check(store.get()!=null){"Accesso richiesto"}}
    private suspend fun generation(account:String)=dao.settingValue(account,"generation")?.toLong()?:-1L
    suspend fun prepareWorkspace(){
        if(store.get()==null)return
        val account=owner()
        // Never assign new reset generations to v0.12 data or outbox operations.
        if(dao.settingValue(account,"legacy-backed-up")==null){
            val old=dao.visitsNow(account).filter{!JSONObject(it.body).has("payload_version")}
            if(old.isNotEmpty())withContext(Dispatchers.IO){privateBackup("legacy-v012",JSONArray(old.map{JSONObject(it.body)}).toString())}
            dao.setting(Setting(account,"legacy-backed-up","done"))
        }
    }
    suspend fun rebuildPackage(account:String){
        val accountProject=account.substringAfterLast('#',AppSpec.LOCAL_PROJECT)
        val items=dao.catalogNow(account).filter{!JSONObject(it.body).deleted()};val body=JSONObject().put("version",AppSpec.PACKAGE).put("area_id",accountProject).put("name",AppSpec.NAME).put("osm",true).put("basemap",JSONObject.NULL).put("attribution","© OpenStreetMap contributors · ODbL")
            .put("rule",JSONObject(context.assets.open("gps-rule.json").bufferedReader().use{it.readText()}))
        listOf("collectors" to "collector","points" to "point","segments" to "segment").forEach{(key,kind)->body.put(key,JSONArray(items.filter{it.kind==kind}.map{JSONObject(it.body)}))}
        val raw=body.toString();dao.install(OfflinePackage(account,AppSpec.PACKAGE,accountProject,raw,sha256(raw.toByteArray()),raw.toByteArray().size.toLong(),Instant.now().toString(),true))
    }
    suspend fun localCatalog():JSONObject?=dao.pack(owner(),AppSpec.PACKAGE)?.body?.let(::JSONObject)
    suspend fun snapshot(v:Visit):JSONObject=dao.settingValue(v.owner,"snapshot:"+v.id)?.let(::JSONObject)?:JSONObject(dao.pack(v.owner,v.datasetId)?.body?:error("Anagrafica originaria non disponibile"))
    suspend fun begin(point:JSONObject,pack:OfflinePackage,method:String):Visit=mutation.withLock{
        checkOffline();val account=owner();val id=UUID.randomUUID().toString();val s=store.get()!!
        var body=JSONObject().put("id",id).put("manhole_id",point.getString("id")).put("dataset_id",pack.id).put("device_id",store.deviceId)
            .put("user_id",s.getString("user_id")).put("selection_method",method).put("started_at",Instant.now().toString()).put("revision",1)
            .put("sheet_version","sheet-2").put("payload_version",AppSpec.PROTOCOL).put("app_version",AppSpec.version).put("project_id",project()).put("generation",generation(account))
            .put("model","ORDINARY").put("periodic_control",false).put("events",JSONArray()).put("sheet",defaultSheet())
        body.put("status","BOZZA").put("created_by",s.getString("user_id")).put("created_at",body.getString("started_at")).put("updated_by",s.getString("user_id")).put("updated_at",body.getString("started_at")).put("server_revision",0).put("local_edit",1).put("shared_protocol",true)
        if(point.optBoolean("under_asphalt"))body=applyTemplate(body,"ASPHALT_EXTERNAL")
        if(point.optString("asset_type","MANHOLE")!="MANHOLE")body=applyTemplate(body,"ASSET_EXTERNAL")
        check(body.getLong("generation")>=0){"Verificare il progetto online prima della prima ispezione; le successive sono disponibili offline"}
        val v=Visit(id,account,point.getString("id"),pack.id,body.toString(),sync=if(authenticated())"IN_ATTESA" else "SALVATO_LOCALMENTE")
        db.withTransaction{dao.save(v);dao.setting(Setting(account,"snapshot:$id",pack.body))};syncNow();v
    }
    suspend fun saveDraft(id:String,body:JSONObject,queueNow:Boolean=false):Visit=mutation.withLock{db.withTransaction{
        val current=dao.visit(id,owner())?:error("Bozza assente")
        if(current.operational!="BOZZA"||isCancelled(current))return@withTransaction current
        check(current.sync!="CONFLICT"){"Bozza modificata altrove. Conserva la copia locale e apri la versione server."}
        val merged=JSONObject(body.toString()).put("events",JSONObject(current.body).getJSONArray("events"))
        stampEdit(merged,JSONObject(current.body));val next=current.copy(body=merged.toString(),sync=if(authenticated())"IN_ATTESA" else "SALVATO_LOCALMENTE");dao.save(next)
        if(queueNow&&authenticated())queueSnapshot(next)
        syncNow();next
    }}
    suspend fun appendEvent(id:String,event:JSONObject):Visit=mutation.withLock{db.withTransaction{
        val v=dao.visit(id,owner())?:error("Bozza assente");check(v.operational=="BOZZA"&&!isCancelled(v)&&v.sync!="CONFLICT"){"Scheda registrata: creare una nuova ispezione"}
        val body=JSONObject(v.body);validateNewEvidence(event,body)
        if(body.getJSONArray("events").objects().any{it.optString("id")==event.getString("id")})return@withTransaction v
        body.getJSONArray("events").put(event)
        if(motivatedGpsException(event))body.getJSONObject("sheet").put("exception_reason",event.getString("exception_reason"))
        stampEdit(body,JSONObject(v.body));v.copy(body=body.toString(),sync=if(authenticated())"IN_ATTESA" else v.sync).also{dao.save(it);syncNow()}
    }}
    private suspend fun queueSnapshot(v:Visit){
        val b=JSONObject(v.body)
        val pending=dao.pendingVisit(v.id,v.owner).filter{it.kind=="shared_inspection"}
        val expected=pending.maxOfOrNull{JSONObject(it.body).getJSONObject("payload").getInt("expected_revision")+1}?:b.optInt("server_revision")
        b.put("expected_revision",expected).put("photos",JSONArray(PhotoRepository(this).list(v).map{p->JSONObject().put("id",p.getString("photoId")).put("created_at",p.getString("createdAt")).put("storage_path",p.optString("storagePath"))}))
        enqueue(v.owner,v.id,"shared_inspection",b,b.getLong("generation"))
    }
    private suspend fun enqueue(account:String,ref:String,kind:String,payload:JSONObject,gen:Long):Pending {
        val id=UUID.randomUUID().toString();val revision=(dao.pendingVisit(ref,account).maxOfOrNull{it.revision}?:0)+1
        val envelope=JSONObject().put("operation_id",id).put("project_id",project()).put("generation",gen).put("payload_version",AppSpec.PROTOCOL).put("kind",kind).put("app_version",AppSpec.version).put("payload",payload)
        return Pending(id,account,ref,revision,envelope.toString(),if(authenticated())"IN_ATTESA" else "AUTH_REQUIRED",null,project(),gen,AppSpec.PROTOCOL,kind).also{dao.enqueue(it)}
    }
    suspend fun complete(id:String,body:JSONObject,status:String):Visit=mutation.withLock{
        checkOffline();val account=owner()
        val next=db.withTransaction{
            val current=dao.visit(id,account)?:error("Bozza assente")
            if(current.operational!="BOZZA")return@withTransaction current
            check(!isCancelled(current));check(JSONObject(current.body).optInt("payload_version")==AppSpec.PROTOCOL){"Bozza precedente: esportare e creare una nuova ispezione"}
            check(current.sync!="CONFLICT"){"Bozza modificata altrove. Conserva la copia locale e apri la versione server."}
            val merged=JSONObject(body.toString()).put("events",JSONObject(current.body).getJSONArray("events"))
            validateInspection(merged,status)
            if(status=="COMPLETO")check(usableInspectionGps(lastEvidence(merged))){"GPS non affidabile: acquisisci una misura accurata e verifica la corrispondenza o motiva l’eccezione."}
            stampEdit(merged,JSONObject(current.body));merged.put("submitted_by",store.get()!!.getString("user_id")).put("submitted_at",Instant.now().toString())
            if(status=="IMPEDITO"){
                val s=merged.getJSONObject("sheet");s.put("opened",false).put("no_open_reason",s.getString("impediment_reason")).put("cleaning",JSONObject.NULL)
                (observationKeys+externalKeys).forEach{s.put(it,"NON_OSSERVABILE")}
            }
            merged.put("status",status).put("completed_at",Instant.now().toString()).put("periodic_control",status=="COMPLETO")
            merged.put("photos",JSONArray(PhotoRepository(this@Repository).list(current).map{p->JSONObject().put("id",p.getString("photoId")).put("created_at",p.getString("createdAt"))}))
            merged.put("original_evidence",lastEvidence(merged)?.optString("id")?:JSONObject.NULL)
            val v=current.copy(body=merged.toString(),operational=status,sync=if(authenticated())"IN_ATTESA" else "SALVATO_LOCALMENTE",error=null)
            val pendingShared=dao.pendingVisit(id,account).filter{it.kind=="shared_inspection"}
            val expected=pendingShared.maxOfOrNull{JSONObject(it.body).getJSONObject("payload").getInt("expected_revision")+1}?:merged.optInt("server_revision")
            dao.save(v);if(authenticated())enqueue(account,id,"shared_inspection",JSONObject(merged.toString()).put("expected_revision",expected),merged.getLong("generation"));v
        };syncNow();next
    }
    suspend fun cancel(id:String,eventId:String?,reason:String)=mutation.withLock{db.withTransaction{
        require(reason.trim().length in 3..500){"Indicare una motivazione (3–500 caratteri)"}
        val account=owner();val v=dao.visit(id,account)?:error("Ispezione assente");val body=JSONObject(v.body)
        val actor=store.get()!!.getString("user_id")
        require(v.operational=="BOZZA"||body.getString("user_id")==actor||body.optString("submitted_by")==actor||store.get()!!.optString("role")=="admin"){"Annullamento riservato all’autore o al responsabile"}
        if(isCancelled(v))return@withTransaction
        val target=if(eventId==null)body else body.getJSONArray("events").objects().first{it.getString("id")==eventId}
        if(target.has("cancelled"))return@withTransaction
        val audit=Audit(UUID.randomUUID().toString(),account,id,eventId,actor,Instant.now().toString(),reason.trim(),target.toString())
        dao.audit(audit)
        val payload=JSONObject().put("id",audit.id).put("inspection_id",id).put("event_id",eventId?:JSONObject.NULL).put("author",audit.author).put("at",audit.at).put("reason",audit.reason)
        target.put("cancelled",payload)
        val registered=v.operational!="BOZZA"&&body.optInt("payload_version")==AppSpec.PROTOCOL
        if(!registered)stampEdit(body,JSONObject(v.body))
        if(eventId!=null&&registered)body.put("evidence_rectified",true)
        dao.save(v.copy(body=body.toString(),sync=if(registered||authenticated())"IN_ATTESA" else v.sync))
        if(registered)enqueue(account,id,"cancel",payload,body.getLong("generation"))
    };syncNow()}
    suspend fun setVisible(id:String,value:Boolean){dao.setting(Setting(owner(),"visible:$id",value.toString()))}
    suspend fun visibility():Set<String> = dao.catalogNow(owner()).filter{it.kind=="collector"&&dao.settingValue(owner(),"visible:${it.id}")=="false"}.map{it.id}.toSet()
    suspend fun saveCatalog(items:List<CatalogItem>,provenance:JSONObject?=null)=mutation.withLock{
        check(canManageCatalog()){"Gestione riservata al responsabile del progetto"}
        val account=owner()
        val deleted=dao.settingsNow(account).filter{it.key.startsWith("deleted:")}.map{it.key.removePrefix("deleted:")}.toSet()
        require(items.none{it.id in deleted||JSONObject(it.body).memberships().any{cid->cid in deleted}}){"Elemento eliminato: non è possibile ripubblicarlo"}
        val all=(dao.catalogNow(account).associateBy{it.id}+items.associateBy{it.id}).values
        val collectors=all.filter{it.kind=="collector"&&!JSONObject(it.body).deleted()}.map{JSONObject(it.body)};collectors.forEach(::validateCollector)
        require(collectors.map{it.getString("code").lowercase()}.distinct().size==collectors.size){"Codice collettore già utilizzato"}
        val collectorIds=collectors.map{it.getString("id")}.toSet()
        items.filter{it.kind=="point"}.forEach{val p=JSONObject(it.body);require(validCoordinates(p.numberOrNull("latitude"),p.numberOrNull("longitude"))&&p.optString("code").isNotBlank()){ "Codice e coordinate del pozzetto obbligatori" };require(p.memberships().isNotEmpty()&&p.memberships().all{it in collectorIds}){"Collettore del pozzetto assente"}}
        val payload=JSONObject().put("items",JSONArray(items.map{JSONObject().put("kind",it.kind).put("data",JSONObject(it.body))})).put("provenance",provenance?:JSONObject.NULL)
        val bytes=payload.toString().toByteArray().size
        require(bytes<=40*1024*1024){"Importazione strutturata oltre 40 MiB: dividere il file; nessun dato salvato"}
        db.withTransaction{
            items.forEach{dao.putCatalog(it.copy(owner=account,sync="IN_ATTESA"))}
            enqueueCatalog(account,payload)
            if(provenance!=null)dao.saveImport(ImportRecord(provenance.getString("id"),account,provenance.getString("source"),provenance.getString("hash"),provenance.getJSONObject("mapping").toString(),provenance.getJSONObject("report").toString(),Instant.now().toString(),"IN_ATTESA"))
            rebuildPackage(account)
        };syncNow()
    }
    // Used inside the caller's Room transaction, including safe cancellation of unsent batches.
    private suspend fun enqueueCatalog(account:String,payload:JSONObject){
            val reference=UUID.randomUUID().toString();val gen=generation(account)
            if(payload.toString().toByteArray().size<=4*1024*1024)enqueue(account,reference,"catalog",payload,gen)
            else{
                val chunks=mutableListOf<List<JSONObject>>();var batch=mutableListOf<JSONObject>();var batchBytes=0
                for(item in payload.getJSONArray("items").objects()){
                    val size=item.toString().toByteArray().size;require(size<=4*1024*1024){"Singola geometria oltre 4 MiB: suddividerla con chiavi sorgente distinte"}
                    if(batch.isNotEmpty()&&batchBytes+size>1024*1024){chunks.add(batch);batch=mutableListOf();batchBytes=0}
                    batch.add(item);batchBytes+=size
                };if(batch.isNotEmpty())chunks.add(batch)
                val ids=chunks.mapIndexed{index,chunk->enqueue(account,reference,"catalog_chunk",JSONObject().put("batch_id",reference).put("index",index).put("items",JSONArray(chunk)),gen).operationId}
                val final=enqueue(account,reference,"catalog",JSONObject().put("batch_id",reference).put("chunks",JSONArray(ids)).put("provenance",payload.opt("provenance")?:JSONObject.NULL),gen)
                dao.setting(Setting(account,"catalog-upload:"+final.operationId,payload.toString()))
            }
    }
    suspend fun archiveCollector(id:String){
        val c=dao.catalogNow(owner()).first{it.id==id&&it.kind=="collector"};val body=JSONObject(c.body).put("archived",true).put("archived_at",Instant.now().toString()).put("archived_by",store.get()!!.getString("user_id"))
        saveCatalog(listOf(c.copy(body=body.toString())))
    }
    suspend fun deleteImpact(id:String):CollectorDeletion {
        val account=owner();val items=dao.catalogNow(account);val visits=dao.visitsNow(account)
        val pointIds=items.filter{it.kind=="point"&&id in JSONObject(it.body).memberships()}.map{it.id}.toSet()
        return deletionImpact(id,items,visits,visits.filter{it.manholeId in pointIds}.sumOf{PhotoRepository(this).list(it).size})
    }
    suspend fun deleteCollector(id:String)=syncLock.withLock{mutation.withLock{
        check(canManageCatalog()){"Eliminazione riservata all’amministratore"}
        val account=owner();val items=dao.catalogNow(account);val collector=items.firstOrNull{it.id==id&&it.kind=="collector"}?:error("Collettore non disponibile")
        val impact=deleteImpact(id)
        require(items.none{it.kind=="segment"&&it.id in impact.shared&&JSONObject(it.body).let{b->b.optString("from_id") in impact.removed||b.optString("to_id") in impact.removed}}){"Relazioni condivise incoerenti: correggere le appartenenze prima di eliminare"}
        val adjusted=items.filter{it.id !in impact.removed}.mapNotNull{item->
            val b=JSONObject(item.body);var changed=false
            if(item.id in impact.shared){b.put("collectors",JSONArray(b.memberships()-id));changed=true}
            val links=b.optJSONArray("next_ids")?.strings()
            if(links?.any{it in impact.removed}==true){b.put("next_ids",JSONArray(links.filter{it !in impact.removed}));changed=true}
            if(changed)item.copy(body=b.toString())else null
        }.associateBy{it.id}
        val localOnly=collector.sync!="RICEVUTO_SERVER"&&impact.inspections==0&&impact.removed.all{dao.settingValue(account,"server-known:$it")==null&&dao.settingValue(account,"catalog-attempted:$it")==null}
        db.withTransaction{
            if(localOnly){
                // Only rewrite never-attempted batches. Immutable requests already sent retain their receipt identity.
                val batches=dao.allPending(account).filter{it.kind=="catalog"}
                for(op in batches){
                    val p=dao.settingValue(account,"catalog-upload:"+op.operationId)?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload")
                    val rows=p.optJSONArray("items")?.objects().orEmpty()
                    if(rows.none{row->val rid=row.getJSONObject("data").getString("id");rid in impact.removed||rid in adjusted})continue
                    val keep=rows.filter{it.getJSONObject("data").getString("id") !in impact.removed}.map{row->
                        adjusted[row.getJSONObject("data").getString("id")]?.let{row.put("data",JSONObject(it.body))};row
                    }
                    dao.pendingVisit(op.visitId,account).filter{it.kind.startsWith("catalog")}.forEach{dao.acknowledge(it.operationId)}
                    dao.removeSetting(account,"catalog-upload:"+op.operationId)
                    if(keep.isNotEmpty())enqueueCatalog(account,JSONObject().put("items",JSONArray(keep)).put("provenance",JSONObject.NULL))
                }
            }
            for(item in items.filter{it.id in impact.removed}){
                dao.setting(Setting(account,"deleted:"+item.id,JSONObject(item.body).put("deleted",true).put("kind",item.kind).toString()))
                dao.removeCatalog(account,item.id);dao.removeSetting(account,"visible:"+item.id)
            }
            adjusted.values.forEach{dao.putCatalog(it)}
            if(!localOnly)enqueue(account,id,"catalog_delete",JSONObject().put("id",id).put("confirmed",true),generation(account))
            rebuildPackage(account)
        };syncNow();localOnly
    }}
    suspend fun refreshDatabase(){
        check(refreshLock.tryLock()){"Aggiornamento già in corso"}
        val account=owner()
        refreshing.value=true
        try{catalog();downloadHistory();check(owner()==account){"Account cambiato durante l’aggiornamento"};dao.setting(Setting(account,"database-last-success",Instant.now().toString()))}finally{refreshing.value=false;refreshLock.unlock()}
    }
    suspend fun reconcile():JSONObject{
        val account=owner();val s=api.rpc("coll_pat_status",JSONObject().put("p_project",project()),account)
        val gen=s.getLong("generation")
        db.withTransaction{
            dao.setting(Setting(account,"generation",gen.toString()))
            for(op in dao.allPending(account))if(op.generation!=gen)dao.updatePending(op.copy(state="RESET_OBSOLETE",error="Generazione precedente: recupero esplicito richiesto"))
            for(v in dao.visitsNow(account))if(JSONObject(v.body).optLong("generation",-1)!=gen)dao.save(v.copy(sync="RESET_OBSOLETE",error="Scheda precedente al reset; non verrà ripubblicata"))
        }
        val current=store.get();if(current!=null&&sessionOwner(current)==account){store.save(current.put("role",s.getString("role")));dao.setting(Setting(account,"verified-role",s.getString("role")))}
        return s
    }
    suspend fun catalog():JSONObject{
        val account=owner();reconcile();val loaded=mutableListOf<CatalogItem>();val archived=mutableListOf<CatalogItem>();val deleted=mutableListOf<JSONObject>();var offset=0;var revision:Long?=null
        do{
            val page=api.rpc("coll_pat_catalog",JSONObject().put("p_project",project()).put("p_offset",offset).put("p_revision",revision?:JSONObject.NULL),account)
            if(revision==null)revision=page.getLong("revision")
            deleted.addAll(page.optJSONArray("deleted")?.objects().orEmpty())
            for(item in page.optJSONArray("archived")?.objects().orEmpty())archived.add(CatalogItem(account,item.getJSONObject("data").getString("id"),item.getString("kind"),item.getJSONObject("data").toString(),"RICEVUTO_SERVER"))
            for(item in page.getJSONArray("items").objects())loaded.add(CatalogItem(account,item.getJSONObject("data").getString("id"),item.getString("kind"),item.getJSONObject("data").toString(),"RICEVUTO_SERVER"))
            offset+=page.getJSONArray("items").length()
            check(offset<=100000){"Catalogo troppo grande"}
        }while(page.getBoolean("has_more"))
        mutation.withLock{db.withTransaction{
            for(item in deleted){val id=item.getString("id");dao.setting(Setting(account,"deleted:$id",item.toString()));dao.removeCatalog(account,id)}
            val tombstones=dao.settingsNow(account).filter{it.key.startsWith("deleted:")}.map{it.key.removePrefix("deleted:")}.toSet()
            val pendingIds=dao.allPending(account).filter{it.kind.startsWith("catalog")}.flatMap{op->val p=dao.settingValue(account,"catalog-upload:"+op.operationId)?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload");p.optJSONArray("items")?.objects().orEmpty().map{i->i.getJSONObject("data").getString("id")}}.toSet()
            loaded.filter{it.id !in pendingIds&&it.id !in tombstones}.forEach{item->val data=JSONObject(item.body);if(item.kind!="collector")data.put("collectors",JSONArray(data.memberships().filter{it !in tombstones}));dao.putCatalog(item.copy(body=data.toString()));dao.setting(Setting(account,"server-known:"+item.id,"true"))}
            archived.filter{it.id !in tombstones}.forEach{dao.putCatalog(it)}
            dao.setting(Setting(account,"catalog-state","Completo dal server · ${Instant.now()}"));rebuildPackage(account)
        }};return localCatalog()!!
    }
    suspend fun downloadHistory(full:Boolean=true,semesterOnly:String?=null){
        val account=owner();reconcile();var offset=0;var cursor:String?=null
        do{
            val page=if(semesterOnly!=null)api.rpc("coll_pat_semester_history",JSONObject().put("p_project",project()).put("p_semester",semesterOnly).put("p_offset",offset),account) else if(full)api.rpc("coll_pat_history",JSONObject().put("p_project",project()).put("p_offset",offset),account) else api.rpc("coll_pat_inspection_summary",JSONObject().put("p_project",project()).put("p_after",cursor?:JSONObject.NULL),account)
            db.withTransaction{for(row in page.getJSONArray("items").objects()){
                val id=row.getString("id");val current=dao.visit(id,account)
                if(id in editing)continue
                if(current!=null&&(current.sync!="RICEVUTO_SERVER"||dao.pendingVisit(id,account).isNotEmpty()))continue
                val body=JSONObject(row.getJSONObject("original").toString()).put("server_revision",row.optInt("revision",0)).put("local_edit",0).put("shared_protocol",true)
                listOf("created_by","created_at","updated_by","updated_at","submitted_by","submitted_at").forEach{k->if(!row.isNull(k))body.put(k,row.get(k))}
                PhotoRepository(this@Repository).mergeRemote(account,id,row.optJSONArray("photos")?:JSONArray())
                for(c in row.getJSONArray("corrections").objects()){
                    val cancellation=JSONObject().put("id",c.getString("id")).put("author",c.getString("author")).put("at",c.getString("at")).put("reason",c.getString("reason"))
                    if(c.isNull("event_id"))body.put("cancelled",cancellation)
                    else{body.getJSONArray("events").objects().find{it.getString("id")==c.getString("event_id")}?.put("cancelled",cancellation);body.put("evidence_rectified",true)}
                }
                dao.save(Visit(id,account,row.getString("manhole_id"),body.getString("dataset_id"),body.toString(),row.getString("status"),"RICEVUTO_SERVER",JSONObject().put("server_gps",row.getJSONObject("server_gps")).toString()))
            }}
            for(id in page.optJSONArray("cancelled_ids")?.strings().orEmpty()){val v=dao.visit(id,account);if(v!=null&&v.sync=="RICEVUTO_SERVER")dao.save(v.copy(body=JSONObject(v.body).put("cancelled",JSONObject()).toString()))}
            offset+=page.getJSONArray("items").length();check(offset<=100000)
            cursor=page.optString("next").takeUnless{it.isBlank()||it=="null"}
        }while(if(full)page.getBoolean("has_more") else cursor!=null)
    }
    suspend fun sync():Boolean=syncLock.withLock{
        if(!authenticated())return@withLock true
        val account=owner()
        try{checkVersion();reconcile()}catch(e:ApiError){if(e.code==401||e.code==403){dao.pauseAuth(account);return@withLock true}else throw e}
        flushShared(account)
        for(op in dao.pending(account)){
            if(owner()!=account)return@withLock true
            // A terminal catalogue error blocks dependants until corrected; never send unknown assets.
            if(!op.kind.startsWith("catalog")&&dao.allPending(account).any{it.kind in listOf("catalog","catalog_chunk")})continue
            if(op.kind=="catalog"&&dao.allPending(account).any{it.kind=="catalog_chunk"&&it.visitId==op.visitId})continue
            if(op.kind=="cancel"&&dao.allPending(account).any{it.visitId==op.visitId&&it.kind in listOf("inspection","shared_inspection")})continue
            try{
                dao.updatePending(op.copy(state="IN_CORSO"))
                if(op.kind=="catalog"||op.kind=="catalog_chunk"){
                    val batchFinal=if(op.kind=="catalog_chunk")dao.pendingVisit(op.visitId,account).firstOrNull{it.kind=="catalog"} else op
                    val p=batchFinal?.let{dao.settingValue(account,"catalog-upload:"+it.operationId)}?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload")
                    p.optJSONArray("items")?.objects().orEmpty().forEach{dao.setting(Setting(account,"catalog-attempted:"+it.getJSONObject("data").getString("id"),"true"))}
                }
                val receipt=api.rpc(when(op.kind){"shared_inspection"->"coll_pat_save_inspection";"catalog_delete"->"coll_pat_delete_collector";else->"coll_pat_apply"},JSONObject().put("operation",JSONObject(op.body)),account)
                check(receipt.getString("operation_id")==op.operationId&&receipt.getLong("generation")==op.generation&&receipt.getString("project_id")==op.project){"Ricevuta non corrispondente"}
                db.withTransaction{
                    dao.acknowledge(op.operationId)
                    dao.visit(op.visitId,account)?.let{latest->
                        val b=JSONObject(latest.body);val sent=JSONObject(op.body).getJSONObject("payload")
                        val changed=op.kind=="shared_inspection"&&b.optLong("local_edit")!=sent.optLong("local_edit")
                        if(op.kind=="shared_inspection"){b.put("server_revision",receipt.getInt("revision"));listOf("created_by","created_at","updated_by","updated_at","submitted_by","submitted_at").forEach{k->if(!receipt.isNull(k))b.put(k,receipt.get(k))}}
                        dao.save(latest.copy(body=b.toString(),sync=if(changed||dao.pendingVisit(op.visitId,account).isNotEmpty())"IN_ATTESA" else "RICEVUTO_SERVER",receipt=receipt.toString(),error=null))
                    }
                    if(op.kind=="catalog"){
                        val sent=dao.settingValue(account,"catalog-upload:"+op.operationId)?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload")
                        for(item in sent.getJSONArray("items").objects()){
                            val data=item.getJSONObject("data");val local=dao.catalogNow(account).find{it.id==data.getString("id")}
                            dao.setting(Setting(account,"server-known:"+data.getString("id"),"true"))
                            if(local!=null&&JSONObject(local.body).toString()==data.toString())dao.putCatalog(local.copy(sync="RICEVUTO_SERVER"))
                        }
                        sent.optJSONObject("provenance")?.let{p->dao.imports(account).find{it.id==p.getString("id")}?.let{dao.saveImport(it.copy(state="RICEVUTO_SERVER"))}}
                    }
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){
                val auth=e is ApiError&&e.code==401;val terminal=e is ApiError&&e.code in listOf(400,403,404,409,422)
                val state=if(auth)"AUTH_REQUIRED" else if(terminal)"CONFLICT" else "IN_ATTESA"
                db.withTransaction{dao.updatePending(op.copy(state=state,error=friendlyError(e)));dao.visit(op.visitId,account)?.let{dao.save(it.copy(sync=state,error=friendlyError(e)))}}
                if(auth){dao.pauseAuth(account);return@withLock true}
                if(!terminal)return@withLock false
                // Stop dependent cancellation operations behind a failed creation.
                return@withLock true
            }
        }
        PhotoRepository(this).syncAll(account)
        catalog();downloadHistory(false);dao.setting(Setting(account,"database-last-success",Instant.now().toString()));dao.visitsNow(account).none{it.sync=="IN_ATTESA"}
    }
    suspend fun fieldSettings()=dao.settingValue(owner(),"field-settings-v014")?.let{FieldSettings.parse(JSONObject(it))}?:FieldSettings()
    suspend fun saveSettings(value:FieldSettings){dao.setting(Setting(owner(),"field-settings-v014",value.json().toString()))}
    private fun stampEdit(body:JSONObject,current:JSONObject){
        listOf("created_by","created_at","server_revision").forEach{key->if(current.has(key))body.put(key,current.get(key))}
        body.put("status",body.optString("status","BOZZA")).put("shared_protocol",true).put("local_edit",current.optLong("local_edit")+1).put("updated_by",store.get()!!.getString("user_id")).put("updated_at",Instant.now().toString())
    }
    suspend fun changePhotos(visit:Visit,change:(List<JSONObject>)->List<JSONObject>)=mutation.withLock{db.withTransaction{
        check(owner()==visit.owner){"Account cambiato: riapri la scheda"}
        val v=dao.visit(visit.id,visit.owner)?:error("Bozza non disponibile")
        check(v.operational=="BOZZA"&&v.sync!="CONFLICT"&&!isCancelled(v)){"Apri una bozza modificabile prima di aggiungere o rimuovere foto"}
        val photos=change(PhotoRepository(this@Repository).list(v));check(photos.size<=100){"Sono consentite al massimo 100 foto per ispezione"}
        dao.setting(Setting(visit.owner,"photos:"+visit.id,JSONArray(photos).toString()))
        val b=JSONObject(v.body);stampEdit(b,JSONObject(v.body));dao.save(v.copy(body=b.toString(),sync=if(authenticated())"IN_ATTESA" else v.sync))
    };syncNow()}
    private suspend fun flushShared(account:String)=mutation.withLock{db.withTransaction{
        for(v in dao.visitsNow(account).filter{it.sync=="IN_ATTESA"&&JSONObject(it.body).optBoolean("shared_protocol")}){
            if(dao.pendingVisit(v.id,account).isNotEmpty())continue
            val b=JSONObject(v.body).put("expected_revision",JSONObject(v.body).optInt("server_revision"))
            b.put("photos",JSONArray(PhotoRepository(this@Repository).list(v).map{p->JSONObject().put("id",p.getString("photoId")).put("created_at",p.getString("createdAt")).put("storage_path",p.optString("storagePath"))}))
            enqueue(account,v.id,"shared_inspection",b,b.getLong("generation"))
        }
    }}
    suspend fun reloadConflict(id:String)=syncLock.withLock{mutation.withLock{
        val account=owner();val v=dao.visit(id,account)?:error("Scheda assente")
        check(v.sync=="CONFLICT");privateBackup("conflitto-$id",v.body)
        val row=api.rpc("coll_pat_inspection",JSONObject().put("p_project",project()).put("p_id",id),account)
        val b=row.getJSONObject("original").put("server_revision",row.getInt("revision")).put("local_edit",0).put("shared_protocol",true)
        db.withTransaction{dao.pendingVisit(id,account).forEach{dao.acknowledge(it.operationId)};PhotoRepository(this@Repository).mergeRemote(account,id,row.optJSONArray("photos")?:JSONArray());dao.save(v.copy(body=b.toString(),operational=row.getString("status"),sync="RICEVUTO_SERVER",error=null))}
    }}
    suspend fun checkVersion(refreshBlocked:Boolean=false):JSONObject?{
        val session=store.get();val publicPrefs=context.getSharedPreferences("public-config",Context.MODE_PRIVATE);val base=session?.optString("base")?.takeIf{it.startsWith("https://")}?:publicPrefs.getString("url",BuildConfig.SUPABASE_URL).orEmpty()
        val key=session?.optString("public_key")?.takeIf{it.isNotBlank()}?:publicPrefs.getString("key",BuildConfig.SUPABASE_PUBLISHABLE_KEY).orEmpty()
        if(base.isBlank()||key.isBlank())return null
        val cacheOwner="version-policy:$base"
        val cached=dao.settingValue(cacheOwner,"policy")?.let(::JSONObject)
        if(!refreshBlocked&&cached!=null&&compareVersions(AppSpec.version,cached.getString("minimum_supported_version"))<0)throw ApiError(426,cached.optString("message","Installa l’aggiornamento di COLL-PAT."))
        val policy=try{withTimeoutOrNull(5000){JSONObject(String(api.raw(base,"/rest/v1/rpc/coll_pat_version",key,body=JSONObject()))).also{compareVersions(AppSpec.version,it.getString("minimum_supported_version"));dao.setting(Setting(cacheOwner,"policy",it.toString()))}}?:cached}catch(e:CancellationException){throw e}catch(_:Exception){cached}
        if(policy!=null&&compareVersions(AppSpec.version,policy.getString("minimum_supported_version"))<0)throw ApiError(426,policy.optString("message","Installa l’aggiornamento di COLL-PAT."))
        return policy
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
