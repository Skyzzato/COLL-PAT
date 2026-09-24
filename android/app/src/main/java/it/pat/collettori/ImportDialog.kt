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
    var source by remember{mutableStateOf("")};var crs by remember{mutableStateOf("")};var encoding by remember{mutableStateOf("AUTO")};var advanced by remember{mutableStateOf(false)};var layerEncodings by remember{mutableStateOf<Map<String,String>>(emptyMap())};var fallback by remember{mutableStateOf("")};var mode by remember{mutableStateOf("ORDERED")}
    var order by remember{mutableStateOf(true)};var tolerance by remember{mutableStateOf("5")};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var original by remember{mutableStateOf<String?>(null)}
    DisposableEffect(Unit){onDispose{original?.let{java.io.File(it).delete()}}}
    var identityCatalog by remember{mutableStateOf(emptyList<CatalogItem>())}
    fun task(block:suspend()->Unit){if(busy)return;busy=true;error="";scope.launch{try{block()}catch(e:TimeoutCancellationException){error="Aggiornamento del catalogo non riuscito. Verifica la connessione e riprova."}catch(e:CancellationException){throw e}catch(e:OutOfMemoryError){archive=null;plan=null;error="Memoria insufficiente: dividere il file in layer più piccoli. Nessuna importazione confermata."}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=when{
        e is ApiError->friendlyError(e)
        e is IllegalArgumentException||e is IllegalStateException->e.message?.takeUnless{it.isBlank()||it=="Failed requirement."||it=="Check failed."}?:"File o configurazione non validi: verifica i campi e riprova."
        else->"Impossibile leggere o importare il file. Verifica che lo ZIP sia completo e riprova."
    }}finally{busy=false}}}
    suspend fun reload(){
        plan=null;archive=null
        val loaded=withContext(Dispatchers.IO){java.io.File(requireNotNull(original)).inputStream().use{Shapefile.read(it,crs.trim().takeIf{it.isNotBlank()}?.toIntOrNull()?:if(crs.isBlank())null else error("EPSG numerico richiesto"),encoding,layerEncodings)}}
        val old=mappings.associateBy{it.layer};archive=loaded
        val proposed=loaded.layers.map{old[it.name]?:ImportPlanner.propose(it)}
        mappings=proposed;mode=ImportPlanner.automaticMode(loaded,proposed)
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)task{
        archive=null;plan=null;mappings=emptyList();original?.let{java.io.File(it).delete()};original=null
        val file=withContext(Dispatchers.IO){
            val dir=java.io.File(repo.context.cacheDir,"import-preview").apply{mkdirs()};val target=java.io.File(dir,java.util.UUID.randomUUID().toString()+".zip")
            try{repo.context.contentResolver.openInputStream(uri)!!.use{input->java.io.FileOutputStream(target).use{out->val buffer=ByteArray(8192);var total=0;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=Shapefile.MAX_BYTES){"ZIP compresso oltre 32 MiB"};out.write(buffer,0,n)};out.fd.sync()}};target}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;target.delete();throw e}
        };original=file.absolutePath
        source=repo.context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0).substringBeforeLast('.') else ""}.orEmpty()
        if(source.length<3)source="sorgente-shapefile"
        val saved=repo.dao.settingValue(repo.owner(),"import-options:$source")?.let(::JSONObject)
        layerEncodings=saved?.keys()?.asSequence()?.associateWith{saved.getJSONObject(it).optString("encoding","AUTO")}.orEmpty()
        reload()
        mappings=mappings.map{m->saved?.optJSONObject(m.layer)?.let{s->val fields=s.optJSONObject("fields");if(fields!=null)m.copy(fields=fields.keys().asSequence().associateWith{fields.getString(it)}) else s.optString("key").takeIf{it.isNotBlank()}?.let{m.copy(fields=m.fields+("key" to it))}?:m}?:m}

    }}
    Dialog(onDismissRequest={if(!busy)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())){
        Text("Importa shapefile",style=MaterialTheme.typography.headlineSmall);Text("Seleziona uno ZIP con .shp, .shx e .dbf, includendo .prj e .cpg se disponibili. Massimo 32 MiB e 10000 oggetti. Potrai controllare la mappa e il riepilogo prima di confermare.",style=MaterialTheme.typography.bodySmall)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(plan==null){
            Text("Codifica dei testi: "+encodingOptions.first{it.first==encoding}.second)
            TextButton(enabled=!busy,onClick={advanced=!advanced}){Text("Opzioni avanzate")}
            if(advanced||original!=null&&archive==null){
                Choice("Codifica dei testi",encoding,encodingOptions){value->encoding=value;layerEncodings=emptyMap();if(original!=null)task{reload()}}
                Choice("Sistema delle coordinate",crs,listOf("" to "Leggi il sistema dichiarato nel .prj","4326" to "WGS84 · latitudine/longitudine","32632" to "WGS84 / UTM 32N · metri","32633" to "WGS84 / UTM 33N · metri","25832" to "ETRS89 / UTM 32N · metri","25833" to "ETRS89 / UTM 33N · metri","3857" to "Web Mercator · metri")){crs=it;plan=null;archive=null;if(original!=null)task{reload()}}
                if(original!=null)OutlinedButton(enabled=!busy,onClick={task{reload()}}){Text("Aggiorna anteprima del file")}
            }
            OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream"))}){Text("Seleziona ZIP dal telefono")}
            archive?.let{a->
                Text("${a.layers.size} layer · ${a.layers.sumOf{it.features.size}} oggetti")
                Text("Importazione automatica: sorgente «$source» · "+when(mode){"LINES"->"punti e linee";"ORDERED"->"punti ordinati";else->"punti indipendenti"}+". Se manca il collettore viene creato un collettore automatico per questa sorgente.",style=MaterialTheme.typography.bodySmall)
                Choice("Collegamenti",mode,listOf("LINES" to "Usa le linee reali","ORDERED" to "Collega i pozzetti con tratti rettilinei","ISOLATED" to "Importa solo punti, senza collegamenti")){mode=it}
                if(advanced){
                    Field("Nome sorgente stabile per le reimportazioni",source){source=it}
                    Choice("Collettore per record senza campo collettore",fallback,listOf("" to "Crea automaticamente")+catalog.filter{it.kind=="collector"&&!JSONObject(it.body).optBoolean("archived")}.map{it.id to JSONObject(it.body).getString("code")}){fallback=it}
                    Choice("Modalità",mode,listOf("LINES" to "Punti e linee reali","ORDERED" to "Punti con ordine affidabile","ISOLATED" to "Punti senza ordine / opere isolate")){mode=it}
                }
                a.layers.forEach{layer->
                    Text("${layer.name} · ${if(layer.kind=="point")"Punti" else "Linee"} · ${layer.features.size} elementi · EPSG:${layer.crs}",style=MaterialTheme.typography.titleSmall)
                    Text("Codifica caratteri: ${layer.encoding}. È distinta dai codici dei pozzetti.",style=MaterialTheme.typography.bodySmall)
                    Text(layer.encodingNote,style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!busy,onClick={advanced=true}){Text("Caratteri non corretti? Cambia codifica")}
                    if(advanced)Choice("Codifica dei testi · ${layer.name}",layer.encodingChoice,encodingOptions){value->layerEncodings=layerEncodings+(layer.name to value);task{reload()}}
                    layer.features.take(3).forEachIndexed{index,f->Text("Anteprima ${index+1}: "+f.fields.entries.joinToString(" · "){"${it.key}: ${it.value}"},style=MaterialTheme.typography.bodySmall)}
                    val map=mappings.first{it.layer==layer.name}
                    val report=keyReport(layer,map)
                    Text("Colonne per riconoscere i record: ${if(map.fields["key"]==REGISTERED_IDENTITY)"corrispondenze registrate" else report.field.ifBlank{"da scegliere"}} · ${report.empty} vuoti · ${report.duplicates} duplicati",color=if(report.valid)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    report.examples.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                    Text("Scegli colonne già presenti nel file. L’identità interna viene gestita dall’app; non devi digitare UUID. Il codice originale è conservato separatamente.",style=MaterialTheme.typography.bodySmall)
                    if(!report.valid)Text("Queste colonne non distinguono tutti i record. Aggiungi una colonna di ambito/distinzione oppure usa l’assegnazione guidata.")
                    if(map.fields["key"]==REGISTERED_IDENTITY)Text("Assegnazione guidata: ogni record distinto riceve un’identità persistente. La reimportazione riconosce soltanto record identici; per record modificati o nuovi dovrai confermare la corrispondenza. Nessuna fusione per numero o vicinanza.")
                    OutlinedButton(enabled=!busy,onClick={mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+("key" to REGISTERED_IDENTITY)+("scope" to "")+("discriminator" to ""))else it}}){Text("Usa assegnazione guidata delle identità")}
                    listOf("key" to "Colonna che riconosce il record","scope" to "Ambito della chiave (es. collettore)","discriminator" to "Ulteriore colonna di distinzione","code" to "Colonna del codice/numero originale","collector" to "Colonna del collettore di appartenenza").forEach{(k,label)->
                        Choice(label,map.fields[k].orEmpty(),listOf("" to "Non presente")+(if(k=="key")listOf(REGISTERED_IDENTITY to "Assegnazione guidata e persistente")else emptyList())+layer.fields.map{it to fieldExample(layer,it)}){field->mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+(k to field))else it}}
                    }
                    if(layer.kind=="point")Choice("Tipo per i valori GIS non riconosciuti",map.fields["default_type"]?:"UNKNOWN",listOf("UNKNOWN" to "Da verificare","MANHOLE" to "Pozzetto","PUMP_STATION" to "Sollevamento","ACCESSORY" to "Opera accessoria")){value->mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+("default_type" to value))else it}}
                    val collectorColumn=map.fields["collector"].orEmpty()
                    if(collectorColumn.isNotBlank()&&layer.features.any{it.fields[collectorColumn].orEmpty() in listOf("","0")})Text("Attenzione: $collectorColumn contiene valori vuoti o 0. Verifica l’appartenenza: il gruppo 0 non identifica necessariamente un collettore reale.",color=MaterialTheme.colorScheme.error)
                    val unresolved=unresolvedFeatures(layer,map,source.trim(),identityCatalog)
                    if(unresolved.isNotEmpty()){
                        Text("${unresolved.size} record richiedono una corrispondenza esplicita. Nessun record verrà escluso silenziosamente.")
                        unresolved.take(10).forEach{f->val fingerprint=featureFingerprint(f);val prior=sourceRecords(identityCatalog,source.trim(),layer)
                            Text(f.fields.entries.joinToString(" · "){"${it.key}: ${it.value}"},style=MaterialTheme.typography.bodySmall)
                            Choice("Questo record corrisponde a…",map.fields["match:$fingerprint"].orEmpty(),listOf("" to "Scegli una corrispondenza","@NEW" to "Confermo: è un elemento nuovo")+prior.filter{!JSONObject(it.body).deleted()}.map{old->val b=JSONObject(old.body);old.id to ("#"+b.optString("code")+" · "+b.optJSONObject("source_attributes")?.toString().orEmpty().take(140))}){chosen->mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+("match:$fingerprint" to chosen))else it}}
                        }
                    }
                    if(mode=="ORDERED"&&layer.kind=="point"&&listOf("previous","next","sequence","chainage").all{map.fields[it].isNullOrBlank()}){
                        Text("Ordine non presente nel file. Indica ramo e ordine di ogni punto: il numero 1 è l’origine del ramo. Controlla poi i collegamenti sulla mappa.")
                        CollapsibleItems("Pozzetti da ordinare",layer.features,{featureFingerprint(it)}){f->val fp=featureFingerprint(f);val label=map.value(f,"code").ifBlank{map.recordKey(f)}
                            Field("Ordine · $label",map.fields["order:$fp"].orEmpty()){value->mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+("guided_order" to "true")+("order:$fp" to value))else it}}
                            Field("Ramo · $label",map.fields["branch:$fp"].orEmpty()){value->mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+("branch:$fp" to value))else it}}
                        }
                    }
                    val keys=if(layer.kind=="point")listOf("key","code","description","collector","asset_type","under_asphalt")+(if(mode=="ORDERED")listOf("sequence","chainage","branch","previous","next")else listOf("chainage"))else listOf("key","code","collector","from","to")
                    if(advanced)keys.filter{it !in listOf("key","code","collector")}.forEach{k->Choice(mapOf("description" to "Descrizione","asset_type" to "Tipo manufatto (codici GIS originali conservati)","under_asphalt" to "Sotto asfalto (sì/no)","sequence" to "Sequenza ordinale","chainage" to "Progressiva GIS (metri)","branch" to "Ramo","previous" to "Chiave precedente","next" to "Chiave successivo","from" to "Chiave estremo iniziale","to" to "Chiave estremo finale")[k]!!,map.fields[k]?:"",listOf("" to "Non presente")+layer.fields.map{it to fieldExample(layer,it)}){field->mappings=mappings.map{if(it.layer==layer.name)it.copy(fields=it.fields+(k to field))else it}}}
                }
                if(advanced&&mode=="LINES")Field("Tolleranza automatica per estremi senza chiave, metri",tolerance){tolerance=it}
                Button(enabled=!busy&&a.layers.all{keyReport(it,mappings.first{m->m.layer==it.name}).valid},onClick={
                    val chosenMappings=mappings;val chosenSource=source.trim();val chosenFallback=fallback;val chosenMode=mode;val chosenOrder=order;val chosenTolerance=tolerance
                    task{
                        // Reimports must compare with the complete current project, not a stale UI snapshot.
                        if(repo.authenticated())try{withTimeout(30000){repo.catalog()}}catch(e:java.io.IOException){if(e is ApiError)throw e}
                        check(repo.canManageCatalog()){"Importazione riservata al responsabile del progetto"}
                        val owner=repo.owner();val existing=repo.dao.catalogNow(owner).filter{!JSONObject(it.body).deleted()}
                        identityCatalog=existing
                        if(a.layers.any{unresolvedFeatures(it,chosenMappings.first{m->m.layer==it.name},chosenSource,existing).isNotEmpty()}){error="Completa le corrispondenze dei record modificati o nuovi, poi riapri l’anteprima.";return@task}
                        plan=withContext(Dispatchers.Default){ImportPlanner.plan(a,chosenMappings,chosenSource,chosenFallback,chosenMode,chosenOrder,false,decimalItalian(chosenTolerance)?:error("Tolleranza non valida"),existing,owner)}
                    }
                }){Text(if(busy)"Preparazione anteprima…" else "Anteprima importazione automatica")}
            }
        }else plan?.let{p->
            listOf("collector" to "Collettori","point" to "Pozzetti / manufatti","segment" to "Tronchi").forEach{(kind,label)->val count=p.counts.getValue(kind)
                Text("$label: ${count.inserted} nuovi · ${count.updated} aggiornati · ${count.unchanged} invariati · ${count.missing} assenti dal file",style=MaterialTheme.typography.bodySmall)
            }
            Text("${p.warnings.size} avvisi · nessun errore bloccante")
            p.warnings.forEach{Text("• $it",style=MaterialTheme.typography.bodySmall)}
            OfflineMap(p.preview,p.preview.getJSONArray("points").objects(),null,null,null,Modifier.fillMaxWidth().height(300.dp),{},{error=it},p.preview.getJSONArray("points").objects().map{org.maplibre.android.geometry.LatLng(it.getDouble("latitude"),it.getDouble("longitude"))},1)
            CollapsibleItems("Pozzetti",p.preview.getJSONArray("points").objects(),{it.getString("id")}){point->Text("#"+point.optString("code")+" · "+topologyLabel(point,p.preview.getJSONArray("points").objects()),style=MaterialTheme.typography.bodySmall)}
            if(p.missing.isNotEmpty())Text("Assenti: "+p.missing.take(10).joinToString{JSONObject(it.body).optString("code").ifBlank{it.id}}+if(p.missing.size>10)" …" else "",style=MaterialTheme.typography.bodySmall)
            Text("Gli elementi assenti restano conservati, insieme alle ispezioni e alle foto. Il file originale rimane privato sul telefono.")
            if(repo.authenticated())Text("Dopo la conferma, l’aggiornamento verrà inviato al server. Puoi verificarne l’esito in Server e sincronizzazione.",style=MaterialTheme.typography.bodySmall)
            Button(enabled=!busy,onClick={task{repo.saveCatalog(p.items,p.provenance);repo.dao.setting(Setting(repo.owner(),"import-options:$source",JSONObject().apply{archive!!.layers.forEach{layer->put(layer.name,JSONObject().put("encoding",layer.encodingChoice).put("fields",JSONObject(mappings.first{it.layer==layer.name}.fields)))}}.toString()));message("Importazione salvata. Sincronizzazione automatica in attesa di conferma dal server.");dismiss()}}){Text("Conferma importazione")}
            TextButton(enabled=!busy,onClick={plan=null}){Text("Modifica associazione campi")}
        }
        TextButton(enabled=!busy,onClick=dismiss){Text("Chiudi")}
    }}}
}

val encodingOptions=listOf("AUTO" to "Automatica (consigliata)","UTF-8" to "Unicode — UTF-8","windows-1252" to "Europa occidentale / Windows — Windows-1252","ISO-8859-1" to "Europa occidentale / Latin-1 — ISO-8859-1")
