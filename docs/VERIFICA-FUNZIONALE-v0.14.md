# COLL-PAT v0.14 — verifica funzionale e correzioni mirate

Verifica del 22 settembre 2026 sui sorgenti correnti. Le modifiche sono locali e non sono ancora nell’APK installato o pubblicato. Nessuna build Android, installazione, modifica al progetto Supabase remoto o pubblicazione eseguita durante questa attività.

## Dati sintetici

I dati non erano stati rimossi: sono definiti in `demo/trento-lavis-gilli-v0.14.json`, trasformati dal seed esistente in `android/app/src/demo/assets/demo-package.json` e caricati in Room da `Repository.prepareWorkspace()`. Sono previsti:

| Codice | Collettore | Dotazione |
| --- | --- | --- |
| COLL_DEMO_01 | Coll_Trento | Dati sintetici preesistenti |
| COLL_DEMO_02 | Coll_Lavis | Dati sintetici preesistenti |
| COLL_DEMO_03 | Demo Via Gilli · Trento | 6 manufatti e 5 tronchi presso Via Gilli 3 |

Complessivamente: **3 collettori, 22 manufatti e 19 tronchi**.

La causa individuata nel codice è la separazione dell’archivio `demo://local` dagli account Supabase: dopo il login le query leggono il catalogo del proprio account/progetto, non quello demo. Il seed è locale, non viene caricato automaticamente sul server e non dipende da una ricevuta di sincronizzazione. I filtri per archiviazione e visibilità restano intenzionali; non è stata trovata una perdita del terzo collettore nel dataset.

È ora disponibile **Impostazioni → Account → Apri demo offline**, oltre all’ingresso dal login. L’interfaccia identifica l’archivio demo e spiega come raggiungerlo quando l’account server non ha collettori. Mappa, Collettori, Pozzetti e filtri continuano a usare lo stesso catalogo locale. Per tornare al progetto server occorre accedere nuovamente; le copie locali sono conservate.

Il seed esistente ora ripristina solo gli UUID mancanti anche se il marcatore di inizializzazione esiste già. Non duplica gli oggetti né sovrascrive modifiche, archiviazioni o preferenze di visibilità. Le ispezioni di esempio rimangono inizializzate una sola volta. Nessuna seconda procedura di seed e nessuna mescolanza con dati operativi.

## Esportazione trimestre

Il collegamento al pulsante e il selettore Android esistevano. Prima del selettore, però, il flusso attendeva il server; l’indicatore era in cima a una pagina scorrevole, l’annullamento non aveva feedback e un errore di API poteva essere trattato come semplice indisponibilità di rete. Non era un pulsante privo di listener.

Correzioni:

- Voce **Esporta ispezioni trimestre**, descrizione e nome `COLL-PAT_ispezioni_2026_T3.csv`.
- Trimestre civile T1–T4 in `Europe/Rome`, non tre mesi mobili. Esportabili gli esiti conclusi `COMPLETO` e `IMPEDITO`; esclusi bozze, annullati, dati precedenti al reset e date future.
- Messaggio e avanzamento vicino al pulsante fin dal clic, prevenzione dei clic ripetuti.
- Nessun record: messaggio dedicato, senza aprire il selettore e senza generare un CSV vuoto.
- Con record: CSV temporaneo privato, `CreateDocument("text/csv")`, scrittura della destinazione scelta, messaggi per successo, annullamento ed errore. Il percorso temporaneo sopravvive alla ricreazione dell’attività; viene eliminato al termine.
- Attesa di rete limitata a 10 secondi, poi uso dichiarato della cache locale. Gli errori di autorizzazione/versione sono espliciti, senza eccezioni tecniche a schermo.

È stata riutilizzata la RPC paginata `coll_pat_semester_history` già presente: scarica il semestre contenente il trimestre, ma nel CSV entrano soltanto le righe del trimestre. Non servono migrazioni o nuovi endpoint. La periodicità delle visite dei collettori rimane semestrale: è distinta dal periodo di esportazione.

## Mappa e aspetto

I puntini della legenda erano caratteri tipografici, influenzati dalla baseline. Ora sono `Box` circolari in righe con `Alignment.CenterVertically`, senza offset verticali manuali.

