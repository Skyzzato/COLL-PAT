# COLL-PAT v0.15 — pre-release

Importazione shapefile guidata: codifica automatica con anteprima, opzioni per layer e controllo completo di vuoti/duplicati nella chiave. GPS con acquisizione reale su cinque secondi, media spaziale e accuratezza conservativa; la mancata corrispondenza richiede un'eccezione motivata, l'accuratezza oltre soglia resta bloccante.

Ispezione e bozza mostrano una conferma veritiera dopo il salvataggio durevole e tornano alla pagina precedente dopo due secondi. Ricevute server, conflitti e foto in coda sono distinti. Incluse le correzioni v0.14: tre collettori demo, 22 manufatti/19 tronchi, seed idempotente, CSV trimestrale, sette simboli e accesso all'importazione dalle impostazioni.

## Installazione

`COLL-PAT-v0.15-demo.apk`: versione base `0.15`, versionCode `15`, versione della variante distribuita `0.15-demo`; applicationId `it.pat.collettori.pilot.demo`. Stessa firma delle precedenti pre-release: installazione come aggiornamento della stessa variante. Non occorre disinstallare. Room rimane alla versione 3 e conserva schede e fotografie.

Il checksum è allegato in `SHA256SUMS.txt`. URL e publishable key Supabase sono preconfigurati; nessuna chiave amministrativa è nel client. La migrazione server `202609220005_coll_pat_v015.sql` è già applicata e verificata sul progetto corrente. La versione minima supportata resta `0.14`.

## Collaudo

Risultati e limiti in [VALIDATION-v0.15](https://github.com/Skyzzato/COLL-PAT/blob/v0.15/docs/VALIDATION-v0.15.md), allegato anche alla pre-release. Test JVM, Python/PostgreSQL, UI/persistenza Android e avvio su emulatore sono distinti dalle verifiche reali Supabase. Non collaudati direttamente: ZIP Bleggio, GPS su telefono fisico e nuovo percorso remoto completo con due operatori e foto reali.
