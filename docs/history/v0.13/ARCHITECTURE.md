# Architettura COLL-PAT v0.13

La UI Compose condivide catalogo, filtri e navigazione. MapLibre è inizializzato in Application **prima** del client HTTP; la mappa resta montata cambiando scheda, le sorgenti GeoJSON sono aggiornate senza ricaricare tutto lo stile. Camera salvabile e liste con stato conservato. Splash applicativo asincrono di almeno 3 secondi al primo avvio del processo, con preparazione locale parallela e timeout recuperabile; nessuna rete necessaria.

Room v2 (`pilot-v1.db`) è l'archivio di lavoro: schede, anagrafica, importazioni, audit, preferenze e outbox. Migrazione 1→2 aggiunge tabelle/colonne; conserva i body e sospende le code precedenti. Snapshot del catalogo all'inizio della scheda per evitare di cambiare il punto di riferimento delle evidenze.

Autosalvataggi ordinati in scope applicativo; uscita e registrazione attendono le scritture precedenti. Un mutex serializza le mutazioni e la transazione salva scheda+outbox prima del feedback. Le scritture tardive non riportano una scheda registrata allo stato bozza. Doppio tap idempotente sulla stessa scheda, UUID nuovo per ogni nuovo sopralluogo.

WorkManager: vincolo rete, lavoro univoco e backoff; recupera gli `IN_CORSO`. Una coda è legata a URL, utente Auth, progetto e generazione. Auth scaduta sospende; payload invalidi/conflitti diventano terminali e non vengono riaccodati automaticamente. Un errore dell'anagrafica blocca le schede dipendenti. Creazione precede annullamento; nessuna cancellazione locale della creazione già accodata.

RPC `coll_pat_apply`: identifica l'autore dalla sessione Auth, verifica ruolo/progetto, blocca la riga progetto, confronta generazione e contenuto dell'operazione, scrive e rilascia ricevuta atomicamente. Retry con risposta persa restituisce la stessa ricevuta. Tutte le tabelle private hanno RLS attiva e nessun DML client; funzioni SECURITY DEFINER con `search_path=''`, riferimenti qualificati ed EXECUTE solo a authenticated. Nessuna API per autoassegnarsi ruoli.

Reset e invii condividono il blocco della riga progetto. Backup con token legato a revisione ispezioni, autore, generazione e scadenza; se arrivano nuove schede occorre un nuovo backup. Il reset elimina solo schede/dipendenze del progetto, incrementa la generazione e conserva log minimo e anagrafica. Al ritorno online le vecchie operazioni sono sospese, mai rietichettate.

Importazione grande: chunk privati, idempotenti e ordinati; elenco esplicito delle ricevute nella pubblicazione finale. La validazione e la scrittura dell'intero catalogo avvengono in un'unica transazione. Nessun catalogo parzialmente pubblico. La controparte locale salva tutti gli oggetti e la coda in una transazione Room.

Sessioni cifrate con Android Keystore. La chiave client è pubblica, mai service_role/secret o password PostgreSQL. Le credenziali legacy restano private e richiedono un collegamento Auth esplicito; l'identità demo non viene automaticamente trasformata in autore remoto. Il backend Python resta storico, non obbligatorio.
