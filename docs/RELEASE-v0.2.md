# COLL-PAT v0.2

Pre-release Android per collaudo, basata sulla v0.16. VersionName **0.2**, versionCode **17**, application ID `it.pat.collettori.pilot` e firma precedente conservati.

- Importazione shapefile con colonne ed esempi, chiavi composte e assegnazione guidata persistente per dataset senza identificativi affidabili. Codifica testi e sistema delle coordinate hanno controlli separati. Anteprima e riconciliazioni ambigue precedono la conferma.
- Creazione/modifica collettori con pozzetti manuali, coordinate WGS84 con punto o virgola, verifica sulla mappa, ordine e collegamenti. Salvataggio atomico locale e sincronizzazione dipendente.
- Eliminazione persistente distinta dall'archiviazione, senza ricomparsa dal server; conferma dell'impatto e conservazione dello storico.
- Pulsanti **Aggiorna database collettori**, data dell'ultimo aggiornamento riuscito, ruolo dell'account, conteggio della coda reale, successivo/distanza e frequenza nominale nelle card.
- Logo completo e checkbox per la selezione multipla. Conservati server/cache offline, bozze condivise, GPS mediato ed esportazione trimestrale.
- Lettura dei JSON grandi senza superare il limite del cursore SQLite Android; conservato il caricamento atomico a chunk.

La migrazione SQL `202609230007_coll_pat_v02.sql` aggiunge marcatori privati di cancellazione e aggiorna l'ordinamento tecnico: la versione pubblicata 0.2 segue 0.16. Non modifica gli account e non cancella dati esistenti durante l'applicazione. Nessuna migrazione distruttiva Room.

Verifiche: **101 test JVM, 28 test sull'emulatore API 35 e 102 test Python/SQL superati**. Migrazione 007 applicata al progetto Supabase configurato e dati preesistenti conservati. Lint senza errori, con 36 warning. Prove GPS sul campo e concorrenza fra telefoni reali restano da eseguire.

Lo ZIP reale fornito per il collaudo resta privato e non è incluso nei sorgenti, nell'APK o negli allegati. [Rapporto delle verifiche](https://github.com/Skyzzato/COLL-PAT/blob/v0.2/docs/VALIDATION-v0.2.md) e [comportamento funzionale](https://github.com/Skyzzato/COLL-PAT/blob/v0.2/docs/REQUIREMENTS-v0.2.md).
