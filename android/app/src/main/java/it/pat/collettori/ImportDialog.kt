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
    if(!repo.canManageCatalog())return
    val scope=rememberCoroutineScope();var archive by remember{mutableStateOf<ShapeArchive?>(null)};var plan by remember{mutableStateOf<ImportPlan?>(null)};var mappings by remember{mutableStateOf(emptyList<LayerMapping>())}
    var source by remember{mutableStateOf("")};var crs by remember{mutableStateOf("")};var encoding by remember{mutableStateOf("AUTO")};var advanced by remember{mutableStateOf(false)};var layerEncodings by remember{mutableStateOf<Map<String,String>>(emptyMap())};var fallback by remember{mutableStateOf("")};var mode by remember{mutableStateOf("ISOLATED")}
    var order by remember{mutableStateOf(false)};var tolerance by remember{mutableStateOf("0")};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var original by remember{mutableStateOf<String?>(null)}
    fun task(block:suspend()->Unit){if(busy)return;busy=true;error="";scope.launch{try{block()}catch(e:TimeoutCancellationException){error="Aggiornamento del catalogo non riuscito. Verifica la connessione e riprova."}catch(e:CancellationException){throw e}catch(e:OutOfMemoryError){archive=null;plan=null;error="Memoria insufficiente: dividere il file in layer più piccoli. Nessuna importazione confermata."}catch(e:Exception){error=when{
        e is ApiError->friendlyError(e)
        e is IllegalArgumentException||e is IllegalStateException->e.message?.takeUnless{it.isBlank()||it=="Failed requirement."||it=="Check failed."}?:"File o configurazione non validi: verifica i campi e riprova."
        else->"Impossibile leggere o importare il file. Verifica che lo ZIP sia completo e riprova."
    }}finally{busy=false}}}
    suspend fun reload(){
        plan=null;archive=null
        val loaded=withContext(Dispatchers.IO){java.io.File(requireNotNull(original)).inputStream().use{Shapefile.read(it,crs.trim().takeIf{it.isNotBlank()}?.toIntOrNull()?:if(crs.isBlank())null else error("EPSG numerico richiesto"),encoding,layerEncodings)}}
        val old=mappings.associateBy{it.layer};archive=loaded
        mappings=loaded.layers.map{old[it.name]?:ImportPlanner.propose(it)}
        if(loaded.layers.any{it.kind=="segment"})mode="LINES"
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)task{
        archive=null;plan=null;mappings=emptyList()
        val file=withContext(Dispatchers.IO){
            val dir=java.io.File(repo.context.filesDir,"import-originals").apply{mkdirs()};val target=java.io.File(dir,java.util.UUID.randomUUID().toString()+".zip")
            try{repo.context.contentResolver.openInputStream(uri)!!.use{input->java.io.FileOutputStream(target).use{out->val buffer=ByteArray(8192);var total=0;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=Shapefile.MAX_BYTES){"ZIP compresso oltre 32 MiB"};out.write(buffer,0,n)};out.fd.sync()}};target}catch(e:Exception){target.delete();throw e}
        };original=file.absolutePath
        source=repo.context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0).substringBeforeLast('.') else ""}.orEmpty()
        if(source.length<3)source="sorgente-shapefile"
        val saved=repo.dao.settingValue(repo.owner(),"import-options:$source")?.let(::JSONObject)
        layerEncodings=saved?.keys()?.asSequence()?.associateWith{saved.getJSONObject(it).optString("encoding","AUTO")}.orEmpty()
        reload()
        mappings=mappings.map{m->saved?.optJSONObject(m.layer)?.optString("key")?.takeIf{it.isNotBlank()}?.let{m.copy(fields=m.fields+("key" to it))}?:m}

    }}
    Dialog(onDismissRequest={if(!busy)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){
        Text("Importa shapefile",style=MaterialTheme.typography.headlineSmall);Text("Seleziona uno ZIP con .shp, .shx e .dbf, includendo .prj e .cpg se disponibili. Massimo 32 MiB e 10000 oggetti. Potrai controllare la mappa e il riepilogo prima di confermare.",style=MaterialTheme.typography.bodySmall)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(plan==null){
            Text("Codifica dei testi: "+encodingOptions.first{it.first==encoding}.second)
            TextButton(enabled=!busy,onClick={advanced=!advanced}){Text("Opzioni avanzate")}
            if(advanced){
                Choice("Codifica dei testi",encoding,encodingOptions){value->encoding=value;layerEncodings=emptyMap();if(original!=null)task{reload()}}
                Field("EPSG esplicito (vuoto = leggi .prj)",crs){crs=it;plan=null;archive=null}
                if(original!=null)OutlinedButton(enabled=!busy,onClick={task{reload()}}){Text("Aggiorna anteprima del file")}
            }
            OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))}){Text("Seleziona ZIP dal telefono")}
            archive?.let{a->
                Text("${a.layers.size} layer · ${a.layers.sumOf{it.features.size}} oggetti")
                Field("Nome sorgente stabile per le reimportazioni",source){source=it}
                Choice("Collettore per record senza campo collettore",fallback,listOf("" to "Seleziona")+catalog.filter{it.kind=="collector"&&!JSONObject(it.body).optBoolean("archived")}.map{it.id to JSONObject(it.body).getString("code")}){fallback=it}
                Choice("Modalità",mode,listOf("LINES" to "A · Punti e linee reali","ORDERED" to "B · Punti con ordine affidabile","ISOLATED" to "C · Punti senza ordine / opere isolate")){mode=it}
                a.layers.forEach{layer->
                    Text("${layer.name} · ${layer.kind} · EPSG:${layer.crs} · ${layer.encoding}",style=MaterialTheme.typography.titleSmall)
                    Text(layer.encodingNote,style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!busy,onClick={advanced=true}){Text("Caratteri non corretti? Cambia codifica")}
                    if(advanced)Choice("Codifica dei testi · ${layer.name}",layer.encodingChoice,encodingOptions){value->layerEncodings=layerEncodings+(layer.name to value);task{reload()}}
                    layer.features.take(3).forEachIndexed{index,f->Text("Anteprima ${index+1}: "+f.fields.entries.joinToString(" · "){"${it.key}: ${it.value}"},style=MaterialTheme.typography.bodySmall)}
                    val map=mappings.first{it.layer==layer.name}
                    val report=keyReport(layer,map.fields["key"].orEmpty())
                    Text("Identificativo: ${report.field.ifBlank{"da scegliere"}} · ${report.empty} vuoti · ${report.duplicates} duplicati (intero layer)",color=if(report.valid)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    report.examples.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                    if(!report.valid)Text("Scegli un campo stabile con valori tutti compilati e univoci, oppure correggi il file sorgente. Nessun aggiornamento ambiguo sarà importato.")
                    val keys=if(layer.kind=="point")listOf("key","code","description","collector","asset_type","under_asphalt")+(if(mode=="ORDERED")listOf("sequence","chainage","branch","previous","next")else listOf("chainage"))else listOf("key","code","collector","from","to")
                    keys.forEach{k->Choice(mapOf("key" to if(layer.kind=="point")"Campo identificativo del pozzetto" else "Campo identificativo del tronco","code" to "Codice (testo)","description" to "Descrizione","collector" to "Codice collettore","asset_type" to "Tipo manufatto (MANHOLE = pozzetto)","under_asphalt" to "Sotto asfalto (sì/no)","sequence" to "Sequenza ordinale","chainage" to "Progressiva GIS (metri)","branch" to "Ramo","previous" to "Chiave precedente","next" to "Chiave successivo","from" to "Chiave estremo iniziale","to" to "Chiave estremo finale")[k]!!,map.fields[k]?:"",listOf("" to "Non presente")+layer.fields.map{it to if(k=="key")"$it · "+(if(keyReport(layer,it).valid)"univoco" else "vuoti o duplicati") else it}){field->mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+(k to field))else it}}}
                }
                if(mode=="LINES")Field("Tolleranza esplicita estremi senza chiave, metri (0 = disabilitata)",tolerance){tolerance=it}
                if(mode=="ORDERED")Row{Checkbox(order,{order=it});Text("Confermo ordine e separazione dei rami; il primo punto documenta l'origine",Modifier.padding(top=10.dp))}
                Button(enabled=!busy&&a.layers.all{keyReport(it,mappings.first{m->m.layer==it.name}.fields["key"].orEmpty()).valid},onClick={
                    val chosenMappings=mappings;val chosenSource=source.trim();val chosenFallback=fallback;val chosenMode=mode;val chosenOrder=order;val chosenTolerance=tolerance
                    task{
                        // Reimports must compare with the complete current project, not a stale UI snapshot.
                        if(repo.authenticated())withTimeout(30000){repo.catalog()}
                        check(repo.canManageCatalog()){"Importazione riservata al responsabile del progetto"}
                        val owner=repo.owner();val existing=repo.dao.catalogNow(owner)
                        plan=withContext(Dispatchers.Default){ImportPlanner.plan(a,chosenMappings,chosenSource,chosenFallback,chosenMode,chosenOrder,false,decimalItalian(chosenTolerance)?:error("Tolleranza non valida"),existing,owner)}
                    }
                }){Text(if(busy)"Preparazione anteprima…" else "Anteprima e rapporto")}
            }
        }else plan?.let{p->
            listOf("collector" to "Collettori","point" to "Pozzetti / manufatti","segment" to "Tronchi").forEach{(kind,label)->val count=p.counts.getValue(kind)
                Text("$label: ${count.inserted} nuovi · ${count.updated} aggiornati · ${count.unchanged} invariati · ${count.missing} assenti dal file",style=MaterialTheme.typography.bodySmall)
            }
            Text("${p.warnings.size} avvisi · nessun errore bloccante")
            p.warnings.forEach{Text("• $it",style=MaterialTheme.typography.bodySmall)}
            OfflineMap(p.preview,p.preview.getJSONArray("points").objects(),null,null,null,Modifier.fillMaxWidth().height(300.dp),{},{error=it},p.preview.getJSONArray("points").objects().map{org.maplibre.android.geometry.LatLng(it.getDouble("latitude"),it.getDouble("longitude"))},1)
            if(p.missing.isNotEmpty())Text("Assenti: "+p.missing.take(10).joinToString{JSONObject(it.body).optString("code").ifBlank{it.id}}+if(p.missing.size>10)" …" else "",style=MaterialTheme.typography.bodySmall)
            Text("Gli elementi assenti restano conservati, insieme alle ispezioni e alle foto. Il file originale rimane privato sul telefono.")
            if(repo.authenticated())Text("Dopo la conferma, l’aggiornamento verrà inviato al server. Puoi verificarne l’esito in Server e sincronizzazione.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=!busy,onClick={task{repo.saveCatalog(p.items,p.provenance);repo.dao.setting(Setting(repo.owner(),"import-options:$source",JSONObject().apply{archive!!.layers.forEach{layer->put(layer.name,JSONObject().put("encoding",layer.encodingChoice).put("key",mappings.first{it.layer==layer.name}.fields["key"]))}}.toString()));message(if(repo.authenticated())"Importazione salvata. Sincronizzazione automatica in attesa di conferma dal server." else "Importazione salvata nell’archivio demo locale.");dismiss()}}){Text("Conferma importazione")}
            TextButton(enabled=!busy,onClick={plan=null}){Text("Modifica associazione campi")}
        }
        TextButton(enabled=!busy,onClick=dismiss){Text("Chiudi")}
    }}}
}

val encodingOptions=listOf("AUTO" to "Automatica (consigliata)","UTF-8" to "Unicode — UTF-8","windows-1252" to "Europa occidentale / Windows — Windows-1252","ISO-8859-1" to "Europa occidentale / Latin-1 — ISO-8859-1")
