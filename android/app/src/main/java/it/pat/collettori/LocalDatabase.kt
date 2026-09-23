package it.pat.collettori

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SimpleSQLiteQuery

// CursorWindow is commonly 2 MiB, regardless of the permitted JSON upload size.
// Keep normal rows in one query; fetch oversized text as bounded byte slices.
private const val TEXT_SLICE=262144
private const val PENDING_COLUMNS="operationId,owner,visitId,revision,substr(body,1,262144) AS body,state,error,project,generation,payloadVersion,kind"
private const val PACKAGE_COLUMNS="owner,id,area,substr(body,1,262144) AS body,checksum,bytes,installedAt,baseReady"
private const val CATALOG_COLUMNS="owner,id,kind,substr(body,1,262144) AS body,sync"

@Entity(tableName="visits", primaryKeys=["owner","id"],indices=[Index("owner"),Index("manholeId")])
data class Visit(val id:String,val owner:String,val manholeId:String,val datasetId:String,val body:String,val operational:String="BOZZA",val sync:String="SALVATO_LOCALMENTE",val receipt:String?=null,val error:String?=null)
@Entity(tableName="outbox", indices=[Index("owner"),Index(value=["owner","visitId","revision"],unique=true)])
data class Pending(@PrimaryKey val operationId:String,val owner:String,val visitId:String,val revision:Int,val body:String,val state:String="IN_ATTESA",val error:String?=null,
    @ColumnInfo(defaultValue="'legacy'") val project:String="legacy", @ColumnInfo(defaultValue="-1") val generation:Long=-1,
    @ColumnInfo(defaultValue="1") val payloadVersion:Int=1, @ColumnInfo(defaultValue="'inspection'") val kind:String="inspection")
@Entity(tableName="packages",primaryKeys=["owner","id"],indices=[Index("owner"),Index("area")])
data class OfflinePackage(val owner:String,val id:String,val area:String,val body:String,val checksum:String,val bytes:Long,val installedAt:String,val baseReady:Boolean)
@Entity(tableName="settings",primaryKeys=["owner","key"])
data class Setting(val owner:String,val key:String,val value:String)

@Entity(tableName="catalog",primaryKeys=["owner","id"],indices=[Index(value=["owner","kind"])])
data class CatalogItem(val owner:String,val id:String,val kind:String,val body:String,val sync:String="IN_ATTESA")
@Entity(tableName="audit",indices=[Index("owner"),Index("visitId")])
data class Audit(@PrimaryKey val id:String,val owner:String,val visitId:String,val eventId:String?,val author:String,val at:String,val reason:String,val original:String)
@Entity(tableName="imports",indices=[Index("owner")])
data class ImportRecord(@PrimaryKey val id:String,val owner:String,val source:String,val hash:String,val mapping:String,val report:String,val at:String,val state:String)

