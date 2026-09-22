package it.pat.collettori

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName="visits", indices=[Index("owner"),Index("manholeId")])
data class Visit(@PrimaryKey val id:String,val owner:String,val manholeId:String,val datasetId:String,val body:String,val operational:String="BOZZA",val sync:String="SALVATO_LOCALMENTE",val receipt:String?=null,val error:String?=null)
@Entity(tableName="outbox", indices=[Index("owner"),Index(value=["visitId","revision"],unique=true)])
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
    @Query("SELECT * FROM visits WHERE owner=:owner ORDER BY rowid DESC") fun visits(owner:String):Flow<List<Visit>>
    @Query("SELECT * FROM visits WHERE owner=:owner ORDER BY rowid DESC") suspend fun visitsNow(owner:String):List<Visit>
    @Query("SELECT * FROM visits WHERE id=:id AND owner=:owner") suspend fun visit(id:String,owner:String):Visit?
    @Upsert suspend fun save(visit:Visit)
    @Query("SELECT * FROM outbox WHERE owner=:owner AND state IN ('IN_ATTESA','IN_CORSO') ORDER BY CASE WHEN kind IN ('catalog','catalog_chunk') THEN 0 ELSE 1 END, rowid") suspend fun pending(owner:String):List<Pending>
    @Query("UPDATE outbox SET state='IN_ATTESA', error=NULL WHERE owner=:owner AND state='AUTH_REQUIRED' AND payloadVersion=2 AND generation>=0") suspend fun resumeAuth(owner:String)
    @Query("UPDATE outbox SET state='AUTH_REQUIRED' WHERE owner=:owner AND state IN ('IN_ATTESA','IN_CORSO')") suspend fun pauseAuth(owner:String)
    @Query("UPDATE outbox SET state='IN_ATTESA', error=NULL WHERE owner=:owner AND state IN ('AUTH_REQUIRED','IN_CORSO') AND payloadVersion=2 AND generation>=0") suspend fun retryBlocked(owner:String)
    @Query("SELECT * FROM outbox WHERE owner=:owner ORDER BY rowid") suspend fun allPending(owner:String):List<Pending>
    @Query("SELECT * FROM outbox WHERE owner=:owner ORDER BY rowid") fun outbox(owner:String):Flow<List<Pending>>
    @Query("SELECT * FROM outbox WHERE visitId=:id") suspend fun pendingVisit(id:String):List<Pending>
    @Insert suspend fun enqueue(pending:Pending)
    @Upsert suspend fun updatePending(pending:Pending)
    @Query("DELETE FROM outbox WHERE operationId=:id") suspend fun acknowledge(id:String)
    @Query("SELECT * FROM packages WHERE owner=:owner ORDER BY installedAt DESC") fun packages(owner:String):Flow<List<OfflinePackage>>
    @Query("SELECT * FROM packages WHERE owner=:owner ORDER BY installedAt DESC") suspend fun packagesNow(owner:String):List<OfflinePackage>
    @Query("SELECT * FROM packages WHERE owner=:owner AND id=:id") suspend fun pack(owner:String,id:String):OfflinePackage?
    @Upsert suspend fun install(pack:OfflinePackage)
    @Upsert suspend fun setting(setting:Setting)
    @Query("SELECT value FROM settings WHERE owner=:owner AND `key`=:key") suspend fun settingValue(owner:String,key:String):String?
    @Query("SELECT * FROM catalog WHERE owner=:owner") fun catalog(owner:String):Flow<List<CatalogItem>>
    @Query("SELECT * FROM catalog WHERE owner=:owner") suspend fun catalogNow(owner:String):List<CatalogItem>
    @Upsert suspend fun putCatalog(item:CatalogItem)
    @Insert suspend fun audit(item:Audit)
    @Query("SELECT * FROM audit WHERE owner=:owner AND visitId=:id ORDER BY at") suspend fun audits(owner:String,id:String):List<Audit>
    @Upsert suspend fun saveImport(item:ImportRecord)
    @Query("SELECT * FROM imports WHERE owner=:owner") suspend fun imports(owner:String):List<ImportRecord>
    @Query("DELETE FROM visits WHERE owner=:owner") suspend fun clearVisits(owner:String)
    @Query("DELETE FROM audit WHERE owner=:owner") suspend fun clearAudit(owner:String)
    @Query("DELETE FROM outbox WHERE owner=:owner AND kind NOT IN ('catalog','catalog_chunk')") suspend fun clearInspectionQueue(owner:String)
    @Query("DELETE FROM settings WHERE owner=:owner AND (`key` LIKE 'photos:%' OR `key` LIKE 'identification:%' OR `key` LIKE 'history:%' OR `key` LIKE 'revision:%' OR `key` LIKE 'photo-revision:%' OR `key` LIKE 'snapshot:%')") suspend fun clearInspectionSettings(owner:String)
}

@Database(entities=[Visit::class,Pending::class,OfflinePackage::class,Setting::class,CatalogItem::class,Audit::class,ImportRecord::class],version=2,exportSchema=true)
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
