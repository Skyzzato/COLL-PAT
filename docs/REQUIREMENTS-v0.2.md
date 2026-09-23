# COLL-PAT v0.2 — comportamento funzionale

Base: v0.16, commit `9ec05af`. Nome COLL-PAT, identità Android `it.pat.collettori.pilot`, Room 3 e protocollo RPC 2 conservati. VersionName 0.2, versionCode 17. Non si introduce un nuovo archivio o ambiente demo.

## Importazione

I layer sono riconosciuti per percorso e nome comune di SHP/SHX/DBF, anche in sottocartelle. Componenti mancanti, CRS non dichiarato e dati illeggibili hanno errori espliciti. La codifica dei caratteri è separata dalle colonne usate per riconoscere gli oggetti. Il menu del CRS presenta sistemi e unità; non interpreta coordinate proiettate come WGS84.

Le colonne proposte mostrano nomi ed esempi. Codice originale, ambito (es. collettore) e colonna aggiuntiva di distinzione possono comporre una chiave. Gli UUID interni non sono campi da compilare. Nessuna conversione dei codici testuali elimina zeri iniziali; codici identici fra collettori non causano fusioni.

Quando nessuna combinazione affidabile è disponibile, l'operatore sceglie l'assegnazione guidata: ogni record distinto riceve una corrispondenza persistente nella provenienza del catalogo. Un'impronta esatta di attributi e geometria consente di riconoscere lo stesso record anche quando cambia l'ordine delle righe. L'impronta non è una prova di identità per record modificati: quelli nuovi o modificati richiedono una scelta esplicita fra elemento esistente e nuovo elemento. Record perfettamente indistinguibili restano bloccati. Non si riconcilia per vicinanza, FID, numero di riga o codice ripetuto.

L'anteprima comprende conteggi per tipo, nuovi/aggiornati/invariati/assenti, avvisi, mappa e nessuna esclusione silenziosa. Gli assenti restano conservati. I dati originali sono privati sul dispositivo e nella provenienza autorizzata sul server. Gli import non vengono eseguiti automaticamente dall'apertura dello ZIP.

Punti senza linee e senza ordine vengono importati come anagrafica. Gli eventuali collegamenti da ordine affidabile restano schematici e dichiarati come stime. L'anagrafica aggiornata non sostituisce le geometrie ufficiali preesistenti.

## Collettori e pozzetti

Gestione collettori include la sezione Pozzetti, aggiunta e modifica. Codice, latitudine e longitudine sono distinti; coordinate firmate WGS84 accettano punto o virgola, senza scambio automatico degli assi. Gli esempi sono placeholder. La conferma del pozzetto richiede l'anteprima mappa; il pulsante finale salva collettore e pozzetti nella stessa transazione locale e nella stessa operazione server, preservando la dipendenza.

L'associazione usa UUID, quindi modificare il codice del collettore non scollega i pozzetti. Ordine, ramo e collegamenti in uscita possono essere espliciti. I collegamenti manuali non generano geometrie ufficiali. Le geometrie importate conservano i loro estremi e il proprio tracciato.

L'eliminazione richiede conferma dell'impatto. Un collettore mai inviato e senza storico viene eliminato dal catalogo locale insieme alle operazioni inutili. Per elementi server l'app mantiene una cancellazione persistente in coda: la scomparsa locale non viene descritta come ricevuta server. I marcatori tecnici non sono mostrati come collettori archiviati. Pozzetti condivisi restano negli altri collettori, mentre ispezioni e foto storiche sono conservate. Vecchi aggiornamenti e reimportazioni non riattivano UUID o identità sorgente cancellati.

## Informazioni e aggiornamenti

Entrambi gli accessi usano il pulsante **Aggiorna database collettori**. L'ultimo successo è persistito per server, account e progetto soltanto dopo download e acquisizione locale del catalogo e delle ispezioni. Un errore conserva la data precedente e le modifiche non inviate. La stessa azione non viene avviata due volte; la sincronizzazione automatica mantiene il riepilogo ispezioni.

La coda conta entità di catalogo distinte, ispezioni distinte, correzioni distinte e fotografie locali in attesa di ricevuta. Tentativi e segmenti tecnici dell'upload non moltiplicano il numero. Errori, conflitti e invii in corso restano nel conteggio. I dati esclusivamente locali non destinati al server sono esclusi. Le bozze condivise rimangono nel normale flusso della v0.16, come confermato per questa versione.

Account mostra il ruolo verificato dal server, tradotto in Amministratore/Ispettore; offline usa l'ultimo valore verificato. Non esiste selettore per assegnarsi privilegi. Le operazioni amministrative restano verificate anche dalle RPC.

Il successivo deriva da tronchi diretti, collegamenti espliciti o sequenze non ambigue nel medesimo ramo. Le diramazioni mostrano tutte le destinazioni. La lunghezza geometrica è distinta dalla stima in linea d'aria e dalla distanza dell'operatore. Fine ramo accertata, successivo sconosciuto e distanza mancante hanno messaggi distinti.

La frequenza nominale mostrata è `round(365,2425 / 2 / visite_del_semestre_corrente)` giorni. Questo espone la conversione già usata dal motore di stato, senza modificare visite contrattuali o scadenze. Il dato mancante/non positivo è indicato come non configurato. Le frequenze dei collettori condivisi sono mostrate separatamente.

Logo: area vettoriale estesa per comprendere interamente la T; icona e testo separati sullo splash. La selezione multipla usa una checkbox selezionata con descrizione accessibile. Restano GPS mediato 5 secondi, soglia accuracy bloccante, eccezione motivata con focus, impedimento server, ritorno dopo 2 secondi e CSV del trimestre civile.

[Collaudo e limiti](VALIDATION-v0.2.md).
