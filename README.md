# Collettori — v0.1

Applicazione Android e server per le **sole ispezioni dei collettori intercomunali e dei relativi pozzetti**. Nome provvisorio, nessun logo istituzionale. Il GPS documenta un evento dichiarato: **non verifica l'apertura e non certifica il controllo tecnico**.

## Contenuto

- Android Kotlin/Compose, Room, WorkManager, MapLibre Native; cinque schermate, ricerca, mappa locale, schede, bozze, eventi GPS, revisioni e invii persistenti.
- FastAPI, SQLAlchemy, migrazione Alembic; PostgreSQL/PostGIS in Docker Compose. SQLite è disponibile **solo come ambiente locale di sviluppo e test**, non sostituisce il collaudo PostgreSQL.
- Portale amministrativo HTML/JavaScript senza dipendenze frontend: ispezioni, verifica documentale, scadenze, anomalie, account, versioni GPS, emissioni XLSX/CSV/JSON e stampa.
- Importazione amministrativa di shapefile completi, UUID stabili, versioni immutabili e rapporti d'importazione.
- Dataset e base dimostrativi **interamente sintetici**. Nessun dato PAT reale è incluso. Nessuna scadenza trimestrale viene creata automaticamente.

Fotografie, QR, codice targhetta, NFC e sensori non sono implementati né richiesti nei permessi Android.

## Avvio locale su Windows (senza Docker)

Prerequisiti: Python 3.12, JDK 17 o 21, Android SDK 36. Gradle Wrapper incluso. Le dipendenze Python e Gradle sono bloccate; la prima installazione richiede Internet.

Dalla cartella del progetto, in PowerShell:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend\requirements.lock
.\.venv\Scripts\python.exe scripts\make_demo.py
Set-Location backend
New-Item -ItemType Directory -Force runtime
..\.venv\Scripts\python.exe -m alembic upgrade head
..\.venv\Scripts\python.exe -m app.cli bootstrap --username nome.cognome --company "Impresa pilota sintetica" --area DEMO --area-name "Area dimostrativa sintetica" --synthetic
..\.venv\Scripts\python.exe -m app.cli import-gis ..\demo\synthetic.zip ..\demo\mapping.json
..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

`bootstrap` chiede due volte una password di almeno 12 caratteri; nessuna credenziale predefinita è inclusa. Non riutilizzare un nome account esistente. Se `.venv` è già presente e funzionante, saltare la sua creazione.

Aprire **http://127.0.0.1:8000**, accedere con l'amministratore individuale e creare almeno un account operaio in **Amministrazione**, autorizzato all'area DEMO. Il portale richiede ruolo verificatore/amministratore; gli operai usano l'app. Documentazione API: `/docs`.

Su macOS/Linux usare `python3` e `.venv/bin/python` al posto degli eseguibili Windows.

## Avvio PostgreSQL/PostGIS con Docker

1. Copiare `.env.example` in `.env` e valorizzare `POSTGRES_PASSWORD` con un segreto casuale URL-safe. Non usare password reali dell'organizzazione.
2. Dalla radice:

```text
docker compose up -d --build
docker compose exec api python -m app.cli bootstrap --username nome.cognome --company "Impresa pilota sintetica" --area DEMO --area-name "Area dimostrativa sintetica" --synthetic
docker compose exec api python -m app.cli import-gis demo/synthetic.zip demo/mapping.json
```

L'API esegue la migrazione prima dell'avvio. Solo la porta locale `127.0.0.1:8000` è esposta; PostgreSQL non è pubblicato all'esterno. I volumi `database` e `files` conservano dati e fonti. **Non usare `docker compose down -v` su un ambiente con dati da conservare.**

Per uso reale: terminazione HTTPS, protezione dell'accesso amministrativo, segreti gestiti, infrastruttura approvata, supervisione, conservazione e backup verificati. Questa configurazione è di sviluppo, non un deploy produttivo già autorizzato.

## Compilare e collegare Android

Aprire la cartella `android` in Android Studio, configurare SDK 36 e JDK 17/21. Oppure, da PowerShell nella cartella `android`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

APK: **`android/app/build-pilot/outputs/apk/debug/app-debug.apk`**. La cartella `build-pilot` evita una cache di compilazione bloccata osservata su Windows. Minimo Android 8 (API 26). La localizzazione usa Google Play services: per il pilota serve un dispositivo che li supporti. I dispositivi senza tali servizi producono un'eccezione di localizzazione, non una posizione fittizia.

Se Windows assegna l'attributo sola lettura alle cartelle generate, dalla radice usare `powershell -File scripts/build-android.ps1`: normalizza soltanto gli artefatti di build e avvia compilazione e test. Non cancella dati applicativi o progetti estranei.

Telefono USB con debug USB autorizzato:

```text
adb install -r android/app/build-pilot/outputs/apk/debug/app-debug.apk
adb reverse tcp:8000 tcp:8000
```

Nella schermata di accesso dell'app impostare **`http://127.0.0.1:8000`** e l'account individuale. Per l'emulatore Android il server del computer è normalmente **`http://10.0.2.2:8000`**. HTTP è ammesso soltanto nella build debug; la configurazione principale richiede HTTPS.

1. Accedere online.
2. Aprire **Dati offline → Aggiorna catalogo e sessione → Scarica area e storico**.
3. Verificare versione, copertura, dimensione e presenza della base.
4. Aprire **Pozzetti** o **Mappa**, selezionare esplicitamente un manufatto, **Avvia controllo**.
5. Concedere o negare il permesso di localizzazione: entrambi i casi vengono gestiti. Compilare, motivare eventuali eccezioni, salvare.
6. Usare **Controlli → Sincronizza ora**; la ricezione si considera avvenuta soltanto dopo la risposta server.

La demo usa coordinate nell'area di Trento ma **non rappresenta manufatti o strade reali**. Un telefono altrove darà normalmente un esito negativo: è corretto. Non aumentare le soglie per trasformare la demo in una falsa evidenza favorevole.

## Test

Dalla radice:

```text
.venv\Scripts\python.exe -m pytest -q
```

Da `android`:

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest
gradlew.bat :app:connectedDebugAndroidTest
```

L'ultimo comando richiede un dispositivo o emulatore realmente disponibile. Compilare i test strumentali non significa averli eseguiti. Risultati e limiti del collaudo sono in [docs/VALIDATION.md](docs/VALIDATION.md); le prove sul campo sono in [docs/PILOT-CHECKLIST.md](docs/PILOT-CHECKLIST.md).

## Documentazione

- [Supabase: migrazione SQL e compatibilità APK](docs/SUPABASE.md)
- [Prompt di passaggio v0.1](PROMPT-v0.1.md)
- [Importazione GIS e cartografia offline](docs/GIS.md)
- [Protocollo offline, account, conflitti e recupero](docs/OFFLINE.md)
- [Regola GPS e limiti delle evidenze](docs/GPS.md)
- [Server, backup e verifiche organizzative](docs/OPERATIONS.md)
- [Scelte, dipendenze e fonti ufficiali](docs/ARCHITECTURE.md)
- [Roadmap](ROADMAP.md) e [changelog](CHANGELOG.md)

Non disinstallare l'app, cancellarne i dati o smarrire il dispositivo prima della trasmissione: la persistenza locale non protegge dalla perdita fisica del telefono. Nessun aggiornamento dell'app o dei pacchetti cancella volontariamente i controlli pendenti.
