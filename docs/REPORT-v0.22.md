# Resoconto COLL-PAT v0.22

Base verificata: main `1acd6631dd185c2b211b19f4490fd26ad20bd301`, v0.21/build 21; working tree iniziale pulito. Secondo fetch senza aggiornamenti concorrenti. L'app Android usa Compose → Repository → Room/WorkManager → Supabase Auth/RPC/Storage. Il backend FastAPI storico non è il percorso Android.

## Funzionalità

1. Aspetto risolto per singola proprietà: null significa ereditarietà; ripristino rimuove colore/spessore/forma, senza dedurre override intenzionali da vecchi default. Per la forma: pozzetto → collettore contestuale → generale. Colori pozzetti riservati allo stato. Sorgenti e layer si aggiornano mantenendo camera; preferenze scritte in sequenza fuori dal lifecycle della pagina.
2. “Collega a un pozzetto vicino”: candidati del collettore disponibili ordinati per distanza, ricerca e primi 12 risultati, selezione multipla. Nessuna selezione automatica. Un punto isolato resta consentito. Il repository valida gli estremi e salva punto/segmenti/outbox nella stessa transazione. Identità stabile e controllo delle coppie evitano duplicati sui retry, anche quando esiste una geometria importata. Il verso resta UNSPECIFIED. Spostare un estremo ricalcola solo i segmenti schematici.
3. Posizionamento e anteprima ricevono tronchi e pozzetti locali esistenti, con una sorgente separata per la bandierina. Inquadramento iniziale e comando dedicato, contesto con codice/nome, nessuna modifica prima della conferma.
4. Activity portrait, target SDK 36 conservato. I limiti Android per grandi schermi sono distinti nel collaudo.
5. Card/elenco con codice, appartenenza, stato e ultima ispezione ricavati dallo stesso indice. Dettagli inizialmente chiusi per ogni pozzetto, dati tecnici conservati; nessun invito a confermare il codice nella card. La verifica nel flusso GPS rimane.
6. Comandi: Nuova ispezione, eventuale bozza, storico, centra; separatore, ripristino ordinario, forma e cancellazione distruttiva finale. Conferma dell'ambito effettivo invariata.
7. Elenchi pozzetti dell'importazione e del modulo collettore richiudibili e lazy, altezza limitata. Conteggi, avvisi, errori e conferme esterni alla lista.
8. Un solo “Rimuovi filtri” basato su date/collettore effettivi, comprese estremità singole. Storico del manufatto con codice e ritorno; accesso generale Ispezioni elimina il contesto pozzetto.
9. Slider discreti: distanza 5/10/15/20/25/30 m; accuratezza 20/40/60/80/100/120/140/150 m. Stesse soglie e default, descrizione accessibile, salvataggio locale.
10. Tre CSV distinti: ultimo trimestre civile concluso, anno scelto e tutto lo storico. Europe/Rome, [inizio,fine), data esecuzione, pagine complete indipendenti dalla UI, deduplica ID/revisione, esclusione bozze/annullate, impedimenti con esito proprio. Colonne precedenti conservate e colonne aggiunte per sync/revisione/sintetico/ambito. L'offline richiede la scelta esplicita SOLO DATI LOCALI. Annullamento, timeout, file non scrivibile e memoria insufficiente hanno esiti distinti.
11–13. Ruoli e simulazione come sotto; gestione utenti online con ricerca/pagine da 50, registro COLL-PAT senza abilitazione automatica, conferma vecchio/nuovo ruolo, confronto concorrenziale, audit e protezione dell'ultimo admin sotto lock progetto. Nessun accesso client diretto ad auth.users.
14–15. CancellationException propagata nei catch generici; salvataggi durevoli nel repository. Rimosso il testo sulla coda sotto Aggiorna database, conservati timestamp ed errori; coda dedicata invariata.
16. Dataset Barbaniga esplicitamente caricabile e rimovibile, mai seed d'avvio.

## Matrice dei ruoli

| Funzione | Visualizzatore (`viewer`) | Operatore (`inspector`) | Amministratore (`admin`) |
|---|---|---|---|
| Catalogo, mappa, storico, CSV autorizzato | Sì | Sì | Sì |
| Preferenze personali | Sì | Sì | Sì |
| Bozze, ispezioni, impedimenti, foto | No | Sì | Sì |
| Ripristino condizione ordinaria | No | Sì | Sì |
| Annullamento/eliminazione ispezioni | No | Regole operative e autore preesistenti | Sì |
| Import/catalogo/aspetto degli elementi | No | No | Sì |
| Eliminare pozzetti/collettori | No | No | Sì |
| Gestione utenti applicativi | No | No | Sì, online |
| Simulare viewer/operatore | No | No | Sì, nessuna nuova scrittura operativa |

La simulazione non modifica JWT o membership e non blocca i lavori legittimi precedentemente accodati. Si chiude a logout, nuova sessione o perdita del ruolo admin. Controlli applicati in UI, repository, RPC e Storage; il server consulta sempre il ruolo corrente. Offline il dispositivo può conoscere solo l'ultimo ruolo verificato: le scritture server sono comunque rifiutate dopo revoca e le code restano conservate.

## Barbaniga

`shared/demo-barbaniga-v022.json`, generato da `scripts/build_barbaniga_demo.py`: codice DEMO-BAR-5KM, 126 pozzetti, 125 tronchi, 5.000 m complessivi misurati con haversine WGS84/sfera media del progetto, progressive cumulative e UUID v5 stabili. Percorso inventato; non rappresenta una rete fognaria reale. Distanze medie 40 m, intervalli da 27,80 a 57,83 m lungo la curva.

La [Provincia autonoma di Trento](https://www.cultura.trentino.it/Patrimonio-on-line/Dizionario-toponomastico-trentino/AreaVisitatore/DetailsPage.aspx?param=2733548) identifica Barbaniga come frazione di Civezzano. Punto di passaggio 46.1016388889 N, 11.18585 E, da [Wikidata Q18478677](https://www.wikidata.org/wiki/Q18478677), che riporta anche GeoNames 8958102. Coordinate del centro abitato, non rilievo della rete.

Da Impostazioni → Dati cartografici → Dataset di collaudo, un admin sceglie Carica e conferma il progetto corrente. Ripetere conserva gli elementi già presenti; conflitti con identità estranee interrompono il caricamento. La rimozione usa la normale anteprima di cancellazione con un vincolo aggiuntivo agli UUID sintetici: se coinvolge nuovi elementi esterni al dataset è bloccata. Dopo una cancellazione definitiva i tombstone impediscono una ripubblicazione automatica degli stessi UUID: per ripetere il collaudo usare un progetto isolato nuovo, senza eliminare i tombstone operativi.

Il dataset non è stato caricato sul server operativo. È stato caricato esplicitamente nel database isolato dell'emulatore. I metadati `synthetic`, `synthetic_dataset` e `source` lo distinguono, e il CSV marca le ispezioni associate come sintetiche.

[Collaudo e limiti](VALIDATION-v0.22.md) · [Migrazione e deployment](MIGRATIONS-v0.22.md).