**Simbologia pozzetti** resta un menu compatto e offre sette forme: cerchio pieno, quadrato, rombo, esagono, anello, chiusino, anello con punto. I simboli monocromatici locali vengono colorati da MapLibre in base allo stato dell’ispezione. Restano indipendenti il colore del collettore, il segno sotto asfalto, la dimensione e lo zoom minimo; i tronchi non vengono nascosti insieme ai pozzetti. Anche gli anelli di selezione seguono la dimensione scelta.

## Importazione shapefile

**A. Funzione già presente.** È documentata anche nei commit locali `7daabf4` (v0.13) ed `e0ab481` (v0.14). Non era stata rimossa: si trovava nella dashboard amministrativa annidata nelle Impostazioni, con uno sblocco locale poco chiaro nella variante demo.

Componenti trovati: `ImportDialog.kt`, `Shapefile.kt`, `ImportPlan.kt`, `Repository.saveCatalog()`, outbox/worker esistenti, RPC `coll_pat_apply` e migrazioni Supabase v0.13/v0.14. Il percorso Android non usa il vecchio importatore desktop/Python né richiede una nuova Edge Function.

Accesso aggiornato: **Impostazioni → Dati cartografici → Importa shapefile**. La UI, il dialogo e il repository applicano il ruolo amministrativo già esistente; sul server rimangono le verifiche delle RPC. Un account `inspector` non acquisisce permessi grazie alla variante demo. La demo locale consente prove separate dal server. Non sono state aggiunte credenziali amministrative nel client.

Il parser nativo preesistente è stato conservato. Legge uno ZIP con `.shp`, `.shx`, `.dbf`; `.prj` e `.cpg` sono richiesti salvo indicazione esplicita verificata dell’amministratore. Supporta EPSG 4326, 3857, 32632/33 e 25832/33; CRS mancanti o non riconosciuti bloccano l’importazione, senza presumere WGS84. Restano i limiti e controlli su dimensioni, struttura del file, coordinate e geometrie. Polilinee multipart richiedono la separazione in rami con chiavi distinte.

Le chiavi degli attributi hanno priorità per collegare gli estremi; la vicinanza si usa solo con tolleranza esplicita e candidato univoco. Rimangono le modalità punti/linee reali, punti ordinati con tratti schematici e punti isolati senza collegamenti inventati.

Correzioni dell’anteprima:

- Aggiornamento del catalogo completo dal server prima del confronto, con ricontrollo del ruolo. Senza rete l’anteprima remota si ferma con un messaggio; la demo non richiede il server.
- Conteggi distinti per collettori, pozzetti/manufatti e tronchi: nuovi, aggiornati, invariati e assenti dal file.
- Riutilizzo degli UUID per sorgente/layer/chiave; confronto dei codici collettore senza distinzione di maiuscole/minuscole; collettori archiviati bloccati fino al ripristino esplicito.
- Assenti limitati alla stessa sorgente e ai layer effettivamente forniti, per rispettare gli aggiornamenti parziali. Sono segnalati e conservati: nessun hard delete né archiviazione automatica. Per i collettori, che sono raggruppamenti per codice, non viene dedotta l’archiviazione dall’assenza di un layer.
- Mappa di anteprima con i colori dei collettori; messaggi di errore comprensibili e conferma obbligatoria.

Dopo la conferma resta la pipeline esistente: transazione Room → outbox → sincronizzazione automatica → ricevuta server → aggiornamento cache. Gli invii grandi conservano i lotti privati e la pubblicazione finale atomica. Ispezioni, anomalie, fotografie e GPS non vengono cancellati. Il messaggio distingue il salvataggio locale dalla ricezione remota; il comando Sincronizza non dichiara completate operazioni cartografiche ancora bloccate.

## Verifiche e limiti

