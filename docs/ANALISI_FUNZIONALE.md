> Documento storico del pilota precedente. Per COLL-PAT v0.13 vedere [indice corrente](README.md).

# Analisi funzionale — v0.11

## Stato iniziale, rilevato prima delle modifiche

Android Kotlin/Compose: MainActivity conteneva navigazione, login, elenco/mappa, editor, dati offline e account. Cinque schede; avvio nell'elenco. Campi di ricerca, area e filtri occupavano anche la mappa. Scheda a fondo pagina e modulo lungo, osservazioni NON_VERIFICATO. Room v1 con visits, outbox, packages e settings; pacchetti e schede JSON. Repository con revisioni, ricevute server e WorkManager. Backend FastAPI/SQLAlchemy, PostGIS, Alembic e script Supabase; portale amministrativo distinto.

LocationCapture acquisiva una posizione reale a richiesta, con precisione, età, permesso e mock. GpsRule confrontava il pozzetto scelto con regola versionata. Selezione MAP/LIST già presente. QR/NFC/codice tag solo enum e interfacce future; nessuna fotocamera, scanner o upload. MapLibre Native 13.6.1 con GeoJSON e glifi locali; base sintetica, non OSM. Demo autonoma: 16 pozzetti, 15 segmenti, nessun login/sync.

## Decisioni

| Funzione | Decisione | Motivazione |
|---|---|---|
| Mappa | Principale; OSM online, scheda modale, posizione e candidati | Orientarsi prima di compilare |
| Elenco/codice | Mantenuto come fallback, con filtri | GPS e tag possono mancare |
| Cinque tab demo | Quattro tab; informazioni in Altro | Più spazio e meno scelte |
| Area/ricerca/filtri permanenti sulla mappa demo | Rimossi dalla schermata mappa; ricerca e filtri in Pozzetti | Liberare cartografia |
| Ispezioni | Valori regolari, riepilogo e controlli espandibili | Operatore interviene sulle eccezioni |
| Apertura | Default effettuata, dichiarazione esplicita visibile prima di registrare | Richiesta di default regolare; non prova automatica dell'apertura |
| Pulizia | Default non eseguita | Non inventare un'attività distinta |
| Anomalie | Etichetta e colore, note obbligatorie, foto suggerita | Comprensibile anche senza percezione cromatica |
| Bozze/revisioni/storico | Mantenuti | Recupero da interruzioni e audit |
| Foto | Acquisizione, importazione, anteprima e rimozione locali | Documentare criticità |
| GPS | Candidati con precisione/età; conferma sempre esplicita | Evitare certezza falsa |
| Prossimo pozzetto | Avanzamento per progressiva nella scheda | Ridurre ricerca ripetuta |
| Controllati/mancanti | Conteggio e filtro locale | Evidenziare lavoro residuo nella demo |
| Backend/pilota/admin | Conservati, separati dalla nuova demo | Non perdere funzioni operative già esistenti |
| QR/NFC/RFID | Contratti mantenuti ed estesi, hardware rinviato | Nessuna lettura fittizia |
| Editing GIS e dashboard | Non introdotti | Nessuna utilità nel giro di ispezione |

Nessuna funzione persistente del pilota eliminata. Il dataset demo precedente rimane leggibile negli storici e il fixture GIS backend resta nei test. I dati di conto/ispezione pregressi non vengono azzerati.

## Ergonomia e limiti

Comandi principali almeno 48–56 dp, contrasto Material 3, testo oltre al colore, modulo scorrevole. Mappa quasi a pieno schermo quando non c'è una scheda aperta. Fotocamera esterna Android tramite intent, senza richiedere permessi generici sulla libreria foto. Stato navigazione conservato in rotazione e bozze su Room. Validazione con guanti, pioggia, luce solare e orientamento su dispositivo reale ancora necessaria.

## Funzioni future utili

Storage fotografico con coda e ricevuta, esportazione portabile comprensiva di immagini, NFC/HF on-metal con gestione sostituzioni, QR come fallback, base offline da fornitore autorizzato, test su dispositivi rugged. Sincronizzazione del pilota già presente; non abilitata artificialmente nella demo.
