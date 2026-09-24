package it.pat.collettori

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class InspectionPhoto(val photoId:String,val inspectionId:String,val manholeId:String,val localUri:String,val remoteUrl:String?=null,val uploadStatus:String="LOCAL_ONLY",val createdAt:String=Instant.now().toString()) {
    fun json()=JSONObject().put("photoId",photoId).put("inspectionId",inspectionId).put("manholeId",manholeId).put("localUri",localUri).put("remoteUrl",remoteUrl?:JSONObject.NULL).put("uploadStatus",uploadStatus).put("createdAt",createdAt)
}
class PhotoRepository(private val repo:Repository) {
    fun newCapture():Uri {
        val directory=File(repo.context.filesDir,"photos").apply{mkdirs()}
        return FileProvider.getUriForFile(repo.context,repo.context.packageName+".photos",File(directory,UUID.randomUUID().toString()+".jpg"))
    }
    suspend fun list(visit:Visit):List<JSONObject> = repo.dao.settingValue(visit.owner,"photos:"+visit.id)?.let{JSONArray(it).objects()}?:emptyList()
    suspend fun attach(visit:Visit,source:Uri,copy:Boolean):Unit=withContext(Dispatchers.IO) {
        repo.requireWrite()
        val uri=if(copy)newCapture() else source
        try {
            if(copy)repo.context.contentResolver.openInputStream(source).use{input->
                requireNotNull(input){"Foto non leggibile"}
                repo.context.contentResolver.openOutputStream(uri).use{out->input.copyTo(requireNotNull(out))}
            }
            val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            repo.context.contentResolver.openInputStream(uri).use{android.graphics.BitmapFactory.decodeStream(it,null,bounds)}
            check(bounds.outWidth>0 && bounds.outHeight>0){"Immagine non valida"}
            repo.changePhotos(visit){photos->photos+InspectionPhoto(UUID.randomUUID().toString(),visit.id,visit.manholeId,uri.toString()).json().put("createdBy",repo.store.get()!!.getString("user_id"))}

        }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;repo.context.contentResolver.delete(uri,null,null);throw e}
    }
    suspend fun remove(visit:Visit,photo:JSONObject) {
        repo.requireWrite()
        repo.changePhotos(visit){photos->photos.filter{it.getString("photoId")!=photo.getString("photoId")}}
        // Preserve the file for any conserved audit revision referencing it.
    }

    fun path(project:String,inspection:String,id:String)="$project/$inspection/$id.jpg"
    suspend fun mergeRemote(account:String,id:String,remote:JSONArray){
        val local=repo.dao.settingValue(account,"photos:$id")?.let{JSONArray(it).objects()}.orEmpty().associateBy{it.getString("photoId")}
        val combined=remote.objects().map{r->
            val pid=r.getString("id");val p=local[pid]?.let{JSONObject(it.toString())}?:JSONObject().put("photoId",pid).put("inspectionId",id).put("localUri","")
            p.put("createdAt",r.getString("created_at")).put("createdBy",r.getString("created_by")).put("storagePath",r.getString("storage_path")).put("uploadStatus",if(r.optBoolean("uploaded"))"UPLOADED" else "PENDING")
        }.toMutableList()
        // New photos not present in the downloaded snapshot are preserved for the next local edit.
        combined.addAll(local.values.filter{p->combined.none{it.getString("photoId")==p.getString("photoId")}&&p.optString("storagePath").isBlank()})
        repo.dao.setting(Setting(account,"photos:$id",JSONArray(combined).toString()))
    }
    suspend fun pendingCount(account:String)=repo.dao.visitsNow(account).sumOf{v->list(v).count{it.optString("uploadStatus")!="UPLOADED"&&it.optString("localUri").isNotBlank()}}
    suspend fun syncAll(account:String){
        for(v in repo.dao.visitsNow(account).filter{it.sync=="RICEVUTO_SERVER"&&!isCancelled(it)})for(p in list(v)){
            if(p.optString("uploadStatus") in setOf("UPLOADED","BLOCKED")||p.optString("localUri").isBlank())continue
            try{
            update(v,p,"IN_CORSO")
            val objectPath=path(repo.project(),v.id,p.getString("photoId"))
            val data=withContext(Dispatchers.IO){
                val uri=Uri.parse(p.getString("localUri"));val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                repo.context.contentResolver.openInputStream(uri).use{android.graphics.BitmapFactory.decodeStream(it,null,bounds)}
                val options=android.graphics.BitmapFactory.Options().apply{inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/2400).coerceAtLeast(1)}
                repo.context.contentResolver.openInputStream(uri)?.use{input->
                val bitmap=android.graphics.BitmapFactory.decodeStream(input,null,options)?:error("Foto non leggibile")
                val scaled=if(maxOf(bitmap.width,bitmap.height)>2400){val ratio=2400.0/maxOf(bitmap.width,bitmap.height);android.graphics.Bitmap.createScaledBitmap(bitmap,(bitmap.width*ratio).toInt(),(bitmap.height*ratio).toInt(),true)}else bitmap
                val out=java.io.ByteArrayOutputStream();check(scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out));if(scaled!==bitmap)scaled.recycle();bitmap.recycle();out.toByteArray()
            }?:error("Foto non disponibile sul telefono")}
            check(data.size<=6*1024*1024){"Foto troppo grande: ridurre l’immagine a meno di 6 MB"}
            try{repo.api.request("/storage/v1/object/coll-pat-photos/$objectPath",expectedOwner=account,bytes=data)}catch(e:ApiError){if(e.code!=409&&!(e.code==400&&e.message?.contains("already exists",true)==true))throw e}
            update(v,p,"UPLOADED_UNCONFIRMED")
            repo.api.rpc("coll_pat_photo_uploaded",JSONObject().put("p_project",repo.project()).put("p_inspection",v.id).put("p_id",p.getString("photoId")),account)
            repo.db.withTransaction{val current=list(v);current.find{it.getString("photoId")==p.getString("photoId")}?.put("storagePath",objectPath)?.put("uploadStatus","UPLOADED");repo.dao.setting(Setting(account,"photos:"+v.id,JSONArray(current).toString()))}
            }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;update(v,p,if(e is ApiError&&!e.retryable&&e.code!=401)"BLOCKED" else if(e is ApiError&&e.code==401)"AUTH_REQUIRED" else "PENDING",friendlyError(e))}

        }
    }
    private suspend fun update(v:Visit,p:JSONObject,state:String,error:String?=null){repo.db.withTransaction{
        val current=list(v);current.find{it.getString("photoId")==p.getString("photoId")}?.put("uploadStatus",state)?.put("lastAttempt",Instant.now().toString())?.put("error",error?:JSONObject.NULL)
        repo.dao.setting(Setting(v.owner,"photos:"+v.id,JSONArray(current).toString()))
    }}
    suspend fun localUri(visit:Visit,photo:JSONObject):Uri?=withContext(Dispatchers.IO){
        if(photo.optString("localUri").isNotBlank())return@withContext Uri.parse(photo.getString("localUri"))
        if(photo.optString("uploadStatus")!="UPLOADED"||!repo.authenticated())return@withContext null
        val objectPath=photo.getString("storagePath");require(objectPath==path(repo.project(),visit.id,photo.getString("photoId")))
        val bytes=repo.api.request("/storage/v1/object/authenticated/coll-pat-photos/$objectPath",expectedOwner=visit.owner)
        val uri=newCapture();repo.context.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}
        repo.db.withTransaction{val current=list(visit);current.find{it.getString("photoId")==photo.getString("photoId")}?.put("localUri",uri.toString());repo.dao.setting(Setting(visit.owner,"photos:"+visit.id,JSONArray(current).toString()))};uri
    }
}
