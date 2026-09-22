# COLL-PAT — v0.14 pre-release

App Android per cartografia, pozzetti, ispezioni periodiche, GPS e fotografie, con cache locale e sincronizzazione Supabase. I tre collettori demo di Trento, Lavis e Via Gilli sono sintetici e separati dagli account server.

[Pre-release v0.14](https://github.com/Skyzzato/COLL-PAT/releases/tag/v0.14) · [APK demo](https://github.com/Skyzzato/COLL-PAT/releases/download/v0.14/COLL-PAT-v0.14-demo.apk) · [Collaudo](docs/VALIDATION-v0.14.md) · [Requisiti verificati](docs/REQUIREMENTS-v0.14.md)

## Uso

- Login o registrazione email/password; nella variante demo è disponibile **Apri demo offline**. Il recupero password è esplicitamente disattivato. Gli account nuovi richiedono l’abilitazione al progetto da parte del responsabile.
- **Mappa | Collettori | Pozzetti | Ispezioni | Impostazioni**. La card del collettore apre i suoi pozzetti. Occhio e mirino conservano visibilità e centraggio; la bandierina attiva la selezione multipla.
- I pozzetti compaiono al livello di zoom configurato. Colore = ultima ispezione valida e frequenza prevista; il segno sotto asfalto è indipendente. Filtri per stato, collettore e codice.
- Le bozze degli account collegati vengono condivise alla sincronizzazione. Il lavoro locale resta disponibile senza rete. Una revisione obsoleta viene fermata e conservata: dalla scheda si può salvare una copia di recupero e aprire la versione server.
- Accuratezza e distanza GPS hanno soglie separate. Un controllo completo richiede una misura affidabile; un impedimento conserva anche un tentativo fallito. Le misure eliminate spariscono dall’interfaccia, rimanendo nell’audit.
- **Impostazioni → Ispezioni → Esporta ispezioni semestre** salva un CSV UTF-8 con `;`, intestazioni, note e indicazione foto SI/NO tramite Storage Access Framework.

## Aggiornamento e server

VersionCode **14**, versione base **0.14**. L’APK distribuita mantiene `it.pat.collettori.pilot.demo`, suffisso `-demo` e firma debug storica. Aggiornare sopra la stessa variante. Room 1→2→3 conserva le schede e separa le copie locali della stessa bozza fra account.

Le migrazioni SQL `202609220003_coll_pat_v014.sql` e `202609220004_coll_pat_photos.sql` sono additive e ripetibili dopo le due migrazioni v0.13. Estendono le ispezioni esistenti, aggiungono metadati foto e configurazione versione, senza duplicare il catalogo.

Configurazione Android in `android/local.properties` o proprietà Gradle: **SUPABASE_URL** e **SUPABASE_PUBLISHABLE_KEY**. Esempio senza credenziali: [local.properties.example](android/local.properties.example). Nessuna chiave amministrativa nell’APK. [Procedura Supabase e autorizzazioni](docs/SUPABASE.md).

## Build e verifiche

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-android.ps1 -Demo
cd android
.\gradlew.bat :app:lint :app:assembleDemoAndroidTest -PtestBuildType=demo
# Emulatore dedicato: i task connected possono disinstallare la variante al termine.
.\gradlew.bat :app:connectedDemoAndroidTest -PtestBuildType=demo
```

APK: `android/app/build-pilot/outputs/apk/demo/app-demo.apk`. JDK 17/21, SDK 36, dipendenze bloccate.

```powershell
$env:COLL_PAT_SQL_TEST_URL='postgresql://postgres@127.0.0.1:55433/postgres'
$env:POSTGIS_TEST_DATABASE_URL='postgresql+psycopg://postgres@127.0.0.1:55433/postgres'
.venv/Scripts/python.exe -m pytest -q
```

I test SQL creano database temporanei su localhost e non leggono credenziali remote da `.env`. `scripts/build_demo_asset.py` rigenera il seed v0.14 mantenendo gli UUID precedenti.

## Limiti verificati

Migrazioni e autorizzazioni collaudate su PostgreSQL/PostGIS locale; APK su emulatore API 35. Il progetto Supabase remoto e il servizio Storage reale non sono stati configurati/collaudati in questa sessione. I test Auth HTTP usano risposte controllate; non rappresentano una registrazione remota effettuata.

Gli invii v0.13 ancora pendenti sono conservati e sospesi per recupero esplicito; non vengono inventate soglie GPS mancanti. Le foto sono disponibili offline dopo il primo download. La cache cartografica rimane 200 MiB, senza garanzia sulle aree mai visitate. Nessun collaudo fisico sul campo.

[Architettura](docs/ARCHITECTURE.md) · [Modello dati](docs/DATA_MODEL.md) · [Offline](docs/OFFLINE.md) · [GIS](docs/GIS.md) · [Changelog](CHANGELOG.md)
