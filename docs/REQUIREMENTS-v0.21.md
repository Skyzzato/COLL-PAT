# Requisiti e contratto v0.21

La v0.21 estende la v0.2 (build 17) preservando identità Android, firma, account/progetto e Room 3. La fonte del perimetro è PROMPT-COLL-PAT-v0.21 fornito dall'utente ed esplicitamente autorizzato con «esegui».

| Area | Comportamento richiesto/implementato | Prove e limiti |
|---|---|---|
| Eliminazione | Anteprima, conferma irreversibile, rimozione di catalogo/schede/bozze/audit/allegati esclusivi e snapshot; coda durevole offline, controllo concorrenza e ricevute idempotenti; dipendenze condivise conservate | SQL reale locale, Room e filesystem; nessun purge remoto in questa consegna |
| Identità GIS | Sorgente attiva idempotente; reimport dopo eliminazione con UUID nuovi; vecchi UUID non ripristinabili | Cicli SQL e unitari import; seconda coda simulata |
| Collegamenti | Modalità ordinata predefinita per punti; ordine/rami espliciti se dati insufficienti; N−1 tratti schematici, geometrie ufficiali preservate | Test import/topologia; import da picker reale resta prova operativa da eseguire |
| Aspetto | Default generali, override collettore colore/spessore/forma, override forma punto, reset eredità; colori stato invariati | Test risoluzione e SQL patch; UI dedicata |
| Etichette | Codice collettore su ancore metriche: almeno due posizioni su rami brevi, intervallo circa 1 km sui lunghi; collisioni native | Test geometrie; a zoom lontano due etichette possono essere nascoste per leggibilità |
| Coordinate | WGS84 manuali, virgola/punto, mappa con conferma/annulla, posizione recente con conferma | UI e controlli coordinate; GPS fisico non provato |
| GPS | Soglie esatte 20/40/60/80/100/120/140/150 m e 5/10/15/20/25/30 m; normalizzazione preferenze esplicita | Kotlin + SQL, eventi vecchi non riscritti |
| Acquisizione | 3 s stabilizzazione esclusa, almeno 5 s utili/3 fix, timeout 30 s, media sferica, media aritmetica delle accuracy, dispersione separata; nessuna selezione opportunistica dei fix peggiori | Finestre pure e validazione SQL dei campioni; niente prova sul campo |
| Mancato rilievo | Dopo tentativo: motivazione obbligatoria, OK/ANNULLA, autore/tempi, zero eventi/coordinate; retry invalida la motivazione precedente | UI, persistenza dopo riapertura, SQL; non equivale a impedimento |
| Scheda | Note / Anomalie unificate senza duplicazione; nota neutra non anomala; sicurezza impone solo stati effettivamente osservabili; help centralizzati | Test strutturati e interfaccia |
| Asfalto | Conferma modello aggiorna bozza e `under_asphalt` atomicamente in locale; RPC strettamente limitata e conflitti; ripristino ordinario esplicito | Room e ruoli SQL; due telefoni fisici non provati |
| Foto | JPEG 85%, massimo lato 2400, bucket privato invariati; stato caricata distinto da confermata; coda foto separata dal successo scheda | Test controllati e ispezione del percorso; upload remoto non effettuato |
| Sync | HTTP distinti dalla rete, niente reset indiscriminato dei conflitti, mutex sync/refresh, timestamp separati, code per elementi logici | Test HTTP, Room e paging; diagnosi ambiente remoto limitata dai permessi |
| Consegna | Test, lint, APK compatibile, commit/tag, prerelease, hash e rapporti | Risultati effettivi in VALIDATION-v0.21 |

La qualità GPS ammessa non garantisce la corrispondenza: resta il controllo conservativo `distanza + accuracy + incertezza cartografica <= raggio`, oltre al controllo dei punti vicini. I tre valori hanno significati distinti e sono conservati separatamente. Non si presenta la media delle accuracy come precisione statistica garantita.
