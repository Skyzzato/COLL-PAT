package it.pat.collettori

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun ImportDialog(repo:Repository,catalog:List<CatalogItem>,dismiss:()->Unit,message:(String)->Unit){
    val scope=rememberCoroutineScope();var archive by remember{mutableStateOf<ShapeArchive?>(null)};var plan by remember{mutableStateOf<ImportPlan?>(null)};var mappings by remember{mutableStateOf(emptyList<LayerMapping>())}
    var source by remember{mutableStateOf("")};var crs by remember{mutableStateOf("")};var encoding by remember{mutableStateOf("")};var fallback by remember{mutableStateOf("")};var mode by remember{mutableStateOf("ISOLATED")}
    var order by remember{mutableStateOf(false)};var newKeys by remember{mutableStateOf(false)};var tolerance by remember{mutableStateOf("0")};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var original by remember{mutableStateOf<String?>(null)}
    fun task(block:suspend()->Unit){if(busy)return;busy=true;scope.launch{try{block();error=""}catch(e:OutOfMemoryError){archive=null;plan=null;error="Memoria insufficiente: dividere il file in layer più piccoli. Nessuna importazione confermata."}catch(e:Exception){error=e.message?:"Importazione non riuscita"}finally{busy=false}}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)task{
        val file=withContext(Dispatchers.IO){
            val dir=java.io.File(repo.context.filesDir,"import-originals").apply{mkdirs()};val target=java.io.File(dir,java.util.UUID.randomUUID().toString()+".zip")
            try{repo.context.contentResolver.openInputStream(uri)!!.use{input->java.io.FileOutputStream(target).use{out->val buffer=ByteArray(8192);var total=0;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=Shapefile.MAX_BYTES){"ZIP compresso oltre 32 MiB"};out.write(buffer,0,n)};out.fd.sync()}};target}catch(e:Exception){target.delete();throw e}
        };original=file.absolutePath
        val loaded=withContext(Dispatchers.IO){file.inputStream().use{Shapefile.read(it,crs.trim().takeIf{it.isNotBlank()}?.toIntOrNull()?:if(crs.isBlank())null else error("EPSG numerico richiesto"),encoding.trim().takeIf{it.isNotBlank()})}}
        archive=loaded;mappings=loaded.layers.map{ImportPlanner.propose(it)};mode=if(loaded.layers.any{it.kind=="segment"})"LINES" else "ISOLATED";plan=null
    }}
    Dialog(onDismissRequest={if(!busy)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){
        Text("Importa shapefile",style=MaterialTheme.typography.headlineSmall);Text("ZIP · .shp + .shx + .dbf; .prj e .cpg oppure configurazione esplicita. Fino a 32 MiB decompressi / 10000 oggetti. Gli invii grandi usano lotti privati e pubblicazione finale atomica.",style=MaterialTheme.typography.bodySmall)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(plan==null){
            Field("EPSG esplicito (vuoto = leggi .prj)",crs){crs=it};Field("Codifica esplicita (vuoto = leggi .cpg)",encoding){encoding=it}
            OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))}){Text("Seleziona ZIP dal telefono")}
            archive?.let{a->
                Text("${a.layers.size} layer · ${a.layers.sumOf{it.features.size}} oggetti")
                Field("Nome sorgente stabile per le reimportazioni",source){source=it}
                Choice("Collettore per record senza campo collettore",fallback,listOf("" to "Seleziona")+catalog.filter{it.kind=="collector"&&!JSONObject(it.body).optBoolean("archived")}.map{it.id to JSONObject(it.body).getString("code")}){fallback=it}
                Choice("Modalità",mode,listOf("LINES" to "A · Punti e linee reali","ORDERED" to "B · Punti con ordine affidabile","ISOLATED" to "C · Punti senza ordine / opere isolate")){mode=it}
                a.layers.forEach{layer->
                    Text("${layer.name} · ${layer.kind} · EPSG:${layer.crs} · ${layer.encoding}",style=MaterialTheme.typography.titleSmall)
                    val map=mappings.first{it.layer==layer.name}
                    val keys=if(layer.kind=="point")listOf("key","code","description","collector","asset_type")+(if(mode=="ORDERED")listOf("sequence","chainage","branch","previous","next")else listOf("chainage"))else listOf("key","code","collector","from","to")
                    keys.forEach{k->Choice(mapOf("key" to "Chiave sorgente stabile","code" to "Codice (testo)","description" to "Descrizione","collector" to "Codice collettore","asset_type" to "Tipo manufatto (MANHOLE = pozzetto)","sequence" to "Sequenza ordinale","chainage" to "Progressiva GIS (metri)","branch" to "Ramo","previous" to "Chiave precedente","next" to "Chiave successivo","from" to "Chiave estremo iniziale","to" to "Chiave estremo finale")[k]!!,map.fields[k]?:"",listOf("" to "Non presente")+layer.fields.map{it to it}){field->mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+(k to field))else it}}}
                }
                if(mode=="LINES")Field("Tolleranza esplicita estremi senza chiave, metri (0 = disabilitata)",tolerance){tolerance=it}
                if(mode=="ORDERED")Row{Checkbox(order,{order=it});Text("Confermo ordine e separazione dei rami; il primo punto documenta l'origine",Modifier.padding(top=10.dp))}
                Row{Checkbox(newKeys,{newKeys=it});Text("Senza chiave affidabile: consento soltanto nuovi oggetti, nessuna associazione automatica",Modifier.padding(top=10.dp))}
                Button(enabled=!busy,onClick={task{plan=withContext(Dispatchers.Default){ImportPlanner.plan(a,mappings,source.trim(),fallback,mode,order,newKeys,decimalItalian(tolerance)?:error("Tolleranza non valida"),catalog,repo.owner())}}}){Text("Anteprima e rapporto")}
            }
        }else plan?.let{p->
            Text("${p.inserted} inserimenti · ${p.updated} aggiornamenti · ${p.unchanged} invariati · ${p.warnings.size} avvisi · 0 errori")
            p.warnings.forEach{Text("• $it",style=MaterialTheme.typography.bodySmall)}
            OfflineMap(p.preview,p.preview.getJSONArray("points").objects(),null,null,null,Modifier.fillMaxWidth().height(300.dp),{},{error=it},p.preview.getJSONArray("points").objects().map{org.maplibre.android.geometry.LatLng(it.getDouble("latitude"),it.getDouble("longitude"))},1)
            Text("La mancanza di oggetti nel file non li elimina. Origine, hash, mapping e rapporto vengono conservati; l'archivio originale resta privato sul telefono.")
            Button(enabled=!busy,onClick={task{repo.saveCatalog(p.items,p.provenance);message("Importazione salvata localmente · in attesa di sincronizzazione");dismiss()}}){Text("Conferma importazione")}
            TextButton(enabled=!busy,onClick={plan=null}){Text("Modifica mapping")}
        }
        TextButton(enabled=!busy,onClick=dismiss){Text("Chiudi")}
    }}}
}
