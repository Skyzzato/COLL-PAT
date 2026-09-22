# Registro requisiti → implementazione → test

| Area | Implementazione | Evidenza / limite |
|---|---|---|
| Nome/versione/splash | COLL-PAT, BuildConfig, stessa firma/package; spinner asincrono ≥3 s | Build e upgrade emulatori; nessun telefono fisico |
| Navigazione | 5 schede, mappa mantenuta, filtri/list state, icone accessibili | UI emulatore e test avvio |
| Anagrafica | UUID, campi/default/BOE'/decimali, lunghezza nullable e provenienza | Unitari Kotlin e SQL/PostGIS |
| Seed | Trento UUID conservati + Lavis, una tantum | Fixture e test seed/upgrade |
| Selettore | Componente condiviso, ricerca, multi, occhio, mirino, catalogo paginato | UI e codice; remoto non configurato |
| Pozzetti/topologia | Appartenenze multiple, filtro rimovibile, distanza precedente/progressiva distinta | Test GIS e UI |
| Scheda | Bozza locale, ordinaria/esterna/impedimento, niente nuovo parziale, autosave ordinato | Unitari, Room, SQL, UI |
| UUID/annullamenti | Nuova visita UUID, doppio tap protetto, audit ed eventi originali | Android e SQL concurrent retry |
| GPS | FINE+COARSE, current fix/timeout, eccezioni, ultimo attivo, gps-1 | Test condivisi e emulatore; non campo |
| Outbox | Transazione, scope, UUID operazione, ricevute reali, backoff/stati terminali | Room e SQL; Auth remoto non testato |
| Supabase | RPC dirette, Auth, RLS, ruolo verificato, nessun secret client | Migrazioni collaudate localmente; remoto non applicato |
| Admin | admin/admin solo demo, ruolo Auth separato, modifica/archivia/importa/reset | UI/SQL; nessuna elevazione automatica |
| GIS A/B/C | Parser nativo, CRS/codifica espliciti, mapping/provenienza, staging atomico | Fixture sintetiche; limiti di formato documentati |
| Reset | Backup, AZZERA, generation, lock concorrente, log; procedura iniziale dry run | SQL locale; pulizia iniziale non eseguita |
| Cache | Una cache 200 MiB, User-Agent, OSM invariato | API e controllo emulatore; non pacchetto offline |
| Ispezioni/Info | Giorni Europe/Rome, calendario, stati/anomalie/cestino | Unitari confini giorno/semestre e SQL |
| Migrazioni/docs | Room1→2 e 2 SQL nuove, docs correnti + archivio | Test migrazione e documenti versionati |
| Collaudo | Test codice, SQL/PostGIS, Android disponibili | Dettaglio e prove non eseguite in VALIDATION-v0.13 |
| GitHub/APK | Rinomina effettiva stesso repo; branch codex; prerelease v0.13 | Identità GitHub 1372629407, checksum e commit release |

Decisioni minori: Cancellare collettori significa archiviarli conservando tutte le dipendenze. Un import senza chiave affidabile può soltanto creare nuovi oggetti dopo consenso; nessun matching per somiglianza. I manufatti di tipo sconosciuto usano verifica esterna, senza apertura presunta. Si accede ad Auth prima di creare lavoro destinato al server; nessuna attribuzione retroattiva dell'operatore locale.
