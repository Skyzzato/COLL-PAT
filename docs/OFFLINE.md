# Archivio e cartografia offline — COLL-PAT v0.13

Schede, outbox, anagrafica e preferenze risiedono in Room, foto e originali GIS in files privati. Non dipendono dalla cache cartografica. Le bozze sono solo locali; gli invii strutturati richiedono sessione Auth e rete. Disinstallazione o cancellazione dati rimuovono l'archivio; aggiornare la stessa variante conserva database e firma.

Unica cache nominale MapLibre: **209715200 byte (200 MiB)**, costante AppSpec.MAP_CACHE_BYTES. Nessuna seconda cache OkHttp; il vecchio `osm-http` è rimosso in background. User-Agent COLL-PAT/versione e attribuzione OpenStreetMap. [API cache MapLibre](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/-offline-manager/set-maximum-ambient-cache-size.html).

Le risorse visitate possono essere riutilizzate secondo le regole HTTP/MapLibre; cache incompleta, scaduta o rimossa non equivale a un pacchetto offline. Nessun download preventivo di aree/zoom dai server standard OSM. Dati locali e selezione manuale restano accessibili anche senza base.

WorkManager riprende con rete, avvio e ritorno in primo piano. Android può sospendere il processo; dopo arresto forzato occorre riaprire l'app. Nessun invio a telefono spento. Nessuna posizione in background richiesta per trasmettere. Sessione scaduta, conflitto e generation obsoleta sono stati distinti; i dati rimangono conservati.
