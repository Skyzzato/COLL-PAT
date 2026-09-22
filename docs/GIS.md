# GIS sul telefono — COLL-PAT v0.13

Flusso: selettore Android OpenDocument → copia privata ZIP → lettura nativa Kotlin → rilevamento layer/campi/CRS → mapping proposto modificabile → anteprima MapLibre e rapporto → conferma → transazione Room → outbox Supabase. Nessun Python/FastAPI richiesto sul telefono e nessuna nuova libreria Android; parser originale secondo [specifica ESRI](https://www.esri.com/library/whitepapers/pdfs/shapefile.pdf).

Richiesti .shp/.shx/.dbf. .prj/.cpg obbligatori salvo configurazione esplicita dell'amministratore; nessuna assunzione automatica WGS84/codifica. Punti/polilinee e varianti Z/M (XY trasformate, Z/M non utilizzate per lunghezze planimetriche). EPSG 4326, 3857, WGS84 UTM 32/33N, ETRS89 UTM 32/33N; altri CRS rifiutati. UTF-8, Windows-1252, Latin-1. La trasformazione inversa UTM è verificata contro fixture prodotte da PROJ/pyproj.

Limiti: 32 MiB compressi/decompressi, 80 componenti, 10000 oggetti, percorsi assoluti/parent traversal/duplicati rifiutati, rapporti di decompressione limitati, lunghezze SHP/SHX e corrispondenza DBF verificate. Memoria insufficiente produce errore senza conferma. I layer multipart sono riconosciuti ma occorre separarli in rami con chiavi individuali per pubblicarli con topologia.

Tre modalità:

- **A punti e linee**: geometrie reali, estremi per chiavi. Se assenti, prossimità solo con tolleranza esplicita 0–20 m e candidato unico. Avvisi su discontinuità/biforcazioni/punti isolati. Progressiva calcolabile solo lungo un percorso diretto continuo senza ambiguità; origine identificata.
- **B punti ordinati**: sequenza, progressiva GIS o riferimenti precedente/successivo sono campi distinti. Ordine e rami confermati, duplicati/cicli/discontinuità rifiutati; tratti schematici e metri stimati. Il codice visuale non è mai interpretato come sequenza. La progressiva GIS resta separata da quella calcolata.
- **C punti isolati**: nessuna linea inventata, nessun precedente o progressiva obbligatori. Tipo del manufatto conservato; lunghezza non disponibile se non dichiarata.

Nome sorgente e layer devono restare stabili nella reimportazione. Chiave assente: consenso a creare nuovi UUID, mai aggiornamenti per somiglianza. Per cambiare chiave/sorgente occorre prima predisporre un'associazione esplicita; non è presente un algoritmo di fusione automatica. Importazioni parziali non eliminano oggetti esistenti o appartenenze condivise.

Rapporto: inserimenti/aggiornamenti/invariati/avvisi/errori. Originale ZIP nello spazio privato, hash dei componenti normalizzati, mapping e rapporto in Room e provenienza remota. Fino a 4 MiB invio unico; oltre, staging privato con pubblicazione finale atomica (40 MiB strutturati complessivi, singola geometria ≤4 MiB). Interruzioni prima della conferma non pubblicano nulla. Catalogo locale visibile subito come pendente; ispezioni dipendenti attendono il completamento dell'anagrafica.

Gli shapefile di test nel repository contengono esclusivamente coordinate sintetiche. Non inserire dati infrastrutturali reali nel repository pubblico o in OSM.
