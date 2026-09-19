# Collettori v0.12 — diagnosi e collaudo

## CRASH V0.11

Riprodotto il crash dell'APK distribuito, SHA-256 `c72b5ca437675cd24b395c5ad71a525e8e40b9968e77615d0828014763918879`, su Android 15 / API 35, Google APIs x86_64, emulatore WHPX. Il commit di partenza è `1576000`, tag `v0.11`; il confronto è con `fa343bf`, tag `v0.1-demo`. Anche quest'ultima versione è stata compilata, installata e avviata: funzionava.

La v0.11 fallisce sia dopo installazione pulita sia aggiornando la precedente con una bozza presente. Identica eccezione nelle varianti demo distribuita, debug e release (release firmata con la chiave di test per poterla installare).

Catena completa rilevante del Logcat originale:

```text
FATAL EXCEPTION: main
java.lang.ExceptionInInitializerError
  at org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(HttpRequestUtil.java:51)
  at it.pat.collettori.PilotApplication.onCreate(Repository.kt:21)
  at android.app.Instrumentation.callApplicationOnCreate(Instrumentation.java:1386)
  at android.app.ActivityThread.handleBindApplication(ActivityThread.java:7504)
  ... framework Android ...
Caused by: org.maplibre.android.exceptions.MapLibreConfigurationException:
Using MapView requires calling MapLibre.getInstance(...) before inflating or creating the view.
  at org.maplibre.android.MapLibre.validateMapLibre(MapLibre.java:261)
  at org.maplibre.android.MapLibre.getApplicationContext(MapLibre.java:208)
  at org.maplibre.android.http.HttpIdentifier.getIdentifier(HttpIdentifier.java:22)
  at org.maplibre.android.module.http.HttpRequestImpl.<clinit>(HttpRequestImpl.java:43)
  ... 13 more
```

La regressione è l'introduzione in `PilotApplication.onCreate`, nella v0.11, della configurazione HTTP per OSM prima di inizializzare MapLibre. L'inizializzatore statico di `HttpRequestImpl` legge il contesto del singleton; questo non esiste ancora. L'errore avviene prima della costruzione del repository e prima della Activity: non dipende da GPS, foto o schema Room. Il messaggio nomina MapView, ma il punto di attivazione effettivo è `setOkHttpClient`.

Soluzione: `MapLibre.getInstance(this)` prima della configurazione HTTP, senza catturare o ignorare l'errore. Con questa correzione minima, prima del restyling, l'app è stata reinstallata e avviata con dati azzerati: mappa OSM e 10 pozzetti visibili, nessun crash.

Riferimento tecnico: [inizializzazione di MapLibre](https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.6.1/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/MapLibre.java) e [inizializzatore HTTP](https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.6.1/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/module/http/HttpRequestImpl.java).

## DATABASE

**MIGRAZIONE NECESSARIA: NO.** Room rimane schema 1, file `pilot-v1.db`. Entità, tabelle, DAO e schema esportato non sono cambiati tra la versione precedente, v0.11 e v0.12. Foto e identificazione utilizzano i contenitori JSON nella tabella `settings` già esistente. Nessun `fallbackToDestructiveMigration`, nessun reset automatico dei dati.

Provata la sequenza precedente → v0.11 originale (crash) → v0.12: bozza, identificativo del pozzetto, valori non verificati originali e precedente evento GPS conservati e riaperti. Il dataset corrente conserva gli identificativi v0.11 e 10 punti / 9 segmenti; non sovrascrive i pacchetti storici.

Il dataset incorporato è validato prima della scrittura: cardinalità, identificativi unici, riferimenti dei segmenti, geometrie e coordinate. Gli errori di preparazione sono registrati nel Logcat e mostrati con azione Riprova, senza terminare il processo o cancellare dati.

## MAPPA