@Dao
interface PilotDao {
    @RawQuery suspend fun textPart(query:SupportSQLiteQuery):ByteArray?
    suspend fun largeText(table:String,column:String,where:String,args:List<Any>,preview:String?=null):String? {
        if(preview!=null&&preview.length<TEXT_SLICE)return preview
        val output=java.io.ByteArrayOutputStream();var offset=1
        while(true){
            val part=textPart(SimpleSQLiteQuery("SELECT substr(CAST($column AS BLOB),?,262144) FROM $table WHERE $where",(listOf(offset)+args).toTypedArray()))?:return null
            output.write(part);if(part.size<TEXT_SLICE)return output.toString(Charsets.UTF_8.name())
            offset+=part.size
        }
    }
    @Transaction suspend fun completeQueue(rows:List<Pending>)=rows.mapNotNull{row->largeText("outbox","body","operationId=?",listOf(row.operationId),row.body)?.let{row.copy(body=it)}}
    @Transaction suspend fun completePacks(rows:List<OfflinePackage>)=rows.mapNotNull{row->largeText("packages","body","owner=? AND id=?",listOf(row.owner,row.id),row.body)?.let{row.copy(body=it)}}
    @Transaction suspend fun completeCatalog(rows:List<CatalogItem>)=rows.mapNotNull{row->largeText("catalog","body","owner=? AND id=?",listOf(row.owner,row.id),row.body)?.let{row.copy(body=it)}}
    @Transaction suspend fun completeSettings(rows:List<Setting>)=rows.mapNotNull{row->largeText("settings","value","owner=? AND `key`=?",listOf(row.owner,row.key),row.value)?.let{row.copy(value=it)}}
    @Query("SELECT * FROM visits WHERE owner=:owner ORDER BY rowid DESC") fun visits(owner:String):Flow<List<Visit>>
    @Query("SELECT * FROM visits WHERE owner=:owner ORDER BY rowid DESC") suspend fun visitsNow(owner:String):List<Visit>
    @Query("SELECT * FROM visits WHERE id=:id AND owner=:owner") suspend fun visit(id:String,owner:String):Visit?
    @Upsert suspend fun save(visit:Visit)
    @Query("SELECT "+PENDING_COLUMNS+" FROM outbox WHERE owner=:owner AND state IN ('IN_ATTESA','IN_CORSO') ORDER BY CASE WHEN kind IN ('catalog','catalog_chunk') THEN 0 WHEN kind='catalog_delete' THEN 2 ELSE 1 END, rowid") suspend fun pendingRows(owner:String):List<Pending>
    @Transaction suspend fun pending(owner:String)=completeQueue(pendingRows(owner))
    @Query("UPDATE outbox SET state='IN_ATTESA', error=NULL WHERE owner=:owner AND state='AUTH_REQUIRED' AND payloadVersion=2 AND generation>=0") suspend fun resumeAuth(owner:String)
    @Query("UPDATE outbox SET state='AUTH_REQUIRED' WHERE owner=:owner AND state IN ('IN_ATTESA','IN_CORSO')") suspend fun pauseAuth(owner:String)
    @Query("UPDATE outbox SET state='IN_ATTESA', error=NULL WHERE owner=:owner AND state IN ('AUTH_REQUIRED','IN_CORSO') AND payloadVersion=2 AND generation>=0") suspend fun retryBlocked(owner:String)
    @Query("SELECT "+PENDING_COLUMNS+" FROM outbox WHERE owner=:owner ORDER BY rowid") suspend fun allPendingRows(owner:String):List<Pending>
    @Transaction suspend fun allPending(owner:String)=completeQueue(allPendingRows(owner))
    @Query("SELECT "+PENDING_COLUMNS+" FROM outbox WHERE owner=:owner ORDER BY rowid") fun outboxRows(owner:String):Flow<List<Pending>>
    fun outbox(owner:String):Flow<List<Pending>> = outboxRows(owner).map{completeQueue(it)}
    @Query("SELECT "+PENDING_COLUMNS+" FROM outbox WHERE visitId=:id AND owner=:owner") suspend fun pendingVisitRows(id:String,owner:String):List<Pending>
    @Transaction suspend fun pendingVisit(id:String,owner:String)=completeQueue(pendingVisitRows(id,owner))
    @Insert suspend fun enqueue(pending:Pending)
    @Upsert suspend fun updatePending(pending:Pending)
    @Query("DELETE FROM outbox WHERE operationId=:id") suspend fun acknowledge(id:String)
    @Query("SELECT "+PACKAGE_COLUMNS+" FROM packages WHERE owner=:owner ORDER BY installedAt DESC") fun packageRows(owner:String):Flow<List<OfflinePackage>>
    fun packages(owner:String):Flow<List<OfflinePackage>> = packageRows(owner).map{completePacks(it)}
    @Query("SELECT "+PACKAGE_COLUMNS+" FROM packages WHERE owner=:owner ORDER BY installedAt DESC") suspend fun packagesNowRows(owner:String):List<OfflinePackage>
    @Transaction suspend fun packagesNow(owner:String)=completePacks(packagesNowRows(owner))
    @Query("SELECT "+PACKAGE_COLUMNS+" FROM packages WHERE owner=:owner AND id=:id") suspend fun packRow(owner:String,id:String):OfflinePackage?
    @Transaction suspend fun pack(owner:String,id:String):OfflinePackage? = packRow(owner,id)?.let{completePacks(listOf(it)).single()}
    @Upsert suspend fun install(pack:OfflinePackage)
    @Upsert suspend fun setting(setting:Setting)
    @Query("SELECT substr(value,1,262144) FROM settings WHERE owner=:owner AND `key`=:key") suspend fun settingPreview(owner:String,key:String):String?
    @Transaction suspend fun settingValue(owner:String,key:String):String? = settingPreview(owner,key)?.let{largeText("settings","value","owner=? AND `key`=?",listOf(owner,key),it)}
    @Query("SELECT value FROM settings WHERE owner=:owner AND `key`=:key") fun settingFlow(owner:String,key:String):Flow<String?>
    @Query("SELECT owner,`key`,substr(value,1,262144) AS value FROM settings WHERE owner=:owner") fun settingRows(owner:String):Flow<List<Setting>>
    fun settings(owner:String):Flow<List<Setting>> = settingRows(owner).map{completeSettings(it)}
    @Query("SELECT owner,`key`,substr(value,1,262144) AS value FROM settings WHERE owner=:owner") suspend fun settingsNowRows(owner:String):List<Setting>
    @Transaction suspend fun settingsNow(owner:String)=completeSettings(settingsNowRows(owner))
    @Query("DELETE FROM settings WHERE owner=:owner AND `key`=:key") suspend fun removeSetting(owner:String,key:String)
    @Query("SELECT "+CATALOG_COLUMNS+" FROM catalog WHERE owner=:owner") fun catalogRows(owner:String):Flow<List<CatalogItem>>
    fun catalog(owner:String):Flow<List<CatalogItem>> = catalogRows(owner).map{completeCatalog(it)}
    @Query("SELECT "+CATALOG_COLUMNS+" FROM catalog WHERE owner=:owner") suspend fun catalogNowRows(owner:String):List<CatalogItem>
    @Transaction suspend fun catalogNow(owner:String)=completeCatalog(catalogNowRows(owner))
    @Upsert suspend fun putCatalog(item:CatalogItem)
    @Query("DELETE FROM catalog WHERE owner=:owner AND id=:id") suspend fun removeCatalog(owner:String,id:String)
    @Insert suspend fun audit(item:Audit)
    @Query("SELECT * FROM audit WHERE owner=:owner AND visitId=:id ORDER BY at") suspend fun audits(owner:String,id:String):List<Audit>
    @Upsert suspend fun saveImport(item:ImportRecord)
    @Query("SELECT * FROM imports WHERE owner=:owner") suspend fun imports(owner:String):List<ImportRecord>
    @Query("DELETE FROM visits WHERE owner=:owner") suspend fun clearVisits(owner:String)
    @Query("DELETE FROM audit WHERE owner=:owner") suspend fun clearAudit(owner:String)
    @Query("DELETE FROM outbox WHERE owner=:owner AND kind NOT IN ('catalog','catalog_chunk','catalog_delete')") suspend fun clearInspectionQueue(owner:String)
    @Query("DELETE FROM settings WHERE owner=:owner AND (`key` LIKE 'photos:%' OR `key` LIKE 'identification:%' OR `key` LIKE 'history:%' OR `key` LIKE 'revision:%' OR `key` LIKE 'photo-revision:%' OR `key` LIKE 'snapshot:%')") suspend fun clearInspectionSettings(owner:String)
}

