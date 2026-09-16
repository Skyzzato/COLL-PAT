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
class Api(private val store:SessionStore){
    private val client=OkHttpClient.Builder().connectTimeout(20,java.util.concurrent.TimeUnit.SECONDS).readTimeout(60,java.util.concurrent.TimeUnit.SECONDS).build()
    companion object{val refreshLock=Mutex()}
    suspend fun raw(base:String,path:String,token:String?=null,body:JSONObject?=null):ByteArray=withContext(Dispatchers.IO){
        require(base.startsWith("https://")||(BuildConfig.DEBUG&&base.startsWith("http://"))){"HTTPS richiesto"}
        val request=Request.Builder().url(base.trimEnd('/')+path)
        if(token!=null)request.header("Authorization","Bearer $token")
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(request.build()).execute().use{r->
            val data=r.body?.bytes()?:byteArrayOf()
            if(!r.isSuccessful)throw ApiError(r.code,String(data).take(1000))
            data
        }
    }
    suspend fun login(base:String,user:String,password:String):JSONObject{
        val result=JSONObject(String(raw(base,"/api/login",body=JSONObject().put("username",user).put("password",password).put("device_id",store.deviceId))))
        result.put("base",base.trimEnd('/'));store.save(result);return result
    }
    suspend fun request(path:String,body:JSONObject?=null,expectedOwner:String?=null):ByteArray{
        val original=store.get()?:throw ApiError(401,"Accedi per inviare")
        val owner=original.getString("base")+"#"+original.getString("user_id")
        if(expectedOwner!=null&&expectedOwner!=owner)throw ApiError(401,"Account cambiato: invio sospeso")
        try{return raw(original.getString("base"),path,original.getString("access_token"),body)}catch(e:ApiError){
            if(e.code!=401)throw e
            return refreshLock.withLock{
                val current=store.get()?:throw e
                if(current.getString("user_id")!=original.getString("user_id")||current.getString("base")!=original.getString("base"))throw e
                val next=JSONObject(String(raw(current.getString("base"),"/api/refresh",body=JSONObject().put("refresh_token",current.getString("refresh_token")))))
                next.put("refresh_token",current.getString("refresh_token")).put("base",current.getString("base"))
                store.save(next)
                raw(next.getString("base"),path,next.getString("access_token"),body)
            }
        }
    }
}
