# Scelte tecniche della v0.1

Repository inizialmente vuoto, senza AGENTS.md o componenti esistenti. Nessuna applicazione estranea modificata.

## Architettura

```text
Shapefile + mapping + base autorizzata
             │ import amministrativo
             ▼
FastAPI ── PostgreSQL/PostGIS ── snapshot dei rapporti
   │              │
   │              └── fonti e rapporti d'import in volume persistente
   ├── portale web HTML/JS (stessa origine, token individuale)
   └── API autenticata
          │ catalogo, pacchetti, visite, ricevute
          ▼
Android: Compose → Repository → Room / outbox → WorkManager
             │                     │
             ├── MapLibre          └── dati separati per account
             └── acquisizione posizione in primo piano
```

Separazioni architetturali: `AssetSelection`/`AssetIdentifier`, `EvidenceCollector`, `GpsRule`, scheda tecnica. I tipi futuri QR/codice targhetta/NFC non hanno implementazioni, permessi o pulsanti. Le evidenze sono una collezione associata alla visita, non un campo esclusivo "GPS oppure QR".

Entità relazionali: imprese, utenti, autorizzazioni territoriali, collettori, tratti, pozzetti, collegamenti versionati, dataset, regole, ispezioni, eventi di localizzazione, revisioni, operazioni di invio, anomalie, audit, scadenze, emissioni. Posizione cartografica e dispositivo non vengono fuse.

## Versioni e dipendenze

- Android Gradle Plugin 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.2.21, KSP 2.2.21-2.0.4.
- Compose BOM 2025.10.01, Room 2.8.4, WorkManager 2.11.2, MapLibre Native 13.6.1, Play services location 21.3.0.
- Versioni dirette Python in `backend/requirements.in`; dipendenze risolte in `requirements.lock`.
- Risoluzione Android in `android/app/gradle.lockfile`; nessun `+` o `latest` nelle dipendenze dichiarate.
- Build debug HTTP solo per il server locale; build principale HTTPS. Nessun servizio a pagamento, nessun deploy reale.

## Fonti tecniche ufficiali consultate

- [MapLibre Native GeoJsonSource](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-source/index.html)
- [MapLibre Style.Builder.fromJson](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps/-style/-builder/from-json.html)
- [MapLibre OfflineManager](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-manager/index.html): esaminato; la pipeline scelta usa dati locali espliciti, non presume importazione universale di MBTiles.
- [CurrentLocationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder)
- [Room](https://developer.android.com/jetpack/androidx/releases/room), [WorkManager](https://developer.android.com/jetpack/androidx/releases/work), [AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [FastAPI, sicurezza e hashing password](https://fastapi.tiangolo.com/tutorial/security/oauth2-jwt/): usato hashing Argon2; il progetto impiega token opachi, non JWT.
- [GDAL, specificità shapefile](https://gdal.org/en/stable/drivers/vector/shapefile.html): controllo dei file accessori/CRS/codifica; implementazione pilota con pyshp e pyproj.
- [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/): nessun download massivo dai server standard.

## Licenze e risorse

MapLibre e le altre librerie mantengono le proprie licenze upstream. I glifi Noto Sans provengono dal repository pubblico [maplibre/demotiles](https://github.com/maplibre/demotiles/tree/gh-pages/font); licenza Noto SIL Open Font License inclusa in `android/app/src/main/assets/glyphs/LICENSE-Noto.txt`. Sono risorse font, non cartografia OSM.

Lo stile e il dataset sintetico sono prodotti per questo progetto. Per una base OSM o di altro fornitore occorre verificarne licenza, attribuzione e distribuzione offline prima dell'importazione. La scelta del formato locale non attribuisce diritti sui dati.
