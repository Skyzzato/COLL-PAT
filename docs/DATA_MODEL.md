# Modello dati COLL-PAT v0.14

## PostgreSQL

Il catalogo esistente `coll_pat.catalog(project_id,id,kind,data)` rappresenta collettori, pozzetti e tronchi. I collettori conservano `description` (nome), `type`, `visits_h1/h2`, lunghezza e provenienza; acquisiscono `display_color`, `created_at/updated_at`, `archived_at/by` nel JSONB. `archived` rimane il campo operativo esistente. I punti hanno associazioni `collectors`, codice, coordinate, progressiva e `under_asphalt`. Nessuna duplicazione in tabelle omonime.

`coll_pat.inspections` conserva identificativo, pozzetto, progetto, generazione, modello e `original` con checklist, note, GPS e soglie applicate. Sono aggiunti `created_by/at`, `updated_by/at`, `submitted_by/at`, `revision`. `BOZZA` è condivisibile; `COMPLETO` è un controllo periodico e `IMPEDITO` è un esito concluso non conteggiato. Le correzioni successive rimangono in `cancellations`. I colori non sono persistiti come stato.

`inspection_photos`: UUID, progetto, ispezione, percorso Storage univoco, creatore, data, ricevuta upload e archiviazione. `app_config`: latest/minimum version, messaggio e aggiornamento. RLS attiva e nessun accesso diretto client alle tabelle private.

Indici: ultima ispezione per progetto/pozzetto/data, stato e aggiornamento, appartenenze del catalogo GIN, archiviazione e foto per ispezione. Gli indici spaziali PostGIS della v0.13 restano invariati.

## Android

Room 3 mantiene `visits`, `outbox`, `packages`, `settings`, `catalog`, `audit`, `imports`. La chiave visita e l’unicità delle revisioni outbox includono ora l’account. Preferenze mappa/GPS, metadati foto, policy versione e copie originali sono in `settings` e nello spazio privato esistente.

La migrazione 2→3 copia integralmente le schede prima di sostituire la tabella, ricrea gli indici e sospende gli invii v0.13 non ancora trasmessi. Non elimina fotografie o storico. [Schema precedente](history/v0.13/DATA_MODEL.md).
