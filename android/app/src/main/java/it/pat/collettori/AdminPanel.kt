package it.pat.collettori

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

@Composable fun AccountPanel(repo:Repository,onChange:()->Unit){
    val verified by repo.dao.settingFlow(repo.owner(),"verified-role").collectAsState(null)
    Column{Text(repo.store.get()?.optString("username")?:"Nessun account")
        Text("Livello di abilitazione: "+roleLabel(verified?:repo.store.get()?.optString("role")))
        Text("Ultimo livello verificato dal server; la disponibilità offline non amplia i permessi.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={repo.store.clear();onChange()}){Text("Logout")}}
}

@Composable fun AdminPanel(repo:Repository,catalog:List<CatalogItem>,visitCount:Int,onMessage:(String)->Unit){
    if(!repo.canManageCatalog())return
    var expanded by rememberSaveable{mutableStateOf(false)};var form by remember{mutableStateOf<JSONObject?>(null)}
    var archive by remember{mutableStateOf<CollectorDeletion?>(null)};var deletionConfirmed by remember{mutableStateOf(false)};var reset by remember{mutableStateOf(false)};var resetText by remember{mutableStateOf("")};var resetStatus by remember{mutableStateOf<JSONObject?>(null)}
    var busy by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    fun task(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{block()}catch(e:Exception){onMessage(friendlyError(e))}finally{busy=false}}}
    TextButton(onClick={expanded=!expanded}){Text("Gestione collettori")}
    if(expanded)Column{
        Text("Gestione riservata al responsabile del progetto")
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick={form=collectorDefaults(UUID.randomUUID().toString(),"","")}){Text("Nuovo collettore")}
        catalog.filter{it.kind=="collector"&&JSONObject(it.body).available()}.forEach{item->val c=JSONObject(item.body)
            Row{TextButton(modifier=Modifier.weight(1f),onClick={form=JSONObject(item.body)}){Text(c.getString("description")+" · "+c.getString("code"))};IconButton(enabled=!busy,onClick={task{archive=repo.deleteImpact(item.id);deletionConfirmed=false}}){Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_trash),"Elimina collettore")}}
        }
        OutlinedButton(enabled=!busy,onClick={task{resetStatus=repo.reconcile();resetText="";reset=true}}){Text("Azzera tutte le ispezioni")}
        Text("Locali nell'account: $visitCount. Il reset richiede rete, backup privato e permessi amministrativi reali.",style=MaterialTheme.typography.bodySmall)
    }
    form?.let{c->CollectorForm(c,catalog,{form=null}){next,points->task{repo.saveCatalog(listOf(CatalogItem(repo.owner(),next.getString("id"),"collector",next.toString()))+points.map{CatalogItem(repo.owner(),it.getString("id"),"point",it.toString())});form=null;onMessage("Collettore e pozzetti salvati localmente · accodati al server")}}}
    archive?.let{c->AlertDialog(onDismissRequest={archive=null},title={Text("Elimina collettore")},text={Column{
        Text("Il collettore scomparirà da elenchi, mappa e selettori. Dati associati presenti sul telefono: ${c.points} pozzetti, ${c.segments} tronchi, ${c.inspections} ispezioni e ${c.photos} foto. ${c.shared.size} elementi condivisi resteranno negli altri collettori.")
        Text("Le ispezioni e gli allegati conservano il loro storico privato. Gli elementi privi di storico vengono rimossi. Senza rete, la cancellazione sul server rimarrà in coda.")
        Row{Checkbox(deletionConfirmed,{deletionConfirmed=it});Text("Confermo l’eliminazione e ho verificato i dati associati",Modifier.padding(top=8.dp))}
    }},confirmButton={TextButton(enabled=!busy&&deletionConfirmed,onClick={task{val local=repo.deleteCollector(c.id);archive=null;onMessage(if(local)"Collettore locale eliminato; operazioni inutili rimosse" else "Collettore rimosso dall’app · eliminazione server in coda")}}){Text("Elimina collettore")}},dismissButton={TextButton(onClick={archive=null}){Text("Indietro")}})}
    if(reset)AlertDialog(onDismissRequest={if(!busy)reset=false},title={Text("Azzera tutte le ispezioni")},text={Column{Text("Progetto: ${repo.project()}\nServer: ${resetStatus?.optInt("inspection_count")} ispezioni\nLocali account: $visitCount\nSi conserva un backup privato sul telefono. Anagrafica e account rimangono intatti.");Field("Digita AZZERA",resetText){resetText=it}}},confirmButton={TextButton(enabled=resetText=="AZZERA"&&!busy,onClick={task{val result=repo.reset(resetText);reset=false;onMessage("Reset ricevuto: ${result.getInt("count")} ispezioni, generazione ${result.getLong("generation")}")}}){Text("Azzera adesso")}},dismissButton={TextButton(enabled=!busy,onClick={reset=false}){Text("Indietro")}})
}

