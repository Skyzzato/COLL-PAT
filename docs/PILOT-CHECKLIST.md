# Checklist del pilota sul dispositivo e sul territorio

Compilare per ogni prova: data, operatore di collaudo, modello telefono/Android, build, server, UUID dataset/regola, risultato, evidenza e difetto. Non spuntare prove non eseguite.

## Preparazione

- [ ] Referenti contrattuali, sicurezza, privacy e informatici hanno approvato il pilota reale.
- [ ] Periodicità/scadenze derivate dai documenti, o chiaramente non configurate.
- [ ] Dataset reale validato e separato dalla demo; identità dei pozzetti verificata.
- [ ] Base cartografica autorizzata, attribuzione corretta, copertura e glifi completi.
- [ ] Telefoni, accessori, Play services, permessi e procedure sicure verificati.
- [ ] Canale urgente noto agli operatori e distinto dalla sincronizzazione.

## Offline e persistenza

- [ ] Primo accesso e download online con verifica hash/dimensione.
- [ ] Modalità aereo: riavvio, apertura dell'app e mappa completa, anche in zone non visitate a video.
- [ ] Ricerca per codice con zeri iniziali e numero ripetuto in collettori diversi.
- [ ] Creazione bozza prima del GPS; chiusura forzata e ripresa.
- [ ] Salvataggio scheda offline e riavvio: contenuto integro.
- [ ] Memoria insufficiente: nessun falso messaggio di successo, dati precedenti integri.
- [ ] Aggiornamento app e pacchetto con visite pendenti: nessuna perdita.
- [ ] Base mancante: avviso e schede ancora utilizzabili.
- [ ] Cambio account: nessun dato personale dell'altro account visibile/inviato.
- [ ] Logout/relogin dello stesso account: recupero delle bozze e della coda.
- [ ] Scadenza access token e abilitazione offline; rinnovo online, dati preservati.
- [ ] Dispositivo revocato offline: limiti dichiarati correttamente, recupero amministrativo controllato.

## GPS e operatività

- [ ] Posizione recente, precisa e cartografia nota: risultato atteso.
- [ ] Misura vecchia, GPS debole, assente, disattivato e timeout: stati/motivi corretti.
- [ ] Permesso negato o approssimativo: salvataggio motivato possibile.
- [ ] g sconosciuta: distanza visibile, esito incerto.
- [ ] Due pozzetti vicini: ambiguità visibile, nessuna selezione automatica del più vicino.
- [ ] Simulazione dichiarata dal sistema: esito incerto; assenza del flag non presentata come autenticità.
- [ ] Rileva nuovamente: nuovo UUID evento, nessuna cancellazione del precedente.
- [ ] Completamento e invio altrove: coordinate e orario originari invariati.
- [ ] Accesso impedito e controllo non sicuro: non conteggiati come completi.
- [ ] GPS compatibile senza apertura: l'app non dichiara apertura verificata.
- [ ] Scheda completa con GPS incerto: ammessa con motivazione.
- [ ] Pulizia separata dall'ispezione; anomalia non interpretata come cattiva prestazione.
- [ ] Uso con guanti/luminosità reale; nessuna necessità di usare il telefono in operazioni pericolose.

## Invio e server

- [ ] Connessione intermittente, richiesta interrotta, risposta persa dopo commit: una sola visita.
- [ ] Due operatori sullo stesso pozzetto: visite distinte, copertura non gonfiata.
- [ ] Revisione concorrente: conflitto visibile, nessuna sovrascrittura.
- [ ] Accesso a un altro ambito o con ruolo insufficiente negato dal server.
- [ ] Posizione cliente favorevole ma dati ufficiali incerti: server ricalcola.
- [ ] Nuova regola GPS: nessuna modifica silenziosa degli esiti precedenti.
- [ ] Revisione motivata: copia precedente e autore visibili.
- [ ] Anomalia chiusa/riaperta con nota e audit.

## Rapporto e continuità

- [ ] Scadenze manuali: nessuna periodicità trimestrale implicita.
- [ ] Visita tardiva: il ritardo resta visibile.
- [ ] Impedito/parziale non soddisfano automaticamente l'obbligo.
- [ ] XLSX/CSV: zeri, Unicode, note che iniziano con simboli formula.
- [ ] Confini trimestri Europe/Rome, anche cambio ora legale.
- [ ] Invio dopo chiusura trimestre: nuova emissione, precedente invariata.
- [ ] Rapporto operativo esclude la demo.
- [ ] Esportazione completa e recupero dopo cambio gestore/fornitore.
- [ ] Backup PostgreSQL e fonti ripristinati in ambiente isolato.

## Prestazioni

- [ ] Pacchetto con circa 12.500 punti sintetici: misurati download, installazione, primo disegno, zoom, selezione, ricerca, memoria e consumo.
- [ ] Dimensioni delle basi alternative misurate, senza stime presentate come fatti.
- [ ] Raccolte anomalie ergonomiche e decisa la taratura dei parametri con referenti competenti.

QR, NFC, targhette e fotografie non sono oggetto di accettazione della v0.1.
