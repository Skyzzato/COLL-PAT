# Modello dati COLL-PAT v0.13

## Anagrafica

`catalog` locale e `coll_pat.catalog` remoto: UUID stabile, ambito owner/progetto, kind (`collector`, `point`, `segment`), body strutturato, stato locale. I campi sono persistiti in JSON/JSONB e validati in Kotlin e SQL; PostGIS conserva inoltre una geometria SRID 4326 indicizzata.

Collettore: `code` obbligatorio e unico nel progetto (SQL senza distinzione maiuscole), `description` distinta, `type` ∈ CV/CZI/CR/BOE'/opere accessorie, `visits_h1=2`, `visits_h2=2`, `hours_km_visit=2`, `length_m` nullable, `length_source` UNAVAILABLE/MEASURED/ESTIMATED/DECLARED, `length_complete`, `archived`. Visite intere non negative; ore decimali non negative, interfaccia con virgola. Non sono conteggi di visite completate dell'intero collettore.

Punti: codice testuale (zeri conservati), descrizione, lat/lon, tipo manufatto, appartenenze multiple `collectors`, incertezza cartografica nullable. Segmenti: geometria, estremi UUID, appartenenze multiple, flag schematico. Lunghezze in metri: Haversine locale, PostGIS geography sferica remota; unione spaziale dei tratti per non duplicare geometrie sovrapposte. Un dato dichiarato non è sovrascritto dall'importazione.

Provenienza: `source_identity = sorgente|layer|tipo|chiave`, `source_key`, hash, mapping, rapporto e originale privato locale. Reimportazioni ritrovano l'UUID per chiave persistente, mai per codice, coordinate o posizione di riga. Nessuna rimozione implicita degli oggetti assenti da un file parziale.

## Schede e protocollo

Scheda: UUID nuovo, autore, manufatto, progetto, generazione, versione payload 2, versione app da BuildConfig, timestamp UTC, modello, sheet, eventi GPS, metadati foto, esito e qualificazione periodica esplicita. Modelli ORDINARY, ASPHALT_EXTERNAL, ASSET_EXTERNAL. Nuovi esiti COMPLETO/IMPEDITO; PARZIALE precedente solo leggibile. Le bozze non hanno operazioni remote.

Ogni outbox contiene operation UUID, scope, tipo, riferimento, payload immutabile e generazione. Nessun UNIQUE manufatto/data. Stato ricevuto soltanto dopo RPC verificata. Tipi: inspection, cancel, catalog_chunk, catalog. La versione di reset non viene ricostruita da data o sessione attuale.

Annullamento: audit UUID, autore, timestamp, motivo, target evento/scheda e originale preservato. Nelle bozze l'evento annullato resta nel JSON; dopo registrazione è una rettifica separata. La valutazione corrente usa l'ultimo evento **attivo** per timestamp/UUID, non il più favorevole. Il riepilogo originale non viene riscritto.

Date: timestamp ISO UTC; giornata e semestre Europe/Rome dalla data di esecuzione, non ricezione. Reset: generation persistente, log autore/ambito/data/conteggio; nessun CASCADE generalizzato.

## Migrazioni

Room 1→2: aggiunte `catalog`, `audit`, `imports`, colonne outbox project/generation/payloadVersion/kind. Vecchie operazioni `LEGACY_SUSPENDED`; schede inalterate, backup privato v0.12 una tantum. SQL `202609220001_coll_pat_v013.sql` e `202609220002_coll_pat_spatial.sql`; precedente migrazione 202609160001 immutata. [Setup e stato reale](SUPABASE.md).
