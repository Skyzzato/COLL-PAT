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
    val db=Room.databaseBuilder(context,LocalDatabase::class.java,databaseName).addMigrations(MIGRATION_1_2,MIGRATION_2_3).addCallback(object:androidx.room.RoomDatabase.Callback(){override fun onOpen(db:androidx.sqlite.db.SupportSQLiteDatabase){db.query("PRAGMA secure_delete=ON").use{it.moveToFirst()}}}).build()
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
    val simulation=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private var simulationSession:String?=null
    fun realRole()=store.get()?.optString("role").orEmpty()
    fun simulatedRole():String? {
        val s=store.get()
        if(!mayManageCatalog(s)||s?.optString("session_epoch")!=simulationSession)simulation.value=null
        return simulation.value
    }
    fun effectiveRole()=simulatedRole()?:realRole()
    fun canOperate()=authenticated()&&effectiveRole() in setOf("admin","inspector")
    fun canManageCatalog()=authenticated()&&effectiveRole()=="admin"
    fun canManageUsers()=mayManageCatalog(store.get())&&simulatedRole()==null
    fun simulate(role:String?){check(mayManageCatalog(store.get()));require(role==null||role in setOf("viewer","inspector"));simulationSession=store.get()?.optString("session_epoch");simulation.value=role}
    fun requireWrite(admin:Boolean=false){
        check(simulatedRole()==null){"Simulazione: anteprima senza scritture. Termina la simulazione per modificare dati."}
        check(if(admin)canManageCatalog()else canOperate()){"Operazione non autorizzata per questo livello di abilitazione"}
    }
    fun checkOffline(){check(store.get()!=null){"Accesso richiesto"}}
    private suspend fun generation(account:String)=dao.settingValue(account,"generation")?.toLong()?:-1L
    suspend fun prepareWorkspace(){
        if(store.get()==null)return
        val account=owner()
        purgeLocalFiles()
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
        requireWrite()
        checkOffline();val account=owner();val id=UUID.randomUUID().toString();val s=store.get()!!
        var body=JSONObject().put("id",id).put("manhole_id",point.getString("id")).put("dataset_id",pack.id).put("device_id",store.deviceId)
            .put("user_id",s.getString("user_id")).put("selection_method",method).put("started_at",Instant.now().toString()).put("revision",1)
            .put("sheet_version","sheet-3").put("gps_contract",2).put("payload_version",AppSpec.PROTOCOL).put("app_version",AppSpec.version).put("project_id",project()).put("generation",generation(account))
            .put("model","ORDINARY").put("periodic_control",false).put("events",JSONArray()).put("sheet",defaultSheet())
        body.put("status","BOZZA").put("created_by",s.getString("user_id")).put("created_at",body.getString("started_at")).put("updated_by",s.getString("user_id")).put("updated_at",body.getString("started_at")).put("server_revision",0).put("local_edit",1).put("shared_protocol",true)
        if(point.optBoolean("under_asphalt"))body=applyTemplate(body,"ASPHALT_EXTERNAL")
        if(point.optString("asset_type","MANHOLE")!="MANHOLE")body=applyTemplate(body,"ASSET_EXTERNAL")
        check(body.getLong("generation")>=0){"Verificare il progetto online prima della prima ispezione; le successive sono disponibili offline"}
        val v=Visit(id,account,point.getString("id"),pack.id,body.toString(),sync=if(authenticated())"IN_ATTESA" else "SALVATO_LOCALMENTE")
        db.withTransaction{dao.save(v);dao.setting(Setting(account,"snapshot:$id",pack.body))};syncNow();v
    }
    suspend fun saveDraft(id:String,body:JSONObject,queueNow:Boolean=false):Visit=mutation.withLock{db.withTransaction{
        requireWrite()
        val current=dao.visit(id,owner())?:error("Bozza assente")
        if(current.operational!="BOZZA"||isCancelled(current))return@withTransaction current
        check(current.sync!="CONFLICT"){"Bozza modificata altrove. Conserva la copia locale e apri la versione server."}
        val merged=JSONObject(body.toString()).put("events",JSONObject(current.body).getJSONArray("events")).put("gps_state",JSONObject(current.body).opt("gps_state")?:JSONObject.NULL)
        stampEdit(merged,JSONObject(current.body));val next=current.copy(body=merged.toString(),sync=if(authenticated())"IN_ATTESA" else "SALVATO_LOCALMENTE");dao.save(next)
        if(queueNow&&authenticated())queueSnapshot(next)
        syncNow();next
    }}
    suspend fun appendEvent(id:String,event:JSONObject):Visit=mutation.withLock{db.withTransaction{
        requireWrite()
        val v=dao.visit(id,owner())?:error("Bozza assente");check(v.operational=="BOZZA"&&!isCancelled(v)&&v.sync!="CONFLICT"){"Scheda registrata: creare una nuova ispezione"}
        val body=JSONObject(v.body);validateNewEvidence(event,body)
        if(body.getJSONArray("events").objects().any{it.optString("id")==event.getString("id")})return@withTransaction v
        body.put("events",JSONArray(listOf(event))).put("gps_state",JSONObject().put("status","RECORDED"))
        if(motivatedGpsException(event))body.getJSONObject("sheet").put("exception_reason",event.getString("exception_reason"))
        stampEdit(body,JSONObject(v.body));v.copy(body=body.toString(),sync=if(authenticated())"IN_ATTESA" else v.sync).also{dao.save(it);syncNow()}
    }}
    suspend fun beginGpsAttempt(id:String)=changeGpsState(id){body->
        body.put("events",JSONArray()).put("gps_contract",2).put("gps_state",JSONObject().put("status","ATTEMPTED").put("attempt_id",UUID.randomUUID().toString()).put("attempted_at",Instant.now().toString()).put("author",store.get()!!.getString("user_id")))
    }
    suspend fun recordNoGps(id:String,reason:String)=changeGpsState(id){body->
        require(reason.trim().length in 1..1000){"Motivazione non rilievo GPS obbligatoria (massimo 1000 caratteri)"}
        val state=body.optJSONObject("gps_state")?:error("Esegui prima un tentativo GPS")
        require(state.optString("status")=="ATTEMPTED"){"Ripeti il tentativo GPS prima di confermare una nuova motivazione"}
        state.put("status","NOT_RECORDED_WITH_REASON").put("reason",reason.trim()).put("author",store.get()!!.getString("user_id")).put("confirmed_at",Instant.now().toString())
        body.put("events",JSONArray()).put("gps_state",state)
    }
    private suspend fun changeGpsState(id:String,change:(JSONObject)->Unit)=mutation.withLock{db.withTransaction{
        requireWrite()
        val v=dao.visit(id,owner())?:error("Bozza assente");check(v.operational=="BOZZA"&&v.sync!="CONFLICT")
        val b=JSONObject(v.body);change(b);stampEdit(b,JSONObject(v.body));dao.save(v.copy(body=b.toString(),sync="IN_ATTESA"));syncNow()
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
        val envelope=JSONObject().put("operation_id",id).put("queued_at",Instant.now().toString()).put("project_id",project()).put("generation",gen).put("payload_version",AppSpec.PROTOCOL).put("kind",kind).put("app_version",AppSpec.version).put("payload",payload)
        return Pending(id,account,ref,revision,envelope.toString(),if(authenticated())"IN_ATTESA" else "AUTH_REQUIRED",null,project(),gen,AppSpec.PROTOCOL,kind).also{dao.enqueue(it)}
    }
    suspend fun complete(id:String,body:JSONObject,status:String):Visit=mutation.withLock{
        requireWrite()
        checkOffline();val account=owner()
        val next=db.withTransaction{
            val current=dao.visit(id,account)?:error("Bozza assente")
            if(current.operational!="BOZZA")return@withTransaction current
            check(!isCancelled(current));check(JSONObject(current.body).optInt("payload_version")==AppSpec.PROTOCOL){"Bozza precedente: esportare e creare una nuova ispezione"}
            check(current.sync!="CONFLICT"){"Bozza modificata altrove. Conserva la copia locale e apri la versione server."}
            val merged=JSONObject(body.toString()).put("events",JSONObject(current.body).getJSONArray("events")).put("gps_state",JSONObject(current.body).opt("gps_state")?:JSONObject.NULL)
            validateInspection(merged,status)
            if(status=="COMPLETO")check(inspectionLocationReady(merged)){"GPS non affidabile: acquisisci una misura accurata e verifica la corrispondenza o motiva l’eccezione."}
            stampEdit(merged,JSONObject(current.body));merged.put("submitted_by",store.get()!!.getString("user_id")).put("submitted_at",Instant.now().toString())
            if(status=="IMPEDITO"){
                val s=merged.getJSONObject("sheet");s.put("opened",false).put("no_open_reason",s.getString("impediment_reason")).put("cleaning",JSONObject.NULL)
                listOf("deposits","flow","walls","damage").forEach{s.put(it,"NON_OSSERVABILE")};listOf("closure","restored").forEach{s.put(it,"NON_APPLICABILE")}
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
        requireWrite()
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
    suspend fun saveCatalog(input:List<CatalogItem>,provenance:JSONObject?=null){
        requireWrite(admin=true);val account=owner()
        writes.async{check(owner()==account){"Account cambiato"};saveCatalogInternal(input,provenance)}.await()
    }
    private suspend fun saveCatalogInternal(input:List<CatalogItem>,provenance:JSONObject?)=mutation.withLock{
        requireWrite(admin=true)
        val items=materializeManualConnections(input,dao.catalogNow(owner()))
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
    internal suspend fun enqueueCatalog(account:String,payload:JSONObject){
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
        return deletionImpact(id,items,visits,visits.filter{it.manholeId in items.filter{i->i.kind=="point"&&id in JSONObject(i.body).memberships()}.map{it.id}}.sumOf{PhotoRepository(this).list(it).size})
    }
    suspend fun deleteCollector(id:String):Boolean {deletePermanently(deletionPreview("collector",id));return false}
    suspend fun deletionPreview(kind:String,id:String):JSONObject {
        val local=localDeletionScope(kind,id)
        return try{api.rpc("coll_pat_deletion_preview",JSONObject().put("p_project",project()).put("p_kind",kind).put("p_id",id),owner()).also{remote->
            // Unsent local children must be included in the visible confirmation as well.
            listOf("removed","shared","inspections","photos").forEach{k->val combined=(remote.getJSONArray(k).strings()+local.getJSONArray(k).strings()).distinct().sorted();remote.put(k,JSONArray(combined))}
            listOf("points","segments").forEach{k->remote.put(k,JSONArray((remote.optJSONArray(k)?.strings().orEmpty()+local.getJSONArray(k).strings()).distinct().sorted()))}
        }}catch(e:ApiError){throw e}catch(_:java.io.IOException){local}
    }
    suspend fun deletePermanently(scope:JSONObject)=syncLock.withLock{mutation.withLock{
        requireWrite(admin=scope.getString("kind")!="inspection")
        val account=owner();val kind=scope.getString("kind");val id=scope.getString("id")
        if(kind!="inspection")check(canManageCatalog()){"Eliminazione riservata all’amministratore"}
        val current=localDeletionScope(kind,id)
        listOf("removed","shared","inspections","photos").forEach{k->check(current.getJSONArray(k).strings().all{it in scope.getJSONArray(k).strings()}){"Ambito locale cambiato: riapri la conferma"}}
        val gen=generation(account)
        db.withTransaction{
            purgeLocal(scope)
            dao.allPending(account).filter{it.kind=="permanent_delete"&&it.visitId==id&&it.state=="CONFLICT"}.forEach{dao.acknowledge(it.operationId)}
            val payload=JSONObject().put("id",id).put("kind",kind).put("confirmed",true).put("scope",scope)
            enqueue(account,id,"permanent_delete",payload,gen)
            dao.setting(Setting(account,"deletion-state:$id","Eliminato dal dispositivo — cancellazione sul server in attesa"))
        }
        purgeLocalFiles();syncNow()
    }}
    suspend fun patchObject(id:String,changes:JSONObject)=mutation.withLock{db.withTransaction{
        requireWrite()
        val account=owner();val item=dao.catalogNow(account).firstOrNull{it.id==id}?:error("Oggetto assente")
        check(changes.keys().asSequence().all{it=="under_asphalt"&&item.kind=="point"||canManageCatalog()})
        val data=JSONObject(item.body);val expected=JSONObject();changes.keys().forEach{k->expected.put(k,data.opt(k)?:JSONObject.NULL);data.put(k,changes.get(k))}
        dao.putCatalog(item.copy(body=data.toString(),sync="IN_ATTESA"))
        enqueue(account,id,"object_patch",JSONObject().put("id",id).put("changes",changes).put("expected",expected),generation(account));rebuildPackage(account);syncNow()
    }}
    suspend fun changeTemplate(id:String,input:JSONObject,model:String):Visit=mutation.withLock{db.withTransaction{
        requireWrite()
        val account=owner();val current=dao.visit(id,account)?:error("Bozza assente");check(current.operational=="BOZZA"&&current.sync!="CONFLICT")
        val body=applyTemplate(input,model).put("events",JSONObject(current.body).getJSONArray("events")).put("gps_state",JSONObject(current.body).opt("gps_state")?:JSONObject.NULL)
        if(model=="ASPHALT_EXTERNAL"){
            val point=dao.catalogNow(account).first{it.id==current.manholeId};val data=JSONObject(point.body)
            if(!data.optBoolean("under_asphalt")){
                val expected=JSONObject().put("under_asphalt",data.opt("under_asphalt")?:JSONObject.NULL);data.put("under_asphalt",true)
                dao.putCatalog(point.copy(body=data.toString(),sync="IN_ATTESA"))
                enqueue(account,point.id,"object_patch",JSONObject().put("id",point.id).put("expected",expected).put("changes",JSONObject().put("under_asphalt",true)),generation(account));rebuildPackage(account)
            }
        }
        stampEdit(body,JSONObject(current.body));val next=current.copy(body=body.toString(),sync="IN_ATTESA");dao.save(next);syncNow();next
    }}
    suspend fun setPointAsphalt(id:String,value:Boolean){
        val p=dao.catalogNow(owner()).firstOrNull{it.id==id}?:error("Pozzetto assente")
        if(JSONObject(p.body).optBoolean("under_asphalt")!=value)patchObject(id,JSONObject().put("under_asphalt",value))
    }
    suspend fun refreshDatabase(){
        check(refreshLock.tryLock()){"Aggiornamento già in corso"}
        val account=owner()
        refreshing.value=true
        try{syncLock.withLock{catalogInternal();downloadHistoryInternal()};check(owner()==account){"Account cambiato durante l’aggiornamento"};dao.setting(Setting(account,"database-last-success",Instant.now().toString()))}finally{refreshing.value=false;refreshLock.unlock()}
    }
    suspend fun reconcile():JSONObject{
        val account=owner();val s=try{api.rpc("coll_pat_status",JSONObject().put("p_project",project()),account)}catch(e:ApiError){
            if(e.code==403){val current=store.get();if(current!=null&&sessionOwner(current)==account){simulation.value=null;store.save(current.put("role",""));dao.setting(Setting(account,"verified-role",""))}}
            throw e
        }
        val gen=s.getLong("generation")
        db.withTransaction{
            dao.setting(Setting(account,"generation",gen.toString()))
            for(op in dao.allPending(account))if(op.generation!=gen)dao.updatePending(op.copy(state="RESET_OBSOLETE",error="Generazione precedente: recupero esplicito richiesto"))
            for(v in dao.visitsNow(account))if(JSONObject(v.body).optLong("generation",-1)!=gen)dao.save(v.copy(sync="RESET_OBSOLETE",error="Scheda precedente al reset; non verrà ripubblicata"))
        }
        val current=store.get();if(current!=null&&sessionOwner(current)==account){store.save(current.put("role",s.getString("role")));dao.setting(Setting(account,"verified-role",s.getString("role")))}
        return s
    }
    suspend fun catalog():JSONObject=syncLock.withLock{catalogInternal()}
    private suspend fun catalogInternal():JSONObject{
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
        val permanent=api.rpc("coll_pat_deleted",JSONObject().put("p_project",project()),account).getJSONArray("items").objects()
        mutation.withLock{db.withTransaction{
            if(permanent.isNotEmpty())purgeRemoteMarkers(permanent)
            for(item in deleted){val id=item.getString("id");dao.setting(Setting(account,"deleted:$id",item.toString()));dao.removeCatalog(account,id)}
            val tombstones=dao.settingsNow(account).filter{it.key.startsWith("deleted:")}.map{it.key.removePrefix("deleted:")}.toSet()
            val pendingIds=dao.allPending(account).filter{it.kind.startsWith("catalog")||it.kind=="object_patch"}.flatMap{op->val p=dao.settingValue(account,"catalog-upload:"+op.operationId)?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload");if(op.kind=="object_patch")listOf(p.getString("id"))else p.optJSONArray("items")?.objects().orEmpty().map{i->i.getJSONObject("data").getString("id")}}.toSet()
            loaded.filter{it.id !in pendingIds&&it.id !in tombstones}.forEach{item->val data=JSONObject(item.body);if(item.kind!="collector")data.put("collectors",JSONArray(data.memberships().filter{it !in tombstones}));dao.putCatalog(item.copy(body=data.toString()));dao.setting(Setting(account,"server-known:"+item.id,"true"))}
            archived.filter{it.id !in tombstones}.forEach{dao.putCatalog(it)}
            dao.setting(Setting(account,"catalog-state","Completo dal server · ${Instant.now()}"));rebuildPackage(account)
        }};return localCatalog()!!
    }
    suspend fun downloadHistory(full:Boolean=true,semesterOnly:String?=null)=syncLock.withLock{downloadHistoryInternal(full,semesterOnly)}
    private suspend fun downloadHistoryInternal(full:Boolean=true,semesterOnly:String?=null){
        val account=owner();reconcile();var offset=0;var cursor:String?=null
        do{
            val page=if(semesterOnly!=null)api.rpc("coll_pat_semester_history",JSONObject().put("p_project",project()).put("p_semester",semesterOnly).put("p_offset",offset),account) else if(full)api.rpc("coll_pat_history",JSONObject().put("p_project",project()).put("p_offset",offset),account) else api.rpc("coll_pat_inspection_summary",JSONObject().put("p_project",project()).put("p_after",cursor?:JSONObject.NULL),account)
            db.withTransaction{for(row in page.getJSONArray("items").objects()){
                val id=row.getString("id");val current=dao.visit(id,account)
                if(dao.settingValue(account,"deleted:$id")!=null)continue
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
        val deleted=api.rpc("coll_pat_deleted",JSONObject().put("p_project",project()),account).getJSONArray("items").objects()
        if(deleted.isNotEmpty())mutation.withLock{db.withTransaction{purgeRemoteMarkers(deleted)}}
        if(realRole() in setOf("admin","inspector"))flushShared(account)
        for(op in dao.pending(account)){
            if(owner()!=account)return@withLock true
            val needsAdmin=op.kind.startsWith("catalog")||op.kind=="permanent_delete"&&JSONObject(op.body).getJSONObject("payload").optString("kind")!="inspection"||op.kind=="object_patch"&&JSONObject(op.body).getJSONObject("payload").getJSONObject("changes").keys().asSequence().any{it!="under_asphalt"}
            if(realRole() !in setOf("admin","inspector")||needsAdmin&&realRole()!="admin"){
                dao.updatePending(op.copy(state="BLOCKED",error="Permesso revocato: operazione conservata e non inviata"));continue
            }
            // A terminal catalogue error blocks dependants until corrected; never send unknown assets.
            if(op.kind!="permanent_delete"&&!op.kind.startsWith("catalog")&&dao.allPending(account).any{it.kind in listOf("catalog","catalog_chunk")})continue
            if(op.kind=="catalog"&&dao.allPending(account).any{it.kind=="catalog_chunk"&&it.visitId==op.visitId})continue
            if(op.kind in listOf("inspection","shared_inspection")){
                val pointId=dao.visit(op.visitId,account)?.manholeId
                if(dao.allPending(account).any{it.kind=="object_patch"&&it.visitId==pointId})continue
            }
            if(op.kind=="cancel"&&dao.allPending(account).any{it.visitId==op.visitId&&it.kind in listOf("inspection","shared_inspection")})continue
            if(op.kind!="permanent_delete"&&dao.allPending(account).any{it.kind=="permanent_delete"})continue
            try{
                dao.setting(Setting(account,"attempt:"+op.operationId,Instant.now().toString()))
                dao.updatePending(op.copy(state="IN_CORSO"))
                if(op.kind=="catalog"||op.kind=="catalog_chunk"){
                    val batchFinal=if(op.kind=="catalog_chunk")dao.pendingVisit(op.visitId,account).firstOrNull{it.kind=="catalog"} else op
                    val p=batchFinal?.let{dao.settingValue(account,"catalog-upload:"+it.operationId)}?.let(::JSONObject)?:JSONObject(op.body).getJSONObject("payload")
                    p.optJSONArray("items")?.objects().orEmpty().forEach{dao.setting(Setting(account,"catalog-attempted:"+it.getJSONObject("data").getString("id"),"true"))}
                }
                val receipt=api.rpc(when(op.kind){"shared_inspection"->"coll_pat_save_inspection";"catalog_delete"->"coll_pat_delete_collector";"permanent_delete"->"coll_pat_delete_permanent";"object_patch"->"coll_pat_patch_object";else->"coll_pat_apply"},JSONObject().put("operation",JSONObject(op.body)),account)
                check(receipt.getString("operation_id")==op.operationId&&receipt.getLong("generation")==op.generation&&receipt.getString("project_id")==op.project){"Ricevuta non corrispondente"}
                db.withTransaction{
                    dao.acknowledge(op.operationId)
                    dao.setting(Setting(account,"last-send-success",Instant.now().toString()))
                    if(op.kind=="permanent_delete"){dao.setting(Setting(account,"deletion-operation:"+op.visitId,op.operationId));dao.setting(Setting(account,"deletion-state:"+op.visitId,if(receipt.optInt("storage_pending")>0)"Dati eliminati dal server · allegati in attesa di rimozione" else "Eliminazione definitiva confermata dal server"))}
                    if(op.kind=="object_patch"&&dao.pendingVisit(op.visitId,account).isEmpty())dao.catalogNow(account).find{it.id==op.visitId}?.let{dao.putCatalog(it.copy(sync="RICEVUTO_SERVER"))}

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
                        dao.removeSetting(account,"catalog-upload:"+op.operationId)
                    }
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;
                val auth=e is ApiError&&e.code==401;val terminal=e is ApiError&&!e.retryable&&e.code!=401
                val state=if(auth)"AUTH_REQUIRED" else if(e is ApiError&&e.code==409)"CONFLICT" else if(terminal)"BLOCKED" else "IN_ATTESA"
                db.withTransaction{dao.updatePending(op.copy(state=state,error=friendlyError(e)));dao.visit(op.visitId,account)?.let{dao.save(it.copy(sync=state,error=friendlyError(e)))}}
                if(auth){dao.pauseAuth(account);return@withLock true}
                if(!terminal)return@withLock false
                // Stop dependent cancellation operations behind a failed creation.
                return@withLock true
            }
        }
        if(realRole() in setOf("admin","inspector")){PhotoRepository(this).syncAll(account);syncStorageDeletions(account)}
        catalogInternal();downloadHistoryInternal(false);dao.setting(Setting(account,"database-last-success",Instant.now().toString()));dao.allPending(account).isEmpty()&&dao.visitsNow(account).none{it.sync in listOf("IN_ATTESA","CONFLICT","BLOCKED","AUTH_REQUIRED")}&&PhotoRepository(this).pendingCount(account)==0
    }
    suspend fun fieldSettings():FieldSettings{
        settingValues[owner()]?.let{return it}
        val raw=dao.settingValue(owner(),"field-settings-v014")?.let(::JSONObject)?:return FieldSettings()
        val value=FieldSettings.parse(raw)
        if(raw.optDouble("accuracy")!=value.maxAccuracy||raw.optDouble("distance")!=value.maxDistance){
            dao.setting(Setting(owner(),"settings-normalized","Soglie GPS aggiornate: accuratezza ${value.maxAccuracy.toInt()} m, distanza ${value.maxDistance.toInt()} m. Le rilevazioni storiche mantengono le soglie originali."));saveSettings(value)
        };return value
    }
    val settingsError=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val settingValues=java.util.concurrent.ConcurrentHashMap<String,FieldSettings>()
    private var settingsWrite:Job?=null
    @Synchronized fun updateSettings(value:FieldSettings){
        val account=owner();settingValues[account]=value;val previous=settingsWrite
        settingsWrite=writes.launch{previous?.join();try{dao.setting(Setting(account,"field-settings-v014",value.json().toString()));settingsError.value=null}
            catch(e:CancellationException){throw e}catch(e:Exception){settingsError.value=friendlyError(e)}}
    }
    suspend fun saveSettings(value:FieldSettings){settingValues[owner()]=value;dao.setting(Setting(owner(),"field-settings-v014",value.json().toString()))}
    private fun stampEdit(body:JSONObject,current:JSONObject){
        listOf("created_by","created_at","server_revision").forEach{key->if(current.has(key))body.put(key,current.get(key))}
        body.put("status",body.optString("status","BOZZA")).put("shared_protocol",true).put("local_edit",current.optLong("local_edit")+1).put("updated_by",store.get()!!.getString("user_id")).put("updated_at",Instant.now().toString())
    }
    suspend fun changePhotos(visit:Visit,change:(List<JSONObject>)->List<JSONObject>)=mutation.withLock{db.withTransaction{
        requireWrite()
        check(owner()==visit.owner){"Account cambiato: riapri la scheda"}
        val v=dao.visit(visit.id,visit.owner)?:error("Bozza non disponibile")
        check(v.operational=="BOZZA"&&v.sync!="CONFLICT"&&!isCancelled(v)){"Apri una bozza modificabile prima di aggiungere o rimuovere foto"}
        val previousPhotos=PhotoRepository(this@Repository).list(v)
        val photos=change(previousPhotos);check(photos.size<=100){"Sono consentite al massimo 100 foto per ispezione"}
        val removed=previousPhotos.filter{old->photos.none{it.getString("photoId")==old.getString("photoId")}}
        if(removed.isNotEmpty()){
            val key="photo-revision:"+visit.id
            val prior=dao.settingValue(visit.owner,key)?.let{JSONArray(it).objects()}.orEmpty()
            dao.setting(Setting(visit.owner,key,JSONArray((prior+removed).distinctBy{it.getString("photoId")}).toString()))
        }
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
        requireWrite()
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
        val policy=try{withTimeoutOrNull(5000){JSONObject(String(api.raw(base,"/rest/v1/rpc/coll_pat_version",key,body=JSONObject()))).also{compareVersions(AppSpec.version,it.getString("minimum_supported_version"));dao.setting(Setting(cacheOwner,"policy",it.toString()))}}?:cached}catch(e:CancellationException){throw e}catch(ignored:Exception){if(ignored is kotlinx.coroutines.CancellationException)throw ignored;cached}
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
        requireWrite(admin=true)
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
        return try{if(repo.sync()||runAttemptCount>=5||repo.dao.allPending(repo.owner()).none{it.state=="IN_ATTESA"}&&PhotoRepository(repo).pendingCount(repo.owner())==0)Result.success()else Result.retry()}catch(e:CancellationException){throw e}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;if(runAttemptCount>=5||e is ApiError&&!e.retryable)Result.failure()else Result.retry()}
    }
}
