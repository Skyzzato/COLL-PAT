> Documento storico del pilota precedente. Per COLL-PAT v0.13 vedere [indice corrente](README.md).

> Documento del pilota originario: i riferimenti v0.1 e relativi collaudi sono storici. Per la demo corrente v0.11 consultare [README](../README.md) e [collaudo v0.11](VALIDATION-v0.11.md). Foto locali e nuova UX sono descritti lì.

# Operazioni del server e verifiche prima dell'uso reale

## Account, ambiti e ruoli

- Operaio: catalogo/dataset degli ambiti concessi, proprie ispezioni, invio associato all'account e al dispositivo della sessione.
- Verificatore: consultazione delle ispezioni nell'ambito, verifiche documentali tracciate, scadenze, anomalie e rapporti.
- Amministratore: funzioni precedenti, creazione/disabilitazione account e nuove versioni della regola. Ruolo tecnico da assegnare in modo limitato.

I controlli di ambito vengono ripetuti sul server. Nessun accesso anonimo al database, nessuna chiave amministrativa nell'APK, nessuna autoregistrazione. L'utility CLI è un canale amministrativo locale: limitarne l'accesso al personale autorizzato.

I dati sono sotto controllo dell'Amministrazione/fornitore incaricato, non legati a un account cloud dell'impresa. Definire un referente per versioni GIS, un referente per schede/scadenze e un gestore tecnico per account, incidenti e backup.

## Import e continuità

Importare shapefile mediante CLI come da GIS.md. Per aggiungere una nuova area/impresa iniziale è disponibile `bootstrap` (crea anche un amministratore individuale); in esercizio gli accessi vanno concessi tramite procedure amministrative e log. Non introdurre UUID casuali a ogni reimportazione.

Esportazione interoperabile completa (senza password/token):

```text
docker compose exec api python -m app.cli export-data /srv/runtime/export-patrimonio.zip
docker compose cp api:/srv/runtime/export-patrimonio.zip ./export-patrimonio.zip
```

Contiene JSON per entità, relazioni, versioni cartografiche complete, eventi, ispezioni, revisioni, anomalie, audit, scadenze ed emissioni. La v0.1 non raccoglie fotografie. Conservare separatamente le fonti importate e la configurazione come parte del backup; il modulo allegati futuro avrà una relazione uno-a-molti con la visita, non un campo che sostituisce l'identificazione GPS.

## Backup e ripristino

### Recupero controllato di invii bloccati

Nell'app, **Account → Esporta lavoro non trasmesso** produce un file scelto dall'utente con operazioni e bozze del solo account corrente, senza token/password. Il file contiene dati personali e deve essere trasferito solo al referente autorizzato. La coda originale non viene cancellata.

Nel server, da un terminale amministrativo:

```text
python -m app.cli recover /percorso/collettori-recupero.json --approver nome.amministratore --reason "Recupero autorizzato dopo guasto/sessione revocata"
```

Richiede password dell'amministratore, controlla l'ambito, conserva autore/dispositivo/eventi originali e registra l'autorizzazione nel log. Può ricevere dati di un account disabilitato tramite questo canale locale controllato; non riattiva l'account. Usa le stesse validazioni e idempotenza dell'API. Non forza conflitti: 409/422 richiedono esame e rettifica motivata. Le bozze restano nel file e non vengono trasformate in controlli eseguiti. Archiviare file e ricevute; il normale ritentativo con lo stesso UUID riceverà la medesima conferma.

Dalla radice, con ambiente Docker avviato:

```text
python scripts/backup.py backup-2026-09-16
```

Produce `database.dump` PostgreSQL in formato custom, archivio `runtime.tar.gz` e manifest SHA-256. I file contengono dati personali: deposito cifrato e autorizzazioni limitate. Il backup deve essere schedulato dall'infrastruttura approvata; questo progetto non installa automazioni sul computer dell'utente.

Ripristino in una **copia separata del progetto con nome Compose diverso e database vuoto**:

1. Preparare `.env`; impostare un nome progetto Compose diverso. Non avviare l'API prima del ripristino perché eseguirebbe le migrazioni.
2. `docker compose up -d db`
3. `docker compose build api`
4. `python scripts/restore.py <cartella-backup>`
5. `docker compose up -d api`
6. Verificare login, conteggi, dataset/versioni, ispezioni e revisioni, scadenze, anomalie, hash degli export e rigenerazione di un rapporto di prova.