MapLibre Native 13.6.1, raster OpenStreetMap HTTPS, GeoJSON locale e glifi incorporati. Rimosso dal manifest demo il comando che eliminava `INTERNET`: era un problema distinto, ereditato dalla vecchia demo interamente offline. `INTERNET` e `ACCESS_NETWORK_STATE` sono permessi normali, senza dialogo runtime.

Eliminata la doppia chiamata `onStart/onResume`: l'osservatore Lifecycle riceve già gli eventi correnti quando viene registrato. Posizione assente o coordinate fuori intervallo non generano il marker del dispositivo; precisione assente, negativa o non finita non genera il cerchio. I dati locali restano visualizzabili senza rete. La superficie della mappa resta circa 432×732 px nell'emulatore configurato, come prima della revisione grafica.

## GPS E PERMESSI

Posizione e precisione assenti hanno messaggi espliciti. Numeri non finiti/non numerici sono trattati come mancanti. Coordinate fuori intervallo non sono utilizzate per centrare o identificare. Su API 28+ viene letto `LocationManager.isLocationEnabled`, mantenendo la compatibilità API 26. Nessuna richiesta GPS all'avvio; permessi già concessi non vengono richiesti di nuovo. La cancellazione della coroutine viene propagata.

Fine/coarse richiesti insieme solo su azione dell'operatore; rifiuto e localizzazione disattivata sono evidenze registrabili. Nessun permesso notifiche, fotocamera o accesso generale ai file richiesto dall'app. Le fotografie usano la Activity fotocamera esterna e la galleria usa un URI autorizzato dall'utente.

## FOTOCAMERA

FileProvider non esportato, authority `${applicationId}.photos`, percorso privato limitato a `files/photos/`. URI creato solo al click, launcher `TakePicture`, nessuna apertura fotocamera durante l'avvio. Provati scatto sintetico, conferma, miniatura, riavvio e persistenza della foto associata all'ispezione. Provato il rifiuto reale del permesso CAMERA dell'app fotocamera dell'emulatore: ritorno a Collettori con messaggio di annullamento/rifiuto, scheda intatta. Foto mancanti/non decodificabili mostrano un testo; errori di elenco e pulizia sono diagnosticabili.

## BRANDING E UI

**Nome app: Collettori.** Launcher, dialogo permessi, schermate, metadata e documentazione usano il nome unico. Demo indica solo variante/dataset; applicationId e suffisso APK sono mantenuti per aggiornare le installazioni esistenti.

Logo originale: tubo verde verticale in pixel art, apertura superiore scura, pochi blocchi di luce/ombra. Nessun asset Nintendo o sprite di terzi. VectorDrawable scalabile, adaptive icon e round icon per tutte le densità (minSdk 26); splash Android 12+ con logo e nome, risorsa di avvio precedente compatibile con Android 8. Nessuna dipendenza grafica aggiunta.

Palette: primario petrolio `#176B68`, conferma `#216E40`, attenzione `#875000`, errore `#BA1A1A`, superfici chiare e secondari grigio-verdi. Icone vettoriali originali per mappa, marker, mirino, cronologia, informazioni, check, attenzione, fotocamera, modifica e rimozione. Etichette sempre presenti; controlli esclusivamente iconici hanno descrizione accessibile.

Avvia/Salva ispezione sono primari; Scatta foto è tonale; parziale e impedimento hanno peso secondario e testo esplicito. La mappa mantiene FAB sovrapposti e scheda pozzetto a comparsa. Corretto inoltre un difetto osservato durante la digitazione: il testo locale non viene più rimpiazzato da ogni emissione Room dell'autosalvataggio; gli eventi GPS sono letti dalla versione persistita.

## TEST E VERIFICA INSTALLAZIONE PULITA

Ambiente: Android 15/API 35 Google APIs x86_64, emulatore locale dedicato, WHPX, rendering software, display 432×960. I test hardware non sono stati eseguiti su un telefono fisico. Logcat integrali e screenshot sono conservati in `.tools/crash-v011/` ed esportati in `local-output/verification/v0.12/Collettori-v0.12-evidenze.zip`, esclusi da Git.

