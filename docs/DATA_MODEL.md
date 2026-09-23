# Modello dati COLL-PAT v0.2

## Aggiornamento v0.21

La v0.21 aggiunge object_deletions, retired_operations e storage_deletions; i payload eliminati non sono conservati nelle ricevute ritirate. Room resta v3. GPS v2 e mancato rilievo sono proprietà JSON versionate; under_asphalt, display_color, display_width e symbol sono aggiornati dalla RPC limitata. [Dettagli](MIGRATIONS-v0.21.md).

## Documentazione delle versioni precedenti

## PostgreSQL

La migrazione 007 aggiunge `catalog_deletions(project_id,id,kind,source_identity,deleted_at,deleted_by,original)`, privata con RLS. ID e identità sorgente eliminati non sono riutilizzabili. La RPC di eliminazione conserva gli elementi condivisi e i riferimenti necessari allo storico; i marcatori escludono tutti gli eliminati dal catalogo visibile. Un trigger differito verifica che `next_ids` punti a pozzetti attivi appartenenti allo stesso collettore, anche quando creati nello stesso batch.

I punti manuali usano UUID, `collectors`, `next_ids`, `topology_end`, `sequence` e `branch`. Le importazioni conservano attributi originali, `source_fingerprint`, `identity_mode` e chiave sorgente. Nessun codice descrittivo sostituisce una relazione UUID.

La migrazione 006 pubblica 3 collettori, 22 punti e 19 tronchi sintetici nello stesso catalogo del progetto, conservando gli UUID storici. I dati sono marcati `synthetic=true`, `server_seed=v0.16` e `source_identity` stabile. I trigger conservano le date e ricalcolano le lunghezze dei tronchi con PostGIS. Non crea utenti né modifica permessi.

Il catalogo esistente `coll_pat.catalog(project_id,id,kind,data)` rappresenta collettori, pozzetti e tronchi. I collettori conservano `description` (nome), `type`, `visits_h1/h2`, lunghezza e provenienza; acquisiscono `display_color`, `created_at/updated_at`, `archived_at/by` nel JSONB. `archived` rimane il campo operativo esistente. I punti hanno associazioni `collectors`, codice, coordinate, progressiva e `under_asphalt`. Nessuna duplicazione in tabelle omonime.

`coll_pat.inspections` conserva identificativo, pozzetto, progetto, generazione, modello e `original` con checklist, note, GPS e soglie applicate. Sono aggiunti `created_by/at`, `updated_by/at`, `submitted_by/at`, `revision`. `BOZZA` è condivisibile; `COMPLETO` è un controllo periodico e `IMPEDITO` è un esito concluso non conteggiato. Le correzioni successive rimangono in `cancellations`. I colori non sono persistiti come stato.

`inspection_photos`: UUID, progetto, ispezione, percorso Storage univoco, creatore, data, ricevuta upload e archiviazione. `app_config`: latest/minimum version, messaggio e aggiornamento. RLS attiva e nessun accesso diretto client alle tabelle private.

Indici: ultima ispezione per progetto/pozzetto/data, stato e aggiornamento, appartenenze del catalogo GIN, archiviazione e foto per ispezione. Gli indici spaziali PostGIS della v0.13 restano invariati.

## Android

Room 3 mantiene `visits`, `outbox`, `packages`, `settings`, `catalog`, `audit`, `imports`. La chiave visita e l’unicità delle revisioni outbox includono ora l’account. Preferenze mappa/GPS, metadati foto, policy versione e copie originali sono in `settings` e nello spazio privato esistente.

La v0.2 conserva lo schema 3. `settings` registra ultimo aggiornamento riuscito, ruolo verificato, marcatori di eliminazione, dati già noti al server e tentativi di invio del catalogo. La nuova operazione `catalog_delete` è distinta dalle ispezioni. Il conteggio della coda deduplica gli UUID logici fra batch, chunk e retry; le foto non confermate sono unità distinte.

La migrazione 2→3 copia integralmente le schede prima di sostituire la tabella, ricrea gli indici e sospende gli invii v0.13 non ancora trasmessi. Non elimina fotografie o storico. [Schema precedente](history/v0.13/DATA_MODEL.md).

La v0.15 estende il JSON degli eventi esistente: `method`, `sample_count`, `duration_ms`, `sample_span_ms`, `acquisition_started_at/ended_at`, `started/ended_elapsed_ns`, `last_sample_elapsed_ns`, `dispersion_m`, `match_outcome` e `exception_reason`. Accuracy/coordinate, UUID, identità e `applied_limits` erano già presenti. Nessuna colonna equivalente duplicata e nessuna nuova migrazione Room. La migrazione SQL 005 estende la validazione senza riscrivere i record storici.