Lo script rifiuta il ripristino se trova già la tabella utenti. Non sovrascrive un database operativo. Definire frequenza, tempo massimo di recupero e perdita massima accettabile con i responsabili. La presenza degli script **non è una prova di ripristino riuscito**: consultare VALIDATION.md.

Per SQLite locale di sviluppo fermare il processo API prima della copia del database, oppure usare l'API di backup SQLite; non copiare soltanto il file `.db` mentre è aperto ignorando WAL/SHM.

## Rapporti e scadenze

Ogni scadenza è un obbligo assegnato a pozzetto/impresa, con fonte e termine esplicito. La v0.1 non genera periodicità, deroghe o ordini di lavoro. Una visita può essere associata a una scadenza; il server controlla manufatto e impresa. Impedito e parziale non soddisfano automaticamente l'obbligo. Un completamento tardivo rimane tale.

Il periodo del rapporto è il trimestre del **completamento originario dichiarato** in Europe/Rome; il momento GPS e la ricezione restano colonne separate. Le correzioni successive non spostano quel completamento. Questo criterio di attribuzione è una scelta pilota da confermare con l'Amministrazione, non una regola contrattuale dedotta.

Ogni emissione ha UUID versione e istante di estrazione; snapshot immutabile nel database. Una trasmissione tardiva o nuova revisione modifica una nuova emissione, non quella precedente. Indicatori: obblighi dovuti, completati entro termine, pozzetti distinti con dichiarazione completa e arretrati al termine. La copertura non è il numero di visite; ripetizioni sullo stesso manufatto non aumentano il conteggio distinto.

XLSX: identificativi e note esplicitamente testuali; CSV UTF-8 con BOM, separatore `;`, campi quotati, protezione da formule tramite prefisso apostrofo per stringhe potenzialmente interpretate come formule. **CSV non possiede tipi di cella**: importare le colonne identificative come testo per mantenere gli zeri. Non usare formule `="0001"` per aggirare il limite. La riga `record_type=EMISSIONE` conserva periodo, versione e data anche nei rapporti vuoti; le visite hanno `record_type=CONTROLLO`. JSON preserva integralmente tipi e valori originali. La pagina amministrativa offre stampa tramite browser.

Copertura: pozzetti distinti con obblighi dovuti nel trimestre soddisfatti entro la sua fine / pozzetti distinti con obblighi dovuti nel trimestre. Se non esistono scadenze configurate il rapporto non inventa un denominatore (copertura `null`); resta disponibile il numero di pozzetti distinti con visita completa nel periodo. Le visite sono dichiarazioni, da leggere insieme allo stato di revisione documentale.

## Verifiche organizzative obbligatorie prima del pilota reale

- Contratto/capitolato: periodicità, prestazioni già dovute, nuovi oneri, documentazione, gestione guasti e contraddittorio. Nessuna penale automatica implementata.
- Sicurezza: datore di lavoro, RSPP e preposti validano procedure, accessi, traffico, movimentazione, rischio biologico/chimico, eventuali atmosfere esplosive e idoneità telefoni/accessori. App e GPS non autorizzano un'apertura né l'ingresso nel pozzetto.
- Privacy e lavoro: DPO/ufficio legale definiscono ruoli PAT/impresa/fornitore, base giuridica, informativa, conservazione, autorizzazioni, valutazione d'impatto e disciplina dei controlli sui lavoratori. Non basta una casella di consenso.
- Infrastruttura: referente informatico PAT verifica classificazione dati/servizio, requisiti ACN applicabili, backup, aggiornamenti, HTTPS, certificati, protezione da tentativi di accesso, monitoraggio e continuità.
- Dispositivi: blocco schermo, aggiornamenti, smarrimenti, revoca, accessi individuali, disponibilità Play services, autonomia e memoria.
- Organizzazione: controllare tutte le scadenze e la completezza; verifiche approfondite a campione casuale e per rischi. Non premiare velocità o scarsità di anomalie.

Fonti ufficiali di riferimento da applicare al contesto effettivo: [GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj/?locale=it), [Garante, geolocalizzazione e controlli sui lavoratori](https://www.garanteprivacy.it/web/guest/home/docweb/-/docweb-display/docweb/10293888), [D.P.R. 177/2011](https://www.normattiva.it/uri-res/N2Ls?urn:nir:stato:dpr:2011-09-14;177!vig=), [regolamento infrastrutture/cloud PA](https://www.gazzettaufficiale.it/atto/vediMenuHTML?atto.codiceRedazionale=24A03576&atto.dataPubblicazioneGazzetta=2024-07-13&tipoSerie=serie_generale&tipoVigenza=originario).
