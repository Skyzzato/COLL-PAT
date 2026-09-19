# Changelog

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