@Database(entities=[Visit::class,Pending::class,OfflinePackage::class,Setting::class,CatalogItem::class,Audit::class,ImportRecord::class],version=3,exportSchema=true)
abstract class LocalDatabase:RoomDatabase(){abstract fun dao():PilotDao}

val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){
    db.execSQL("ALTER TABLE outbox ADD COLUMN project TEXT NOT NULL DEFAULT 'legacy'")
    db.execSQL("ALTER TABLE outbox ADD COLUMN generation INTEGER NOT NULL DEFAULT -1")
    db.execSQL("ALTER TABLE outbox ADD COLUMN payloadVersion INTEGER NOT NULL DEFAULT 1")
    db.execSQL("ALTER TABLE outbox ADD COLUMN kind TEXT NOT NULL DEFAULT 'inspection'")
    db.execSQL("UPDATE outbox SET state='LEGACY_SUSPENDED', error='Protocollo precedente: esportare e riconciliare; nessun invio automatico'")
    db.execSQL("CREATE TABLE IF NOT EXISTS catalog (owner TEXT NOT NULL, id TEXT NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL, sync TEXT NOT NULL, PRIMARY KEY(owner,id))")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_owner_kind ON catalog(owner,kind)")
    db.execSQL("CREATE TABLE IF NOT EXISTS audit (id TEXT NOT NULL PRIMARY KEY, owner TEXT NOT NULL, visitId TEXT NOT NULL, eventId TEXT, author TEXT NOT NULL, at TEXT NOT NULL, reason TEXT NOT NULL, original TEXT NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_owner ON audit(owner)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_visitId ON audit(visitId)")
    db.execSQL("CREATE TABLE IF NOT EXISTS imports (id TEXT NOT NULL PRIMARY KEY, owner TEXT NOT NULL, source TEXT NOT NULL, hash TEXT NOT NULL, mapping TEXT NOT NULL, report TEXT NOT NULL, at TEXT NOT NULL, state TEXT NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_imports_owner ON imports(owner)")
}}

val MIGRATION_2_3=object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){
    db.execSQL("CREATE TABLE visits_v3 (id TEXT NOT NULL, owner TEXT NOT NULL, manholeId TEXT NOT NULL, datasetId TEXT NOT NULL, body TEXT NOT NULL, operational TEXT NOT NULL, sync TEXT NOT NULL, receipt TEXT, error TEXT, PRIMARY KEY(owner,id))")
    db.execSQL("INSERT INTO visits_v3 SELECT id,owner,manholeId,datasetId,body,operational,sync,receipt,error FROM visits")
    db.execSQL("DROP TABLE visits")
    db.execSQL("ALTER TABLE visits_v3 RENAME TO visits")
    db.execSQL("CREATE INDEX index_visits_owner ON visits(owner)")
    db.execSQL("CREATE INDEX index_visits_manholeId ON visits(manholeId)")
    db.execSQL("DROP INDEX index_outbox_visitId_revision")
    db.execSQL("CREATE UNIQUE INDEX index_outbox_owner_visitId_revision ON outbox(owner,visitId,revision)")
    // A v0.13 submission cannot be silently re-attributed or have GPS thresholds invented.
    db.execSQL("UPDATE outbox SET state='LEGACY_SUSPENDED', error='Invio precedente alla v0.14 conservato: esportare l’archivio per il recupero' WHERE kind='inspection' OR (kind='cancel' AND visitId IN (SELECT visitId FROM outbox WHERE kind='inspection'))")
}}
