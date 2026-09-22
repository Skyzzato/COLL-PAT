# GIS sul telefono — COLL-PAT v0.15

Accesso nei sorgenti aggiornati: **Impostazioni → Dati cartografici → Importa shapefile**. Per un account server occorre il ruolo `admin` del progetto applicativo, verificato anche dalle RPC Supabase; `inspector` non può importare. La variante demo consente importazioni nel solo archivio demo locale. Nessuna chiave amministrativa viene inserita nel client. Le correzioni funzionali v0.14 sono incluse nella v0.15.

Flusso: selettore Android OpenDocument → copia privata ZIP → lettura nativa Kotlin → rilevamento layer/campi/CRS → mapping proposto modificabile → anteprima MapLibre e rapporto → conferma → transazione Room → outbox Supabase. Nessun Python/FastAPI richiesto sul telefono e nessuna nuova libreria Android; parser originale secondo [specifica ESRI](https://www.esri.com/library/whitepapers/pdfs/shapefile.pdf).

Richiesti .shp/.shx/.dbf. .prj obbligatorio salvo EPSG esplicito verificato; nessuna assunzione automatica WGS84. Il .cpg è facoltativo: la sua assenza non blocca l’importazione. Punti/polilinee e varianti Z/M (XY trasformate, Z/M non utilizzate per lunghezze planimetriche). EPSG 4326, 3857, WGS84 UTM 32/33N, ETRS89 UTM 32/33N; altri CRS rifiutati. UTF-8, Windows-1252, Latin-1. La trasformazione inversa UTM è verificata contro fixture prodotte da PROJ/pyproj.

Limiti: 32 MiB compressi/decompressi, 80 componenti, 10000 oggetti, percorsi assoluti/parent traversal/duplicati rifiutati, rapporti di decompressione limitati, lunghezze SHP/SHX e corrispondenza DBF verificate. Memoria insufficiente produce errore senza conferma. I layer multipart sono riconosciuti ma occorre separarli in rami con chiavi individuali per pubblicarli con topologia.

Tre modalità:

- **A punti e linee**: geometrie reali, estremi per chiavi. Se assenti, prossimità solo con tolleranza esplicita 0–20 m e candidato unico. Avvisi su discontinuità/biforcazioni/punti isolati. Progressiva calcolabile solo lungo un percorso diretto continuo senza ambiguità; origine identificata.
- **B punti ordinati**: sequenza, progressiva GIS o riferimenti precedente/successivo sono campi distinti. Ordine e rami confermati, duplicati/cicli/discontinuità rifiutati; tratti schematici e metri stimati. Il codice visuale non è mai interpretato come sequenza. La progressiva GIS resta separata da quella calcolata.
- **C punti isolati**: nessuna linea inventata, nessun precedente o progressiva obbligatori. Tipo del manufatto conservato; lunghezza non disponibile se non dichiarata.

Nome sorgente e layer devono restare stabili nella reimportazione. Chiave assente, vuota o duplicata: importazione bloccata; scegliere un campo stabile univoco o correggere la sorgente. Non si usano automaticamente FID/numero di riga né UUID sostitutivi. Per cambiare chiave/sorgente occorre prima predisporre un'associazione esplicita; non è presente un algoritmo di fusione automatica. Importazioni parziali non eliminano oggetti esistenti o appartenenze condivise.

Prima dell’anteprima un account server aggiorna il catalogo completo del progetto; se manca la rete, l’anteprima viene fermata con un messaggio, evitando associazioni basate su una cache incompleta. Il ruolo viene ricontrollato dopo l’aggiornamento. I codici dei collettori vengono confrontati senza distinzione maiuscole/minuscole; un collettore archiviato richiede ripristino esplicito.

Rapporto distinto per collettori, pozzetti/manufatti e tronchi: nuovi, aggiornati, invariati, assenti, avvisi ed errori bloccanti. Gli assenti vengono confrontati solo per la stessa sorgente e i layer presenti nel file; un layer omesso può essere un aggiornamento parziale. Gli elementi assenti non vengono eliminati né archiviati automaticamente. I collettori sono raggruppamenti per codice: l’assenza di un layer non determina la loro archiviazione. La mappa di anteprima conserva i colori dei collettori.

Originale ZIP nello spazio privato, hash dei componenti normalizzati, mapping e rapporto in Room e provenienza remota. Fino a 4 MiB invio unico; oltre, staging privato con pubblicazione finale atomica (40 MiB strutturati complessivi, singola geometria ≤4 MiB). Interruzioni prima della conferma non pubblicano nulla. Dopo conferma il catalogo locale è visibile come pendente e viene avviata la sincronizzazione automatica; solo la ricevuta remota attesta l’invio. Ispezioni dipendenti attendono il completamento dell'anagrafica. **Impostazioni → Server e sincronizzazione** permette di riprovare e segnala anche operazioni cartografiche bloccate, senza dichiararle completate.

Gli shapefile di test nel repository contengono esclusivamente coordinate sintetiche. Non inserire dati infrastrutturali reali nel repository pubblico o in OSM.

## Codifica e identificativi v0.15

«Codifica dei testi» parte da «Automatica (consigliata)». La priorità è .cpg riconosciuto, language driver DBF Windows ANSI (0x03) o legacy Latin-1 (0x57), verifica UTF-8 rigorosa su tutti i campi, proposta Windows-1252 per dati legacy. Una proposta non è attestata come rilevamento certo; ASCII non richiede distinzioni prive di effetto. Nessun decoder sostituisce byte illeggibili. In Opzioni avanzate sono disponibili Unicode/UTF-8, Windows-1252 e Latin-1; il cambio rilegge lo ZIP e aggiorna gli attributi senza scritture server. CRS e codifica rimangono controlli separati.

Ogni layer mostra attributi reali, campo identificativo, vuoti, duplicati ed esempi dei record problematici. La proposta valuta l’univocità sull’intero layer e non sceglie FID automaticamente. I valori restano stringhe (anche «0001», «abc» e «0»); i duplicati non vengono consentiti o eliminati. Le scelte confermate si conservano per account/sorgente/layer, anche nella provenienza dell’importazione. Cambiare la sorgente o la chiave di record già importati richiede una migrazione esplicita delle identità, non un’associazione per somiglianza.

L’eccezione «Chiave sorgente duplicata in bleggio-pozzetto: 0» proveniva dal controllo in ImportPlanner.identity. Il parser non convertiva chiavi non numeriche/vuote in zero; la proposta precedente sceglieva ID/FID solo dal nome. Il dataset Bleggio reale non è disponibile: caso riprodotto con fixture, nessun collaudo diretto di quel file.

Per LDID/87 la lettura Latin-1 segue la [documentazione GDAL](https://gdal.org/en/stable/drivers/vector/shapefile.html#encoding), che ne segnala l’ambiguità storica: l’interfaccia richiede di controllare l’anteprima e consente la correzione per layer.
