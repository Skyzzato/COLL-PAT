# COLL-PAT — v0.21 prerelease

App Android per cartografia, pozzetti, ispezioni periodiche, GPS e fotografie, con cache locale e sincronizzazione Supabase.

[Prerelease v0.21](https://github.com/Skyzzato/COLL-PAT/releases/tag/v0.21) · [APK](https://github.com/Skyzzato/COLL-PAT/releases/download/v0.21/COLL-PAT-v0.21.apk) · [Collaudo](docs/VALIDATION-v0.21.md) · [Ergonomia](docs/UX-REVIEW-v0.21.md) · [Requisiti](docs/REQUIREMENTS-v0.21.md)

**Migrazioni 008 e 009 applicate e verificate anche sul server remoto. Versione disponibile 0.21, minima 0.14; pulizia degli archivi non eseguita.** [Esito deployment](docs/DEPLOYMENT-v0.21.md) · [Procedura amministrativa](docs/MIGRATIONS-v0.21.md).

## Uso

- Mappa, Collettori, Pozzetti, Ispezioni e Impostazioni mantengono il catalogo scaricato e il lavoro offline. Login e abilitazione al progetto restano necessari; nessun ambiente demo è incorporato nell'APK.
- Importazione ZIP/shapefile: con soli punti viene proposto il collegamento ordinato. Senza ordine affidabile occorre indicare ordine/rami o scegliere esplicitamente i soli punti. Stessa sorgente attiva = aggiornamento; dopo eliminazione = nuovi UUID.
- Eliminazione definitiva di collettore, pozzetto o ispezione con anteprima dell'ambito. Le dipendenze condivise restano; senza rete la richiesta resta in coda. La rimozione degli allegati Storage è confermata separatamente dal database.
- Aspetto generale e per collettore: colore, spessore e forma. Il singolo pozzetto cambia solo forma; i colori continuano a rappresentare lo stato. Le etichette riportano il codice del collettore su posizioni distribuite lungo i tratti.
- Inserimento WGS84 con punto/virgola, selezione sulla mappa e coordinate attuali, sempre con conferma. Il modello sotto asfalto memorizza la condizione del pozzetto; il ritorno a ordinario è esplicito.
- GPS: stabilizzazione di 3 secondi, almeno 5 secondi utili e 3 fix distinti, media geografica e media delle accuratezze. Accuratezza configurabile 20–150 m, distanza 5–30 m. La corrispondenza conserva il controllo conservativo di incertezza cartografica e punti vicini. Se non utilizzabile, occorre riprovare o confermare una motivazione senza coordinate.
- Note / Anomalie unificate, spiegazioni dei campi e condizioni di sicurezza coerenti. Il riepilogo di salvataggio aspetta Chiudi.
- Sincronizza invia modifiche e fotografie; Aggiorna database collettori scarica catalogo e ispezioni condivise. Visualizza coda raggruppa importazioni, bozze, fotografie ed eliminazioni e conserva i conflitti.

## Build e aggiornamento

VersionName **0.21**, versionCode **21**, application ID `it.pat.collettori.pilot`. Firma compatibile con la v0.2; Room rimane alla versione 3. Nessun reset o fallback distruttivo.

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lint
```

APK: `android/app/build-v021/outputs/apk/debug/app-debug.apk`. JDK dell'Android Studio, SDK 36. Configurazione pubblica in `android/local.properties`: SUPABASE_URL e SUPABASE_PUBLISHABLE_KEY; nessuna chiave amministrativa nel client.

```powershell
$env:COLL_PAT_SQL_TEST_URL='postgresql://postgres@127.0.0.1:55433/postgres'
$env:POSTGIS_TEST_DATABASE_URL='postgresql+psycopg://postgres@127.0.0.1:55433/postgres'
.venv/Scripts/python.exe -m pytest -q
```

I test SQL creano database temporanei su localhost. Le fixture sintetiche sono risorse di test, non dati operativi da importare durante il collaudo.

[Architettura](docs/ARCHITECTURE.md) · [Database](docs/DATA_MODEL.md) · [GPS](docs/GPS.md) · [GIS](docs/GIS.md) · [Supabase](docs/SUPABASE.md) · [Changelog](CHANGELOG.md)
