# COLL-PAT v0.13 — verifica del 22 settembre 2026

## Esito e limiti

Prerelease Android compilata e verificata su emulatore Android 15/API 35, x86_64 con Google APIs. Nessun telefono fisico e nessuna prova GPS sul campo. I test SQL usano PostgreSQL 16.15/PostGIS 3.5.3 **locali e isolati**; non dimostrano che il progetto Supabase remoto sia configurato.

| Verifica eseguita | Risultato |
|---|---|
| Python, backend storico e nuove integrazioni SQL | **67 passati**, nessuno skip; un warning di deprecazione AnyIO/Starlette |
| Kotlin/JVM | **32 passati per variante** demo, debug e release; nessun errore/skip; gli stessi casi vengono eseguiti nelle tre varianti |
| Android strumentale sull'APK finale | **9 passati**, nessuno skip |
| Assemble demo, debug, release | Riusciti; distribuita soltanto la demo firmata, release operativa non pubblicata |
| Lint demo | **0 errori, 30 warning**: versioni SDK/plugin/dipendenze, convenzioni Compose/log, API KTX e regole estrazione backup |
| Firma e upgrade | Firma APK v2 valida, certificato SHA-256 uguale alla v0.12; installazione `-r` riuscita |
| Conservazione v0.12 | Una bozza creata nella v0.12 con evento GPS: UUID e hash del contenuto invariati dopo upgrade; Room 2 |
| Seed locale | 2 collettori, 16 punti e 14 tratti; UUID Trento storici conservati |

Le compilazioni hanno incontrato attributi Windows di sola lettura e un riferimento transitorio a `desktop.ini` nei file **generati**. Normalizzati gli attributi nella sola directory di build, la compilazione è stata ripetuta con `--no-watch-fs`. Nessuna dipendenza aggiornata per aggirare l'errore.

## Cosa coprono i test

- **17 test SQL v0.13**: funzioni realmente eseguite su PostgreSQL/PostGIS, ruoli `authenticated`/`anon`, RLS attiva, grants minimi, search_path sicuro e autore verificato. Include tentativi di import/reset non autorizzati e rettifica di dati altrui.
- Ordinaria, sotto asfalto e impedimento, interni non osservabili, modello/controllo periodico, rifiuto del nuovo parziale, campi invalidi e foto senza URL/base64. GPS rivalutato sul server, fix simulato/approssimativo e ultimo evento attivo.
- UUID distinti nello stesso giorno; retry dopo ricevuta persa, operazione identica da due connessioni concorrenti, conflitto a parità di ID con contenuto differente. Creazione e annullamento ordinati, originale conservato e nessuna resurrezione.
- Reset con backup vincolato alla revisione; modifica successiva invalida il token. Lock concorrente reset/invio; payload di generazione vecchia respinto. Il secondo dispositivo è rappresentato da una connessione/payload vecchio, **non da un secondo telefono reale**.
- Anagrafica, BOE', valori numerici, default, lunghezza dichiarata preservata; PostGIS deduplica geometrie condivise. Staging privato, parti incomplete non pubblicate, commit finale atomico e retry idempotente.
- **Kotlin**: regole GPS condivise, coordinate invalide, cancellazioni dell'ultima evidenza, confini data/semestre Europe/Rome; modelli e note; GIS A/B/C con fixture esclusivamente sintetiche, UTM/WGS84, zeri nei codici, CRS assente/non supportato, ZIP pericoloso, ordine ambiguo, riimportazione per chiavi e conservazione delle dichiarazioni.
- **Android**: migrazione Room da schema v1 reale, dati conservati e vecchie code sospese; bozza senza outbox; doppio tap; nuova visita distinta; ultima nota; annullamenti/audit offline e riapertura; seed una tantum; foto JPEG private reali; permesso GPS negato; inizializzazione MapLibre e ricreazione Activity. Lo splash verifica un'attesa effettiva di almeno 3000 ms e il flag di processo non viene azzerato alla ricreazione.

## Prove manuali su emulatore

