# Offline e sincronizzazione — v0.14

La sessione persistente consente di lavorare sui dati disponibili nel proprio archivio senza rete. I dati di altri account restano isolati. La demo è un archivio distinto, avviato esplicitamente dalla schermata iniziale o da Impostazioni → Account → Apri demo offline. Tornare al progetto server richiede nuovamente il login; le copie locali precedenti restano conservate.

Le bozze e le foto si salvano subito sul telefono. Per gli account autenticati il worker trasmette le modifiche appena possibile, conservando un payload immutabile per ogni tentativo. Una ricevuta valida chiude solo la modifica corrispondente; modifiche successive rimangono in attesa. Il pulsante Sincronizza effettua anche un aggiornamento del catalogo e del riepilogo ispezioni. Lo storico completo viene richiesto entrando nella pagina Ispezioni.

L’export CSV include esclusivamente il trimestre civile corrente, secondo Europe/Rome, con nome `COLL-PAT_ispezioni_2026_T3.csv`. Riutilizza la RPC paginata `coll_pat_semester_history` già distribuita per scaricare il semestre contenente il trimestre e filtra le righe sul telefono. L’attesa di rete è limitata a 10 secondi: in caso di indisponibilità esporta i dati locali, dichiarandolo all’utente. Errori di autorizzazione o versione restano espliciti. Se mancano record non apre il selettore di salvataggio; annullamento ed errori del selettore hanno un messaggio dedicato. Correzioni incluse nella v0.15, descritte inizialmente nella [verifica funzionale](VERIFICA-FUNZIONALE-v0.14.md).

Un conflitto di revisione non sostituisce i dati locali. La scheda spiega il problema e permette di conservare una copia di recupero prima di aprire la versione server. Dopo la risoluzione l’operatore decide come riportare le proprie modifiche: nessuna sovrascrittura automatica della bozza remota.

Le fotografie remote mostrano i metadati condivisi anche prima del download. Per vederle senza rete occorre averle aperte e memorizzate almeno una volta. Il nome Storage rimane lo stesso durante i retry. Originali locali e audit rimangono privati.

Le soglie GPS vengono incluse nella rilevazione. Modificare le preferenze non cambia la qualità storica. La creazione di eventi usa il [flusso GPS mediato](GPS.md). Una distanza o corrispondenza non verificata richiede eccezione motivata; accuracy oltre soglia è sempre bloccante. L’impedimento può essere documentato senza inventare un evento GPS.

La mappa usa l’unica cache MapLibre preesistente da 200 MiB; non è un pacchetto cartografico garantito. Le aree mai caricate possono mancare. Nessun download preventivo dai server OSM standard.

Una policy server già nota di versione obsoleta blocca l’app anche offline. In assenza di rete e di una policy nota, la cache rimane utilizzabile. Dopo arresto forzato i lavori riprendono alla riapertura.

Le operazioni v0.13 ancora in attesa sono conservate nell’archivio di recupero, senza inventare autori o soglie GPS. [Documentazione v0.13](history/v0.13/OFFLINE.md).

Salva bozza e Registra Ispezione mostrano un esito dopo il commit Room e tornano dopo due secondi alla pagina precedente. Le bozze esplicite hanno già una outbox persistente; gli autosalvataggi sono conservati e recuperabili dal worker. Rete disponibile non equivale a ricevuta server. Una ricevuta corrispondente aggiorna il messaggio senza riavviare il timer, mentre foto in coda restano segnalate separatamente. Errore locale o conflitto mantengono la pagina aperta.
