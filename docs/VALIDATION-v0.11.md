# Collaudo v0.11

Il report distingue test automatici da prove fisiche: compilare non dimostra apertura dell'app o buon funzionamento della fotocamera.

## Automatici

- Backend: 48 passati, 1 PostGIS saltato (database di test non configurato); una deprecation warning Starlette/AnyIO.
- Android: assembleDebug, assembleDemo, testDebugUnitTest, testDemoUnitTest, assembleDebugAndroidTest: BUILD SUCCESSFUL. 11 test JUnit per variante, zero errori/fallimenti; include casi GPS condivisi esistenti.
- Casi GPS: candidato unico, accuratezza variabile, ambiguità, precisione assente/eccessiva, misura vecchia, mock, permesso approssimativo.
- Dataset: 10 punti, 9 segmenti, riferimenti validi, indicazione sintetica e sorgente OSM/attribuzione.
- Default regolari e foto senza upload/URL remoto.

## Artefatto verificato

`Collettori-v0.11-demo.apk`: applicationId it.pat.collettori.pilot.demo, versionName 0.11-demo, versionCode 11, minSdk 26, targetSdk 36. Firma APK v2 verificata con apksigner. SHA-256: `c72b5ca437675cd24b395c5ad71a525e8e40b9968e77615d0828014763918879`.

Analisi automatica delle firme di secret sui 167 oggetti storici Git: nessuna corrispondenza; .env, keystore, build, APK e directory locali esclusi. Questo controllo non equivale a un audit di sicurezza completo.

## Prove da eseguire su Android reale

Nessun telefono collegato e nessun AVD/system image configurato durante lo sviluppo. Non dichiarare questi casi passati: installazione/apertura, raster OSM visibile, selezione dei 10 pozzetti, fix reale, permesso negato/GPS spento, avvio e ripresa bozza, anomalia e note, fotocamera/galleria/annullamento, miniatura/rimozione, riavvio e persistenza foto, completamento, tab, rotazione e assenza crash. APK pre-release, non release stabile.

Checklist operativa: provare un fix presso Trento e uno distante; un punto candidato deve sempre essere confermato. Negare il GPS e registrare una motivazione. Scattare foto, riavviare app e verificare associazione alla visita. Ruotare durante bozza e durante fotocamera; verificare il risultato della camera. Disattivare rete e verificare tracciato senza base OSM. Riattivarla e verificare il raster e link attribuzione. Provare aggiornamento sopra demo precedente senza disinstallazione; ispezioni precedenti conservate.