- Apertura senza crash, splash con tubo originale, versione e spinner anche in modalità aereo.
- Mappa Trento/Lavis, selettore di tutti i collettori locali, occhio nascondi e mirino che rende visibile/inquadra, passaggio al filtro Lavis nella pagina Pozzetti, distanze e progressiva distinta.
- Selezione multipla dei due risultati filtrati, azioni Nascondi/Mostra su entrambi; calendario dello storico con applicazione e rimozione del filtro giorno.
- Scheda sotto asfalto: nessuna apertura né pulizia fittizia, soli controlli esterni. Tentativo con localizzazione disattivata conservato; nota ed eccezione GPS salvate. Dopo arresto forzato e riapertura offline, bozza e testi presenti. Registrazione offline riuscita con stato di coda, senza dichiarare una ricevuta remota.
- Riavvio completo dell'emulatore: confronto integrale prima/dopo di **14 ispezioni, 16 operazioni in outbox, 32 oggetti anagrafici e 6 audit**, con conteggi e hash identici.
- Risorse OSM già visitate visibili dopo arresto forzato e riapertura in modalità aereo. Database MapLibre osservato: **49 tile, 1060864 byte**; vecchia cache `osm-http` assente. Limite configurato 209715200 byte. **Non eseguito un test di saturazione/evizione a 200 MiB** e nessuna garanzia su aree mai visitate.

Schermate, copie private del database, backup v0.12 e log integrali rimangono in `local-output/verification/v0.13` e `.tools`, esclusi dal repository e dalla release. Nessun dato infrastrutturale reale o credenziale è allegato.

## Migrazioni, reset e configurazione

| Operazione | Preparata | Applicata localmente | Applicata al remoto | Verificata |
|---|---|---|---|---|
| Room 1→2 | Sì | Sì, emulatore | Non pertinente | Upgrade + test strumentale |
| SQL `202609220001_coll_pat_v013.sql` | Sì | Sì, DB isolati dei test | **No** | SQL/RLS locale |
| SQL `202609220002_coll_pat_spatial.sql` | Sì | Sì, PostGIS locale | **No** | Geometrie/lunghezze locali |
| Bootstrap Auth/membership e collegamento account legacy | Procedura privata pronta | Fixture nei test | **No** | Non collaudato con Supabase Auth reale |
| Migrazione anagrafica legacy | Script dry run pronto | Non eseguita sul dataset operativo | **No** | Nessuna migrazione dati remota dichiarata |
| Pulizia iniziale delle prove | Backup/identificazione/dry run pronti | Backup v0.12 effettuato, cancellazione **non eseguita** | **No** | Reset testato solo su fixture isolate |

Il collegamento PostgreSQL configurato ha restituito timeout. Non erano disponibili chiave client pubblica e sessione Supabase Auth per una prova Android→Auth→RPC sul progetto. Non è stata eseguita una cancellazione remota o locale indiscriminata per sostituire questa verifica. Le vecchie code non ricevono automaticamente la nuova generazione.

## Verifiche ancora necessarie prima dell'uso operativo

- Applicare/verificare le migrazioni nel progetto corretto, configurare Auth e ruolo amministratore; controllare dati anagrafici e collegamenti legacy, poi eseguire la pulizia iniziale una tantum con backup.
- Prova completa **Android→Supabase Auth→RPC** per ordinaria, esterna, impedimento, importazione, annullamenti, refresh della sessione scaduta e reset; verificare policy/limiti effettivamente installati. I test locali non sostituiscono questo passaggio.
- Collaudo su telefono fisico: GPS preciso/approssimativo, timeout e interruzione della richiesta, revoca permessi durante la scheda, process death imposto dal sistema e camera/galleria reali. L'emulatore non equivale al campo.
- Due telefoni reali con vecchie code durante reset; file GIS operativi autorizzati, memoria limitata e interruzione di importazione/invio grande nel percorso Android completo. Atomicità e staging sono collaudati a livello Room/SQL, non con interruzione di ogni fase dell'interfaccia.
- Prova estesa dei controlli multipli/calendario e accessibilità con lettore schermo; saturazione della cache secondo le policy del fornitore cartografico.

La variante demo abilita dati sintetici e dashboard locale admin/admin; **non disabilita gli invii strutturati**. Occorre accedere prima di creare lavoro destinato al server. Il caricamento foto rimane esplicitamente simulato. I limiti del parser GIS e i CRS supportati sono in [GIS.md](GIS.md).

## Artefatto

- File: `COLL-PAT-v0.13-demo.apk`, **62747867 byte**.
- SHA-256: `bf6f34b44cd38b5060bf51df3498f1da377bf05c37197f71ed72e41b6fb7000c`.
- Certificato storico SHA-256: `acf785391278fa98832980a51e594c88ffb06ff36b590c73c18c917f975080c6`.
- applicationId `it.pat.collettori.pilot.demo`, versionCode 13, versionName `0.13-demo`.
- Repository rinominato **Skyzzato/COLL-PAT**, stesso ID GitHub 1372629407. Tag `v0.13`, prerelease, senza sovrascrivere tag storici. La release espone il commit del tag e checksum allegato.
