# Collaudo COLL-PAT v0.2

Base verificata: `9ec05af` (v0.16), branch main pulito e allineato a origin prima delle modifiche; tag v0.2 assente. VersionName 0.2 e versionCode 17, superiore al massimo storico 16. Application ID e firma mantenuti.

## Risultati

Data di collaudo: 23 settembre 2026.

| Verifica | Esito |
|---|---|
| Python/backend/SQL/PostGIS | 102 test superati; un warning di deprecazione Starlette/AnyIO |
| JVM Android | 101 test superati, inclusa lettura e reimportazione dello ZIP privato |
| Android API 35 | 28 test strumentati superati nell'ultima esecuzione (77,152 s), nessun fallimento |
| Build APK, APK test e lint | Build riuscita; lint senza errori, 36 warning non bloccanti |
| Firma e application ID | Identità `it.pat.collettori.pilot` e certificato precedente conservati |
| Aggiornamento dalla v0.16 | Installazione `-r` riuscita; conservate tutte le righe preesistenti di catalogo, visite, outbox, audit e import, più 3 fotografie identiche byte per byte |
| Supabase remoto | Migrazione 007 eseguita; 45 righe catalogo e 1 ispezione conservate, nessuna eliminazione effettuata |

La suite SQL applica la migrazione due volte e verifica permessi, conferma obbligatoria, cancellazione effettiva, ricevute idempotenti, rifiuto della ripubblicazione, storico e metadati foto conservati, pozzetti condivisi e collegamenti creati nello stesso batch. Le fixture sono sintetiche e i database temporanei risiedono su localhost.

Le prove Android coprono salvataggio dei pozzetti, riapertura Room, sincronizzazione con HTTP controllato, cancellazione offline e refresh obsoleto, timestamp solo dopo catalogo e storico riusciti, ruolo verificato, conteggi e flussi preesistenti GPS/bozze. La prova UI percorre aggiunta pozzetto, rifiuto della latitudine fuori intervallo, virgola decimale, anteprima mappa e salvataggio finale. Screenshot ispezionati per logo e selezione multipla a 320 dp con font 150%; splash vettoriale renderizzato separatamente.

Una prova aggiuntiva su un batch sintetico oltre 9 MiB ha inizialmente rilevato `SQLiteBlobTooBigException`: corretto l'accesso ai JSON grandi con lettura a porzioni, senza cambiare schema o dati. La prova include cancellazione parziale mantenendo il protocollo a chunk e ricomposizione UTF-8. Verificati anche gli aggiornamenti separati di un pozzetto condiviso durante una cancellazione locale.

Esito finale: **231 test superati** fra le tre suite. Nessun test finale fallito o saltato; la variabile del dataset privato era impostata durante le prove JVM. Restano i warning indicati e le prove operative non eseguite elencate sotto.

## Artefatto

- APK: `COLL-PAT-v0.2.apk`, 63.265.284 byte, versionName `0.2`, versionCode `17`.
- SHA-256: `7a841504a3fae8b6a22ca6b2ee11e6f8404c28bb81f55df8b69ca436af5d40fe`.
- Certificato SHA-256: `acf785391278fa98832980a51e594c88ffb06ff36b590c73c18c917f975080c6`, uguale alla v0.16.
- Configurazione pubblica Supabase presente; nessun seed demo locale o ZIP privato nell'APK. I file di configurazione privati e le chiavi di firma non sono versionati.
- [Pre-release v0.2](https://github.com/Skyzzato/COLL-PAT/releases/tag/v0.2), con APK, checksum e questo rapporto.

## Perimetro e limiti

Le prove SQL usano PostgreSQL/PostGIS locale con ruoli autenticati reali e database temporanei. Le prove Android di sincronizzazione usano Room e HTTP controllato, non sostituiscono un login reale dal telefono o una prova Storage remota. L'emulatore verifica l'interfaccia Compose e la persistenza; non è un collaudo GPS sul campo.

Il dataset reale è letto dal parser Kotlin attraverso `COLL_PAT_PRIVATE_ZIP`, senza includerne file, coordinate o righe nei test versionati. La reimportazione controlla che non vengano creati nuovi elementi. Non viene importato automaticamente sul progetto operativo.

Sul progetto Supabase configurato la migrazione 007 è stata eseguita dal SQL Editor autenticato, verificando che il testo coincidesse con il file collaudato. RLS attiva su `catalog_deletions`, nessuna lettura diretta per `authenticated`, ordine 0.2 > 0.16 verificato. Le RPC eseguite con ruolo autenticato in transazione senza scritture restituiscono 44 elementi attivi e l'ispezione esistente. Non sono state create, cancellate o modificate infrastrutture reali per i test.

La pubblicazione aggiorna latest a 0.2 solo dopo disponibilità della prerelease; minimum resta 0.14. L'esito finale della pubblicazione e i checksum scaricati vengono conservati nel rapporto locale di provenienza.

Non eseguiti: prova GPS su telefono fisico sul campo, confronto concorrente fra due telefoni reali, nuovo upload fotografico su Storage remoto. Il collaudo automatico non sostituisce queste prove operative. La APK mantiene la firma debug delle build precedenti ed è distribuita come prerelease.
