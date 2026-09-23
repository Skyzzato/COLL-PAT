# Supabase — COLL-PAT v0.2

## Aggiornamento v0.21

Le nuove migrazioni 008 e 009 e la pulizia separata sono descritte in [MIGRATIONS-v0.21](MIGRATIONS-v0.21.md). Sono state applicate solo in test locale. Le policy DELETE/SELECT per gli allegati autorizzano esclusivamente i lavori di cancellazione confermati dal progetto.

## Documentazione delle versioni precedenti

Android usa direttamente Supabase Auth, RPC HTTPS e Storage. FastAPI rimane per compatibilità storica. Lo stato delle migrazioni locali e remote effettivamente applicate è riportato in [VALIDATION-v0.2](VALIDATION-v0.2.md).

Per aggiornare dalla v0.16 applicare `202609230007_coll_pat_v02.sql`: introduce la cancellazione con autorizzazione admin e ricevute, protegge identità eliminate, verifica i successivi e registra l'ordine di pubblicazione 0.2 successivo a 0.16. Lo script è transazionale e ripetibile; l'applicazione non cancella record operativi. Dopo pubblicazione dell'APK impostare latest a **0.2**, mantenendo minimum **0.14**. Non occorre ripetere il seed 006.

## Configurazione del progetto

1. Su un progetto nuovo applicare le migrazioni `202609220001_coll_pat_v013.sql` e `202609220002_coll_pat_spatial.sql` una sola volta. Su un progetto già v0.13 non ripeterle.
2. Applicare nell’ordine `202609220003_coll_pat_v014.sql` e `202609220004_coll_pat_photos.sql`. Entrambe le nuove migrazioni sono ripetibili. Non cancellano l’archivio. La configurazione iniziale è latest/minimum **0.14**; una configurazione successivamente modificata non viene sovrascritta dalla ripetizione.
3. Applicare `202609220005_coll_pat_v015.sql` dopo le precedenti: aggiorna il contratto GPS, senza modificare latest/minimum.
4. Applicare come proprietario del database `202609230006_coll_pat_v016_server_seed.sql`: richiede il progetto COLL-PAT e un membro esistente, inserisce o riallinea soltanto i 3 collettori sintetici, 22 manufatti e 19 tronchi marcati `server_seed=v0.16`. È additiva e idempotente; non elimina né modifica dati estranei, utenti o permessi. Aggiornare solo latest a 0.16 dopo la pubblicazione dell’APK; il minimo resta 0.14.
5. Dopo la conferma dell’identità, aggiungere gli operatori a `coll_pat.memberships` tramite un responsabile: [bootstrap-v014.sql.example](../supabase/bootstrap-v014.sql.example). La registrazione non assegna automaticamente permessi né ruoli amministrativi. L’UUID del progetto applicativo è distinto dal riferimento del progetto Supabase.
6. Copiare `android/local.properties.example` in `android/local.properties`, impostando **SUPABASE_URL** e **SUPABASE_PUBLISHABLE_KEY**. Sono supportate anche proprietà Gradle e configurazione pubblica nella schermata iniziale. La chiave deve iniziare con `sb_publishable_`. Non utilizzare secret, service_role o password database nel client.
7. Verificare che Storage contenga il bucket privato `coll-pat-photos`, limite 6 MiB e MIME JPEG, creato dalla migrazione 004. Le foto vengono ridimensionate al massimo a 2400 pixel prima dell’upload; gli originali locali restano conservati.

## Permessi

RLS è attiva su tutte le tabelle di `coll_pat`, senza grant diretti di SELECT/DML ai client. Le RPC SECURITY DEFINER hanno `search_path` vuoto, nomi qualificati e controllo di `auth.uid()`, progetto e ruolo. Il catalogo è modificabile soltanto dagli amministratori; le bozze sono condivise fra i membri del progetto.

`coll_pat_version` è l’unica lettura di configurazione disponibile prima del login: non espone dati operativi. `app_config` non è scrivibile dai client. Le altre RPC richiedono l’header `X-Coll-Pat-Version`; una versione precedente al minimo viene rifiutata anche lato server.

Storage autorizza lettura ai membri del progetto e inserimento solo all’autore del metadato prenotato. Il nome è `{project}/{inspection}/{photo}.jpg`. Non esistono policy COLL-PAT per overwrite/delete client. Il retry usa lo stesso percorso e la ricevuta controlla l’esistenza dell’oggetto; la rimozione dalla bozza archivia il metadato, senza cancellare il file.

## Concorrenza e offline

`coll_pat_save_inspection` confronta `expected_revision` sotto lock e conserva ricevute idempotenti. La bozza ha stato `BOZZA`; gli stati finali esistenti `COMPLETO` e `IMPEDITO` equivalgono alla submission. Creatore, modificatore e mittente vengono determinati dal server. Un secondo utente non può inventare evidenze GPS attribuite al primo.

In caso di conflitto l’app conserva la propria copia, interrompe gli invii dipendenti e propone dalla scheda **Conserva copia locale e apri versione server**. Le foto locali nuove restano disponibili. Non viene eseguito un merge automatico dei campi.

Le query operative escludono i collettori archiviati; il download riceve separatamente i relativi marcatori per aggiornare le cache. Un aggiornamento vecchio non può riattivarli. Lo storico conserva i riferimenti originali. L’RPC di riepilogo invia soltanto ultime ispezioni valide e bozze, con cursore; cronologia ed export richiedono esplicitamente i dati più estesi.

## Validazione

`test_v014_sql.py` verifica transazioni e ruoli PostgreSQL reali con due operatori; le tabelle Storage del test sono un ambiente per verificare le policy, non un servizio Storage remoto. I test Android Auth verificano richieste, sessione cifrata e refresh con risposte HTTP controllate. Per completare il collaudo sul progetto reale servono due account abilitati e la configurazione pubblica sopra indicata.

Riferimenti: [Auth email/password](https://supabase.com/docs/guides/auth/passwords), [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [Storage e policy](https://supabase.com/docs/guides/storage/security/access-control), [upload standard](https://supabase.com/docs/guides/storage/uploads/standard-uploads). Documentazione precedente: [v0.13](history/v0.13/SUPABASE.md).
