# Architettura COLL-PAT v0.2

Compose → Repository → Room/WorkManager → Supabase Auth/RPC/Storage, con cartografia MapLibre. L'app usa solo account server. Il catalogo JSONB/PostGIS viene scaricato al login e conservato in Room per il lavoro offline; include i dati sintetici pubblicati nel progetto. Il pacchetto locale ricostruito comprende sempre la regola GPS comune, senza dipendere da un seed demo.

- `InspectionStatus.kt`: unica logica di periodicità, ultima ispezione valida/anomalia e palette; indice costruito una volta per aggiornamento dei dati, mai una richiesta server per pozzetto.
- `OfflineMap.kt`: sorgenti GeoJSON memorizzate, aggiornate soltanto se cambiano; soglia di zoom applicata ai livelli nativi. Lo zoom non ricompone il catalogo; i tronchi non hanno una soglia minima.
- `SettingsPanel.kt`: sezioni espandibili e preferenze persistenti in Room. Login e registrazione usano la rete autentica; password non persistite, sessione cifrata con Keystore.
- `Repository.kt`: modifiche locali serializzate, salvataggio atomico. Le bozze modificate sono durevoli con stato in attesa; il worker congela il payload in outbox prima dell’invio. Un invio definitivo è accodato nella stessa transazione della scheda, anche se il caricamento della bozza precedente è in corso.
- Revisione server e contatore modifiche locali sono distinti: una ricevuta di una versione precedente non dichiara sincronizzate le modifiche locali successive. Il server usa un confronto condizionale di revisione e ricevute immutabili.
- `PhotoRepository.kt`: file originali privati, UUID persistenti, metadati prenotati con la scheda, upload JPEG sullo stesso percorso e download autenticato su richiesta. Nessuna URL pubblica inventata.
- `InspectionCsv.kt`: righe concluse del trimestre civile Europe/Rome, escaping CSV e salvataggio tramite SAF. Con rete aggiorna il semestre dal server, senza scaricare fotografie.

Room 3 usa `(owner,id)` per le visite: due account possono conservare copie distinte della stessa bozza, incluso lavoro non inviato. Le migrazioni 1→2→3 preservano i dati. Gli invii del vecchio protocollo sono conservati per recupero esplicito.

La policy di versione viene verificata all’avvio e prima della sincronizzazione. Una policy già nota che blocca la versione viene applicata anche offline; una prima verifica senza rete non elimina l’accesso ai dati locali. Il server controlla indipendentemente la versione nelle RPC.

[Architettura v0.13](history/v0.13/ARCHITECTURE.md) · [GIS](GIS.md) · [Collaudo](VALIDATION-v0.2.md).

La v0.2 aggiunge `ImportIdentity` per chiavi composte e corrispondenze sorgente persistenti, `PointForm` per i pozzetti manuali e `CatalogActions` per ciclo di vita e presentazione. `DatabaseRefreshButton` condivide un mutex e il timestamp Room dell'ultimo successo di catalogo più ispezioni. L'eliminazione usa outbox `catalog_delete` e marcatori tecnici isolati per account; il server mantiene ricevute e storico. Nessuna nuova versione dello schema Room.

Il DAO legge i JSON grandi di catalogo, pacchetti, impostazioni e outbox in porzioni da 256 KiB, ricomponendo i byte UTF-8. Le righe piccole restano nella query iniziale. Questo evita il limite Android CursorWindow senza cambiare dati memorizzati, formato SQL o limite d'importazione. Una prova strumentata copre un batch oltre 9 MiB, cancellazione parziale, pacchetto locale, payload residuo e caratteri Unicode.

L'ordine di pubblicazione registra esplicitamente 0.2 dopo 0.16 (build 17); il nome visibile non viene riscritto. La stessa regola è applicata da `compareVersions` e `coll_pat.version_parts`.

La v0.15 aggiunge GpsWindow/LocationCapture per le misure mediate e SaveFeedback per il timer monotono di due secondi. Il modulo è sovrapposto alla pagina precedente, mantenuta viva con filtri e scroll; chiuderlo conserva la cronologia reale. Un esplicito Salva bozza scrive scheda e outbox nella stessa transazione. La sincronizzazione appartiene al worker, non alla schermata.
