package it.pat.collettori
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPersistenceTest {
    @Test fun restartAndAccountIsolation()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="persistence-test.db";context.deleteDatabase(name)
        val first=Room.databaseBuilder(context,LocalDatabase::class.java,name).build()
        first.withTransaction{
            first.dao().save(Visit("visit-a","alice","point","dataset","{}"))
            first.dao().enqueue(Pending("op-a","alice","visit-a",1,"{}"))
        }
        first.close()
        val reopened=Room.databaseBuilder(context,LocalDatabase::class.java,name).build()
        assertEquals("visit-a",reopened.dao().visitsNow("alice").single().id)
        assertTrue(reopened.dao().visitsNow("bob").isEmpty())
        assertTrue(reopened.dao().pending("bob").isEmpty())
        assertEquals("op-a",reopened.dao().pending("alice").single().operationId)
        reopened.dao().install(OfflinePackage("alice","new","area","{}","hash",2,"2026-01-01",false))
        assertNotNull(reopened.dao().visit("visit-a","alice"))
        reopened.close();context.deleteDatabase(name);Unit
    }
    @Test fun transactionRollbackPreservesDraft()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,LocalDatabase::class.java).build()
        db.dao().save(Visit("id","user","point","old","{draft}"))
        try{db.withTransaction{db.dao().save(Visit("id","user","point","old","{changed}"));error("interrupted")}}catch(_:IllegalStateException){}
        assertEquals("{draft}",db.dao().visit("id","user")!!.body)
        db.close()
    }
}
