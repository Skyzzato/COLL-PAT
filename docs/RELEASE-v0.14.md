# COLL-PAT v0.14 — pre-release

Consolida la v0.13 per le ispezioni sul campo: periodicità e anomalie dall’ultimo controllo valido, quattro stati cromatici, pozzetti visibili secondo zoom configurabile, simboli e colori dei collettori, impostazioni raccolte in sezioni e CSV del semestre.

Login e registrazione Supabase Auth, bozze condivise con autori e revisione, gestione esplicita dei conflitti, fotografie con identificativi stabili e Storage privato. Controlli separati di accuratezza GPS e distanza, soglie conservate con la misura e blocco dei controlli completi con GPS inaffidabile. Gli impedimenti conservano anche i tentativi GPS falliti.

La demo aggiunge Via Gilli a Trento, mantenendo gli identificativi e i dati precedenti. Il catalogo demo viene installato soltanto nell’archivio demo locale.

## Installazione

`COLL-PAT-v0.14-demo.apk`, applicationId `it.pat.collettori.pilot.demo`, versionCode 14, versionName `0.14-demo`. Stessa firma debug delle precedenti pre-release: aggiornare sopra la stessa variante. Room migra da 1/2 a 3 senza cancellare schede o fotografie.

Prima di utilizzare il server applicare, dopo le migrazioni v0.13, `202609220003_coll_pat_v014.sql` e `202609220004_coll_pat_photos.sql`. Configurare soltanto URL e publishable key nel client; abilitare gli operatori del progetto tramite la procedura amministrativa descritta in [SUPABASE](https://github.com/Skyzzato/COLL-PAT/blob/v0.14/docs/SUPABASE.md).

## Collaudo e limiti

I risultati effettivi sono in [VALIDATION-v0.14](https://github.com/Skyzzato/COLL-PAT/blob/v0.14/docs/VALIDATION-v0.14.md): 80 test Python/SQL, 50 JVM per variante e 13 strumentali passati; build e lint senza errori. Database, RLS e concorrenza sono collaudati su PostgreSQL/PostGIS locale; Android su emulatore API 35. Le prove del contratto Auth HTTP usano risposte controllate. Non equivalgono a un collaudo del progetto Supabase remoto, di SMTP o del servizio Storage reale, non configurati in questa sessione. Nessun test sul campo o telefono fisico.

Le operazioni v0.13 pendenti sono conservate per recupero esplicito, senza modificare i dati originali per adattarli ai nuovi vincoli. Offline il CSV esporta quanto disponibile nella cache, indicandolo se l’aggiornamento server non riesce. Le foto condivise sono visualizzabili offline dopo il primo download. La cache cartografica conserva il limite esistente di 200 MiB e non garantisce la copertura di aree mai visitate.
