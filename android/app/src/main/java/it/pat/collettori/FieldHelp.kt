package it.pat.collettori
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

val fieldHelp=mapOf(
    "Manufatto accessibile" to "Indica se è possibile raggiungere il pozzetto e la zona di lavoro nelle condizioni presenti. L'accessibilità non implica che apertura e controllo siano eseguibili in sicurezza.",
    "Apertura effettuata" to "Indica se la botola è stata effettivamente aperta durante questo controllo. Se non è stata aperta, non dichiarare osservazioni interne che non hai potuto svolgere.",
    "Botola e telaio" to "Condizioni visibili del coperchio e del suo telaio: integrità, corretto appoggio, stabilità ed eventuali difetti o ostacoli alla normale apertura e chiusura.",
    "Depositi e materiali estranei" to "Presenza di sedimenti, rifiuti o altri materiali che occupano il pozzetto o possono ostacolare il passaggio del refluo. Valuta soltanto quanto effettivamente osservabile.",
    "Deflusso" to "Condizioni visibili del passaggio del refluo: regolarità dello scorrimento, ristagni, rigurgiti o possibili ostruzioni. La voce non richiede una misura della portata.",
    "Pareti e canalette" to "Stato visibile delle superfici interne e delle canalette di scorrimento, con attenzione a deterioramenti, discontinuità e depositi che ne compromettono la funzionalità.",
    "Danni, infiltrazioni e radici" to "Evidenze visibili di rotture, fessure, ingressi d'acqua o radici. Descrivi nelle Note / Anomalie ciò che hai osservato e la zona interessata.",
    "Richiusura" to "Verifica conclusiva del corretto riposizionamento della botola e delle condizioni di chiusura dopo l'apertura. Se la botola non è stata aperta, la voce può essere non applicabile.",
    "Ripristino area" to "Condizioni della zona al termine dell'attività: rimozione dei materiali e delle attrezzature usate e ripristino dell'area interessata. Non dichiarare eseguite attività non svolte.",
    "Pulizia eseguita" to "Seleziona soltanto se durante questa attività è stata effettivamente eseguita una pulizia. L'assenza di depositi non significa che sia stata effettuata una pulizia.",
    "Controllo non eseguibile in sicurezza" to "Segnala che le condizioni presenti non permettono il controllo in sicurezza. Le parti non verificate non devono risultare regolari; documenta il motivo tramite la registrazione dell'impedimento.",
    "Condizioni del manto" to "Stato visibile della pavimentazione nell'area del pozzetto sotto asfalto: lesioni, dissesti o altri segni superficiali da descrivere.",
    "Avvallamenti / cedimenti" to "Presenza di abbassamenti o deformazioni visibili della superficie in prossimità del manufatto. La verifica è esterna e non certifica lo stato delle parti interrate.",
    "Necessità di rimessa in quota" to "Segnala la necessità osservata di un intervento sul rapporto tra botola e quota della superficie. Descrivi l'esigenza; la segnalazione non equivale a progettazione o autorizzazione dell'intervento.",
    "Necessità di ripristino stradale" to "Segnala problemi della pavimentazione nell'area del pozzetto che richiedono una valutazione o un intervento. Specifica quanto osservato nelle Note / Anomalie.",
    "Note / Anomalie" to "Spazio unico per osservazioni, anomalie e necessità di intervento. Distingui ciò che hai osservato dalle ipotesi e indica, quando utile, la parte interessata."
)
@Composable fun HelpButton(label:String){
    var show by remember{mutableStateOf(false)}
    IconButton(onClick={show=true},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)){
        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_info),"Informazioni: $label")
    }
    if(show)AlertDialog(onDismissRequest={show=false},title={Text(label)},text={Text(fieldHelp[label].orEmpty())},confirmButton={TextButton(onClick={show=false}){Text("Chiudi")}})
}
@Composable fun InfoLabel(label:String){Row(verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));HelpButton(label)}}
@Composable fun InfoCheck(label:String,value:Boolean,enabled:Boolean=true,change:(Boolean)->Unit){Row(verticalAlignment=Alignment.CenterVertically){Checkbox(value,change,enabled=enabled);Text(label,Modifier.weight(1f));HelpButton(label)}}
@Composable fun InfoChoice(label:String,value:String,choices:List<Pair<String,String>>,change:(String)->Unit){
    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Choice(label,value,choices,change)};HelpButton(label)}
}
