> Documento del pilota originario: i riferimenti v0.1 e relativi collaudi sono storici. Per la demo corrente v0.11 consultare [README](../README.md) e [collaudo v0.11](VALIDATION-v0.11.md). Foto locali e nuova UX sono descritti lì.

# Supabase — Collettori v0.1

## Compatibilità

La build Android 0.1 usa `/api/login`, `/api/sync` e gli altri endpoint FastAPI.
Resta utilizzabile senza ricompilazione con questa architettura:

**Android → HTTPS FastAPI → PostgreSQL/PostGIS Supabase**

Supabase ospita il database. FastAPI e il suo disco persistente `runtime` devono
essere ospitati separatamente. Supabase Auth, REST automatiche, Storage e Realtime
non sostituiscono le API del pilota. Nell'app inserire l'URL HTTPS di FastAPI,
non `https://<progetto>.supabase.co`. Nessuna chiave Supabase va nell'APK.

Gli account individuali restano in `collettori.users`, con password Argon2 e
sessioni proprie. Non sono account di `auth.users`. RLS permette l'accesso al
solo ruolo backend; i limiti per utente e area sono applicati da FastAPI.

## 1. Inizializzare un progetto nuovo

Nel SQL Editor Supabase, come `postgres`, eseguire tutto il file
[`202609160001_pilot.sql`](../supabase/migrations/202609160001_pilot.sql).

La migrazione è atomica e crea schema privato `collettori`, tabelle, relazioni,
indici, PostGIS e versione Alembic `0001`. Mantiene gli stessi tipi del pilota:
UUID come varchar, date ISO come testo e payload JSON; nessuna conversione
incompatibile con il codice esistente. PostGIS già presente viene rispettato.
`anon`, `authenticated` e `service_role` non ricevono accesso alle tabelle.
Non aggiungere `collettori` agli schemi esposti dalle Data API.

Il ruolo `collettori_backend` nasce NOLOGIN. Impostare **privatamente**, nel SQL
Editor, una password casuale forte e abilitarne il login:

```sql
ALTER ROLE collettori_backend LOGIN PASSWORD '<PASSWORD_CASUALE_PRIVATA>';
```

Il segreto non deve essere salvato nei file SQL, nel repository o nella chat.
La migrazione non è ripetibile deliberatamente: se schema o ruolo esistono,
fallisce e annulla le modifiche. Non cancellare schemi per forzarla su un database
già utilizzato. Le modifiche successive richiedono una nuova migrazione.

## 2. Configurare il backend

Usare la connessione **Direct** o **Session pooler (5432)** indicata da Connect
nel progetto. Il Transaction pooler (6543) non è supportato da questa configurazione:
il backend imposta un `search_path` di sessione. Per una rete soltanto IPv4,
usare Session pooler. Sostituire l'utente con il ruolo dedicato:

```text
# Direct
DATABASE_URL=postgresql+psycopg://collettori_backend:<PASSWORD_URL_ENCODED>@db.<PROJECT_REF>.supabase.co:5432/postgres?sslmode=require
# Session pooler: copiare host/regione da Connect, non indovinarli
DATABASE_URL=postgresql+psycopg://collettori_backend.<PROJECT_REF>:<PASSWORD_URL_ENCODED>@<SESSION_POOLER_HOST>:5432/postgres?sslmode=require
DB_SCHEMA=collettori
OFFLINE_HOURS=72
```

Codificare i caratteri speciali della password nella URL. `sslmode=require`
critta il trasporto; per verificare anche l'identità del server usare
`sslmode=verify-full` e `sslrootcert` con il certificato fornito dal progetto.
Le credenziali sono solo lato server. Il backend rileva lo schema PostGIS
(`extensions`, `gis`, `public`, ecc.) e lo aggiunge dopo `collettori`.

Per Docker salvare DATABASE_URL e OFFLINE_HOURS nel `.env` locale, escluso da Git:

```text
docker compose -f compose.supabase.yaml up -d --build
docker compose -f compose.supabase.yaml exec api python -m app.cli bootstrap --username nome.cognome --company "Impresa pilota sintetica" --area DEMO --area-name "Area sintetica" --synthetic
docker compose -f compose.supabase.yaml exec api python -m app.cli import-gis demo/synthetic.zip demo/mapping.json
```

