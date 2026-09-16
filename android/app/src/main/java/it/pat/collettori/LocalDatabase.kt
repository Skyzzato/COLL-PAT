package it.pat.collettori

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="visits", indices=[Index("owner"),Index("manholeId")])
data class Visit(@PrimaryKey val id:String,val owner:String,val manholeId:String,val datasetId:String,val body:String,val operational:String="BOZZA",val sync:String="SALVATO_LOCALMENTE",val receipt:String?=null,val error:String?=null)
@Entity(tableName="outbox", indices=[Index("owner"),Index(value=["visitId","revision"],unique=true)])
data class Pending(@PrimaryKey val operationId:String,val owner:String,val visitId:String,val revision:Int,val body:String,val state:String="IN_ATTESA",val error:String?=null)
@Entity(tableName="packages",primaryKeys=["owner","id"],indices=[Index("owner"),Index("area")])
data class OfflinePackage(val owner:String,val id:String,val area:String,val body:String,val checksum:String,val bytes:Long,val installedAt:String,val baseReady:Boolean)
@Entity(tableName="settings",primaryKeys=["owner","key"])
data class Setting(val owner:String,val key:String,val value:String)

@Dao
interface PilotDao {
    @Query("SELECT * FROM visits WHERE owner=:owner ORDER BY rowid DESC") fun visits(owner:String):Flow<List<Visit>>
    @Query("SELECT * FROM visits WHERE owner=:owner ORDER BY rowid DESC") suspend fun visitsNow(owner:String):List<Visit>
    @Query("SELECT * FROM visits WHERE id=:id AND owner=:owner") suspend fun visit(id:String,owner:String):Visit?
    @Upsert suspend fun save(visit:Visit)
    @Query("SELECT * FROM outbox WHERE owner=:owner AND state NOT IN ('CONFLICT','AUTH_REQUIRED') ORDER BY rowid") suspend fun pending(owner:String):List<Pending>
    @Query("UPDATE outbox SET state='IN_ATTESA', error=NULL WHERE owner=:owner AND state='AUTH_REQUIRED'") suspend fun resumeAuth(owner:String)
    @Query("UPDATE outbox SET state='AUTH_REQUIRED' WHERE owner=:owner AND state!='CONFLICT'") suspend fun pauseAuth(owner:String)
    @Query("UPDATE outbox SET state='IN_ATTESA', error=NULL WHERE owner=:owner") suspend fun retryBlocked(owner:String)
    @Query("SELECT * FROM outbox WHERE owner=:owner ORDER BY rowid") suspend fun allPending(owner:String):List<Pending>
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
}

@Database(entities=[Visit::class,Pending::class,OfflinePackage::class,Setting::class],version=1,exportSchema=true)
abstract class LocalDatabase:RoomDatabase(){abstract fun dao():PilotDao}
