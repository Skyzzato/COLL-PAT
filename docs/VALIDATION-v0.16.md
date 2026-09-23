# COLL-PAT v0.16 — collaudo e consegna

Data: 23 settembre 2026. Evoluzione della v0.15, versione Android 0.16 / versionCode 16. Ambiente demo rimosso; unica identità client distribuita `it.pat.collettori.pilot`.

## Correzioni verificate

- Il catalogo scaricato dal server viene letto dal pacchetto Room appena ricostruito; non dipende più dall'impostazione lasciata dal seed demo. Il pacchetto comprende sempre `gps-rule.json`, necessario sia alla mappa sia alla registrazione GPS.
- Primo accesso con download del catalogo, regola GPS disponibile, acquisizione e annullamento, eccezione motivata, salvataggio bozza, conferma temporizzata e ritorno alla pagina precedente.
- Le bozze esplicitamente salvate entrano nella coda persistente. Il doppio tocco non duplica la registrazione; un annullamento attende la pubblicazione dell'ispezione cui si riferisce. La sincronizzazione conserva l'ordine tra bozza in corso di invio e successiva submission.
- Importazione automatica di punti ordinati, linee e punti indipendenti. Collettore automatico e codice da identificativo quando assenti, riconoscimento di campi identificativi GIS comuni, reimportazione senza nuovi UUID o duplicati. Collettori archiviati e chiavi non affidabili restano bloccati.

## Risultati locali

| Controllo | Esito |
|---|---|
| Kotlin/JVM, variante distribuita Debug | 81 superati, 0 errori/fallimenti/skip |
| Android instrumentation, emulatore API 35 | 20 superati, 0 fallimenti |
| Python/SQL | 96 casi distinti verificati: 95 superati nella suite iniziale; il vecchio controllo dell'asset demo è stato aggiornato al seed server e i 4 casi dei moduli interessati sono poi passati |
| SQL 006 su PostgreSQL/PostGIS locale | Due applicazioni, 44 elementi, revisione stabile, ruolo inspector conservato |
| Gradle | `testDebugUnitTest assembleDebug assembleDebugAndroidTest lint`: BUILD SUCCESSFUL |
| Lint Debug | 0 errori, 34 avvisi |
| Firma APK | Verificata con apksigner |

I dati dei test sono sintetici. Il test GPS usa il modulo Compose reale e una sorgente GPS controllata, senza presentarla come prova su strada. I test SQL usano database temporanei locali.

## Server remoto

Migrazione `202609230006_coll_pat_v016_server_seed.sql` applicata tramite il dashboard autenticato al progetto COLL-PAT. Verificato il contenuto completo prima dell'esecuzione e poi confrontato con il catalogo remoto.

- Prima: catalogo vuoto, revisione 0.
- Dopo: 3 collettori, 22 punti, 19 tronchi, tutti `synthetic=true`, revisione 1.
- Seconda applicazione: revisione ancora 1 e nessun duplicato.
- 44 UUID e campi corrispondenti al sorgente; le sole differenze, oltre alle date dei trigger, sono le lunghezze ricalcolate da PostGIS, con scarto massimo 0,0000005525209 m.
- RPC `coll_pat_catalog` verificata sotto ruolo `authenticated` con identità di un membro esistente: 44 elementi visibili, nessuna pagina residua, conteggi 3/22/19.
- Il caricamento non crea utenti né amplia permessi. L'importazione cartografica continua a richiedere `admin`.

La sola versione disponibile viene aggiornata a 0.16 dopo la pubblicazione effettiva dell'APK. Il minimo compatibile resta 0.14. La verifica conclusiva dei download e della policy viene conservata negli artefatti locali di consegna.

## Pacchetto

- File: `COLL-PAT-v0.16.apk`, **63079105 byte**.
- SHA-256: `e1efdf40fe79f8db82af8dc734ce577b48ef1559645a933d56565c08e203f16a`.
- Certificato SHA-256: `acf785391278fa98832980a51e594c88ffb06ff36b590c73c18c917f975080c6`.
- Application ID `it.pat.collettori.pilot`; versionName `0.16`; versionCode `16`; minSdk 26, targetSdk 36.
- L'APK non contiene `demo-package.json`. La vecchia applicazione `.demo` rimane separata; i suoi dati locali non vengono trasferiti automaticamente.
- Sorgenti su main, tag `v0.16`; pre-release con APK, checksum e questa relazione. Credenziali amministrative, `.env`, `local.properties` e strumenti temporanei esclusi dal commit.

Non sono stati eseguiti un nuovo login reale dal dispositivo, prove GPS fisiche, un ciclo completo di Storage remoto o un collaudo dello shapefile Bleggio originale. I controlli di questi flussi sono quelli dei test controllati descritti sopra; la verifica remota effettiva riguarda catalogo, visibilità RPC, idempotenza e policy versione.