- **52 test JVM mirati superati**: 18 v0.13, 18 v0.14, 3 di sicurezza mappa, 13 nuovi casi funzionali. Coprono confini temporali, filtri CSV, seed idempotente, ruoli, sette simboli, colori/zoom/dimensioni, reimportazione, assenti, codici collettore, priorità degli identificativi, ZIP incompleti e CRS.
- Controllo sintattico Kotlin: **16 file, zero errori**. `git diff --check` senza errori.
- Test eseguiti fuori da Gradle, con sole classi di logica selezionate e classi v0.14 già disponibili come dipendenze. Output nel percorso locale ignorato `.tools/v014-functional-tests`; nessun task Android invocato.
- Lettura del codice SQL di autorizzazione, importazione, ricevute e catalogo. Nessuna scrittura o importazione di prova sul Supabase remoto.
- Non è stata eseguita una nuova app: l’aspetto effettivo dei simboli, i clic sul dispositivo, il selettore Android e una nuova sincronizzazione completa andranno collaudati dopo una futura build autorizzata. La verifica sintattica non sostituisce la compilazione completa dell’interfaccia Compose.

## File modificati

Percorsi Kotlin relativi ad `android/app/src/main/java/it/pat/collettori/`:

| File | Modifica |
| --- | --- |
| `AdminPanel.kt` | Accesso alla demo da Account; gestione collettori separata dall’import e permessi coerenti. |
| `DemoMode.kt` | Individuazione degli elementi mancanti nel seed, rispettando l’archivio proprietario. |
| `Domain.kt` | Regola comune per autorizzare la gestione cartografica. |
| `Repository.kt` | Passaggio esplicito alla demo, ripristino idempotente e controllo dei permessi di scrittura. |
| `Workspace.kt` | Identificazione della demo e indicazione del percorso ai dati di prova. |
| `InspectionCsv.kt` | Trimestre civile, filtro e nome file T1–T4. |
| `InspectionExport.kt` — nuovo | Flusso CSV/SAF con avanzamento, assenza dati, timeout e feedback. |
| `SettingsPanel.kt` | Nuove voci, legenda centrata, sezione Dati cartografici e feedback sincronizzazione. |
| `InspectionStatus.kt` | Persistenza e lettura delle sette scelte di simbolo. |
| `ManholeSymbol.kt` — nuovo | Sette forme monocromatiche indipendenti dai colori. |
| `MapStyle.kt` — nuovo | Generazione dello stile estratta per verificarla senza avviare Android. |
| `OfflineMap.kt` | Registrazione dei simboli locali e aggiornamento delle proprietà senza ricreare la mappa. |
| `ImportDialog.kt` | Percorso amministrativo, aggiornamento catalogo, riepilogo e conferma più chiari. |
| `ImportPlan.kt` | Conteggi per tipo, assenti conservati, confronto codici e colori nell’anteprima. |

Altri file:

| File | Modifica |
| --- | --- |
| `android/app/src/test/java/it/pat/collettori/V014Test.kt` | Nome CSV atteso aggiornato al trimestre. |
| `android/app/src/test/java/it/pat/collettori/V014FunctionalTest.kt` — nuovo | 13 test mirati di regressione. |
| `README.md` | Percorsi UI aggiornati e distinzione tra sorgenti e APK pubblicato. |
| `docs/GIS.md` | Accesso, ruoli, anteprima, elementi assenti e sincronizzazione. |
| `docs/OFFLINE.md` | Ingresso demo, trimestre, compatibilità RPC e uso dichiarato della cache. |
| `docs/VERIFICA-FUNZIONALE-v0.14.md` — nuovo | Questa relazione. |

Ausili solo locali, esclusi da Git: `.tools/run-v014-focused.ps1` e `.tools/V014SyntaxCheck.java` eseguono i controlli descritti senza una build Android.

## Non modificato

- Versione base **v0.14**, `versionCode = 14`, suffisso demo preesistente invariato.
- **Nessuna nuova compilazione dell’app**: soltanto i controlli statici e test mirati consentiti.
- **Nessun APK generato o modificato**. L’APK precedente conserva dimensione 62.955.496 byte, data UTC 2026-09-22 14:36:46 e SHA-256 `bdfc7a19340fd3b50f702d8c31c5ff0855677d2ff6a72454b0ebb7bf36ebfac0`.
- **Nessuna release/pre-release creata o aggiornata**; nessun commit o push eseguito in questa attività.
- Nessuna modifica a migrazioni/database remoti, autenticazione, chiavi, recupero password, gestione fotografie o algoritmo delle frequenze d’ispezione.
