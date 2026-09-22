# COLL-PAT v0.14 — riscontro dei requisiti

Base verificata: commit v0.13 `7daabf4`, già presente su `origin/main`, archivio Room 2, protocollo Supabase RPC 2. Nessuna riscrittura dell’app. Restano importazione GIS, topologia, impedimenti, cartografia offline, fotografie, audit e gestione amministrativa.

| Area richiesta | Implementazione | Verifica |
|---|---|---|
| Zoom, dimensione, simboli, sotto asfalto | `FieldSettings`, livelli MapLibre con `minZoom` nativo; cerchio/anello e segno indipendente dal colore; impostazioni persistenti per account | Test stile e persistenza; controllo visivo emulatore |
| Periodicità e quattro colori | `InspectionStatus.kt`, indice unico dell’ultima ispezione valida, periodo 182,62125 giorni / visite del semestre corrente | Casi A–H, assenza dati, annullati, bozze, futuro, reset |
| Filtri e schede | Tutti/da ispezionare/già ispezionati/anomalie; collettore e codice; ordinamento ultime eseguite; card navigabile; bandierina; centra su mappa; nuova ispezione verde | Test logica e controllo UI |
| Colori e archiviazione | `display_color`; metadati `archived_at/by` dentro il catalogo JSONB esistente; archiviazione irreversibile dal normale aggiornamento | SQL: record e storico conservati, assente dalle query attive, nessuna resurrezione da invio vecchio |
| Impostazioni | Sezioni mappa, GPS, ispezioni, server, account, informazioni e gestione dati; rimossa pubblicazione seed | Controllo UI |
| Auth | Login e registrazione email/password, conferma password; Keystore e refresh; logout; recupero disattivato con spiegazione | Contratto HTTP strumentale e sessione persistente; Auth remoto richiede configurazione |
| Bozze condivise | `BOZZA` / `COMPLETO` / `IMPEDITO`, revisione e autori verificati server, outbox immutabile durante l’invio, conflitti senza sovrascrittura | SQL con due utenti e concorrenza; test Android invio finale durante upload bozza |
| Fotografie | UUID e percorso stabile, bucket privato, upload JPEG, metadati condivisi, download autenticato e cache | Test SQL RLS/associazione; foto reale locale su emulatore; Storage remoto da collaudare |
| Versione minima | Configurazione pubblica in sola lettura, confronto numerico, policy memorizzata; letture/scritture RPC di versioni obsolete rifiutate | Test JVM e SQL |
| GPS | Accuratezza e distanza indipendenti; blocco controllo completo, soglie storiche, qualità in rosso; tentativi falliti ancora utilizzabili per impedimenti | Soglie inclusive 5/10/10,1 m e 8/15/16 m; test dispositivo |
| CSV | Semestre Europe/Rome, concluse/impedite non annullate, UTF-8 BOM, `;`, campi quotati, foto SI/NO; SAF | Test escaping e semestre; salvataggio su emulatore |
| Demo | Terzo collettore Via Gilli, 6 nuovi pozzetti e 5 tronchi; 4 ispezioni sintetiche per gli stati | Coordinate testate; 22 punti e 19 tronchi totali |
| Compatibilità | Room 1→2→3, chiavi visita per account, seed aggiuntivo senza sostituire dati o UUID preesistenti | Test migrazione e confronto delle schede precedenti |

## Scelte di compatibilità

`visits_h1` e `visits_h2` rappresentano già il numero di visite per semestre: non è stato aggiunto un terzo campo duplicato. Per un pozzetto condiviso prevale la frequenza più alta dei collettori attivi. Lo zero preesistente è conservato come periodicità non prevista e non qualifica automaticamente un controllo come aggiornato. La periodicità usa un semestre medio gregoriano, non una scadenza fissa a gennaio/luglio.

Le entità collector/point/segment rimangono in `coll_pat.catalog`; non vengono duplicate in nuove tabelle. Stato operativo e storico restano in `coll_pat.inspections`. I colori dei pozzetti sono derivati e non salvati come stato.

Le vecchie operazioni di invio v0.13 ancora pendenti sono conservate e sospese nella migrazione locale: non si inventano soglie GPS né attribuzioni mancanti. Sono incluse nell’archivio di recupero. I dati già registrati rimangono consultabili. Le bozze compatibili possono essere proseguite e acquisire una nuova misura valida.

Una versione vecchia già installata e completamente offline non può ricevere retroattivamente un blocco server. Dopo la migrazione il server rifiuta le sue RPC; il client v0.14 applica anche la policy memorizzata offline.
