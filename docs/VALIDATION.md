# Verifica della consegna 0.1

## Eseguito in questo ambiente

| Verifica | Esito |
|---|---|
| Suite Python/API e configurazione Supabase | **43 test passati**; 1 test PostGIS saltato perché manca il server di test |
| Migrazione Alembic su database locale vuoto | Eseguita con successo |
| Importazione di 12.500 punti sintetici | Passata; UUID distinti, numeri ripetuti tra collettori ammessi |
| Regola GPS Python | 18 casi condivisi, soglie esatte, permessi, g sconosciuta, simulazione, assenza/vecchiaia della misura |
| Regola/stile Kotlin JVM | **3 test passati**, comprendono gli stessi 18 casi e distanza numerica comune |
| Compilazione Android debug | **Riuscita**, versione 0.1, build 1 |
| Compilazione APK dei test strumentali | Riuscita; non equivale all'esecuzione su dispositivo |
| Manifest dell'APK tramite aapt | Assenti fotocamera, NFC, accesso a identificativi telefonici e localizzazione in background |
| Server HTTP Uvicorn su loopback con DB isolato | Login, catalogo, dataset, creazione rapporto e download XLSX/CSV/JSON verificati |
| Browser Edge headless sul server reale locale | Login, dashboard, dettaglio controllo e revisione documentale, creazione/download rapporto, creazione account, modifica ambiti, disabilitazione e logout verificati |
| Controllo sintassi JavaScript e compilazione Python | Passati |
| `pip check` | Nessuna incompatibilità dichiarata dalle dipendenze |
| Supabase reale, verifica successiva | Migrazione applicata dall'utente; connessione backend, schema, PostGIS, creazione amministratore e importazione di 16 punti/15 tratti sintetici riusciti. Test SQL completo dei permessi e prove Android ancora da eseguire |

I test API coprono inoltre idempotenza dopo risposta persa, conflitti di contenuto, eventi non riutilizzabili tra visite, autorizzazioni e revoca, rinnovo del token, revisioni immutabili, recupero controllato con autore originale disabilitato, importazione senza CRS, aggiornamento dei codici senza cambiare lo storico, ritardi, anomalie/revisione documentale indipendenti, emissioni congelate e zeri iniziali in XLSX.

La suite Python segnala un avviso di deprecazione interno a Starlette/AnyIO relativo a `BlockingPortal`, senza errori nei test. La toolchain Android segnala differenza di versione XML dei metadati SDK e alcune librerie native non spogliate dei simboli: la build riesce. Non sono stati aggiornati indiscriminatamente gli strumenti installati.

## Artefatti

- App: `android/app/build-pilot/outputs/apk/debug/app-debug.apk`
- Test strumentali: `android/app/build-pilot/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Rapporto JVM: `android/app/build-pilot/reports/tests/testDebugUnitTest/index.html`
- Schema Room esportato: `android/app/schemas/it.pat.collettori.LocalDatabase/1.json`
- Log delle esecuzioni locali e screenshot del portale: directory `.tools/` esclusa dal versionamento.

SHA-256 dell'APK prodotto:

```text
43902db6d65706bc510ee069436b6d829bb360d13f59337cfdf960b1048bc55e
```

È una build di debug firmata per sviluppo, non una distribuzione istituzionale approvata. Nessun deploy reale o attivazione di servizi a pagamento è stato eseguito. Il processo HTTP di collaudo viene arrestato dallo script al termine.

## Non eseguito / da verificare

- Nessun telefono collegato (`adb devices` vuoto) e nessun dispositivo virtuale configurato: **nessuna prova reale di GPS, modalità aereo, rendering MapLibre su Android, chiusura forzata, WorkManager o migrazione su telefono** è dichiarata eseguita.
- Docker non disponibile: immagine e Docker Compose non avviati qui; ramo PostgreSQL/PostGIS, backup e ripristino vanno collaudati nell'ambiente predisposto. Il test opzionale richiede `POSTGIS_TEST_DATABASE_URL`; il workflow GitHub è ad attivazione manuale e non è stato eseguito.
- Nessun dataset PAT reale ricevuto: qualità, chiavi stabili, CRS, topologia, g documentata e base autorizzata devono essere confermati dal referente GIS. La cartografia inclusa è sintetica, non pronta per guidare operazioni reali.
- Nessun benchmark grafico Android su 12.500 punti: il test di dimensionamento eseguito riguarda la pipeline dati.
- Licenze della base reale, durata offline, soglie sperimentali, dispositivi compatibili, responsabilità, conservazione e procedure di sicurezza/privacy/contratto devono essere validati prima dell'impiego operativo.

## Limiti funzionali espliciti della v0.1

- Interfaccia amministrativa essenziale; importazione GIS tramite CLI, non editor cartografico web.
- Base offline: GeoJSON locale per un'area limitata. Nessun pacchetto cartografico operativo dell'intero Trentino è incluso.
- Storico sul telefono limitato all'account; portale con visione degli ambiti autorizzati. Il filtro anomalie mobile si riferisce alle schede locali; lo stato aperta/chiusa autorevole è nel portale.
- Conflitti conservati e segnalati, recupero/esame umano; nessuna fusione automatica.
- Nessuna ricorrenza di scadenze, gestione complessa degli ordini di lavoro o importazione dei vecchi fogli storici: le scadenze sono assegnate esplicitamente.
- Nessuna garanzia di autenticità di posizione/orologio su un dispositivo compromesso, né di recupero da un dispositivo perduto senza trasmissione.
- Le funzioni QR, codice targhetta, NFC, sensori e fotografie sono rinviate come richiesto, non presentate come disponibili.
