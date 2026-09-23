# Deployment remoto v0.21

Verifica conclusa il 2026-09-23T17:49:50.638854+00:00.

Le migrazioni `202609230008_coll_pat_v021_lifecycle.sql` e `202609230009_coll_pat_v021_gps.sql` sono state applicate al progetto COLL-PAT tramite SQL Editor Supabase con la sessione amministrativa già autorizzata. Entrambe le transazioni hanno restituito successo.

- Le 19 funzioni installate corrispondono ai sorgenti della release, verificati con SHA-256 dei corpi SQL dopo normalizzazione CRLF/LF.
- RLS attiva sulle tre nuove tabelle; nessuna lettura diretta concessa ai client anonimi o autenticati.
- Le sette nuove RPC hanno EXECUTE per authenticated e lo negano ad anon.
- Conteggi di catalogo, ispezioni, fotografie e ricevute invariati prima/dopo.
- Letture di eliminazioni, lavori Storage e anteprima verificate con ruolo authenticated e identità di un amministratore esistente, in transazione read-only terminata con rollback.
- Le nuove RPC sono presenti nella cache PostgREST: le chiamate anonime restituiscono correttamente 401/42501, anziché 404/PGRST202.
- `latest_version` aggiornata a `0.21`; `minimum_supported_version` conservata a `0.14`. APK e prerelease erano già pubblicati e verificati.

La pulizia una tantum degli archivi e la cancellazione di file Storage non sono state eseguite: sono operazioni distinte dalle migrazioni strutturali. Il collaudo operativo su telefoni e GPS reali resta da svolgere.

Il tag v0.21 e l'APK restano quelli originali. Questo documento aggiorna lo stato remoto successivamente alla pubblicazione; i documenti negli allegati originali descrivono lo stato al momento della release.
