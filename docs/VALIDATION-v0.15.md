# COLL-PAT v0.15 — relazione di collaudo

Data: 22 settembre 2026. Il collaudo distingue codice, prove automatiche, emulatore e server remoto. La relazione storica v0.14 è conservata senza riscriverla.

## Modifiche incluse

Sono incluse le correzioni v0.14 già presenti e non committate: ingresso alla demo da Impostazioni → Account, tre collettori sintetici (Trento, Lavis, Via Gilli 3), 22 manufatti e 19 tronchi, ripristino idempotente degli elementi mancanti, CSV del trimestre civile corrente con feedback, sette simboli e indicatori riallineati, visibilità secondo zoom, importazione dalle impostazioni. Demo e account reale conservano archivi separati. Periodicità, anomalie dall'ultima ispezione valida, archiviazione, bozze condivise, fotografie e impedimenti restano nei flussi esistenti.

L'importazione usa il parser esistente. «Automatica (consigliata)» prova CPG riconosciuto, indicatore DBF utilizzabile e decodifica UTF-8 rigorosa; Windows-1252 è una proposta esplicita per dati legacy, non una certezza. ASCII non richiede una scelta inutile. LDID 0x57 propone Latin-1 con avvertenza di possibile ambiguità. Le opzioni avanzate e l'anteprima degli attributi sono separate per layer; cambiare codifica rilegge lo ZIP senza scritture server. La scelta confermata è conservata per account, sorgente e layer. Il CRS resta un controllo distinto ed esplicito.

L'eccezione «Chiave sorgente duplicata in bleggio-pozzetto: 0» proveniva dal controllo d'identità del piano d'importazione. Il parser mantiene stringhe, senza conversione di mancanti/non numerici in zero. La selezione precedente del campo poteva proporre un candidato in base al nome: ora la proposta verifica l'intero layer. Anteprima e menu mostrano campo, vuoti, duplicati ed esempi. Non vengono consentiti duplicati, saltati record o adottati automaticamente FID/numero di riga. Gli zeri iniziali sono conservati e il reimport usa l'identità della sorgente. **Il vero ZIP Bleggio non era disponibile:** verificati fixture con `0` ripetuto, vuoti, valori non numerici e zeri iniziali; non è dimostrata la causa specifica di quel dataset.

Il GPS esegue un controllo preliminare su permessi, servizio, freschezza e accuratezza. Accuratezza oltre soglia blocca il tentativo, anche durante la finestra, senza deroga; l'uguaglianza è ammessa. Una finestra di cinque secondi raccoglie aggiornamenti distinti e recenti: almeno tre campioni distribuiti su almeno due secondi, ultimo campione entro due secondi dalla fine. La media sferica usa tutte le misure valide; l'indicatore conservativo è la massima accuracy, con dispersione separata. Non viene migliorata artificialmente la precisione dividendo per il numero di campioni.

Una corrispondenza verificata registra un evento unico. Una misura accurata ma incompatibile/incerta richiede il dialogo, poi focus nel campo esistente «Eccezione GPS», motivazione non vuota e conferma esplicita. Proseguire nel dialogo non salva l'evento. Il tentativo conserva identità, orari, campioni, metodo, soglie e motivazione nella coda e sul server. La card distingue in rosso «Corrispondenza non verificata — eccezione motivata» dallo storico di misure imprecise. Annullamento, uscita e cambio delle condizioni invalidano il tentativo e chiudono gli aggiornamenti aggiuntivi.

Ispezione e «Salva bozza» mostrano una conferma dopo la transazione Room e la coda persistente. Il timer di due secondi parte dal salvataggio durevole; una ricevuta server della stessa revisione aggiorna il testo senza riavviarlo. Demo, attesa di sincronizzazione e conferma server hanno messaggi distinti; le foto in coda sono segnalate separatamente. Il modulo è un overlay sulla pagina precedente, che conserva selezione, filtri e scorrimento. Errori locali e conflitti mantengono aperto il modulo. Doppio tocco, ritorno manuale e distruzione della schermata non producono una seconda operazione/navigazione. Worker e upload hanno durata indipendente dal modulo.

## Prove automatiche

| Ambito | Esito finale |
| --- | --- |
| JVM Debug | 80 test passati, nessun errore o salto |
| JVM Demo | 80 test passati, nessun errore o salto |
| Python e PostgreSQL/PostGIS locale | 95 test passati; un avviso di deprecazione Starlette/AnyIO |
| Build APK e APK test | Riuscite |
| Android Lint | 0 errori; 28 avvisi Debug e 34 Demo |

