package it.pat.collettori
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V021PhotoTest{
    @Test fun uploadReceiptFailureRemainsPendingAndRetryConfirmsSamePhoto()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext;var uploads=0;var confirms=0;var width=0
        val paths=mutableListOf<String>()
        val client=OkHttpClient.Builder().addInterceptor{chain->val request=chain.request();val upload=request.url.encodedPath.startsWith("/storage/")
            val code=if(upload){uploads++;paths.add(request.url.encodedPath);val buffer=Buffer();request.body!!.writeTo(buffer);val bytes=buffer.readByteArray();val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options);width=options.outWidth;if(uploads==1)201 else 409}else{confirms++;if(confirms==1)503 else 200}
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("controlled").body("{}".toResponseBody()).build()
        }.build()
        val name="v021-photo-${UUID.randomUUID()}";val r=Repository(context,"$name.db",name,client)
        try{val pack=installTestCatalog(r);val v=r.begin(JSONObject(pack.body).getJSONArray("points").getJSONObject(0),pack,"LIST");val photos=PhotoRepository(r);val uri=photos.newCapture()
            val bitmap=android.graphics.Bitmap.createBitmap(4000,1000,android.graphics.Bitmap.Config.ARGB_8888);context.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle();photos.attach(v,uri,false)
            r.dao.save(r.dao.visit(v.id,v.owner)!!.copy(sync="RICEVUTO_SERVER"));photos.syncAll(v.owner)
            assertEquals(1,photos.pendingCount(v.owner));assertEquals("PENDING",photos.list(v).single().getString("uploadStatus"));assertEquals(2400,width)
            photos.syncAll(v.owner);assertEquals(0,photos.pendingCount(v.owner));assertEquals("UPLOADED",photos.list(v).single().getString("uploadStatus"));assertEquals(2,uploads);assertEquals(2,confirms);assertEquals(paths[0],paths[1]);context.contentResolver.delete(uri,null,null)
        }finally{r.store.clear();r.db.close();context.deleteDatabase("$name.db")};Unit
    }
}
