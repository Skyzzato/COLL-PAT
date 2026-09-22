# COLL-PAT v0.14 — verifica del 22 settembre 2026

## Esito e limiti

Sviluppo incrementale dal commit v0.13 `7daabf4`, senza ricostruzione dell'app. APK demo installata e avviata su emulatore Android 15/API 35. I test SQL utilizzano PostgreSQL 16/PostGIS locali, in database isolati. Nessuna migrazione, registrazione o modifica eseguita sul progetto Supabase remoto; nessun telefono fisico o prova GPS sul campo.

## Ricompilazione con configurazione pubblica, stessa v0.14

L'APK allegata alla pre-release è stata ricompilata con URL `https://zzipvrnhndigepufhkcj.supabase.co` e publishable key forniti dal responsabile, tramite `android/local.properties` escluso da Git. La presenza dei due valori nel codice DEX compilato è stata verificata. VersionName **0.14-demo**, versionCode **14**, application ID e certificato restano invariati. L'APK precedente è conservata localmente in `local-output/releases/v0.14-before-public-config`.

Su questa ricompilazione sono stati rieseguiti `testDemoUnitTest` (**50 passati**), `lintDemo` (**0 errori, 34 warning**), `assembleDemo`, `assembleDemoAndroidTest` e l'instrumentation Android (**13 passati**), dopo installazione con `adb install -r`. Non sono cambiate logica applicativa o migrazioni; i risultati Python/SQL e della variante debug riportati sotto appartengono al collaudo iniziale v0.14.

Due verifiche remote di sola lettura, eseguite con la publishable key, hanno restituito HTTP **200**: `/auth/v1/settings` conferma registrazione email abilitata e conferma email obbligatoria; `/rest/v1/rpc/coll_pat_version` restituisce latest/minimum **0.14**. Queste verifiche non costituiscono un collaudo di registrazione, consegna email, accesso di operatori o upload/download Storage.

| Verifica eseguita | Risultato |
|---|---|
| Python/backend/SQL | **80 passati**, nessuno skip; un warning di deprecazione AnyIO/Starlette |
| Kotlin/JVM debug | **50 passati**, 0 errori, fallimenti o skip |
| Kotlin/JVM demo | **50 passati**, stessi casi nella variante distribuita, 0 errori, fallimenti o skip |
| Android strumentale sull'APK finale | **13 passati**, 0 fallimenti |
| Lint | **0 errori**; 28 warning debug, 34 demo |
| Build | `assembleDebug`, `assembleDemo`, `assembleDemoAndroidTest` riusciti |
| Firma | APK verificata; certificato uguale alla v0.13 |
| Upgrade locale | Room **3**, **18 contenuti di schede v0.13 invariati**, verificati per UUID/account e SHA-256 |
| Catalogo demo | **3 collettori, 22 pozzetti, 19 tronchi**; UUID precedenti conservati |
| CSV tramite SAF | File riletto: **15 righe dati, 40 colonne**, UTF-8 BOM, separatore `;` |

I warning lint riguardano versioni SDK/plugin/dipendenze, convenzioni Compose/log, API KTX e regole di estrazione backup. La distribuzione mantiene la firma debug storica delle pre-release; non è una release firmata per uno store.

## Copertura automatica

- Periodicità: tutti i casi A–H richiesti, quattro stati, ultima anomalia, assenza di controlli, bozze/impedimenti/annullati/record obsoleti o futuri esclusi, frequenze del semestre e pozzetti condivisi. Il calcolo è unico per mappa e filtri.
- GPS: soglia accuratezza 10 m con misure 5/10/10,1 m; distanza 15 m con misure 8/15/16 m; coordinate, permessi, età, fix simulato e soglie storiche. Il test Android verifica il permesso negato e la conservazione del tentativo nell'impedimento.
- CSV: semestre Europe/Rome, esclusione bozze e annullati, campi con separatori/virgolette/ritorni a capo, intestazioni e foto SI/NO.
- **13 nuovi test SQL v0.14**, oltre ai test precedenti: migrazioni applicate due volte, RLS/grant, versione minima e RPC anonima, autori derivati da identità autenticata, bozza letta e modificata da un secondo membro, CAS concorrente, retry idempotente, invio definitivo e annullamento da parte del mittente diverso dal creatore; fotografia prenotata, policy Storage e ricevuta vincolata all'oggetto; archiviazione senza perdita dello storico o riattivazione da aggiornamento vecchio.
- Room 1→2→3, persistenza e separazione per account della stessa bozza e delle code, impostazioni dopo riapertura, code precedenti conservate. Test di invio finale mentre la sincronizzazione della bozza è già in corso: la ricevuta precedente non sovrascrive il lavoro successivo.
- Contratto Auth Android: registrazione, login, header pubblico, sessione cifrata, refresh con rotazione e logout, usando un interceptor HTTP con risposte controllate. **Non è una registrazione su Supabase reale.**
- Versione obsoleta: confronto numerico JVM, rifiuto RPC SQL, memorizzazione della policy Android e rifiuto anche quando la richiesta successiva non ha rete.
- Avvio/ricreazione Activity, inizializzazione MapLibre, bozza modificata e riaperta, JPEG locale reale e associazione persistente; regressioni GIS e validazione ispezioni v0.13.

