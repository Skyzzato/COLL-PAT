# Analisi ergonomica v0.21

## Metodo e limiti

APK Android debug installato con aggiornamento su emulatore API 35, usando le schermate Compose compilate, Room reale e dati esclusivamente sintetici. Le prove UI sono automatizzate tramite Accessibility/UIAutomation; non sono una sessione con operatori sul campo. Le schermate a 320 dp/font 150% riusano il test compatto della v0.2 aggiornato alla nuova versione. Prove specifiche in V015EditorUiTest, V02UiTest e V021UiTest.

Gli screenshot nella cartella `ux-v0.21` sono solo fixture. Nessuna email, sessione, infrastruttura o fotografia operativa è stata esportata.

## Osservazioni e interventi

| Schermata / riproduzione | Conseguenza | Gravità | Intervento concreto | Costo | Stato / evidenza |
|---|---|---|---|---|---|
| Ispezione → Salva bozza → attendere oltre 2 s | Il vecchio timer chiudeva il messaggio prima della lettura | Media | Chiudi esplicito con un solo ritorno al contesto precedente | Basso | Corretto nella v0.21; test UI verifica attesa e assenza di doppia navigazione |
| Coordinate manuali → Verifica posizione | I nuovi controlli spingono l'anteprima sotto il bordo; il primo test APK non riusciva a leggerla | Media | Scorrimento fino all'anteprima, padding tastiera e conferma esplicita | Basso | Corretto nella v0.21; test V02UiTest |
| Modulo → Punta su mappa → Annulla / Conferma | Rischio di perdere codice e coordinate già inserite | Alta | Stato salvabile del modulo e coordinate provvisorie separate | Medio | Corretto nella v0.21; test annulla/conferma; ricreazione del modulo specifico non provata |
| GPS insufficiente → Non rilevare GPS | Una vecchia eccezione poteva sembrare una posizione valida | Alta | Motivazione distinta, OK/ANNULLA, zero coordinate; retry invalida la precedente motivazione | Medio | Corretto nella v0.21; UI e Room su tentativo simulato |
| Menu verifica con aiuto | Duplicare etichetta e controllo allunga la scheda | Bassa | Etichetta unica, pulsante informativo a destra con area 48 dp | Basso | Corretto nella v0.21; verifica codice, interfaccia compilata |
| Collettore → Personalizza aspetto | Controlli lunghi possono uscire dal dialogo su schermi compatti | Media | Contenuto scorrevole, anteprima e reset dell'eredità | Basso | Corretto nella v0.21; screenshot e percorso UI |
| Sync fallita → Visualizza coda | Errore generico offline nasconde validazione/migrazione; conteggio chunk fuorviante | Alta | Classificazione HTTP, un riepilogo, gruppi per importazione/ispezione, dettaglio foto e azioni mirate | Medio | Corretto nella v0.21; HTTP controllato, coda offline e test conteggio |
| Ispezione ricevuta ma foto non confermata | Falso successo completo | Alta | Foto pendenti nel risultato/coda, conferma upload distinta | Medio | Corretto nella v0.21; V021PhotoTest con risposta persa/ritentativo |
| Scheda lunga, caratteri grandi | Molti controlli richiedono scorrimento | Media | Valutare raggruppamenti richiudibili mantenendo la leggibilità e il riepilogo obblighi | Medio | Proposto per versione successiva; dedotto dalla quantità di controlli, nessun test operatore |
| Etichette cartografiche a zoom lontano | Due testi non entrano su pochi pixel senza sovrapporsi | Bassa | Conservare collisioni native e riapparizione agli zoom adeguati | Basso | Comportamento previsto v0.21; test delle ancore, controllo visivo multi-zoom non esaustivo |
| Coda molto grande con etichette lunghe | Navigazione e ricerca possono diventare lente | Media | Valutare filtro per tipo/causa mantenendo i gruppi | Medio | Proposto per versione successiva; deduzione, nessun benchmark UX su operatori |

## Percorsi

- Mappa iniziale → elenco pozzetti → dettaglio → nuova ispezione → Salva bozza → Chiudi → contesto precedente: percorso APK automatizzato. La selezione puntuale tramite tap su geometria è implementata; questo percorso usa l'elenco per una selezione deterministica.
- Collettori → Anagrafica → Personalizza aspetto → reset → Salva → riepilogo: percorso APK automatizzato.
- Inserimento manuale con virgola decimale → mappa → annulla → riapertura → conferma → anteprima → salvataggio: percorso APK automatizzato.
- Sync con errore HTTP controllato → riepilogo → coda consultabile → chiusura; il recupero con ricevuta è verificato separatamente dai test di sincronizzazione e foto.
- Importazione → cancellazione → reimportazione: coperta dal planner, Room e SQL con fixture; non è stato completato un percorso end-to-end nel picker documenti Android.

Non effettuate: due telefoni fisici, GPS sul campo, upload Storage remoto, eliminazione su progetto remoto, ricreazione/rotazione del modulo punto durante tutte le fasi del picker, verifica esaustiva collisioni su ogni zoom, tastiere e display fisici diversi. La ricreazione dell'Activity principale è verificata dal test di avvio esistente.

## Screenshot verificati

- [Mappa con bandierina](ux-v0.21/map-picker.png), [anteprima manuale](ux-v0.21/manual-preview.png), [personalizzazione](ux-v0.21/appearance.png).
- [Salvataggio in attesa](ux-v0.21/save-summary.png), [errore HTTP controllato](ux-v0.21/sync-failure.png), [coda](ux-v0.21/queue-offline.png).
- [Schermo compatto/font 150%](ux-v0.21/multiselect-320-font150.png), [avvio dopo installazione pulita](ux-v0.21/clean-install.png).

Nella vista compatta il codice del collettore va a capo ma resta leggibile; valutare una migliore distribuzione dello spazio fra codice e pulsanti nella versione successiva (gravità bassa, costo basso). Il controllo visivo del selettore ha portato a sostituire il solo cerchio iniziale con una bandierina nativa, corretta nella v0.21.