Questo Compose è autonomo; **non combinarlo con compose.yaml**. Non avvia un
PostgreSQL locale e non esegue Alembic all'avvio: il ruolo runtime non ha DDL.
Per l'avvio Python impostare le stesse variabili d'ambiente nella shell,
poi, da `backend`, eseguire `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`.
Python non carica automaticamente il file `.env`.

Proteggere il servizio con HTTPS prima dell'uso remoto. Conservare `runtime`
su disco persistente e includerlo nei backup insieme al database: contiene
gli archivi originali GIS. Gli script backup/restore del Compose locale non
gestiscono automaticamente Supabase.

## 3. Verificare prima del pilota

### Avvio guidato sul PC del pilota

Per il progetto `zzipvrnhndigepufhkcj`, host Session pooler verificato nel pannello,
è disponibile `scripts/supabase_local.py`. Dalla radice del repository:

```powershell
.\.venv\Scripts\python.exe scripts\supabase_local.py configure
.\.venv\Scripts\python.exe scripts\supabase_local.py check
.\.venv\Scripts\python.exe scripts\supabase_local.py admin bootstrap --username nome.cognome --company "Impresa pilota sintetica" --area DEMO --area-name "Area sintetica" --synthetic
.\.venv\Scripts\python.exe scripts\supabase_local.py admin import-gis ..\demo\synthetic.zip ..\demo\mapping.json
.\.venv\Scripts\python.exe scripts\supabase_local.py serve
```

`configure` chiede la password del ruolo database due volte, in modo nascosto,
e crea `.env` senza sovrascrivere un file esistente. Non cambia la password
su Supabase: impostarla prima nel SQL Editor. `bootstrap` chiede separatamente
la password dell'account amministratore dell'app. Non riutilizzare la password
database. `check` legge soltanto schema e contatori; non modifica dati.

Questo avvio funziona mentre il PC e il processo restano accesi. Per un telefono
USB usare `adb reverse tcp:8000 tcp:8000` e l'URL `http://127.0.0.1:8000` nella
build debug. Per l'uso fuori dal PC serve un server con HTTPS e disco persistente.

Eseguire [`verify.sql`](../supabase/verify.sql) nel SQL Editor dopo la migrazione.
Gli inserimenti di verifica vengono annullati con ROLLBACK. Poi verificare login,
importazione GIS, download area, invio di un controllo, revisione e rapporti
tramite FastAPI. Completare le prove Android in modalità aereo e riconnessione.

La migrazione prepara **lo schema vuoto**: non trasferisce dati da SQLite o dal
precedente PostgreSQL, account, sessioni, archivi GIS o controlli pendenti. Per
un sistema già in uso serve un trasferimento separato con UUID preservati,
backup e riconciliazione. Cambiare URL del server nell'app crea un diverso
ambito locale: sincronizzare o esportare i controlli pendenti prima del cambio.

## Evidenza disponibile

Test automatici di coerenza SQL/modello e configurazione della connessione
inclusi in pytest. Dopo l'applicazione della migrazione da parte dell'utente,
il collegamento reale al progetto del pilota è stato verificato: ruolo backend,
schema collettori, versione 0001 e funzioni PostGIS. FastAPI locale risponde
con HTTP 200; un login inesistente viene rifiutato con 401. Creato il primo
amministratore mediante inserimento privato della password da parte dell'utente.
Importazione demo riuscita: 16 punti, 15 tratti, nessun errore o avviso.
Il test SQL completo dei permessi e il collaudo Android end-to-end restano da eseguire.

Su Windows sono disponibili anche `scripts/configure-supabase.ps1` e
`scripts/create-admin.ps1`: finestre locali con campi password mascherati.
La prima salva il `.env` escluso da Git; la seconda passa la password al processo
Python attraverso stdin e riutilizza il bootstrap transazionale del backend.
Nessuna password viene inserita negli argomenti dei processi o nel repository.

Fonti ufficiali: [connessioni](https://supabase.com/docs/guides/database/connecting-to-postgres),
[PostGIS](https://supabase.com/docs/guides/database/extensions/postgis),
[RLS](https://supabase.com/docs/guides/database/postgres/row-level-security).
