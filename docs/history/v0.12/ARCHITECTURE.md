# Architettura — v0.11

## Componenti

`PilotApplication` crea Repository e client cartografico. `MainActivity` instrada la build demo a `DemoWorkspace`; mantiene il flusso Pilot autenticato. DemoWorkspace gestisce le quattro schede, conferma del pozzetto, selezione del dataset, GPS e ripresa della bozza. `InspectionEditor` è condiviso: riepilogo compatto nella demo, controlli espandibili, revisioni e validazioni. `PhotoPanel` gestisce i contratti Android TakePicture/GetContent e anteprime decodificate fuori dal thread UI.

`Repository` conserva pacchetti, visite, eventi e coda transazionale. `PhotoRepository` separa media privati e metadati da `RemotePhotoStorage`; l'implementazione non configurata restituisce un errore, mai una ricevuta falsa. Non aggiunge URI locali al payload del server pilota. `Identification.kt` definisce metodo, associazione tag e servizio di identificazione; GPS è l'unico adattatore implementato. Gli enum/contratti QR preesistenti restano compatibili.

## Persistenza

Room v1 invariato: visits, outbox, packages, settings. JSON delle visite invariato per compatibilità server. Foto in filesDir/photos tramite FileProvider non esportato; metadati in settings per owner e inspectionId, insieme a eventi di identificazione. Acquisizione da galleria copiata nello spazio privato: non dipende dalla durata del permesso URI esterno. Le foto rimosse non compaiono più nella bozza; i file sono conservati per non rompere riferimenti di revisioni. Nessuna pulizia automatica dei media referenziati. Revisione archivia metadati fotografici precedenti. Esportazione demo include riferimenti, non immagini.

## Cartografia

MapLibre Native Android 13.6.1, mantenuto; raster OSM e layer GeoJSON per linea, pozzetti, selezione ocra, candidati blu, posizione e cerchio di precisione. Glifi inclusi per etichette. Attribuzione OSM visibile e controllo MapLibre cliccabile. Tile HTTPS, User-Agent identificabile Collettori/0.11 e cache HTTP 50 MB rispettosa delle intestazioni. Nessun prefetch né download offline massivo. Offline resta la geometria locale: la base OSM non è garantita. La connettività valida abilita il raster; errori mappa vengono mostrati senza perdere i dati.

Fonti verificate: [MapLibre Android](https://maplibre.org/maplibre-native/android/api/), [policy tile OSM](https://operations.osmfoundation.org/policies/tiles/), [attribuzione](https://www.openstreetmap.org/copyright). Produzione: provider adatto al carico e licenza offline se richiesta.

## Localizzazione e identificazione

LocationCapture usa FusedLocationProvider, richiesta foreground, nessun tracking in background. Permesso negato, timeout, precisione assente, mock e GPS disattivato restano eventi espliciti. GpsIdentification suggerisce candidati: raggio clamp(accuratezza + 5 m, 8 m, 55 m), rifiuta accuratezza >50 m, permesso approssimativo, mock, errore e misura più vecchia di 60 s (età del fix più tempo trascorso). Molto probabile solo candidato unico, accuratezza ≤10 m e distanza+accuratezza ≤15 m. Altrimenti possibile, multiplo o nessuno. Queste soglie sono ipotesi demo da validare sul campo.

La regola GpsRule del pilota resta indipendente e versionata, utilizzata per l'evidenza registrata. Il suggerimento di navigazione non è una certificazione. L'ispezione acquisisce nuovamente la posizione; non riusa automaticamente una vecchia misura di orientamento.

## Backend

FastAPI, SQLAlchemy, PostGIS e migrazioni iniziali conservati. Backend aggiornato ad accettare app_version 0.11 e 0.1 storica. Foto e tag non vengono inviati al server: futuro contratto API autenticato, storage e coda di upload separata necessari. Nessuna migration SQL aggiuntiva nella v0.11. L'integrazione futura deve separare successo dell'ispezione da quello di ogni allegato.
