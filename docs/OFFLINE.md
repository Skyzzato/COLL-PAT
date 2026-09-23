# Offline e sincronizzazione — v0.2

La sessione persistente consente di lavorare senza rete sui dati già scaricati dal proprio progetto server. I dati di altri account restano isolati. Non esiste un archivio demo locale: il catalogo sintetico, quando presente, è quello autorizzato dal server.

Un collettore e i suoi pozzetti manuali sono salvati nella stessa transazione Room e pubblicati nello stesso batch atomico. La cancellazione mai inviata rimuove anagrafiche e operazioni inutili; i batch residui conservano il protocollo a chunk. Dopo un tentativo di invio, anche senza ricevuta, si conserva l'identità immutabile dell'operazione e si accoda la cancellazione server. I marcatori persistenti impediscono ricomparse da cache, refresh o reimportazione.

I due pulsanti **Aggiorna database collettori** condividono il flusso catalogo più storico ispezioni. Il timestamp cambia soltanto dopo entrambi i successi e resta invariato in caso d'errore. Le modifiche pendenti restano locali fino alla propria ricevuta.

Il contatore usa UUID di anagrafiche, schede, rettifiche e foto, una volta ciascuno: batch, chunk e retry non moltiplicano gli elementi. Errori e caricamenti in corso restano pendenti. Sono contate anche le bozze condivise in attesa; una copia di recupero locale senza operazione di invio non è conteggiata.

Le bozze e le foto si salvano subito sul telefono. Per gli account autenticati il worker trasmette le modifiche appena possibile, conservando un payload immutabile per ogni tentativo. Una ricevuta valida chiude solo la modifica corrispondente; modifiche successive rimangono in attesa. Il pulsante Sincronizza effettua anche un aggiornamento del catalogo e del riepilogo ispezioni. Lo storico completo viene richiesto entrando nella pagina Ispezioni.

L’export CSV include esclusivamente il trimestre civile corrente, secondo Europe/Rome, con nome `COLL-PAT_ispezioni_2026_T3.csv`. Riutilizza la RPC paginata `coll_pat_semester_history` già distribuita per scaricare il semestre contenente il trimestre e filtra le righe sul telefono. L’attesa di rete è limitata a 10 secondi: in caso di indisponibilità esporta i dati locali, dichiarandolo all’utente. Errori di autorizzazione o versione restano espliciti. Se mancano record non apre il selettore di salvataggio; annullamento ed errori del selettore hanno un messaggio dedicato. Correzioni incluse nella v0.15, descritte inizialmente nella [verifica funzionale](VERIFICA-FUNZIONALE-v0.14.md).

Un conflitto di revisione non sostituisce i dati locali. La scheda spiega il problema e permette di conservare una copia di recupero prima di aprire la versione server. Dopo la risoluzione l’operatore decide come riportare le proprie modifiche: nessuna sovrascrittura automatica della bozza remota.

Le fotografie remote mostrano i metadati condivisi anche prima del download. Per vederle senza rete occorre averle aperte e memorizzate almeno una volta. Il nome Storage rimane lo stesso durante i retry. Originali locali e audit rimangono privati.

Le soglie GPS vengono incluse nella rilevazione. Modificare le preferenze non cambia la qualità storica. La creazione di eventi usa il [flusso GPS mediato](GPS.md). Una distanza o corrispondenza non verificata richiede eccezione motivata; accuracy oltre soglia è sempre bloccante. L’impedimento può essere documentato senza inventare un evento GPS.

La mappa usa l’unica cache MapLibre preesistente da 200 MiB; non è un pacchetto cartografico garantito. Le aree mai caricate possono mancare. Nessun download preventivo dai server OSM standard.

Una policy server già nota di versione obsoleta blocca l’app anche offline. In assenza di rete e di una policy nota, la cache rimane utilizzabile. Dopo arresto forzato i lavori riprendono alla riapertura.

Le operazioni v0.13 ancora in attesa sono conservate nell’archivio di recupero, senza inventare autori o soglie GPS. [Documentazione v0.13](history/v0.13/OFFLINE.md).

Salva bozza e Registra Ispezione mostrano un esito dopo il commit Room e tornano dopo due secondi alla pagina precedente. Le bozze esplicite hanno già una outbox persistente; gli autosalvataggi sono conservati e recuperabili dal worker. Rete disponibile non equivale a ricevuta server. Una ricevuta corrispondente aggiorna il messaggio senza riavviare il timer, mentre foto in coda restano segnalate separatamente. Errore locale o conflitto mantengono la pagina aperta.
