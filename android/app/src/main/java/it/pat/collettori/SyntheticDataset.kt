package it.pat.collettori

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

fun Repository.barbanigaDataset()=JSONObject(context.assets.open("demo-barbaniga-v022.json").bufferedReader().use{it.readText()})
suspend fun Repository.installBarbaniga(){
    requireWrite(admin=true);val data=barbanigaDataset();val existing=dao.catalogNow(owner()).associateBy{it.id}
    val items=listOf("collectors" to "collector","points" to "point","segments" to "segment").flatMap{(key,kind)->data.getJSONArray(key).objects().map{CatalogItem(owner(),it.getString("id"),kind,it.toString())}}
    check(items.none{item->existing[item.id]?.let{JSONObject(it.body).optString("synthetic_dataset")!=data.getString("dataset")}==true}){"Identità occupate da dati estranei: caricamento interrotto"}
    val missing=items.filter{it.id !in existing};if(missing.isNotEmpty())saveCatalog(missing)
}

@Composable fun SyntheticDatasetPanel(repo:Repository,catalog:List<CatalogItem>,onMessage:(String)->Unit){
    val accessRole=repo.observedRole()
    if(accessRole!="admin")return
    var confirm by remember{mutableStateOf(false)};var remove by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    val dataset=remember{repo.barbanigaDataset()};val cid=dataset.getJSONArray("collectors").getJSONObject(0).getString("id")
    Text("Collaudo Barbaniga · dati sintetici")
    Text("Percorso inventato di 5 km, 126 pozzetti e 125 tronchi. Il caricamento è esplicito nel progetto corrente e non descrive la rete reale.")
    OutlinedButton(enabled=!busy,onClick={confirm=true}){Text("Carica collettore simulato Barbaniga")}
    if(catalog.any{it.id==cid})TextButton(enabled=!busy,onClick={remove=true}){Text("Rimuovi dataset sintetico Barbaniga")}
    if(confirm)AlertDialog(onDismissRequest={if(!busy)confirm=false},title={Text("Carica dati sintetici")},text={Text("Progetto: ${repo.project()}. Verrà aggiunto DEMO-BAR-5KM. Ripetere il caricamento conserva gli elementi già presenti e non crea duplicati.")},confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch{try{repo.writes.async{repo.installBarbaniga()}.await();confirm=false;onMessage("Dataset sintetico disponibile nella mappa e nel catalogo locale; sincronizzazione in coda")}catch(e:CancellationException){throw e}catch(e:Exception){onMessage(friendlyError(e))}finally{busy=false}}}){Text("Conferma caricamento")}},dismissButton={TextButton(enabled=!busy,onClick={confirm=false}){Text("Annulla")}})
    if(remove)PermanentDeleteDialog(repo,"collector",cid,"Solo dataset sintetico DEMO-BAR-5KM",{remove=false},{remove=false},onMessage,allowedRemoved=(dataset.getJSONArray("collectors").objects()+dataset.getJSONArray("points").objects()+dataset.getJSONArray("segments").objects()).map{it.getString("id")}.toSet())
}
