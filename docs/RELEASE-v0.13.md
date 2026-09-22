# COLL-PAT v0.13 — prerelease

COLL-PAT sostituisce il nome visibile Collettori e il repository è stato rinominato in **Skyzzato/COLL-PAT**, mantenendo storia, issue, tag e release. L'APK `COLL-PAT-v0.13-demo.apk` usa versionCode 13, versionName 0.13-demo, applicationId `it.pat.collettori.pilot.demo` e la firma debug storica. Installare come aggiornamento della stessa variante, senza disinstallare.

## Novità

- Cinque schede, anagrafica completa, seed sintetici Trento/Lavis, selettore collettori condiviso, visibilità persistente e mirino.
- Scheda ordinaria e verifica esterna sotto asfalto; bozza locale, registrazione e impedimento distinti. Nessun nuovo flusso parziale. Audit su annullamenti GPS e ispezioni.
- Room v2, outbox transazionale, Supabase Auth/RPC/PostGIS diretti, ricevute idempotenti, generazione di reset, nessun FastAPI obbligatorio.
- Importazione shapefile ZIP direttamente dal telefono con mapping, anteprima, provenienza e tre modalità topologiche; pubblicazione grande tramite staging atomico.
- Dashboard locale di sviluppo admin/admin, separata dai permessi Auth; creazione/modifica/archiviazione, importazione e reset con backup/conferma.
- Splash con logo originale/spinner/versione, cache cartografica unica 200 MiB, calendario e giornate Europe/Rome.

## Migrazioni e configurazione necessarie

**Room 1→2 automatica esplicita**, senza fallback distruttivo. Vecchie schede conservate; code precedenti sospese, senza attribuire loro una nuova generazione. Upgrade v0.12 verificato sull'emulatore con bozza/GPS originali inalterati.

Applicare come proprietario Supabase, in ordine:

1. `supabase/migrations/202609220001_coll_pat_v013.sql`
2. `supabase/migrations/202609220002_coll_pat_spatial.sql`

Creare/collegare un account **Supabase Auth** e assegnare privatamente la membership progetto. In Account inserire URL HTTPS, chiave pubblica publishable/anon, UUID progetto, email/password Auth. Gli account legacy non diventano automaticamente account Auth. Le scritture legacy delle ispezioni vengono dismesse; non esporre tabelle utenti/hash/sessioni. [Setup completo](https://github.com/Skyzzato/COLL-PAT/blob/v0.13/docs/SUPABASE.md).

**Migrazioni remote e pulizia iniziale non eseguite:** la connessione PostgreSQL configurata ha restituito timeout; non erano disponibili configurazione client e sessione Auth per una prova reale. SQL applicato e testato in un database locale isolato. Gli script di migrazione anagrafica/pulizia iniziale sono predisposti in dry run; richiedono ambiente e autorizzazioni verificati.

## Limiti noti

- Dati strutturati inviati realmente quando Supabase è configurato; nessuna ricevuta simulata. Accedere prima di creare lavoro destinato al server. Archivio locale anonimo e account Auth restano separati, senza cambio retroattivo di autore.
- Foto acquisite e conservate realmente sul telefono, **caricamento foto simulato**; vengono trasmessi solo metadati, nessun URL fotografico inventato.
- Base OSM offline incompleta; cache 209715200 byte nominali, nessun download preventivo. Ispezioni/anagrafica/foto non dipendono dalla cache.
- Importatore limitato ai CRS/formati/codifiche documentati. ZIP fino a 32 MiB/10000 oggetti, singola geometria fino a 4 MiB; multipart topologiche da suddividere in rami espliciti. Tipo sconosciuto senza apertura presunta.
- Collaudo su emulatore Android 15, nessun telefono fisico/GPS sul campo. Auth→RPC remoto, policy del progetto installato e scenario multi-telefono reale restano da verificare.
- Firma debug e dashboard temporanea: prerelease di sviluppo, non distribuzione operativa certificata.

[Registro requisiti](https://github.com/Skyzzato/COLL-PAT/blob/v0.13/docs/REQUIREMENTS-v0.13.md) · [Prove realmente eseguite](https://github.com/Skyzzato/COLL-PAT/blob/v0.13/docs/VALIDATION-v0.13.md). Checksum allegato in `SHA256SUMS.txt`.
