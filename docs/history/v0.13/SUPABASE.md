# Supabase diretto — COLL-PAT v0.13

## Stato di questa consegna

Due migrazioni **preparate, applicate e collaudate solo su PostgreSQL/PostGIS locale isolato**. **Non applicate/verificate sul remoto**. La connessione configurata verso il Session pooler del progetto precedente ha restituito timeout su tutti gli indirizzi risolti (porta 5432). Nessuna sessione Supabase Auth o chiave pubblica di progetto era configurata nell'app. Non è stato simulato alcun successo remoto.

Reset iniziale remoto/locale collegato: **non eseguito**, perché l'ambiente di sviluppo e i permessi effettivi non sono stati verificabili. La bozza v0.12 dell'emulatore è stata conservata e verificata byte per byte dopo upgrade. Backup privati ed evidenze stanno in `local-output/verification/v0.13/`, escluso da Git.

## Installazione del protocollo

1. Come proprietario database, esaminare ed eseguire in ordine `supabase/migrations/202609220001_coll_pat_v013.sql` e `202609220002_coll_pat_spatial.sql`. Non rilanciare la prima su uno schema già creato; non cancellare lo schema per forzarla. La migrazione storica 202609160001 non è stata modificata. Su un progetto nuovo le due v0.13 sono autonome da FastAPI, richiedono Auth Supabase e PostGIS.
2. Creare/invitare l'utente con **Supabase Auth** secondo le policy normali. Non usare admin/admin come credenziale remota. Eseguire privatamente `supabase/bootstrap-v013.sql.example`, sostituendo l'UUID dell'utente verificato. Il progetto applicativo sintetico proposto è `00000000-0000-4000-8000-000000000013`.
3. Gli account legacy `collettori.users` non sono Auth: collegare esplicitamente gli UUID in `coll_pat.legacy_identity_links` dopo verifica dell'identità. Nessuna copia di password/hash. Per l'anagrafica legacy utilizzare il dry run `scripts/migrate_legacy_catalog.py --area ... --project ... --auth-admin ...`, quindi `--execute` dopo la verifica del rapporto. Lo script usa `COLL_PAT_ADMIN_DSN` solo nell'ambiente privato.
4. In Android → Altro → Account: URL HTTPS Supabase, chiave **publishable/anon**, UUID progetto, email/password Auth. La sessione viene cifrata con Keystore, con refresh token ruotato correttamente. Mai chiavi secret/service_role o DSN PostgreSQL nell'app.
5. Admin dashboard locale (solo demo): admin/admin. Il server controlla comunque il ruolo admin. Pubblicare i dati sintetici locali oppure importare il ZIP; attendere ricevuta prima di considerarli remoti. Accedere prima di iniziare ispezioni destinate al server.

## Accessi e protocollo

`coll_pat` è privato, non va aggiunto agli schemi Data API esposti. RLS attiva su ogni tabella, niente DML/SELECT client diretti. Pubbliche soltanto RPC `coll_pat_status`, `coll_pat_catalog`, `coll_pat_apply`, `coll_pat_history`, `coll_pat_backup`, `coll_pat_reset`, eseguibili da authenticated e protette internamente con auth.uid()/membership. Funzioni SECURITY DEFINER con search_path vuoto, oggetti qualificati e permessi minimi.

Catalogo paginato 200 righe con revisione coerente; se cambia durante lo scaricamento occorre ripetere il download. Operazioni atomiche, UUID idempotente e confronto integrale JSONB. Autore dell'ispezione deve corrispondere ad Auth. GPS ricalcolato sul server usando gps-1, inclusi accuracy, età, mock, permesso e candidati ambigui. Campi interni regolari nelle verifiche esterne sono rifiutati.

Le vecchie API FastAPI non sono usate dall'APK v0.13. La nuova migrazione revoca al ruolo legacy le scritture nelle tabelle di ispezione; client precedenti non possono aggirare la generazione. Non esporre vecchie tabelle utenti/hash/sessioni. Conservare backend e database storico in sola lettura per audit/migrazione.

## Pulizia iniziale e reset ordinario

`scripts/initial_inspection_cleanup.py` è una procedura privata distinta dalle migrazioni, dry run predefinito. Richiede progetto development, area legacy esplicitamente sintetica, amministratore Auth esistente, nessuna nuova ispezione nel progetto v0.13 e percorso backup privato. L'esecuzione richiede `--execute --confirmation AZZERA`; registra un marcatore una tantum, cancella solo ispezioni di quell'area e relative dipendenze, preserva anagrafica/account/configurazione e incrementa generation.

Dashboard: conteggio/ambito, digitazione AZZERA, backup privato locale della risposta server e archivio locale, reset immediato autorizzato. Nessuna outbox distruttiva. Se il backup diventa obsoleto per un invio concorrente, il server rifiuta e richiede un nuovo backup. Vecchie code sono sospese come RESET_OBSOLETE; il recupero deve essere deciso esplicitamente, senza cambiare generation dei vecchi payload.

## Verifica

`backend/tests/test_v013_sql.py` crea un database temporaneo esclusivamente su localhost, applica le migrazioni e usa il ruolo authenticated con claim di test. Verifica autorizzazioni e transazioni reali, **non** un login Auth Supabase remoto. Variabile: COLL_PAT_SQL_TEST_URL. Test di integrazione remota Auth→RPC, secondo telefono, policy nel progetto installato e reset iniziale rimangono da eseguire.

Fonti: [funzioni Supabase](https://supabase.com/docs/guides/database/functions), [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [chiavi API](https://supabase.com/docs/guides/getting-started/api-keys). Configurazione precedente archiviata in [history/v0.12/SUPABASE.md](history/v0.12/SUPABASE.md).