| Prova realmente eseguita | Esito | Evidenza / limite |
|---|---|---|
| Precedente v0.1-demo: installazione e avvio | PASS | Creata bozza con evento GPS negato |
| Originale v0.11: installazione pulita | FAIL, riprodotto | ExceptionInInitializerError / MapLibreConfigurationException |
| Originale v0.11: aggiornamento con bozza | FAIL, riprodotto | Stessa catena, prima dell'apertura DB |
| Originale v0.11: debug e release | FAIL, riprodotto | Stessa causa in entrambe |
| Fix minimo prima del restyling | PASS | Mappa OSM e 10 marker, dati azzerati |
| Aggiornamento precedente → v0.11 → v0.12 | PASS | Bozza ed evento originali riaperti; nessuna perdita |
| APK demo finale: disinstallazione, reinstallazione, primo avvio | PASS | APK `32e5bf…034c8`; nessun permesso runtime richiesto all'avvio |
| APK debug finale: dati puliti, avvio | PASS | Schermata di accesso pilota; Logcat senza crash |
| APK release finale: dati puliti, avvio | PASS | Schermata di accesso pilota; chiave di test |
| Riavvio e ritorno dopo arresto forzato | PASS | Ispezione completata e foto ancora presenti |
| Posizione negata | PASS | Dialogo realmente rifiutato; messaggio esplicito |
| Posizione concessa, fix e accuratezza disponibili | PASS | Fix inviato via console emulatore; UI ±5 m |
| Posizione/precisione assenti, timeout | PASS | UI e test strumentale senza permessi; nessun `null` visibile |
| Posizione valida con accuracy assente; coordinate invalide | PASS, unitario | Generazione stile senza cerchio/marker errato; non simulato come fix hardware |
| GPS globale disattivato con permessi concessi | PASS | Build finale: `localizzazione disattivata`, nessun nuovo dialogo |
| Mappa con rete | PASS | OSM, collettore e tutti i 10 marker visibili |
| Mappa senza Wi-Fi/dati e con GPS spento | PASS | Geometrie locali visibili; schermate e ispezione utilizzabili |
| Selezione pozzetto da mappa ed elenco | PASS | PZ-001 su mappa e PZ-001/PZ-002 da elenco |
| Ispezione: apertura, default, modifica e salvataggio | PASS | Percorso UI reale e test repository sul dispositivo |
| Digitazione durante autosalvataggio | PASS dopo fix | Prima testo riordinato; dopo fix `Collaudo offline` esatto in campo e scheda conservata |
| Fotocamera: apertura, scatto, conferma, miniatura | PASS | Camera emulata, nessuna foto reale dell'utente |
| Fotocamera: permesso negato | PASS | Revocato CAMERA a com.android.camera2 e rifiutato il dialogo; ritorno sicuro alla scheda |
| Foto dopo force-stop/riapertura | PASS | Miniatura della stessa foto e timestamp conservati |
| Ricreazione Activity e test Room/foto | PASS | 5 test strumentali demo, zero failure nella suite finale |
| Test unitari Android | PASS | 14 per variante × debug/demo/release = 42 |
| Backend | PASS | Suite: 48 pass, 1 skip; successivo test parametrizzato 0.11/0.12 + dataset: 3 pass |
| PostGIS dedicato | NON TESTABILE | Database di test non configurato, skip esplicito |
| GPS/fotocamera su telefono fisico; Android 8 e altri OEM | NON TESTABILE | Nessun telefono collegato; ambiente runtime disponibile API 35 |

Il primo test strumentale di ricreazione falliva sulla lettura della radice di accessibilità, che talvolta è nulla nell'emulatore anche con schermata visibile. Il controllo è stato corretto per verificare direttamente Activity RESUMED, finestra collegata e dimensioni positive; suite rieseguita: `OK (5 tests)`. I test di persistenza passavano già. Non è stata rimossa una verifica di crash dell'app.

