# Collaudo COLL-PAT v0.21

## Ambiente e prove

Data: 23 settembre 2026. Windows, JBR Android Studio, SDK 36, emulatore Android API 35; PostgreSQL/PostGIS locale con database temporanei. La suite non usa la connessione remota per preparare o cancellare fixture.

| Prova | Risultato |
|---|---|
| Python/backend/SQL, suite completa | 117 superati, un warning di deprecazione Starlette; nessun fallimento |
| SQL v0.21 dopo l'ultimo controllo anti-downgrade | 16 superati; include il caso aggiunto dopo la suite completa |
| Kotlin unitari | 108 superati, 0 fallimenti/errori/skipped |
| Compilazione APK e APK test, lint | Task testDebugUnitTest, assembleDebug, assembleDebugAndroidTest e lint superati; lint 0 errori/fatali e 40 warning |
| Android strumentati | 35 superati nella suite completa; 9 prove pertinenti di cancellazione/ripristino ripetute dopo il riepilogo per categoria |
| Installazione -r da v0.2/build 17 | Righe e foto confrontate prima/dopo: 3 catalogo, 24 visite, 39 outbox, 12 audit, 0 import, 6 file foto identici |
| Installazione pulita | Nuovo AVD API 35, pacchetto prima assente; installazione riuscita e login raggiunto, senza toccare i dati del primo emulatore |
| Firma / versione / hash | 0.21 / build 21 / it.pat.collettori.pilot; firma SHA-256 `acf785391278fa98832980a51e594c88ffb06ff36b590c73c18c917f975080c6` |

La migrazione Room non è cambiata: schema 3, nessun fallback distruttivo. Le nuove migrazioni SQL sono applicate due volte nella fixture. Il test di manutenzione confronta un altro progetto prima/dopo e verifica che non sia alterato; un fingerprint superato non autorizza il purge.

## Regressioni esercitate

Cancellazione SQL con bozza e allegato, replay della risposta persa, vecchie operazioni dopo purge, nuova dipendenza dopo anteprima, ruoli e ispezione singola; punto condiviso conservato e rimozione puntuale; tre cicli della stessa source_identity con UUID diversi. Room verifica rimozione di foto locali, audit, snapshot e outbox e conservazione delle altre schede.

GPS v2: esclusione iniziale reale, campioni duplicati/simulati, media delle accuracy senza eliminazione opportunistica dei fix peggiori, no GPS motivato persistente e sostituito da nuovo tentativo. SQL ricalcola media, dispersione e tempi, controlla identità anche nelle bozze e impedisce il downgrade del contratto dopo la creazione. I test UI esercitano OK/ANNULLA, interruzione, conferma persistente e ricevuta senza chiusura automatica.

Test import/topologia e simboli: ordine esplicito, rami, codifica, geometrie e identità; risoluzione override/default, ancore codice collettore, note neutre, sicurezza, code raggruppate e classificazione HTTP. Il flusso foto controllato conserva compressione e dimensioni, non conferma il successo quando la ricevuta fallisce e riutilizza lo stesso percorso al retry.

## Diagnosi accertata

L'import precedente sceglieva ISOLATED quando mancavano le linee e usava identificativi che impedivano una nuova vita dopo eliminazione. Ora ORDERED richiede un ordine affidabile o una scelta esplicita; il riconoscimento della sorgente vale per gli oggetti attivi e i nuovi cicli ricevono UUID nuovi.

`ApiError` deriva da IOException: alcuni HTTP di validazione/RPC mancanti cadevano nel messaggio generico di rete. Il ramo HTTP ora precede quello IOException, conserva codice server/endpoint senza esporre payload privati e distingue autenticazione, permessi, schema, validazione, conflitto, limite richieste e server.

Verifica remota read-only: `coll_pat_version` HTTP 200 (minimo 0.14, ultima 0.2); `coll_pat_status` senza sessione HTTP 401/42501; nuove RPC `coll_pat_deleted` e `coll_pat_deletion_preview` HTTP 404/PGRST202. Il server è raggiungibile e il nuovo contratto non è disponibile. La connessione amministrativa disponibile non può leggere lo schema (42501). Non è stato verificato il rinnovo di una sessione operativa remota: solo HTTP controllato nei test.

## Stato remoto e limiti

**Migrazioni 008/009 non applicate al progetto remoto, pulizia dati archiviati non eseguita, oggetti Storage reali non eliminati e indicazione latest_version non aggiornata.** Seguire [MIGRATIONS-v0.21](MIGRATIONS-v0.21.md). L'APK conserva il lavoro locale, ma le nuove RPC richiedono l'intervento amministrativo prima del collaudo operativo.

Nessuna prova su due telefoni reali o GPS sul campo. Server HTTP controllato e PostgreSQL locale non dimostrano il funzionamento del deployment remoto. Il percorso completo import/cancella/reimport dal picker Android, la ricreazione del modulo punto in tutte le fasi e le collisioni ad ogni zoom restano da provare: [rapporto UX](UX-REVIEW-v0.21.md).

Le cancellazioni eliminano i payload applicativi identificabili e le relative copie/attività; non dimostrano cancellazione forense dei supporti né rimozione di export esterni o backup del provider. Eventuali file orfani pregressi senza riferimento a una scheda richiedono inventario amministrativo e non vengono eliminati alla cieca.

## Riproduzione

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint --no-daemon
adb install -r app/build-v021/outputs/apk/debug/app-debug.apk
adb install -r app/build-v021/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w it.pat.collettori.pilot.test/androidx.test.runner.AndroidJUnitRunner
```

Esportare solo screenshot di fixture sintetiche. I file privati dell'emulatore non sono allegati alla prerelease.

## Artefatto consegnato

APK: `COLL-PAT-v0.21.apk`, 63518820 byte. SHA-256:

```text
11fe3ac6b2ec64cc5c7c5ed85eff27b4c9b446a3727ea38b247208693d0a98c9
```

Configurazione pubblica verificata nel DEX, chiave sb_publishable; nessuna chiave sb_secret, keystore o ambiente demo nel pacchetto. I tag distribuiti hanno versionCode 1, 11, 12, 13, 14, 15, 16 e 17; il codice 21 è superiore. Il sorgente è identificato dal tag v0.21 e dal commit indicato nella prerelease.
