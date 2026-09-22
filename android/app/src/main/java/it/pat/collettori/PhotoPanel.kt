package it.pat.collettori

import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun PhotoPanel(repo:Repository,visit:Visit) {
    val repository=remember(repo){PhotoRepository(repo)}
    val scope=rememberCoroutineScope()
    var photos by remember(visit.id){mutableStateOf<List<JSONObject>>(emptyList())}
    var error by remember{mutableStateOf("")}
    var pending by rememberSaveable{mutableStateOf<String?>(null)}
    var working by remember{mutableStateOf(false)}
    LaunchedEffect(visit.id){try{photos=repository.list(visit)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){android.util.Log.e("Collettori","Elenco fotografie",e);error="Impossibile leggere le fotografie"}}
    fun attach(uri:Uri,copy:Boolean){scope.launch{working=true;try{repository.attach(visit,uri,copy);photos=repository.list(visit)}catch(e:Exception){error=e.message?:"Foto non acquisita"}finally{working=false}}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        pending?.let{if(ok)attach(Uri.parse(it),false)else {try{repo.context.contentResolver.delete(Uri.parse(it),null,null)}catch(e:Exception){android.util.Log.w("Collettori","Pulizia foto annullata",e)};error="Scatto annullato o non autorizzato"}};pending=null
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)attach(uri,true)}
    Text("Foto · solo sul telefono",style=MaterialTheme.typography.titleMedium)
    Text("Caricamento foto simulato; immagini conservate sul telefono. I dati della scheda vengono inviati separatamente.",style=MaterialTheme.typography.bodySmall)
    if(visit.operational=="BOZZA")Row {
        FilledTonalButton(enabled=!working,onClick={try{val uri=repository.newCapture();pending=uri.toString();camera.launch(uri)}catch(e:Exception){error="Fotocamera non disponibile; usa Galleria"}}){ActionIcon(R.drawable.ic_camera);Text("Scatta foto")}
        OutlinedButton(enabled=!working,onClick={try{gallery.launch("image/*")}catch(e:Exception){error="Galleria non disponibile"}}){Text("Galleria")}
    }
    if(working)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    photos.forEach{photo->
        val bitmap by produceState<android.graphics.Bitmap?>(null,photo.getString("photoId")){
            value=withContext(Dispatchers.IO){runCatching{
                val uri=Uri.parse(photo.getString("localUri"))
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                repo.context.contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,bounds)}
                val options=BitmapFactory.Options().apply{inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/512).coerceAtLeast(1)}
                repo.context.contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,options)}
            }.getOrNull()}
        }
        Row(Modifier.fillMaxWidth()){
            bitmap?.let{Image(it.asImageBitmap(),"Foto dell'ispezione",Modifier.size(96.dp))}?:Text("Anteprima non disponibile",Modifier.width(96.dp),style=MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(1f)){
                Text(shown(photo.getString("createdAt")),style=MaterialTheme.typography.bodySmall)
                Text("Locale · non inviata",style=MaterialTheme.typography.labelSmall)
                if(visit.operational=="BOZZA")TextButton(enabled=!working,onClick={scope.launch{try{repository.remove(visit,photo);photos=repository.list(visit)}catch(e:Exception){error=e.message?:"Rimozione non riuscita"}}}){ActionIcon(R.drawable.ic_trash);Text("Rimuovi")}
            }
        }
    }
}
