# COLL-PAT — v0.13 prerelease

App Android per anagrafica e ispezioni di collettori e manufatti. **Trento e Lavis inclusi sono sintetici**, non infrastrutture PAT. Le importazioni mantengono provenienza e tipo originali. Il GPS verifica la compatibilità della prossimità, non l'apertura o la qualità del lavoro.

[Prerelease v0.13](https://github.com/Skyzzato/COLL-PAT/releases/tag/v0.13) · [APK demo](https://github.com/Skyzzato/COLL-PAT/releases/download/v0.13/COLL-PAT-v0.13-demo.apk) · [Collaudo e limiti](docs/VALIDATION-v0.13.md)

La variante distribuita conserva `it.pat.collettori.pilot.demo` e la firma debug già usata nella v0.12. VersionCode 13; versionName 0.13-demo. Aggiornare sopra la stessa variante, senza disinstallarla. `build.gradle.kts` è l'unica fonte di versione/build; i payload leggono BuildConfig.

## Utilizzo

1. **Mappa | Collettori | Pozzetti | Ispezioni | Altro**. Occhio: visibilità locale persistente; mirino: inquadra e rende visibile. La pagina Collettori e il selettore mappa riutilizzano lo stesso componente.
2. Da un manufatto: nuova ispezione oppure riprendi bozza. Ogni nuova ispezione ha un UUID distinto, anche nella stessa giornata.
3. **Salva bozza** conserva solo localmente. **Registra Ispezione** e **Registra impedimento** salvano scheda e outbox in una transazione Room. La coda locale non è una ricevuta server.
4. Modello sotto asfalto: verifica esterna valida periodicamente, senza dichiarazioni interne. Impedimento: motivo obbligatorio, tentativo GPS anche fallito, nessun controllo periodico completato.
5. Cestino: annullamento con motivazione e audit, originale preservato. Calendario: giornata di esecuzione Europe/Rome.
6. In **Altro → Account**, collegare Supabase Auth **prima di creare lavoro da trasmettere**. Il lavoro locale senza account non viene attribuito automaticamente a chi accede dopo. Tornare all'archivio locale uscendo dall'account.

## Server e dati

**Android → Supabase Auth + RPC HTTPS → PostgreSQL/PostGIS**, senza FastAPI obbligatorio. La demo permette invii strutturati reali quando configurata; soltanto il caricamento foto è simulato. Le immagini sono reali e private sul telefono, senza URL remoti inventati.

**Migrazioni necessarie: SÌ.** Room 1→2 esplicita e non distruttiva. Due nuove migrazioni SQL; setup Auth/amministratore in [SUPABASE](docs/SUPABASE.md). Il vecchio backend è conservato per compatibilità storica e test, non è il server della v0.13. Le vecchie code sono sospese, mai rietichettate con la nuova generazione.

Dashboard di sviluppo nella variante demo: sblocco **locale admin/admin**, separato dal ruolo amministratore Auth. Nuovo/modifica/archivia collettore, importazione ZIP/shapefile nativa, backup e reset online. Nessuna duplicazione. Il seed è una tantum per archivio/account; una pubblicazione esplicita verifica prima il catalogo remoto.

## Compilazione e verifiche

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-android.ps1 -Demo
# Android strumentale (emulatore o dispositivo collegato)
cd android
.\gradlew.bat :app:connectedDemoAndroidTest -PtestBuildType=demo
```

JDK 17/21, SDK 36. Dipendenze bloccate; nessuna nuova libreria Android per l'importazione. APK: `android/app/build-pilot/outputs/apk/demo/app-demo.apk`.

```powershell
# PostgreSQL/PostGIS locale isolato, credenziale di test scelta dal collaudatore
$env:COLL_PAT_SQL_TEST_URL='postgresql://postgres@127.0.0.1:55433/postgres'
$env:POSTGIS_TEST_DATABASE_URL='postgresql+psycopg://postgres@127.0.0.1:55433/postgres'
.venv/Scripts/python.exe -m pytest -q
```

`scripts/build_v013_seed.py` genera il seed deterministico conservando gli UUID Trento storici. `scripts/build_gis_test_fixtures.py` genera esclusivamente ZIP sintetici per i test. Test SQL senza variabile dedicata: skip esplicito, mai uso implicito di `.env` remoto.

## Limiti della prerelease

Configurazione Auth/pubblica e migrazioni remote richieste. Il progetto remoto non è stato collaudato in questa sessione: connessione PostgreSQL configurata in timeout e nessuna sessione Auth disponibile. Reset iniziale **non eseguito**. Foto locali; cache nominale unica 200 MiB (209715200 byte), potenzialmente incompleta o rimossa dal sistema. Nessun download preventivo OSM. Nessun collaudo sul campo o telefono fisico.

Importazione: punti e polilinee; EPSG 4326, 3857, 32632/33, 25832/33; UTF-8, Windows-1252, ISO-8859-1. Massimo ZIP 32 MiB, 10000 oggetti; una geometria oltre 4 MiB richiede suddivisione. Gli invii più grandi usano staging privato e pubblicazione atomica. Multipart lette ma da separare in rami con chiavi distinte prima della pubblicazione topologica.

[Requisiti → implementazione → test](docs/REQUIREMENTS-v0.13.md) · [Architettura](docs/ARCHITECTURE.md) · [GIS](docs/GIS.md) · [Modello dati](docs/DATA_MODEL.md) · [Roadmap](ROADMAP.md) · [Documenti storici v0.12](docs/history/v0.12/README.md)