## LOGCAT DOPO IL FIX

Azzerato Logcat, arrestata e riavviata l'app finale, navigate Mappa, Pozzetti, Ispezioni e Altro, ritorno alla Mappa; salvato `final-navigation-logcat.txt`. Nessun `FATAL EXCEPTION`, `Fatal signal` o errore applicativo ripetuto. Ispezionati anche i messaggi del PID applicativo, non soltanto il buffer crash.

Warning residui del runtime emulato: variante CPU x86_64, file opzionale `base.dm` assente, accessi riflessivi ammessi delle librerie, fallback HWUI/EGL e rendernode non disponibile nel renderer software. Nel percorso precedente i warning MapLibre `Canceled` coincidono con richieste tile annullate durante cambio centro/rete. Non mostrano perdita della mappa o crash e non sono stati silenziati.

## BUILD

Eseguiti `clean`, `build`, unit test debug/demo/release, lint, APK debug/demo/release e compilazione test strumentali. `BUILD SUCCESSFUL`. Lint: 0 errori, 28 warning (aggiornamenti dipendenze/target suggeriti, uso KTX/Timber e regole backup Android 12). Risolto l'unico errore di compatibilità introdotto nel restyling: attributo navigation bar API 27 spostato in `values-v27`. Nessun aggiornamento indiscriminato delle librerie.

Gli APK debug e release di test sono conservati in `local-output/verification/v0.12/`; la demo corrente resta in `local-output/`.

| Artefatto | SHA-256 |
|---|---|
| `Collettori-v0.12-demo.apk` — da installare per il dataset locale | `32e5bfdf9e88d7423590dc93a64afea0757cc41b8101d0d877d47f67182034c8` |
| `Collettori-v0.12-debug.apk` — pilota con accesso server | `cb44f3320dc83b5b8c62440e19f71d854c3ed317b998a577c842f93b77bc9cd1` |
| `Collettori-v0.12-release-test.apk` — pilota release, firmata per collaudo | `790c9d0cf9c71b086df8908a396bd86f854e92a5ace55eb2f6bbaad77538bf9f` |

La variante distribuita dal progetto è `demo`, ereditata da debug e firmata con la stessa chiave di test delle versioni precedenti. Non è una release con chiave produttiva. La variante `release` del progetto è invece il pilota autenticato: verificato l'avvio, non un ciclo completo con server live. APK release non firmato disponibile anche in `android/app/build-pilot/outputs/apk/release/`.

VersionCode 12, versionName 0.12 / 0.12-demo: progressione coerente 0.1 → 0.11 → 0.12, necessaria per distinguere la correzione installabile. Schema e identificativi dataset non rinumerati. Aggiornare il backend incluso per accettare app_version 0.12; continua ad accettare 0.1 e 0.11. Nessuna istruzione SQL.

## GIT E PROBLEMI ANCORA APERTI

Collaudo sul branch `fix/v0.12-startup`, commit applicativo `ae641b8`. La successiva pubblicazione v0.12 è descritta nelle [note di rilascio](RELEASE-v0.12.md): allineamento fast-forward di `main`, nuovo tag v0.12, nessun tag precedente sovrascritto. Prima del commit controllati status, diff e file da includere: APK, log, immagine emulatore, cache, chiavi e `.env` restano esclusi.

Nessun crash applicativo residuo osservato negli scenari eseguiti. Restano da verificare dispositivi fisici/OEM e Android 8; GPS sul campo e qualità fotografica reale non sono attestati dal test emulato. Rimangono i limiti funzionali già esistenti: foto locali senza upload, mappa base offline non garantita, export JSON senza file immagine, NFC/RFID futuro. I warning lint residui sono documentati sopra e non sono stati nascosti.
