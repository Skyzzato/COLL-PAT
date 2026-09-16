# Changelog

## 0.1-demo — prova autonoma Android

- APK separato Collettori Demo, senza login, server, scadenza sessione o permesso Internet.
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
