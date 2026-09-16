# Prompt di passaggio — Collettori v0.1

Lavora sul repository Collettori, baseline v0.1. L'app riguarda esclusivamente
ispezioni dei collettori intercomunali PAT e dei pozzetti. Leggi README.md,
docs/ARCHITECTURE.md, docs/VALIDATION.md e docs/SUPABASE.md prima di intervenire.

## Architettura da preservare

- Android Kotlin/Compose, Room, WorkManager, MapLibre; GPS acquisito una sola
  volta in primo piano dopo il salvataggio della bozza. Nessuna foto, fotocamera,
  QR, targhetta, NFC, sensori o tracciamento continuo.
- FastAPI mantiene autenticazione individuale, autorizzazioni per area,
  idempotenza, revisioni, ricalcolo GPS e rapporti congelati.
- Supabase ospita PostgreSQL/PostGIS dietro FastAPI. L'APK comunica con l'URL
  HTTPS del backend, non con le API REST/Auth Supabase. Non mettere segreti
  del database o service-role key nell'app.
- Schema privato collettori e ruolo runtime collettori_backend; migrazione
  iniziale in supabase/migrations/202609160001_pilot.sql. Nessuna policy pubblica.
- UUID e versioni dei riferimenti sono stabili; i codici conservano zeri
  iniziali. Stati operativo, GPS, sincronizzazione e revisione sono separati.
- La regola GPS condivisa resta in shared/: R=20m, accuratezza massima 15m,
  età massima 10s, timeout 45s, incertezza cartografica massima 10m.
  g sconosciuta significa incerto; d+a+g<=R compatibile, d-a-g>R incompatibile.
- Il GPS non prova apertura o esecuzione tecnica. Scadenze assegnate
  esplicitamente; trimestre di rendicontazione non implica una frequenza legale.

## Prossimo obiettivo

Collaudare la migrazione su un progetto Supabase di test indicato dall'utente,
configurare FastAPI e verificare permessi, GIS, login, sincronizzazione e report.
Usare Direct o Session pooler, DB_SCHEMA=collettori, SSL. Eseguire verify.sql.
Non applicare la migrazione iniziale sopra un database con dati; preparare un
piano di trasferimento separato se esistono dati da conservare. Non rigenerare
la migrazione già applicata: aggiungere nuove migrazioni versionate.

## Validazione e consegna

Esegui pytest per modifiche backend e test JVM/build per modifiche Android.
Documenta separatamente verifiche eseguite e verifiche ancora mancanti.
La build APK debug v0.1 esiste localmente ma non è versionata. I test su telefono
reale, il rendering Android e il collaudo Supabase non sono già certificati.
I dati inclusi sono sintetici; durata offline 72 ore sperimentale. Conserva
backup di database e runtime GIS. Non eliminare bozze, outbox o archivi pendenti.

Al termine fornisci modifiche, test, limiti, istruzioni e riferimenti al commit.
Non eseguire deploy produttivi o pubblicazioni ulteriori senza una richiesta
specifica nella conversazione attiva.