Le tabelle `storage` nei test SQL costituiscono un ambiente di prova delle policy, non il servizio HTTP Supabase Storage. Non è stato eseguito il percorso completo di upload/download fra due telefoni collegati al progetto remoto.

## Verifica dell'interfaccia su emulatore

- Login e registrazione con conferma password; comando recupero password con messaggio di indisponibilità; ingresso esplicito nella demo offline.
- Mappa caricata su Via Giuseppe Gilli, quattro colori visibili e segno sotto asfalto indipendente. Soglia alzata a 20: pozzetti, etichette e selezione nascosti, tronchi ancora visibili; ripristino a 14.
- Card del terzo collettore navigabile, elenco filtrato Gilli, filtri anomalie e già ispezionati con date e periodicità. Controlli automatici coprono i restanti confini della logica.
- Impostazioni organizzate in sezioni, slider e legenda, export del semestre tramite selettore documenti Android e rilettura del file salvato in Download.
- Installazione dell'APK finale con `adb install -r`, avvio dopo arresto forzato senza crash. Confronto delle 18 schede originali dopo migrazione: contenuti invariati. I file fotografici originali non sono rimossi dalla migrazione.

Il task Gradle `connectedDemoAndroidTest` disinstalla l'app a fine prova: dopo quella prima esecuzione il backup privato precedente è stato ripristinato per verificare la migrazione. La verifica finale è stata eseguita installando APK e APK di test e invocando direttamente l'instrumentation, conservando l'app installata.

## Comandi finali

```powershell
$env:COLL_PAT_SQL_TEST_URL='postgresql://postgres@127.0.0.1:55433/postgres'
$env:POSTGIS_TEST_DATABASE_URL='postgresql+psycopg://postgres@127.0.0.1:55433/postgres'
.venv\Scripts\python.exe -m pytest -q

.\android\gradlew.bat -p android :app:testDebugUnitTest :app:testDemoUnitTest :app:lint :app:assembleDebug :app:assembleDemo :app:assembleDemoAndroidTest -PtestBuildType=demo --console=plain

adb install -r android/app/build-pilot/outputs/apk/demo/app-demo.apk
adb install -r android/app/build-pilot/outputs/apk/androidTest/demo/app-demo-androidTest.apk
adb shell am instrument -w it.pat.collettori.pilot.demo.test/androidx.test.runner.AndroidJUnitRunner
```

Log, schermate, CSV, hash e backup privati rimangono in `.tools` e `local-output/verification/v0.14`, esclusi dal repository e dagli allegati pubblici. Non si distribuiscono copie dell'archivio locale.

## Migrazioni e configurazione esterna

Room 3 è stata applicata e verificata sull'emulatore. Le migrazioni `202609220003_coll_pat_v014.sql` e `202609220004_coll_pat_photos.sql` sono state applicate e ripetute solo nei database locali di test. Devono essere installate sul progetto remoto dopo le due migrazioni v0.13, secondo [SUPABASE.md](SUPABASE.md).

URL e publishable key pubblica sono già inclusi nell'APK ricompilata. Il provider email risulta abilitato, con conferma dell'indirizzo richiesta. Rimangono da verificare consegna delle email, membri autorizzati e percorso completo con due account reali, RPC e Storage remoto. Nessuna chiave amministrativa va inserita nell'app.

Gli invii v0.13 pendenti sono conservati e sospesi per recupero esplicito: non vengono inventate soglie GPS o attribuzioni mancanti. Le schede restano consultabili; le bozze compatibili possono essere proseguite con nuove misure valide. Il CSV offline può includere soltanto i dati già nella cache. Le foto remote diventano disponibili offline dopo il primo download.

## Artefatto

- `COLL-PAT-v0.14-demo.apk`, **62955496 byte**.
- SHA-256: `bdfc7a19340fd3b50f702d8c31c5ff0855677d2ff6a72454b0ebb7bf36ebfac0`.
- Certificato SHA-256: `acf785391278fa98832980a51e594c88ffb06ff36b590c73c18c917f975080c6`.
- Application ID `it.pat.collettori.pilot.demo`, versionCode **14**, versionName **0.14-demo**.
- Repository `Skyzzato/COLL-PAT`, tag **v0.14**, distribuzione come **pre-release** con APK, checksum e questo rapporto.