Comando Gradle finale: `testDebugUnitTest testDemoUnitTest assembleDemo assembleDemoAndroidTest lint -PtestBuildType=demo`. Gli avvisi lint non sono stati presentati come una verifica senza segnalazioni. L'ultima modifica ai sorgenti precede la build finale; successivamente sono stati compilati soltanto i documenti di consegna.

Le prove nuove coprono codifica e chiavi sull'intero layer, reimportazione, autorizzazione, clock monotono e media effettiva, peggioramento durante la finestra, motivazione e traccia GPS, salvataggio con revisione e coda, conflitti e ricevute. I test SQL usano PostgreSQL/PostGIS reale locale e verificano anche l'applicazione ripetuta della migrazione, il mantenimento della policy minima e il salvataggio di una bozza esistente senza una nuova acquisizione GPS.

## Emulatore e avvio

**20 test strumentali passati** su emulatore API 35, senza test saltati. Installazione di aggiornamento con `adb install -r` riuscita; nessuna disinstallazione. Sono stati verificati avvio e ricreazione Activity, persistenza, account, code e ricevute, sei percorsi UI nuovi (bozza e doppio tocco, ritorno manuale, eccezione/focus/conferma, ricevuta server, annullamento, errore locale) e riapertura del database con coda recuperabile. Il backup privato precedente è conservato tra gli artefatti locali di verifica.

L'emulatore API 35 esegue l'editor Compose e Room reali; le prove GPS dell'editor usano una sorgente di acquisizione simulata. I test HTTP di sincronizzazione usano risposte controllate. Il servizio di localizzazione deve essere attivo per la conferma dell'eccezione: una prova con servizio disattivato è stata correttamente bloccata e ripetuta dopo la riattivazione.

## Database e server realmente verificato

`202609220005_coll_pat_v015.sql` è una migrazione aggiuntiva di funzioni, senza cancellazione di tabelle o dati. È **stata applicata al progetto Supabase remoto**. La successiva lettura dal database ha verificato la corrispondenza dei corpi delle tre funzioni con il sorgente (normalizzando soltanto righe vuote) e i permessi: helper privati, RPC di salvataggio riservata ad `authenticated`. SHA-256 del file: `be51baea4b3b9378aaa38fcb9b398613027610f86489627ddb50fffc31eb44cf`.

Room rimane alla versione 3: i metadati aggiuntivi sono conservati nei JSON esistenti, senza nuova migrazione locale. Autori, revisione, CAS, ricevute e idempotenza della RPC sono mantenuti. Nuovi eventi con accuracy oltre soglia vengono rifiutati anche nelle bozze; l'eccezione giustifica soltanto la mancata corrispondenza. Modificare note/foto di una bozza già condivisa non obbliga a una nuova misura. Gli impedimenti possono essere registrati senza inventare un evento GPS fallito.

Al collaudo precedente alla pubblicazione, la RPC pubblica restituiva `latest_version=0.14` e `minimum_supported_version=0.14`. L'aggiornamento della sola versione disponibile a `0.15` avviene dopo la pubblicazione effettiva dell'APK; la verifica conclusiva è nell'artefatto di consegna. La minima resta `0.14`.

## APK e distribuzione

- Versione base `0.15`, versionCode `15`; variante distribuita `0.15-demo`.
- ApplicationId invariato: `it.pat.collettori.pilot.demo`.
- Firma APK v2 verificata. Certificato SHA-256 uguale alla v0.14: `acf785391278fa98832980a51e594c88ffb06ff36b590c73c18c917f975080c6`.
- APK: `local-output/releases/v0.15/COLL-PAT-v0.15-demo.apk` (63114720 byte).
- SHA-256 APK: `d9f09c76d8600077c220894fa89be744c406735575a73c840224499879550155`.

Sorgenti su `main`, tag previsto `v0.15`, [pre-release](https://github.com/Skyzzato/COLL-PAT/releases/tag/v0.15). L'hash del commit e la verifica del download pubblico sono riportati nell'artefatto di consegna generato dopo commit e pubblicazione. Nessuna credenziale amministrativa, `.env`, `local.properties` o chiave privata è inclusa nel commit. Il client mantiene URL e publishable key pubblici già configurati per il progetto.

## Limiti e attività residue

Non sono state effettuate prove sul telefono fisico o acquisizioni GPS sul campo, né collaudo diretto del dataset Bleggio. Non è stato eseguito un nuovo percorso completo remoto con due operatori reali, registrazione/email SMTP e upload/download di fotografie reali. Il contratto di questi flussi è coperto da test controllati; la verifica remota effettiva riguarda migrazione, funzioni, permessi e policy versione. Non si equiparano le prove simulate a un collaudo sul campo. L'abilitazione degli operatori resta l'amministrazione ordinaria del progetto; per questo progetto non resta una migrazione v0.15 da applicare manualmente.
