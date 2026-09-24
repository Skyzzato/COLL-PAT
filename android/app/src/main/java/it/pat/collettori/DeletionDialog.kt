package it.pat.collettori
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import org.json.JSONObject

@Composable fun PermanentDeleteDialog(repo:Repository,kind:String,id:String,label:String,dismiss:()->Unit,done:()->Unit,message:(String)->Unit,allowedRemoved:Set<String>?=null){
    var scopeData by remember(id){mutableStateOf<JSONObject?>(null)};var error by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    LaunchedEffect(id){try{val preview=repo.deletionPreview(kind,id);check(allowedRemoved==null||preview.getJSONArray("removed").strings().all{it in allowedRemoved}){"Il dataset contiene elementi estranei: rimozione automatica interrotta"};scopeData=preview}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=friendlyError(e)}}
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("Elimina definitivamente")},text={Column{
        Text(label)
        scopeData?.let{s->Text(if(s.optBoolean("verified"))"Ambito verificato sul server, comprese le modifiche locali." else "Solo inventario locale offline; il server verificherà l’ambito prima di eliminare.")
            Text("${s.optJSONArray("points")?.length()?:0} pozzetti, ${s.optJSONArray("segments")?.length()?:0} tratti, ${s.getJSONArray("inspections").length()} ispezioni/bozze e ${s.getJSONArray("photos").length()} allegati coinvolti. ${s.getJSONArray("shared").length()} elementi condivisi resteranno.")
            Text("L’eliminazione è irreversibile. Saranno rimosse anche le copie applicative e gli allegati esclusivi. Senza rete la richiesta resta in coda.")
        }
        if(scopeData==null&&error.isBlank()||busy)LinearProgressIndicator()
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(enabled=scopeData!=null&&!busy,onClick={busy=true;scope.launch{try{repo.writes.async{repo.deletePermanently(scopeData!!)}.await();done();message("Eliminato dal dispositivo — cancellazione sul server in attesa")}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=friendlyError(e)}finally{busy=false}}}){Text("Elimina definitivamente")}},dismissButton={TextButton(enabled=!busy,onClick=dismiss){Text("Annulla")}})
}
