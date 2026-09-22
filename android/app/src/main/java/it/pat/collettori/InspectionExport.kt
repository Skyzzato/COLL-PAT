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
import java.io.IOException
import java.time.Instant

@Composable fun QuarterExportButton(repo:Repository,onMessage:(String)->Unit){
    val scope=rememberCoroutineScope()
    var busy by remember{mutableStateOf(false)}
    var status by rememberSaveable{mutableStateOf("")}
    // Only the private file path is saved in Activity state, never the whole CSV.
    var pendingFile by rememberSaveable{mutableStateOf<String?>(null)}
    var cachedExport by rememberSaveable{mutableStateOf(false)}
    fun report(text:String){status=text;onMessage(text)}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        val path=pendingFile
        busy=true
        scope.launch{
            try{
                if(uri==null){report("Esportazione annullata.");return@launch}
                withContext(Dispatchers.IO){
                    val file=path?.let(::File)?.takeIf{it.isFile}?:throw IOException("Export file unavailable")
                    repo.context.contentResolver.openOutputStream(uri,"wt")?.use{output->file.inputStream().use{it.copyTo(output)}}?:throw IOException("Document unavailable")
                }
                report(if(cachedExport)"CSV salvato con le ispezioni disponibili sul telefono." else "CSV del trimestre salvato.")
            }catch(e:CancellationException){throw e}
            catch(_:Exception){report("Esportazione CSV non riuscita. Scegli un’altra cartella o riprova.")}
            finally{pendingFile=null;busy=false;withContext(NonCancellable+Dispatchers.IO){path?.let{File(it).delete()}}}
        }
    }
    OutlinedButton(enabled=!busy&&pendingFile==null,onClick={
        busy=true;status="Preparazione del CSV del trimestre…"
        scope.launch{
            try{
                val now=Instant.now();cachedExport=false
                if(repo.authenticated()){
                    // Reuse the deployed paged RPC; only civil-quarter rows enter the CSV.
                    cachedExport=try{withTimeoutOrNull(10000){repo.downloadHistory(semesterOnly=semester(now));true}!=true}
                    catch(e:CancellationException){throw e}
                    catch(e:ApiError){if(e.code in listOf(401,403,426))throw e else true}
                    catch(_:IOException){true}
                }
                val account=repo.owner();val rows=InspectionCsv.rows(repo.dao.visitsNow(account),now)
                if(rows.isEmpty()){
                    report(if(cachedExport)"Nessuna ispezione disponibile sul telefono per il trimestre corrente. Non è stato possibile aggiornare i dati dal server." else "Nessuna ispezione disponibile nel trimestre corrente.")
                    return@launch
                }
                val pack=repo.dao.pack(account,AppSpec.PACKAGE)?.let{JSONObject(it.body)}
                val photoIds=rows.filter{PhotoRepository(repo).list(it).isNotEmpty()}.map{it.id}.toSet()
                pendingFile=withContext(Dispatchers.IO){
                    val csv=InspectionCsv.export(rows,pack?.optJSONArray("points")?.objects().orEmpty(),pack?.optJSONArray("collectors")?.objects().orEmpty(),photoIds,now)
                    File.createTempFile("coll-pat-quarter-",".csv",repo.context.cacheDir).also{it.writeText(csv,Charsets.UTF_8)}.absolutePath
                }
                status=if(cachedExport)"Scegli dove salvare il CSV. Sono incluse le ispezioni disponibili sul telefono." else "Scegli dove salvare il CSV del trimestre."
                exporter.launch(InspectionCsv.filename(now))
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                pendingFile?.let{path->withContext(Dispatchers.IO){File(path).delete()}};pendingFile=null
                report(if(e is ApiError&&e.code in listOf(401,403,426))friendlyError(e) else "Impossibile preparare il CSV del trimestre. Riprova tra poco.")
            }finally{busy=false}
        }
    }){ActionIcon(R.drawable.ic_export);Text("Esporta ispezioni trimestre")}
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall)
}
