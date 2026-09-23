package it.pat.collettori

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType

@RunWith(AndroidJUnit4::class)
class V014PersistenceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun obsoleteVersionUsesCachedPolicyOffline()=runBlocking{
        val name="version-policy-test";context.deleteDatabase("$name.db");var offline=false
        val client=OkHttpClient.Builder().addInterceptor{chain->
            if(offline)throw java.io.IOException("offline test")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test").body("{\"minimum_supported_version\":\"99.0\",\"latest_version\":\"99.0\"}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo=Repository(context,"$name.db",name,client)
        repo.store.save(JSONObject().put("base","https://version.example.test").put("public_key","sb_publishable_test"))
        try{
            try{repo.checkVersion();fail("Obsolete version allowed")}catch(e:ApiError){assertEquals(426,e.code)}
            offline=true
            try{repo.checkVersion();fail("Cached policy ignored")}catch(e:ApiError){assertEquals(426,e.code)}
            assertNotNull(repo.dao.settingValue("version-policy:https://version.example.test","policy"))
        }finally{repo.store.clear();repo.db.close();context.deleteDatabase("$name.db")};Unit
    }
    @Test fun accountScopedSharedDraftsAndSettingsSurviveRestart()=runBlocking{
        val name="v014-account-isolation.db";context.deleteDatabase(name)
        val db=Room.databaseBuilder(context,LocalDatabase::class.java,name).build()
        db.dao().save(Visit("same-draft","alice","p","d","Alice unsent","BOZZA","IN_ATTESA"))
        db.dao().save(Visit("same-draft","bob","p","d","Bob server","BOZZA","RICEVUTO_SERVER"))
        db.dao().enqueue(Pending("op-a","alice","same-draft",1,"{}"));db.dao().enqueue(Pending("op-b","bob","same-draft",1,"{}"))
        val settings=FieldSettings(minZoomPozzetti=16f,iconSize=10f,symbol="RING",asphalt=false,maxDistance=15.0,maxAccuracy=10.0)
        db.dao().setting(Setting("alice","field-settings-v014",settings.json().toString()));db.close()
        val reopened=Room.databaseBuilder(context,LocalDatabase::class.java,name).build()
        assertEquals("Alice unsent",reopened.dao().visit("same-draft","alice")!!.body);assertEquals("Bob server",reopened.dao().visit("same-draft","bob")!!.body)
        assertEquals("op-a",reopened.dao().pendingVisit("same-draft","alice").single().operationId)
        assertEquals(settings,FieldSettings.parse(JSONObject(reopened.dao().settingValue("alice","field-settings-v014")!!)))
        reopened.close();context.deleteDatabase(name);Unit
    }
    @Test fun authContractRegistrationLoginRefreshEncryptedPersistenceLogout()=runBlocking{
        val pref="test-auth-v014";context.getSharedPreferences(pref,0).edit().clear().commit();val store=SessionStore(context,pref)
        val paths=mutableListOf<String>();var refreshCount=0
        val client=OkHttpClient.Builder().addInterceptor{chain->
            val r=chain.request();paths.add(r.url.encodedPath);assertEquals("sb_publishable_synthetic",r.header("apikey"));assertEquals(BuildConfig.VERSION_NAME.removeSuffix("-demo"),r.header("X-Coll-Pat-Version"))
            var code=200;val body=when{
                r.url.encodedPath.endsWith("signup")->"{\"user\":{\"id\":\"$TEST_USER\"}}"
                r.url.query?.contains("refresh_token")==true->{refreshCount++;"{\"access_token\":\"renewed\",\"refresh_token\":\"rotated\"}"}
                r.url.encodedPath.endsWith("token")->"{\"access_token\":\"initial\",\"refresh_token\":\"refresh\",\"user\":{\"id\":\"$TEST_USER\"}}"
                r.url.encodedPath.endsWith("coll_pat_status")->"{\"generation\":0,\"role\":\"inspector\"}"
                r.header("Authorization")=="Bearer initial"->{code=401;"{\"message\":\"expired\"}"}
                else->"{\"ok\":true}"
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api=Api(store,client)
        api.register("https://auth.example.test","sb_publishable_synthetic","operator@example.test","test-password")
        api.login("https://auth.example.test","sb_publishable_synthetic","operator@example.test","test-password",AppSpec.LOCAL_PROJECT)
        val reopened=SessionStore(context,pref);assertEquals(TEST_USER,reopened.get()!!.getString("user_id"));assertFalse(reopened.get()!!.has("password"))
        assertTrue(api.rpc("coll_pat_contract",JSONObject()).getBoolean("ok"));assertEquals(1,refreshCount);assertEquals("rotated",reopened.get()!!.getString("refresh_token"))
        assertTrue(paths.contains("/auth/v1/signup"));store.clear();assertNull(reopened.get());Unit
    }
}
