# Pipeline GIS e offline

## Una pipeline di ingresso

ZIP di shapefile → mapping JSON amministrativo → validazione Python (`pyshp`, `pyproj`) → archivio relazionale/versione JSON UTF-8 → download verificato → Room → sorgenti GeoJSON di MapLibre Native.

Lo shapefile non viene modificato sul telefono. Per il pilota si usa JSON/GeoJSON anziché introdurre un secondo database GeoPackage dentro Room. Il pacchetto contiene punti, tratti, collegamenti, coordinate geografiche e una base vettoriale limitata. Non si importa MBTiles in MapLibre e non si confonde MBTiles con il database offline proprietario del motore.

Per ciascun layer: `.shp`, `.shx`, `.dbf`; `.prj` e `.cpg` oppure configurazione esplicita di CRS/codifica. Il sistema **non assegna WGS84 a un file privo di CRS**. Output EPSG:4326, ordine longitudine/latitudine. Il limite ZIP pilota è 200 MB decompressi; i file vengono letti senza estrazione arbitraria dei percorsi.

Esempio operativo da adattare **solo dopo l'esame dei campi reali**:

```json
{
  "area_id": "BACINO_PILOTA",
  "synthetic": false,
  "points": {
    "file": "pozzetti",
    "key": "ID_STABILE",
    "code": "NUMERO",
    "uncertainty": "ERR_METRI"
  },
  "segments": {
    "file": "tratti",
    "key": "ID_TRATTO",
    "collector": "COLLETTORE",
    "from": "ID_INIZIO",
    "to": "ID_FINE"
  },
  "basemap": "base.geojson",
  "attribution": "Fonte e licenza autorizzata della base"
}
```

`uncertainty` può essere omesso: g rimarrà sconosciuta. Se mancano `.prj`/`.cpg`, aggiungere a ogni layer `crs` (es. EPSG verificato) e/o `encoding`. Non usare l'esempio come dichiarazione sul CRS dei file PAT.

I campi identificativi devono essere testuali: se sono numerici, gli zeri già perduti non possono essere ricostruiti inventandoli. La chiave sorgente è obbligatoria, stabile e univoca per area. Se i file reali contengono solo collettore+numero, occorre prima definire con il referente GIS la corrispondenza stabile e il trattamento dei pozzetti condivisi; il sistema non deduce automaticamente fusioni dalla vicinanza.

Gli UUID sono derivati deterministicamente da area, tipo e chiave sorgente; non dal numero di riga o dalle coordinate. Un cambio di chiave o di area va gestito come migrazione amministrativa, non come semplice reimportazione.

## Controlli e storico

- Chiavi mancanti/duplicate, codici mancanti, geometrie non valide, CRS/codifica mancanti e collegamenti irrisolti bloccano la pubblicazione.
- Codice pozzetto duplicato nello stesso collettore: errore da esaminare; stesso numero in collettori diversi: ammesso.
- Coordinate coincidenti: avviso nel rapporto; non si fondono automaticamente i manufatti.
- Manufatti privi di tratti: errore; tratti con estremi sconosciuti: errore.
- Un pozzetto può partecipare a più tratti/collettori; la tabella `connections` conserva anche la versione del dataset.
- I manufatti assenti dal nuovo pacchetto spariscono dalla versione corrente ma rimangono nello storico, con avviso. Non si eliminano visite o UUID precedenti.
- Fonte ZIP, mapping e rapporto rimangono in `runtime/imports/<UUID>/`, anche quando la pubblicazione fallisce.
- PostgreSQL conserva inoltre le geometrie di ciascuna versione in `reference_geometries`, con indice spaziale GiST.

## Base cartografica concreta

`base.geojson` opzionale deve essere una FeatureCollection WGS84 autorizzata (poligoni, linee, punti). Lo stile locale visualizza aree, viabilità e nomi. Per dati reali il referente deve fornire una base propria o con diritto di redistribuzione offline, includendo attribuzione e verifica della copertura. Non viene scaricata cartografia da OSM in questa versione.

La demo include un poligono di contesto e una strada **sintetici**. Non è una cartografia operativa di Trento. La stessa pipeline accetta una base reale autorizzata per il sottoinsieme pilota; produzione e aggiornamento della base reale restano un'attività di preparazione dati.

Stile JSON generato nell'app, simboli geometrici senza sprite remoti, caratteri Noto Sans in PBF inclusi nell'APK (intervalli 0–255 e 256–511). Il profilo pilota copre latino/italiano: nomi o codici con altri alfabeti richiedono l'aggiunta dei rispettivi intervalli di glifi prima di dichiarare pronta la mappa. In elenco e schede il testo originale rimane disponibile tramite i caratteri di Android.

Gli strati PAT e base sono separati. Nessuna pubblicazione verso OSM. La funzione MapLibre usata è `Style.Builder.fromJson` con dati GeoJSON in memoria e glifi `asset://`; tutto proviene dall'archivio persistente, non da cache di navigazione.

## Pacchetti e dimensionamento

Catalogo: UUID versione, area, rettangolo di copertura della rete, byte, SHA-256, natura sintetica/operativa, presenza base. La copertura effettiva della base va verificata sul dataset. L'app controlla spazio (`3 × byte + 20 MB`, riserva iniziale di implementazione) e integrità, valida il contenuto, poi installa con transazione Room. Le versioni precedenti restano disponibili alle visite storiche.

Misurare nel pilota: byte per punto/tratto, complessità della base, memoria, primo disegno, zoom, selezione, ricerca e spazio del database dopo aggiornamenti. Il test di 12.500 punti valida importazione e identità; **non equivale a una misura delle prestazioni grafiche su telefono**.
