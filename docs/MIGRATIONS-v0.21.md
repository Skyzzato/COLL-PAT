# Migrazioni e manutenzione v0.21

## Stato della consegna

| Operazione | Locale di test | Progetto remoto |
|---|---|---|
| Migrazione 008 — ciclo di vita | Applicata due volte per verificare ripetibilità | Applicata e verificata il 23/09/2026 |
| Migrazione 009 — GPS e proprietà limitate | Applicata due volte per verificare ripetibilità | Applicata e verificata il 23/09/2026 |
| Pulizia archivi pregressi | Verificata con fixture sintetiche | Non eseguita |
| Cancellazione file Storage reali | Policy/contratto SQL controllati | Non eseguita |
| Annuncio versione disponibile | Nessuna modifica automatica nelle migrazioni | Latest 0.21; minimo 0.14 invariato |

L'applicazione remota è stata completata tramite la sessione amministrativa Supabase. La connessione locale `collettori_backend` non dispone dei permessi necessari ed è stata usata soltanto per la diagnosi. Verifiche e limiti dell'intervento sono descritti in [DEPLOYMENT-v0.21](DEPLOYMENT-v0.21.md). Nessuna credenziale amministrativa è stata salvata nel repository o nell'APK.

## Applicazione strutturale

Applicare, con il responsabile del progetto, dopo la migrazione 007 già distribuita:

1. `supabase/migrations/202609230008_coll_pat_v021_lifecycle.sql`.
2. `supabase/migrations/202609230009_coll_pat_v021_gps.sql`.

Le migrazioni creano i marcatori tecnici `object_deletions`, le ricevute con fingerprint `retired_operations` e i lavori `storage_deletions`. Modificano RPC, trigger e policy; non eseguono la pulizia dei dati storici. La 009 conserva gli eventi legacy, verifica i campioni v2 e aggiunge `coll_pat_patch_object` (operatore: solo `under_asphalt`; amministratore: aspetto autorizzato).

Controllare la presenza di `coll_pat_deletion_preview`, `coll_pat_delete_permanent`, `coll_pat_deleted`, `coll_pat_storage_pending`, `coll_pat_storage_confirm` e `coll_pat_patch_object` nella cache PostgREST. Entrambi i file notificano il reload. Provare con ruoli autorizzati e non autorizzati su risorse sintetiche dedicate. Verificare anche lettura catalogo/cronologia, vecchie ricevute e upload foto. Un HTTP 404/PGRST202 nel client segnala il contratto mancante, senza cancellare la coda.

Room resta v3: nuove proprietà JSON, nessuna migrazione distruttiva. Il minimo supportato non viene alzato; un vecchio client che tenta la precedente eliminazione riceve conflitto e deve aggiornare e riconfermare. Le altre code legacy mantengono i controlli esistenti.

## Pulizia una tantum degli archivi

Lo script `scripts/purge_archived_v021.py` accetta solo UUID `00000000-0000-4000-8000-000000000013` e nome esatto `COLL-PAT`. Non leggere o cancellare altri progetti. Fornire `COLL_PAT_MAINTENANCE_DSN` tramite il gestore dei segreti della sessione amministrativa.

```powershell
.venv/Scripts/python.exe scripts/purge_archived_v021.py --project 00000000-0000-4000-8000-000000000013
```

La modalità predefinita è una transazione read-only e restituisce conteggi e fingerprint. Include solo collettori già archiviati/eliminati, dipendenze esclusive e ispezioni già annullate; conserva i manufatti ancora appartenenti a collettori attivi. Non applicare se i conteggi non corrispondono agli archivi attesi. Per applicare usare un amministratore del progetto e il fingerprint esatto appena verificato:

```powershell
.venv/Scripts/python.exe scripts/purge_archived_v021.py --project 00000000-0000-4000-8000-000000000013 --apply --actor UUID_AMMINISTRATORE --expected-fingerprint FINGERPRINT_VERIFICATO
```

Il lock del progetto e il nuovo calcolo dell'ambito respingono un'anteprima superata. Nessuna cancellazione Storage tramite DML su `storage.objects`: sincronizzare poi l'app v0.21 con un amministratore per consumare `coll_pat_storage_pending`, eliminare gli oggetti tramite Storage API e confermare l'assenza tramite `coll_pat_storage_confirm`. Se il database è già pulito e Storage fallisce, i lavori restano riprendibili; non dichiarare conclusa l'eliminazione degli allegati.

Verifica post-intervento: ripetere preview, controllare zero payload residui per gli identificativi eliminati, zero lavori Storage non confermati e conservazione delle dipendenze condivise; sincronizzare un secondo client con vecchia coda per verificare che non ricrei gli UUID ritirati. La stessa sorgente reimportata deve ricevere nuovi UUID.

Questa pulizia riguarda copie applicative controllate dal progetto. Export già consegnati a terzi e backup gestiti dal fornitore richiedono una verifica amministrativa separata; non vengono dichiarati rimossi dall'app.

Dopo accessibilità dell'APK e collaudo remoto, aggiornare soltanto `latest_version`/URL nella configurazione server secondo la procedura esistente. Non modificare `minimum_supported_version` senza verificare le code dei client precedenti.
