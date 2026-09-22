# Changelog

## v0.14 — COLL-PAT (pre-release)

- Periodicità centralizzata, quattro stati, filtri aggiornati e zoom/simbologia configurabili.
- Impostazioni a sezioni, card navigabili, colori collettori e archiviazione persistente.
- Login/registrazione, sessione persistente, bozze condivise con revisione e autori server.
- Storage foto privato con UUID stabili, GPS con soglie separate e qualità storica.
- CSV semestrale tramite SAF, controllo versione server/cache, terzo collettore demo Via Gilli.
- Migrazioni SQL additive e Room 3 per isolamento degli account; test SQL e Android.
- Ricompilazione nella stessa v0.14 con URL e publishable key preimpostati; confermate risposte remote Auth e versione minima, 50 test JVM demo e 13 Android rieseguiti.
- Registrazione/accesso reali, Storage remoto e collaudo sul campo ancora da eseguire.

## v0.13 — COLL-PAT (prerelease)

- Rinomina prodotto e repository, versione 13, splash asincrono con logo originale.
- Catalogo Trento/Lavis, anagrafica completa, selezione multipla e visibilità locale.
- Nuova scheda ordinaria/esterna/impedimento, autosave, audit GPS/ispezioni; rimosso il nuovo flusso parziale.
- Room v2 e outbox; Supabase Auth/RPC/PostGIS diretti, idempotenza e reset generazionale.
- Importazione ZIP/shapefile Android, mapping/provenienza e staging atomico; admin temporaneo nella demo.
- Cache unica 200 MiB, calendario Europe/Rome, documentazione e test aggiornati.
- Migrazioni remote e reset iniziale non eseguiti: timeout del collegamento configurato. Foto locali, upload simulato.

## 0.12 — 2026-09-19

Corretto il crash all’avvio: MapLibre inizializzato prima della configurazione HTTP. Ripristinato INTERNET per OSM nella demo. GPS assente/disattivato e coordinate invalide gestiti esplicitamente; eliminata la doppia attivazione del lifecycle mappa. Corretto il reset del testo durante il salvataggio automatico. Nome unico Collettori, logo originale tubo pixel art, splash e icone operative coerenti. Schema Room v1 invariato, nessuna migrazione o cancellazione dati. Collaudo su emulatore Android 35 documentato in `docs/VALIDATION-v0.12.md`.

## 0.11 — 2026-09-18 (pre-release)

Mappa OSM e quattro tab demo; dataset Trento con 10 pozzetti. Identificazione GPS con accuratezza, candidati e conferma. Ispezioni con default regolari, anomalie e riepilogo. Foto locali con repository remoto non configurato. Contratti RFID/NFC/QR e associazioni tag. Documentazione completa e test aggiunti. Nessuna migration SQL; backend compatibile con app_version 0.11.

# Changelog

## 0.1-demo — prova autonoma Android

- APK separato Collettori, senza login, server, scadenza sessione o permesso Internet.
- 16 pozzetti e 15 tratti sintetici inclusi, mappa locale, GPS reale e schede persistenti.
- Revisione locale ed esportazione demo distinta dai recuperi operativi; nessun invio o ricevuta server.

## 0.1 — pilota iniziale

- Migrazione SQL Supabase in schema privato, ruolo backend dedicato, RLS e configurazione Direct/Session pooler.
- Compose Supabase separato, verifica SQL dei permessi e prompt di passaggio v0.1.
- Progetto nuovo Kotlin/Compose con Room, WorkManager e MapLibre Native.
- Selezione esplicita da mappa/elenco, ricerca e filtri locali.
- Evento di localizzazione corrente, età monotona, accuratezza, permesso e flag di simulazione; nessuna localizzazione in background.
- Regola sperimentale condivisa con il server e vettori di test comuni.
- Bozze persistenti, completamenti, correzioni con revisioni, coda idempotente e isolamento tra account.
- Importazione shapefile con mapping, CRS/codifica obbligatori, UUID stabili, archivio fonte e validazione.
- Base GeoJSON locale e risorse dello stile nell'APK; demo interamente sintetica.
- Backend FastAPI, migrazione, modello relazionale, geometrie PostGIS versionate e indice spaziale.
- Portale per controlli, scadenze, revisione documentale, anomalie, account e regole.
- Emissioni trimestrali immutabili XLSX, CSV UTF-8 e JSON; note trattate come testo.
- Procedure di avvio, backup, ripristino ed esportazione del patrimonio dati.
- QR, NFC, codice targhetta, fotocamera e fotografie assenti dalla versione attuale.
