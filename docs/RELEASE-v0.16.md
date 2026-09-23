# COLL-PAT v0.16 — progetto server e importazione automatica

L'app usa esclusivamente il progetto Supabase. Eliminati l'ingresso demo, il seed incluso nell'APK e la variante di compilazione demo. Restano cache locale e lavoro offline dopo il primo accesso al server.

I tre collettori sintetici Trento, Lavis e Via Gilli sono già caricati sul progetto: **3 collettori, 22 manufatti, 19 tronchi**, disponibili agli utenti autorizzati tramite la stessa API dell'app. Conservano gli UUID storici e la marcatura di dati sintetici. Migrazione 006 additiva e idempotente, senza modifica dei permessi degli account.

L'importazione shapefile propone automaticamente mapping, modalità e collettore. Se il file non contiene il collettore, ne crea uno stabile per la sorgente; il codice può derivare dall'identificativo. L'anteprima precede sempre la conferma. Le impostazioni manuali sono nelle opzioni avanzate. Restano i controlli su CRS, chiavi univoche e geometrie ambigue. Il ruolo amministratore del progetto è richiesto per importare.

Corretti il primo download del catalogo e la disponibilità della regola GPS: entrambi dipendevano da impostazioni create dal vecchio seed demo. Le prove Android verificano ora il percorso server e la coda delle bozze.

**Installazione:** `COLL-PAT-v0.16.apk`, versione 0.16, versionCode 16, application ID `it.pat.collettori.pilot`. La precedente app demo ha un'identità diversa: questa installazione la affianca e richiede il login server. Eventuali dati locali della vecchia demo non sono migrati automaticamente. Firma debug storica verificata; pre-release per collaudo.

Verifiche: **81 test JVM, 20 test Android**, suite Python/SQL e prova di idempotenza della migrazione; lint con **0 errori e 34 avvisi**. Catalogo remoto verificato anche con ruolo `authenticated` e identità di un membro del progetto. Nessun collaudo fisico sul campo, nuovo login reale dal dispositivo o ciclo foto remoto completo in questa consegna.

SHA-256 APK: `e1efdf40fe79f8db82af8dc734ce577b48ef1559645a933d56565c08e203f16a`.

[Relazione di collaudo](https://github.com/Skyzzato/COLL-PAT/blob/v0.16/docs/VALIDATION-v0.16.md)
