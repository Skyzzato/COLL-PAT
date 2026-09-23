# COLL-PAT — v0.2 pre-release

App Android per cartografia, pozzetti, ispezioni periodiche, GPS e fotografie, con cache locale e sincronizzazione Supabase. L'app lavora esclusivamente sul progetto server: non contiene un ambiente demo locale.

[Pre-release v0.2](https://github.com/Skyzzato/COLL-PAT/releases/tag/v0.2) · [APK v0.2](https://github.com/Skyzzato/COLL-PAT/releases/download/v0.2/COLL-PAT-v0.2.apk) · [Collaudo](docs/VALIDATION-v0.2.md) · [Requisiti correnti](docs/REQUIREMENTS-v0.2.md)

La v0.16 pubblica sul progetto server tre collettori **sintetici** di Trento, Lavis e Via Gilli (3 collettori, 22 manufatti e 19 tronchi). Sono dati di prova chiaramente marcati `synthetic`, disponibili a tutti gli utenti autorizzati del progetto e non rappresentano infrastrutture reali.

## Uso

- Login o registrazione email/password. Gli account nuovi richiedono l'abilitazione al progetto da parte del responsabile; il recupero password resta disabilitato.
- **Mappa | Collettori | Pozzetti | Ispezioni | Impostazioni**. La card del collettore apre i suoi pozzetti. Occhio e mirino conservano visibilità e centraggio; la checkbox attiva la selezione multipla.
- Il catalogo viene scaricato dal server al login e resta nella cache locale per il lavoro senza rete. Le modifiche vengono accodate e sincronizzate quando possibile.
- **Impostazioni → Dati cartografici → Importa shapefile** richiede il ruolo `admin`. Colonne ed esempi distinguono codice originale, identità del record, appartenenza e codifica testi. Chiavi composte e assegnazione guidata persistente gestiscono identificativi duplicati; i casi ambigui richiedono una scelta esplicita. I soli punti possono essere importati senza inventare linee. [Formati e regole GIS](docs/GIS.md).
- L'anagrafica permette di aggiungere pozzetti con coordinate WGS84 e anteprima mappa, anche offline. L'eliminazione rimuove il collettore dall'app e conserva lo storico. Bozze condivise, coda persistente e ricevute server restano attivi.
- **Aggiorna database collettori** aggiorna catalogo e ispezioni e mostra l'ultimo successo. Le schede indicano successivi, distanze e frequenza nominale; Account mostra il ruolo verificato.
- Accuratezza e distanza GPS hanno soglie separate. La registrazione GPS richiede accuratezza ammessa, almeno tre nuove misure in cinque secondi e conferma della corrispondenza. Una posizione accurata ma non corrispondente richiede Eccezione GPS motivata e conferma esplicita.

## Aggiornamento e server

VersionCode **17**, versione **0.2**, application ID `it.pat.collettori.pilot`. Aggiornamento compatibile con la v0.16, con la stessa firma. La v0.2 segue la v0.16 nell'ordine di pubblicazione, gestito esplicitamente da Android e server. Non sostituisce la vecchia applicazione demo con ID diverso.

Le migrazioni SQL `202609220003_coll_pat_v014.sql`, `202609220004_coll_pat_photos.sql`, `202609220005_coll_pat_v015.sql` e `202609230006_coll_pat_v016_server_seed.sql` sono additive. La 006 carica una sola volta, in modo idempotente, il catalogo sintetico nel progetto COLL-PAT senza cancellare o alterare righe estranee.

Configurazione Android in `android/local.properties` o proprietà Gradle: **SUPABASE_URL** e **SUPABASE_PUBLISHABLE_KEY**. Esempio senza credenziali: [local.properties.example](android/local.properties.example). Nessuna chiave amministrativa nell'APK. [Procedura Supabase e autorizzazioni](docs/SUPABASE.md).

La nuova `202609230007_coll_pat_v02.sql` aggiunge cancellazioni autorizzate, protezione contro la ricomparsa dei dati e controlli sui collegamenti manuali. Applicarla dopo la 006; la migrazione non elimina dati operativi.

## Build e verifiche

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lint
```

APK: `android/app/build-v02/outputs/apk/debug/app-debug.apk`. JDK 17/21, SDK 36, dipendenze bloccate.

```powershell
$env:COLL_PAT_SQL_TEST_URL='postgresql://postgres@127.0.0.1:55433/postgres'
$env:POSTGIS_TEST_DATABASE_URL='postgresql+psycopg://postgres@127.0.0.1:55433/postgres'
.venv/Scripts/python.exe -m pytest -q
```

I test SQL creano database temporanei su localhost e non leggono credenziali remote da `.env`. Il sorgente sintetico resta nel repository come fixture di test e generatore della migrazione server, ma non è incluso nell'APK.

[Architettura](docs/ARCHITECTURE.md) · [Modello dati](docs/DATA_MODEL.md) · [Offline](docs/OFFLINE.md) · [GIS](docs/GIS.md) · [Changelog](CHANGELOG.md)
