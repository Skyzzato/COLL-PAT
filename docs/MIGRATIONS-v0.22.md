# Migrazione e deployment v0.22

Nuovo file: `supabase/migrations/202609240010_coll_pat_v022.sql`. Le migrazioni 001–009 non sono state riscritte. Richiede la base v0.21 con 008/009; non ripetere seed 006 o pulizia archivi.

La 010 è transazionale e ripetibile. Estende il vincolo membership a viewer, conserva inspector e gli UUID esistenti, aggiunge access_requests/role_audit con RLS e senza grant client. Registra solo membri o richieste esplicite COLL-PAT. Le implementazioni v0.21 sono conservate nello schema privato, invocate da wrapper con controllo del ruolo corrente sotto lock progetto prima delle ricevute. Backup/reset restano admin e vengono ricontrollati dopo il lock. Storage impedisce upload/conferma/rimozione ai viewer. Le RPC pubbliche utenti richiedono admin; auth.users non è esposto. Lo storico ha una revisione incrementale per invalidare export a pagine modificati durante il recupero.

## Applicazione verificata il 24 settembre 2026

Eseguita mediante SQL Editor nel progetto Supabase **Collettori**, riferimento `zzipvrnhndigepufhkcj`: risposta “Success. No rows returned”. Le funzioni locali e remote sono state confrontate tramite MD5 dei corpi normalizzati per CRLF: corrispondenza per tutte le funzioni COLL-PAT, incluse implementazioni preservate e nuovi wrapper.

| Controllo | Prima | Dopo 010 |
|---|---:|---:|
| Elementi catalogo | 42 | 42 |
| Ispezioni | 2 | 2 |
| Metadati foto | 0 | 0 |
| Ricevute | 14 | 14 |
| Membership | 1 admin | 1 admin |
| Richieste registrate | — | 1 membro esistente |
| Audit cambi ruolo | — | 0 |

RLS verificata sulle due nuove tabelle; nessun SELECT di authenticated su auth.users; nessuna esecuzione authenticated sulle funzioni private; nessuna esecuzione anon dell'export. Via HTTP reale: versione pubblica 200, utenti/export senza JWT 401. Nessun reset, cancellazione di dati, cambio ruolo reale o seed Barbaniga eseguito durante il deployment.

Alla migrazione `latest_version` restava 0.21 e `minimum_supported_version` 0.14. Il rilascio aggiorna **solo latest_version e il messaggio** a 0.22 dopo conferma degli asset GitHub; il minimo resta 0.14. La migrazione non modifica questa policy.

## Riproduzione

1. Verificare progetto e base 008/009, conteggi e backup secondo la procedura operativa.
2. Applicare interamente 010 come proprietario database. In caso di errore l'intera transazione deve essere annullata.
3. Verificare vincolo ruoli, tabelle RLS, funzioni pubbliche/private e conteggi invariati; attendere ricaricamento PostgREST (NOTIFY incluso).
4. Installare APK con aggiornamento senza disinstallare. Room resta 3, nessun fallback distruttivo.
5. Solo dopo pubblicazione e verifica checksum APK aggiornare la versione disponibile. Non elevare automaticamente il minimo.

I test di permesso con viewer/inspector/admin sono eseguiti con membership e ruoli PostgreSQL reali in database locali isolati; i test UI usano sessioni di prova. Non sono login end-to-end di tre account Supabase remoti: sul progetto reale è disponibile una sola membership admin.
