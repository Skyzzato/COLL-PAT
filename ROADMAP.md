# Roadmap COLL-PAT

La v0.13 comprende sincronizzazione strutturata Supabase diretta, catalogo e importazione GIS Android, audit e reset. Prima dell'uso operativo: applicazione/verifica sul progetto reale, collaudo multi-dispositivo e sul campo, firma operativa separata dalla chiave debug storica e disabilitazione definitiva dello sblocco admin/admin.

- Caricamento reale delle foto con storage autorizzato e controllo delle ricevute; attualmente solo foto locali e metadati.
- Formati/CRS GIS aggiuntivi e gestione esplicita di multipart e associazioni sorgente cambiate.
- NFC/HF, lettori RFID esterni e QR: conservare i contratti di identificazione, senza scansioni simulate.
- Cartografia offline autorizzata: estratti di dati OSM, conversione GeoJSON/vettoriale adeguata, stili COLL-PAT e pacchetti da fonti autorizzate. Preservare origine, licenza ODbL e attribuzione: uno stile personalizzato non rende proprietari i dati OSM. Non scaricare preventivamente aree/zoom dai tile server standard; non pubblicare dati infrastrutturali PAT in OSM.
- Pianificazione delle visite dell'intero collettore, solo dopo definizione del modello di giro/ramo: mai sommare automaticamente le ispezioni dei singoli punti come visite di rete.
