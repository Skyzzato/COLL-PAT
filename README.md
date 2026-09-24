# COLL-PAT — v0.22 prerelease

App Android per cartografia, pozzetti, ispezioni periodiche, GPS e fotografie, con cache locale e sincronizzazione Supabase.

[Prerelease v0.22](https://github.com/Skyzzato/COLL-PAT/releases/tag/v0.22) · [APK](https://github.com/Skyzzato/COLL-PAT/releases/download/v0.22/COLL-PAT-v0.22.apk) · [Resoconto e ruoli](docs/REPORT-v0.22.md) · [Collaudo](docs/VALIDATION-v0.22.md) · [Migrazione 010](docs/MIGRATIONS-v0.22.md)

**Migrazione 010 applicata e verificata sul progetto Supabase Collettori.** La versione minima resta 0.14. Nessun reset dei dati né caricamento automatico del dataset Barbaniga.

## Uso

- Mappa, Collettori, Pozzetti, Ispezioni e Impostazioni mantengono il catalogo scaricato e il lavoro offline. Login e abilitazione al progetto restano necessari; nessun ambiente demo viene caricato all'avvio. Il dataset Barbaniga richiede un'azione amministrativa esplicita.
- Importazione ZIP/shapefile: con soli punti viene proposto il collegamento ordinato. Senza ordine affidabile occorre indicare ordine/rami o scegliere esplicitamente i soli punti. Stessa sorgente attiva = aggiornamento; dopo eliminazione = nuovi UUID.
- Eliminazione definitiva di collettore, pozzetto o ispezione con anteprima dell'ambito. Le dipendenze condivise restano; senza rete la richiesta resta in coda. La rimozione degli allegati Storage è confermata separatamente dal database.
- Aspetto generale e per collettore: colore, spessore e forma. Il singolo pozzetto cambia solo forma; i colori continuano a rappresentare lo stato. Le etichette riportano il codice del collettore su posizioni distribuite lungo i tratti.
- Inserimento WGS84 con punto/virgola, selezione sulla mappa e coordinate attuali, sempre con conferma. Il modello sotto asfalto memorizza la condizione del pozzetto; il ritorno a ordinario è esplicito.
- GPS: stabilizzazione di 3 secondi, almeno 5 secondi utili e 3 fix distinti, media geografica e media delle accuratezze. Accuratezza configurabile 20–150 m, distanza 5–30 m. La corrispondenza conserva il controllo conservativo di incertezza cartografica e punti vicini. Se non utilizzabile, occorre riprovare o confermare una motivazione senza coordinate.
- Note / Anomalie unificate, spiegazioni dei campi e condizioni di sicurezza coerenti. Il riepilogo di salvataggio aspetta Chiudi.
- Sincronizza invia modifiche e fotografie; Aggiorna database collettori scarica catalogo e ispezioni condivise. Visualizza coda raggruppa importazioni, bozze, fotografie ed eliminazioni e conserva i conflitti.

- Collegamenti manuali espliciti a uno o più pozzetti vicini: veri tronchi schematici, UUID stabili, persistenza atomica e aggiornamento dei soli segmenti schematici quando si sposta un estremo.
- Card compatte, dettagli richiudibili, importazione lazy, filtri data/collettore azzerabili e Slider GPS con le soglie già esistenti.
- CSV per ultimo trimestre civile concluso, anno scelto o intero storico; paginazione indipendente dalla schermata e scelta esplicita dei soli dati locali se il server non è disponibile.
- Visualizzatore, Operatore (`inspector`) e Amministratore; simulazione senza nuove scritture e gestione utenti online con audit e protezione dell'ultimo amministratore.

## Build e aggiornamento

VersionName **0.22**, versionCode **22**, application ID `it.pat.collettori.pilot`. Firma compatibile con la v0.2; Room rimane alla versione 3. Nessun reset o fallback distruttivo.

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest lint
```

APK: `android/app/build-v022/outputs/apk/debug/app-debug.apk`. JDK dell'Android Studio, SDK 36. Configurazione pubblica in `android/local.properties`: SUPABASE_URL e SUPABASE_PUBLISHABLE_KEY; nessuna chiave amministrativa nel client.

```powershell
$env:COLL_PAT_SQL_TEST_URL='postgresql://postgres@127.0.0.1:55433/postgres'
$env:POSTGIS_TEST_DATABASE_URL='postgresql+psycopg://postgres@127.0.0.1:55433/postgres'
.venv/Scripts/python.exe -m pytest -q
```

I test SQL creano database temporanei su localhost. I test usano database isolati. Per un collaudo cartografico esplicito, un amministratore può caricare e rimuovere DEMO-BAR-5KM da Impostazioni → Dati cartografici → Dataset di collaudo: il percorso è inventato e marcato come sintetico.

[Architettura](docs/ARCHITECTURE.md) · [Database](docs/DATA_MODEL.md) · [GPS](docs/GPS.md) · [GIS](docs/GIS.md) · [Supabase](docs/SUPABASE.md) · [Changelog](CHANGELOG.md)
