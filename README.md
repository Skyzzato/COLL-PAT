# Collettori — v0.12

Demo Android per trovare, identificare e ispezionare pozzetti di collettori intercomunali. I dati inclusi sono **sintetici**, non rappresentano infrastrutture PAT. Il GPS indica prossimità, non certifica apertura o qualità del controllo.

## Prova della demo

Installare `Collettori-v0.12-demo.apk` da `local-output/` dopo la build. Android 8 o successivo, Google Play services per localizzazione. Nessun account richiesto. APK demo separato dall'app pilota (`it.pat.collettori.pilot.demo`).

1. Mappa → Centra posizione: mostra distanza, precisione e candidati, senza selezione automatica.
2. Toccare un pozzetto → confermare il codice → Avvia ispezione.
3. Verificare il riepilogo regolare, espandere i controlli per anomalie o impedimenti.
4. Scattare/selezionare foto; aggiungere note o motivazione GPS quando richiesta.
5. Registrare. Bozze, ispezioni e immagini rimangono sul telefono.

Quattro schede: Mappa, Pozzetti (fallback manuale e filtri), Ispezioni, Altro (informazioni ed esportazione). La versione pilota mantiene autenticazione, download, sincronizzazione e amministrazione esistenti.

## Tecnologia e compilazione

Kotlin, Jetpack Compose Material 3, Room, WorkManager, MapLibre Native 13.6.1. Backend FastAPI/SQLAlchemy; PostgreSQL/PostGIS e Supabase già predisposti. Dipendenze bloccate nei lockfile. JDK 17/21, Android SDK 36.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-android.ps1 -Demo
```

Oppure nella cartella `android`, con JAVA_HOME e ANDROID_HOME configurati:

```text
gradlew.bat :app:assembleDemo :app:testDemoUnitTest :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

APK generato: `android/app/build-pilot/outputs/apk/demo/app-demo.apk`. La copia di distribuzione si chiama `Collettori-v0.12-demo.apk`. VersionName `0.12-demo`, versionCode `12`; variante pilota `0.12`.

```powershell
.venv/Scripts/python.exe -m pytest -q
```

Per il server: creare una venv Python 3.12, installare `backend/requirements.lock`, configurare `.env` da `.env.example`, applicare le migrazioni iniziali e seguire [OPERATIONS](docs/OPERATIONS.md) e [SUPABASE](docs/SUPABASE.md). Nessun secret va versionato. Aggiornare anche il backend per accettare gli eventi app `0.12`; restano accettati gli eventi storici `0.1` e `0.11`.

## Struttura e dati

- `android/`: applicazione, test e asset locali.
- `backend/`: API, portale e migrazione Alembic iniziale.
- `demo/trento-v0.11.json`: sorgente demo modificabile, 10 pozzetti PZ-001…PZ-010 e 9 segmenti, circa 1,08 km presso Trento.
- `scripts/build_demo_asset.py`: copia deterministica del dataset nell'APK.
- `shared/`: regole GPS del pilota e casi condivisi.
- `docs/`: architettura, analisi funzionale, modello dati, roadmap e collaudo.
- `supabase/migrations/`: schema iniziale esistente; nessuna nuova migration per v0.12.

Il vecchio shapefile sintetico resta per i test di importazione backend: non è il dataset della nuova demo. Aggiornando l'APK, vecchi pacchetti e ispezioni rimangono conservati; la demo apre sempre il pacchetto v0.11.

## Limiti e roadmap

OpenStreetMap online; in assenza di rete restano tracciato e pozzetti locali, non una base geografica offline completa. Nessun download massivo di tile. Foto persistite nello spazio privato dell'app, senza upload. Export JSON con riferimenti alle foto, **non** i file immagine. NFC/RFID e QR sono contratti futuri, nessuna scansione simulata. Mancano storage remoto, associazione amministrativa dei tag e collaudo fisico sul campo. Disinstallazione o cancellazione dati rimuovono il lavoro locale.

**MIGRAZIONE SQL NECESSARIA: NO** per aggiornare dalla v0.11 alla v0.12. Room rimane v1; metadati aggiuntivi nei contenitori JSON già presenti. Un'installazione backend nuova richiede comunque lo schema iniziale già documentato.

Vedi [analisi funzionale](docs/ANALISI_FUNZIONALE.md), [architettura](docs/ARCHITECTURE.md), [modello dati](docs/DATA_MODEL.md), [RFID/NFC](docs/RFID_NFC_ROADMAP.md), [diagnosi e collaudo v0.12](docs/VALIDATION-v0.12.md), [changelog](CHANGELOG.md).

La v0.12 corregge il crash di avvio della v0.11 (inizializzazione MapLibre prima del client HTTP), ripristina INTERNET nella variante demo e introduce il logo originale del tubo pixel art. Il dataset rimane quello della v0.11.

Build completa: `scripts/build-android.ps1 -Full`. Test su dispositivo: `gradlew.bat :app:connectedDebugAndroidTest`; per la variante demo: `gradlew.bat :app:connectedDemoAndroidTest -PtestBuildType=demo`.
