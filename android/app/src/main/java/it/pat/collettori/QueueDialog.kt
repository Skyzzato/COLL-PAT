package it.pat.collettori
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.launch

data class QueueEntry(val id:String,val label:String,val state:String,val error:String?,val at:String,val lastAttempt:String,val operations:List<Pending>,val photos:List<JSONObject>)
fun logicalQueue(queue:List<Pending>,visits:List<Visit>,settings:List<Setting>):List<QueueEntry>{
    val stored=settings.associate{it.key to it.value}
    val grouped=queue.groupBy{if(it.kind in listOf("catalog","catalog_chunk"))"import:"+it.visitId else if(it.kind in listOf("inspection","shared_inspection"))"inspection:"+it.visitId else it.kind+":"+it.visitId}.toMutableMap()
    visits.filter{it.sync in setOf("IN_ATTESA","IN_CORSO","CONFLICT","BLOCKED","AUTH_REQUIRED")||stored["photos:"+it.id]?.let{raw->JSONArray(raw).objects().any{p->p.optString("uploadStatus")!="UPLOADED"&&p.optString("localUri").isNotBlank()}}==true}.forEach{grouped.putIfAbsent("inspection:"+it.id,emptyList())}
    val result=grouped.map{(key,ops)->val ref=key.substringAfter(':');val visit=visits.firstOrNull{it.id==ref};val body=ops.firstOrNull()?.let{JSONObject(it.body)};val payload=body?.optJSONObject("payload")
        val photos=stored["photos:$ref"]?.let{JSONArray(it).objects().filter{p->p.optString("uploadStatus")!="UPLOADED"&&p.optString("localUri").isNotBlank()}}.orEmpty()
        QueueEntry(key,when{key.startsWith("import:")->"Importazione / modifica catalogo";key.startsWith("permanent_delete:")->"Eliminazione definitiva · "+payload?.optString("kind");key.startsWith("object_patch:")->"Modifica aspetto / condizione pozzetto";else->"Ispezione / bozza · "+(visit?.manholeId?:ref)},ops.firstOrNull{it.state in setOf("BLOCKED","CONFLICT","AUTH_REQUIRED")}?.state?:ops.firstOrNull()?.state?:photos.firstOrNull{it.optString("uploadStatus") in setOf("BLOCKED","AUTH_REQUIRED")}?.optString("uploadStatus")?:if(photos.isNotEmpty())"IN_ATTESA" else visit?.sync?:"IN_ATTESA",ops.mapNotNull{it.error}.distinct().joinToString("\n").ifBlank{visit?.error},body?.optString("queued_at")?:visit?.let{JSONObject(it.body).optString("started_at")}.orEmpty(),ops.mapNotNull{stored["attempt:"+it.operationId]}.maxOrNull().orEmpty(),ops,photos)
    }
    return result+settings.filter{it.key.startsWith("deletion-state:")&&it.value.contains("allegati in attesa")}.map{QueueEntry("storage_delete:"+it.key.substringAfter(':'),"Rimozione allegati eliminati","IN_ATTESA",it.value,"","",emptyList(),emptyList())}
}
@Composable fun QueueDialog(repo:Repository,queue:List<Pending>,visits:List<Visit>,settings:List<Setting>,dismiss:()->Unit,message:(String)->Unit,onOpen:(String,String)->Unit={_,_->}){
    val accessRole=repo.observedRole()
    val catalog by repo.dao.catalog(repo.owner()).collectAsState(emptyList())
    val entries=remember(queue,visits,settings){logicalQueue(queue,visits,settings)};val scope=rememberCoroutineScope()
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.padding(16.dp)){
        Text("Coda di sincronizzazione · ${entries.size}",style=MaterialTheme.typography.headlineSmall)
        repo.simulatedRole()?.let{role->Row{Text("Simulazione: "+roleLabel(role),Modifier.weight(1f));TextButton(onClick={repo.simulate(null)}){Text("Termina simulazione")}}}
        if(entries.isEmpty())Text("Nessun elemento in attesa")
        LazyColumn(Modifier.weight(1f)){items(entries,key={it.id}){entry->var expanded by remember{mutableStateOf(false)}
            Card(Modifier.fillMaxWidth().padding(vertical=5.dp)){Column(Modifier.padding(12.dp)){
                val itemId=entry.id.substringAfter(':');val visit=visits.firstOrNull{it.id==itemId};val objectId=visit?.manholeId?:itemId
                val item=catalog.firstOrNull{it.id==objectId};val code=item?.let{JSONObject(it.body).optString("code")}
                Text(if(code!=null)entry.label.substringBefore(" · ")+" · "+code else entry.label,style=MaterialTheme.typography.titleMedium);Text(syncLabel(entry.state));Text("Data: "+entry.at.ifBlank{"non disponibile"});Text("Ultimo tentativo: "+entry.lastAttempt.ifBlank{"mai"})
                entry.error?.takeIf{it.isNotBlank()}?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                TextButton(onClick={expanded=!expanded}){Text("Dettaglio · ${entry.operations.size} operazioni · ${entry.photos.size} fotografie")}
                if(expanded){entry.operations.forEach{Text(it.kind+" · "+it.state,style=MaterialTheme.typography.bodySmall)};entry.photos.forEach{Text("Foto · "+it.optString("uploadStatus")+" · "+it.optString("error"),style=MaterialTheme.typography.bodySmall)}}
                if(visit!=null||item!=null)TextButton(onClick={dismiss();onOpen(if(visit!=null)"inspection" else item!!.kind,itemId)}){Text("Apri oggetto")}
                if(entry.id.startsWith("permanent_delete:")&&entry.state=="CONFLICT"&&(accessRole=="admin"||accessRole=="inspector"&&entry.operations.any{JSONObject(it.body).optJSONObject("payload")?.optString("kind")=="inspection"}))TextButton(onClick={dismiss();onOpen("permanent_delete",itemId)}){Text("Verifica e riconferma eliminazione")}
                if(entry.state=="IN_ATTESA")TextButton(onClick={repo.syncNow();message("Nuovo tentativo accodato")}){Text("Riprova")}
                if(entry.state=="AUTH_REQUIRED")Text("Accedi nuovamente dalla sezione Account.")
                if(entry.state=="CONFLICT")Text("Apri l’oggetto per verificare la versione server. Per una cancellazione, aggiorna l’anteprima prima di confermare nuovamente.")
            }}
        }}
        TextButton(onClick=dismiss,modifier=Modifier.align(androidx.compose.ui.Alignment.End)){Text("Chiudi")}
    }}}
}
