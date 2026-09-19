package it.pat.collettori

import android.app.Application
import android.content.Context
import android.os.StatFs
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PilotApplication:Application(){
    lateinit var repository:Repository
    override fun onCreate(){super.onCreate()
        // HttpRequestImpl's static initializer reads MapLibre's application context.
        // Initialize the SDK before customizing its HTTP client (v0.11 startup crash).
        org.maplibre.android.MapLibre.getInstance(this)
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(okhttp3.OkHttpClient.Builder()
            .cache(okhttp3.Cache(java.io.File(cacheDir,"osm-http"),50L*1024*1024))
            .addInterceptor{chain->chain.proceed(chain.request().newBuilder().header("User-Agent","Collettori/0.12 (Android demo; https://github.com/Skyzzato/Collettori)").build())}.build())
        repository=Repository(this);repository.schedule()}
}

class Repository(val context:Context){
    val db=Room.databaseBuilder(context,LocalDatabase::class.java,"pilot-v1.db").build()
    val dao=db.dao();val store=SessionStore(context);val api=Api(store)
    private val syncLock=Mutex()
    suspend fun prepareDemo(){
        check(BuildConfig.DEMO)
        val bytes=context.assets.open("demo-package.json").use{it.readBytes()}
        val p=JSONObject(String(bytes,Charsets.UTF_8))
        DemoMode.validateDataset(p)
        val rule=JSONObject(context.assets.open("gps-rule.json").bufferedReader().use{it.readText()})
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        db.withTransaction{
            if(dao.pack(DemoMode.owner,p.getString("version"))==null)
                dao.install(OfflinePackage(DemoMode.owner,p.getString("version"),p.getString("area_id"),String(bytes,Charsets.UTF_8),hash,bytes.size.toLong(),"2026-09-18T00:00:00Z",true))
            dao.setting(Setting(DemoMode.owner,"catalog",DemoMode.catalog(rule).toString()))
        }
        store.save(DemoMode.session())
    }
    fun owner():String {val s=store.get()?:error("Accesso richiesto");return s.getString("base")+"#"+s.getString("user_id")}
    private fun requireOffline(){val s=store.get()?:error("Accesso richiesto");if(BuildConfig.DEMO){check(owner()==DemoMode.owner);return};check(store.offlineAllowed(s)){"Abilitazione offline scaduta o orologio incoerente: rinnovare online. Dati conservati."}}
    fun checkOffline(){requireOffline()}
    suspend fun catalog():JSONObject {val owner=owner();val c=JSONObject(String(api.request("/api/catalog",expectedOwner=owner)));dao.setting(Setting(owner,"catalog",c.toString()));return c}
    suspend fun localCatalog():JSONObject?=dao.settingValue(owner(),"catalog")?.let(::JSONObject)
    suspend fun recoveryBundle():String{
        val account=owner()
        if(BuildConfig.DEMO)return DemoMode.export(dao.visitsNow(account).map{visit->JSONObject(visit.body)
            .put("photos",JSONArray(PhotoRepository(this).list(visit)))
            .put("identification",dao.settingValue(account,"identification:"+visit.id)?.let(::JSONObject)?:JSONObject.NULL)}).toString(2)
        return JSONObject().put("format","collettori-recovery-1").put("created_at",Instant.now().toString())
            .put("operations",JSONArray(dao.allPending(account).map{JSONObject(it.body)}))
            .put("drafts",JSONArray(dao.visitsNow(account).filter{it.operational=="BOZZA"}.map{JSONObject(it.body)})).toString(2)
    }
    suspend fun downloadHistory(area:String){
        val account=owner()
        val summaries=JSONArray(String(api.request("/api/inspections?area="+java.net.URLEncoder.encode(area,"UTF-8"),expectedOwner=account)))
        for(summary in summaries.objects()){
            val id=summary.getString("inspection_id")
            val detail=JSONObject(String(api.request("/api/inspections/$id",expectedOwner=account)))
            dao.setting(Setting(account,"history:$id",detail.toString()))
            val revisions=detail.getJSONArray("revisions").objects();val last=revisions.last();val body=last.getJSONObject("payload")
            // Never replace pending local work, even when the server holds another revision.
            val existing=dao.visit(id,account)
            if(existing!=null&&(existing.sync!="RICEVUTO_SERVER"||existing.operational=="BOZZA"))continue
            if(body.getString("user_id")!=store.get()!!.getString("user_id"))continue
            val dataset=body.getString("dataset_id")
            if(dao.pack(account,dataset)==null){
                val raw=api.request("/api/datasets/$dataset",expectedOwner=account);val p=JSONObject(String(raw))
                val hash=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
                // Historical package is kept out of the current-area selection by its original timestamp.
                dao.install(OfflinePackage(account,dataset,p.getString("area_id"),String(raw),hash,raw.size.toLong(),"1970-01-01T00:00:00Z",!p.isNull("basemap")))
            }
            val receipt=JSONObject().put("review",last.getString("review")).put("server_evaluation",last.getJSONObject("server_evaluation"))
            dao.save(Visit(id,account,body.getString("manhole_id"),dataset,body.toString(),body.getString("status"),"RICEVUTO_SERVER",receipt.toString()))
        }
    }
    suspend fun download(meta:JSONObject){
        val account=owner();val size=meta.getLong("bytes")
        check(StatFs(context.filesDir.path).availableBytes > size*3+20_000_000){"Spazio insufficiente per installare senza sostituire il pacchetto precedente"}
        val bytes=api.request("/api/datasets/"+meta.getString("id"),expectedOwner=account)
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        check(bytes.size.toLong()==size && hash==meta.getString("sha256")){"Integrità del pacchetto non verificata: conservata la versione precedente"}
        val payload=JSONObject(String(bytes));check(payload.getString("version")==meta.getString("id"))
        val ranges=payload.optJSONArray("glyph_ranges")?:JSONArray(listOf("0-255"))
        val ready=!payload.isNull("basemap") && (0 until ranges.length()).all{i->try{context.assets.open("glyphs/Noto Sans Regular/${ranges.getString(i)}.pbf").use{it.available()>0}}catch(e:Exception){false}}
        db.withTransaction{dao.install(OfflinePackage(account,meta.getString("id"),meta.getString("area_id"),String(bytes),hash,size,Instant.now().toString(),ready))}
    }
    suspend fun begin(point:JSONObject,pack:OfflinePackage,method:String):Visit{
        requireOffline();val s=store.get()!!;val id=UUID.randomUUID().toString()
        val body=JSONObject().put("id",id).put("manhole_id",point.getString("id")).put("dataset_id",pack.id).put("device_id",store.deviceId)
            .put("user_id",s.getString("user_id")).put("selection_method",method).put("started_at",Instant.now().toString()).put("revision",1).put("revision_reason","")
            .put("sheet_version","sheet-1").put("events",JSONArray()).put("sheet",defaultSheet()).put("deadline_id",JSONObject.NULL)
        val v=Visit(id,owner(),point.getString("id"),pack.id,body.toString());dao.save(v);return v
    }
    suspend fun saveDraft(id:String,body:JSONObject):Visit=db.withTransaction{
        val account=owner();val current=dao.visit(id,account)?:error("Bozza assente")
        check(current.operational=="BOZZA"){"Creare una revisione"}
        val merged=JSONObject(body.toString()).put("events",JSONObject(current.body).getJSONArray("events"))
        val next=current.copy(body=merged.toString());dao.save(next);next
    }
    suspend fun appendEvent(id:String,event:JSONObject):Visit=db.withTransaction{
        val v=dao.visit(id,owner())?:error("Bozza assente");val p=JSONObject(v.body)
        check(v.operational=="BOZZA"){"Visita già completata: evento non allegabile"}
        check(p.getInt("revision")==1){"Le evidenze originarie non possono essere sostituite in revisione"}
        p.getJSONArray("events").put(event);val next=v.copy(body=p.toString());dao.save(next);next
    }
    suspend fun complete(id:String,body:JSONObject,status:String):Visit{
        requireOffline();val account=owner();val current=dao.visit(id,account)?:error("Bozza assente")
        check(current.operational=="BOZZA")
        body.put("events",JSONObject(current.body).getJSONArray("events"))
        val sheet=body.getJSONObject("sheet");check(!sheet.isNull("accessible")&&!sheet.isNull("opened")){"Specificare accesso e apertura"}
        if(!sheet.getBoolean("opened"))check(sheet.getString("no_open_reason").isNotBlank()){"Motivare la mancata apertura"}
        if(sheet.getBoolean("unsafe")||!sheet.getBoolean("accessible"))check(status=="IMPEDITO"){"Selezionare impedito"}
        if(status=="COMPLETO")check(sheet.getBoolean("opened")&&observationKeys.all{sheet.getString(it)!="NON_VERIFICATO"}){"Completare le osservazioni o salvare come parziale"}
        if(observationKeys.any{sheet.getString(it)=="ANOMALO"})check(sheet.getString("anomaly_note").isNotBlank()){"Descrivere l'anomalia"}
        val events=body.getJSONArray("events");check(events.length()>0){"Rilevare la posizione; anche l'assenza viene registrata"}
        if(events.getJSONObject(events.length()-1).getJSONObject("local_evaluation").getString("state")!="COMPATIBILE")check(sheet.getString("exception_reason").isNotBlank()){"Motivare l'eccezione GPS"}
        if(body.getInt("revision")>1)check(body.getString("revision_reason").isNotBlank()){"Motivare la revisione"}
        body.put("status",status)
        if(body.getInt("revision")==1)body.put("completed_at",Instant.now().toString())else body.put("revised_at",Instant.now().toString())
        val operation=JSONObject().put("operation_id",UUID.randomUUID().toString()).put("inspection",body)
        val next=current.copy(body=body.toString(),operational=status,sync=if(BuildConfig.DEMO)"DEMO_LOCALE" else "IN_ATTESA",error=null)
        db.withTransaction{dao.save(next);if(!BuildConfig.DEMO)dao.enqueue(Pending(operation.getString("operation_id"),account,id,body.getInt("revision"),operation.toString()))}
        syncNow();return next
    }
    suspend fun revise(id:String):Visit{
        requireOffline();val account=owner();val old=dao.visit(id,account)?:error("Controllo assente")
        check((old.sync=="RICEVUTO_SERVER"||(BuildConfig.DEMO&&old.sync=="DEMO_LOCALE"))&&dao.pendingVisit(id).isEmpty()){"Sincronizzare la revisione precedente prima della correzione"}
        dao.setting(Setting(account,"revision:$id:${JSONObject(old.body).getInt("revision")}",old.body))
        dao.setting(Setting(account,"photo-revision:$id:${JSONObject(old.body).getInt("revision")}",JSONArray(PhotoRepository(this).list(old)).toString()))
        val p=JSONObject(old.body);p.put("revision",p.getInt("revision")+1).put("revision_reason","")
        val draft=old.copy(body=p.toString(),operational="BOZZA",sync="SALVATO_LOCALMENTE",receipt=null);dao.save(draft);return draft
    }
    suspend fun sync():Boolean=syncLock.withLock{syncInternal()}
    private suspend fun syncInternal():Boolean{
        if(BuildConfig.DEMO)return true
        val account=owner()
        for(op in dao.pending(account)){
            if(owner()!=account)return false
            val visit=dao.visit(op.visitId,account)?:continue
            dao.save(visit.copy(sync="INVIO_IN_CORSO",error=null))
            try{
                val receipt=JSONObject(String(api.request("/api/sync",JSONObject(op.body),account)))
                check(receipt.getString("operation_id")==op.operationId&&receipt.getInt("revision")==op.revision){"Ricevuta non corrispondente"}
                db.withTransaction{dao.save(visit.copy(sync="RICEVUTO_SERVER",receipt=receipt.toString(),error=null));dao.acknowledge(op.operationId)}
            }catch(e:Exception){
                val authentication=e is ApiError && e.code==401
                val terminal=e is ApiError && e.code in listOf(403,409,422)
                dao.updatePending(op.copy(state=if(authentication)"AUTH_REQUIRED" else if(terminal)"CONFLICT" else "IN_ATTESA",error=e.message))
                dao.save(visit.copy(sync="ERRORE",error=e.message))
                if(authentication){dao.pauseAuth(account);return true}
                if(terminal)continue
                return false
            }
        }
        return true
    }
    fun syncNow(){if(BuildConfig.DEMO)return;WorkManager.getInstance(context).enqueueUniqueWork("send-current-account",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())}
    fun schedule(){if(BuildConfig.DEMO)return;WorkManager.getInstance(context).enqueueUniquePeriodicWork("periodic-sync",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build());syncNow()}
    companion object{
        val observationKeys=listOf("cover","deposits","flow","walls","damage","closure","restored")
        fun defaultSheet():JSONObject=JSONObject().apply{
            put("accessible",true);put("opened",true);put("cleaning",false)
            put("unsafe",false);put("map_position_wrong",false)
            observationKeys.forEach{put(it,"REGOLARE")}
            listOf("no_open_reason","anomaly_note","technical_value","notes","exception_reason").forEach{put(it,"")}
            put("priority","MEDIA");put("technical_origin","NON_NOTO")
        }
    }
}

class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result{
        val repo=(applicationContext as PilotApplication).repository
        if(repo.store.get()==null)return Result.success()
        return try{if(repo.sync())Result.success()else Result.retry()}catch(e:Exception){Result.retry()}
    }
}
