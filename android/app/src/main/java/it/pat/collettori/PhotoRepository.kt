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
interface RemotePhotoStorage { suspend fun upload(photo:InspectionPhoto):String }
class UnconfiguredPhotoStorage:RemotePhotoStorage {
    override suspend fun upload(photo:InspectionPhoto):String=error("Storage remoto non configurato: foto conservata solo sul telefono")
}
class PhotoRepository(private val repo:Repository) {
    fun newCapture():Uri {
        val directory=File(repo.context.filesDir,"photos").apply{mkdirs()}
        return FileProvider.getUriForFile(repo.context,repo.context.packageName+".photos",File(directory,UUID.randomUUID().toString()+".jpg"))
    }
    suspend fun list(visit:Visit):List<JSONObject> = repo.dao.settingValue(visit.owner,"photos:"+visit.id)?.let{JSONArray(it).objects()}?:emptyList()
    suspend fun attach(visit:Visit,source:Uri,copy:Boolean):Unit=withContext(Dispatchers.IO) {
        val uri=if(copy)newCapture() else source
        try {
            if(copy)repo.context.contentResolver.openInputStream(source).use{input->
                requireNotNull(input){"Foto non leggibile"}
                repo.context.contentResolver.openOutputStream(uri).use{out->input.copyTo(requireNotNull(out))}
            }
            val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            repo.context.contentResolver.openInputStream(uri).use{android.graphics.BitmapFactory.decodeStream(it,null,bounds)}
            check(bounds.outWidth>0 && bounds.outHeight>0){"Immagine non valida"}
            repo.db.withTransaction {
                check(repo.dao.visit(visit.id,visit.owner)?.operational=="BOZZA"){"Ispezione già conclusa"}
                val photos=list(visit).toMutableList()
                photos.add(InspectionPhoto(UUID.randomUUID().toString(),visit.id,visit.manholeId,uri.toString()).json())
                repo.dao.setting(Setting(visit.owner,"photos:"+visit.id,JSONArray(photos).toString()))
            }
        }catch(e:Exception){repo.context.contentResolver.delete(uri,null,null);throw e}
    }
    suspend fun remove(visit:Visit,photo:JSONObject) {
        repo.db.withTransaction {
            check(repo.dao.visit(visit.id,visit.owner)?.operational=="BOZZA")
            repo.dao.setting(Setting(visit.owner,"photos:"+visit.id,JSONArray(list(visit).filter{it.getString("photoId")!=photo.getString("photoId")}).toString()))
        }
        // Preserve physical files for any previously conserved revision referencing them.
    }
}
