package it.pat.collettori

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.time.Instant

@Composable fun QuarterExportButton(repo:Repository,onMessage:(String)->Unit){
    val scope=rememberCoroutineScope();var job by remember{mutableStateOf<Job?>(null)}
    var busy by remember{mutableStateOf(false)};var status by rememberSaveable{mutableStateOf("")}
    var year by rememberSaveable{mutableStateOf(Instant.now().atZone(ExportPeriod.rome).year.toString())}
    var chosen by remember{mutableStateOf<ExportPeriod?>(null)};var localPrompt by remember{mutableStateOf<ExportPeriod?>(null)}
    var pendingFile by rememberSaveable{mutableStateOf<String?>(null)};var localOnly by rememberSaveable{mutableStateOf(false)}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        val path=pendingFile;pendingFile=null
        // A confirmed SAF write survives navigation. Only a live screen receives the result.
        val write=repo.writes.async {
            try{if(uri!=null){val file=path?.let(::File)?.takeIf{it.isFile}?:error("File temporaneo non disponibile")
                repo.context.contentResolver.openOutputStream(uri,"wt")?.use{output->file.inputStream().use{it.copyTo(output)}}?:error("File non scrivibile")}}
            finally{path?.let{File(it).delete()}}
        }
        scope.launch{try{write.await();status=if(uri==null)"Esportazione annullata" else if(localOnly)"CSV salvato: solo storico locale, completezza remota non verificata" else "CSV salvato: storico server e ispezioni definitive locali";onMessage(status)}catch(e:CancellationException){throw e}catch(e:Exception){onMessage("Scrittura CSV non riuscita: "+friendlyError(e))}}
    }
    fun prepare(period:ExportPeriod,onlyLocal:Boolean){
        busy=true;status="Recupero storico · ${period.label}";chosen=null;localPrompt=null
        job=scope.launch{var temporary:File?=null;try{
            val rows=if(onlyLocal)exportRows(repo.dao.visitsNow(repo.owner()),period) else try{
                withTimeoutOrNull(120000){repo.exportHistory(period)}?:throw java.io.IOException("Tempo di recupero scaduto")
            }catch(e:ApiError){if(e.code in setOf(401,403,426))throw e else {localPrompt=period;return@launch}}
            catch(e:java.io.IOException){localPrompt=period;return@launch}
            if(rows.isEmpty()){status=if(onlyLocal)"Nessuna ispezione nel periodo fra i dati locali; storico remoto non verificato" else "Nessuna ispezione nel periodo selezionato";return@launch}
            val pack=repo.localCatalog();val photos=rows.filter{PhotoRepository(repo).list(it).isNotEmpty()}.map{it.id}.toSet()
            val file=withContext(Dispatchers.IO){File.createTempFile("coll-pat-export-",".csv",repo.context.cacheDir).also{file->temporary=file
                try{file.bufferedWriter(Charsets.UTF_8).use{InspectionCsv.writePeriod(it,rows,pack?.optJSONArray("points")?.objects().orEmpty(),pack?.optJSONArray("collectors")?.objects().orEmpty(),photos,period,onlyLocal)}}catch(e:Exception){file.delete();throw e}
            }}
            pendingFile=file.absolutePath;localOnly=onlyLocal;status="${rows.size} ispezioni · ${period.label}"
            exporter.launch("COLL-PAT_ispezioni_${period.filenamePart}${if(onlyLocal)"_SOLO_LOCALI" else ""}.csv")
            temporary=null
        }catch(e:CancellationException){throw e}catch(e:OutOfMemoryError){status="Storico troppo grande per la memoria disponibile: scegli un anno o un trimestre.";onMessage(status)}catch(e:Exception){status=friendlyError(e);onMessage(status)}finally{temporary?.delete();busy=false}}
    }
    val enabled=!busy&&pendingFile==null
    OutlinedButton(enabled=enabled,onClick={chosen=ExportPeriod.lastCompletedQuarter(Instant.now())}){Text("Esporta ultimo trimestre concluso")}
    Field("Anno da esportare",year){year=it}
    OutlinedButton(enabled=enabled&&(year.toIntOrNull()?:0) in 1900..9998,onClick={chosen=ExportPeriod.year(year.toInt())}){Text("Esporta ispezioni dell’anno")}
    OutlinedButton(enabled=enabled,onClick={chosen=ExportPeriod.all()}){Text("Esporta tutte le ispezioni")}
    if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={job?.cancel();status="Esportazione annullata"}){Text("Annulla esportazione")}}
    if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall)
    chosen?.let{period->AlertDialog(onDismissRequest={chosen=null},title={Text("Esportazione CSV")},text={Text("Periodo: ${period.label}. Sono esclusi bozze e annullamenti; gli impedimenti mantengono il loro esito. I filtri della pagina Ispezioni non si applicano.")},confirmButton={TextButton(onClick={prepare(period,false)}){Text("Esporta")}},dismissButton={TextButton(onClick={chosen=null}){Text("Annulla")}})}
    localPrompt?.let{period->AlertDialog(onDismissRequest={localPrompt=null},title={Text("Storico remoto non disponibile")},text={Text("Non è possibile verificare la completezza dello storico. Puoi esportare solo i dati disponibili sul dispositivo, marcati SOLO DATI LOCALI. Periodo: ${period.label}")},confirmButton={TextButton(onClick={prepare(period,true)}){Text("Esporta solo dati locali")}},dismissButton={TextButton(onClick={localPrompt=null;status="Esportazione annullata"}){Text("Annulla")}})}
}