@Composable fun CollectorForm(initial:JSONObject,catalog:List<CatalogItem>,onDismiss:()->Unit,onSave:(JSONObject,List<JSONObject>)->Unit){
    val cid=initial.getString("id")
    var points by remember{mutableStateOf(catalog.filter{it.kind=="point"&&cid in JSONObject(it.body).memberships()&&JSONObject(it.body).available()}.map{JSONObject(it.body)})}
    var changed by remember{mutableStateOf(emptySet<String>())};var editingPoint by remember{mutableStateOf<JSONObject?>(null)}
    var color by remember{mutableStateOf(collectorColor(initial))}
    var code by remember{mutableStateOf(initial.getString("code"))};var description by remember{mutableStateOf(initial.getString("description"))};var type by remember{mutableStateOf(initial.getString("type"))}
    var first by remember{mutableStateOf(initial.getInt("visits_h1").toString())};var second by remember{mutableStateOf(initial.getInt("visits_h2").toString())};var hours by remember{mutableStateOf(initial.getDouble("hours_km_visit").toString().replace('.',','))}
    var length by remember{mutableStateOf(initial.numberOrNull("length_m")?.toString()?.replace('.',',')?:"")};var source by remember{mutableStateOf(initial.getString("length_source"))};var complete by remember{mutableStateOf(initial.optBoolean("length_complete"))};var error by remember{mutableStateOf("")}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)){
        Text("Anagrafica collettore",style=MaterialTheme.typography.headlineSmall);Text("UUID stabile: "+initial.getString("id"),style=MaterialTheme.typography.labelSmall)
        Field("Codice obbligatorio",code){code=it};Field("Descrizione obbligatoria",description){description=it};Choice("Tipologia",type,collectorTypes.map{it to it}){type=it}
        Choice("Colore tracciato",color,listOf("#176D73" to "Verde petrolio","#254EBC" to "Blu","#783E9F" to "Viola","#8D4617" to "Marrone","#AF235A" to "Magenta")){color=it}
        Field("Numero visite 1° semestre",first){first=it};Field("Numero visite 2° semestre",second){second=it};Field("Ore per km per visita",hours){hours=it}
        Field("Lunghezza in metri (vuoto = sconosciuta)",length){length=it;source=if(it.isBlank())"UNAVAILABLE" else "DECLARED"}
        Text("Origine: $source");Row{Checkbox(complete,{complete=it});Text("Rete completa / totale dichiarato",Modifier.padding(top=12.dp))}
        Text("Obiettivi semestrali; le ispezioni dei singoli manufatti non sono automaticamente visite complete del collettore.")
        Text("Pozzetti",style=MaterialTheme.typography.titleLarge)
        Text("${points.size} pozzetti associati. Le modifiche vengono confermate con “Salva collettore e pozzetti”.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={editingPoint=JSONObject().put("id",UUID.randomUUID().toString()).put("code","")}){Text("Aggiungi pozzetto")}
        points.forEach{p->TextButton(onClick={editingPoint=JSONObject(p.toString())}){Text("#${p.getString("code")} · Modifica dati")}}
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        Button(onClick={try{
            val a=first.toIntOrNull()?:error("Visite: interi non negativi");val b=second.toIntOrNull()?:error("Visite: interi non negativi");val h=decimalItalian(hours)?:error("Ore: numero non negativo")
            val l=if(length.isBlank())null else decimalItalian(length)?:error("Lunghezza non valida")
            val next=JSONObject(initial.toString()).put("display_color",color).put("code",code.trim()).put("description",description.trim()).put("type",type).put("visits_h1",a).put("visits_h2",b).put("hours_km_visit",h).put("length_m",l?:JSONObject.NULL).put("length_source",if(l==null)"UNAVAILABLE" else source).put("length_complete",complete)
            validateCollector(next);onSave(next,points.filter{it.getString("id") in changed})
        }catch(e:Exception){error=friendlyError(e)}}){Text("Salva collettore e pozzetti")};TextButton(onClick=onDismiss){Text("Indietro")}
    }}}
    editingPoint?.let{p->PointForm(p,initial,points,catalog.filter{it.kind=="segment"}.map{JSONObject(it.body)},{editingPoint=null}){next->points=points.filter{it.getString("id")!=next.getString("id")}+next;changed=changed+next.getString("id");editingPoint=null}}
}
