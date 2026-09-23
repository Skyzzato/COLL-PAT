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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiError(val code:Int,message:String,val serverCode:String="",val endpoint:String=""):IOException(message){
    val retryable get()=code==408||code==429||code>=500
    // Diagnostic identifiers only: server messages/details can contain private row values.
    fun diagnostic()="HTTP $code"+(serverCode.takeIf{it.matches(Regex("[A-Za-z0-9_]+"))}?.let{" · $it"}?:"")+(endpoint.takeIf{it.startsWith("/rest/v1/rpc/coll_pat_")}?.let{" · $it"}?:"")
}
fun sessionOwner(s:JSONObject)=s.getString("base")+"#"+s.getString("user_id")+"#"+s.optString("project_id",AppSpec.LOCAL_PROJECT)
class Api(private val store:SessionStore,private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS).readTimeout(45,java.util.concurrent.TimeUnit.SECONDS).build()){
    companion object{val refreshLock=Mutex()}
    suspend fun raw(base:String,path:String,key:String,token:String?=null,body:JSONObject?=null,bytes:ByteArray?=null,delete:Boolean=false):ByteArray=withContext(Dispatchers.IO){
        require(base.startsWith("https://")){"URL HTTPS Supabase richiesta"}
        require(key.startsWith("sb_publishable_")){"Configurare SUPABASE_PUBLISHABLE_KEY con la chiave pubblica del progetto"}
        val request=Request.Builder().url(base.trimEnd('/')+path).header("apikey",key).header("User-Agent","${AppSpec.NAME}/${AppSpec.version}")
        if(token!=null)request.header("Authorization","Bearer $token")
        if(body!=null){val payload=body.toString().toRequestBody("application/json".toMediaType());if(delete)request.delete(payload)else request.post(payload)}
        request.header("X-Coll-Pat-Version",BuildConfig.VERSION_NAME.removeSuffix("-demo"))
        if(bytes!=null)request.header("x-upsert","false").post(bytes.toRequestBody("image/jpeg".toMediaType()))
        suspendCancellableCoroutine{continuation->
            val call=client.newCall(request.build());continuation.invokeOnCancellation{call.cancel()}
            call.enqueue(object:Callback{
                override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
                override fun onResponse(call:Call,response:Response){response.use{r->try{
                    val data=r.body?.bytes()?:byteArrayOf()
                    if(!r.isSuccessful){val error=runCatching{JSONObject(String(data))}.getOrNull();throw ApiError(r.code,error?.optString("message",error.optString("error_description","Richiesta rifiutata"))?.take(500)?:"Richiesta rifiutata",error?.optString("code").orEmpty(),path.substringBefore('?'))}
                    if(continuation.isActive)continuation.resume(data)
                }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}}}
            })
        }
    }
    suspend fun deleteStorage(path:String,account:String){request("/storage/v1/object/coll-pat-photos",JSONObject().put("prefixes",org.json.JSONArray(listOf(path))),account,delete=true)}
    suspend fun register(base:String,key:String,email:String,password:String){
        raw(base,"/auth/v1/signup",key,body=JSONObject().put("email",email.trim()).put("password",password))
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
    suspend fun request(path:String,body:JSONObject?=null,expectedOwner:String?=null,bytes:ByteArray?=null,delete:Boolean=false):ByteArray{
        require(path.startsWith("/rest/v1/rpc/coll_pat_")||path.startsWith("/storage/v1/object/")){"Servizio non disponibile"}
        val original=store.get()?:throw ApiError(401,"Accedi in Account per inviare")
        val account=sessionOwner(original)
        if(!original.has("access_token")||original.optInt("protocol")!=AppSpec.PROTOCOL)throw ApiError(401,"Sessione Supabase Auth richiesta; gli account legacy non sono account Auth")
        if(expectedOwner!=null&&expectedOwner!=account)throw ApiError(401,"Account/progetto cambiato: invio sospeso")
        try{return raw(original.getString("base"),path,original.getString("public_key"),original.getString("access_token"),body,bytes,delete)}catch(e:ApiError){
            if(e.code!=401)throw e
            return refreshLock.withLock{
                val current=store.get()?:throw e
                if(sessionOwner(current)!=account)throw e
                val next=if(current.getString("access_token")!=original.getString("access_token")) current else {
                    val refreshed=JSONObject(String(raw(current.getString("base"),"/auth/v1/token?grant_type=refresh_token",current.getString("public_key"),body=JSONObject().put("refresh_token",current.getString("refresh_token")))))
                    current.put("access_token",refreshed.getString("access_token")).put("refresh_token",refreshed.getString("refresh_token")).also{store.save(it)}
                }
                raw(next.getString("base"),path,next.getString("public_key"),next.getString("access_token"),body,bytes,delete)
            }
        }
    }
}
