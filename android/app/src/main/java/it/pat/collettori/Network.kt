package it.pat.collettori

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ApiError(val code:Int,message:String):IOException(message)
fun sessionOwner(s:JSONObject)=s.getString("base")+"#"+s.getString("user_id")+"#"+s.optString("project_id",AppSpec.LOCAL_PROJECT)
class Api(private val store:SessionStore){
    private val client=OkHttpClient.Builder().connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS).readTimeout(45,java.util.concurrent.TimeUnit.SECONDS).build()
    companion object{val refreshLock=Mutex()}
    suspend fun raw(base:String,path:String,key:String,token:String?=null,body:JSONObject?=null):ByteArray=withContext(Dispatchers.IO){
        require(base.startsWith("https://")){"URL HTTPS Supabase richiesta"}
        require(key.isNotBlank()&&!key.startsWith("sb_secret_")){"Usare soltanto la chiave pubblica publishable/anon"}
        if(key.count{it=='.'}==2){
            val claims=runCatching{JSONObject(String(android.util.Base64.decode(key.split('.')[1],android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)))}.getOrNull()
            require(claims?.optString("role")=="anon"){"Chiave JWT non pubblica: usare anon o publishable"}
        }
        val request=Request.Builder().url(base.trimEnd('/')+path).header("apikey",key).header("User-Agent","${AppSpec.NAME}/${AppSpec.version}")
        if(token!=null)request.header("Authorization","Bearer $token")
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(request.build()).execute().use{r->
            val data=r.body?.bytes()?:byteArrayOf()
            if(!r.isSuccessful){val error=runCatching{JSONObject(String(data))}.getOrNull();throw ApiError(r.code,error?.optString("message",error.optString("error_description","Richiesta rifiutata"))?.take(500)?:"HTTP ${r.code}")}
            data
        }
    }
    suspend fun login(base:String,key:String,email:String,password:String,project:String):JSONObject{
        java.util.UUID.fromString(project)
        val result=JSONObject(String(raw(base,"/auth/v1/token?grant_type=password",key,body=JSONObject().put("email",email.trim()).put("password",password))))
        val session=result.put("base",base.trimEnd('/')).put("public_key",key).put("project_id",project)
            .put("user_id",result.getJSONObject("user").getString("id")).put("username",email).put("protocol",AppSpec.PROTOCOL)
        val status=JSONObject(String(raw(base,"/rest/v1/rpc/coll_pat_status",key,result.getString("access_token"),JSONObject().put("p_project",project))))
        session.put("generation",status.getLong("generation")).put("role",status.getString("role"))
        store.save(session);return session
    }
    suspend fun rpc(name:String,body:JSONObject,expectedOwner:String?=null):JSONObject=JSONObject(String(request("/rest/v1/rpc/$name",body,expectedOwner)))
    suspend fun request(path:String,body:JSONObject?=null,expectedOwner:String?=null):ByteArray{
        require(path.startsWith("/rest/v1/rpc/coll_pat_")){"Endpoint legacy non supportato dal protocollo v0.13"}
        val original=store.get()?:throw ApiError(401,"Accedi in Account per inviare")
        val account=sessionOwner(original)
        if(!original.has("access_token")||original.optInt("protocol")!=AppSpec.PROTOCOL)throw ApiError(401,"Sessione Supabase Auth richiesta; gli account legacy non sono account Auth")
        if(expectedOwner!=null&&expectedOwner!=account)throw ApiError(401,"Account/progetto cambiato: invio sospeso")
        try{return raw(original.getString("base"),path,original.getString("public_key"),original.getString("access_token"),body)}catch(e:ApiError){
            if(e.code!=401)throw e
            return refreshLock.withLock{
                val current=store.get()?:throw e
                if(sessionOwner(current)!=account)throw e
                val next=if(current.getString("access_token")!=original.getString("access_token")) current else {
                    val refreshed=JSONObject(String(raw(current.getString("base"),"/auth/v1/token?grant_type=refresh_token",current.getString("public_key"),body=JSONObject().put("refresh_token",current.getString("refresh_token")))))
                    current.put("access_token",refreshed.getString("access_token")).put("refresh_token",refreshed.getString("refresh_token")).also{store.save(it)}
                }
                raw(next.getString("base"),path,next.getString("public_key"),next.getString("access_token"),body)
            }
        }
    }
}
