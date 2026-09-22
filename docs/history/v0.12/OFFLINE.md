> Documento del pilota originario: i riferimenti v0.1 e relativi collaudi sono storici. Per la demo corrente v0.11 consultare [README](../README.md) e [collaudo v0.11](VALIDATION-v0.11.md). Foto locali e nuova UX sono descritti lì.

# Protocollo offline e recupero

## Identità e sessione

Login online individuale. Password verificata con Argon2, mai salvata sul telefono. Token di accesso opaco (15 minuti), token di rinnovo opaco (30 giorni), nel server solo hash dei token. Rinnovo dell'accesso dopo un 401 quando la rete è disponibile; il token di rinnovo rimane stabile nella sessione, per consentire ritentativi se una risposta si perde. Logout dal portale revoca la sessione server; uscita offline dall'app elimina i token locali senza tentare operazioni che richiedono rete.

Abilitazione offline separata: `OFFLINE_HOURS`, inizialmente 72 ore **come ipotesi da approvare**. Allo scadere: consultazione e conservazione del lavoro già raccolto, possibilità di salvare modifiche alle bozze; avvio, nuove acquisizioni GPS e completamento richiedono rinnovo online. Il dispositivo scollegato non può ricevere una revoca immediata.

Il controllo locale usa scadenza e una soglia temporale massima già osservata per rilevare arretramenti grossolani dell'orologio; non è una difesa assoluta da dispositivi compromessi o manipolazioni del tempo. Un orologio incoerente richiede rinnovo.

I token sono cifrati AES-GCM con chiave Android Keystore. Room e pacchetti rimangono nello spazio privato dell'app, protetto dal sistema operativo e dalla cifratura del dispositivo; non è implementato un secondo motore SQLCipher. Il backup automatico Android dell'app è disabilitato. Bloccoschermo e gestione dei dispositivi devono essere verificati prima del pilota reale.

L'archivio locale è partizionato per URL server e UUID utente. Cambio account non trasferisce, non visualizza e non invia dati dell'account precedente. Il dispositivo ha un UUID applicativo, non IMEI.

## Bozze e completamento

`Avvia controllo` inserisce la bozza con UUID **prima** della localizzazione. Le risposte vengono salvate in Room; il messaggio di successo segue la scrittura. Durante `Salvataggio…` non viene promesso che l'ultima modifica sia già durevole.

Il completamento inserisce, in un'unica transazione, la versione della visita e un'operazione outbox immutabile. Il payload contiene gli eventi originari; rete, worker e pacchetti non modificano coordinate e orari. Riavvio e chiusura forzata lasciano disponibili le scritture confermate. Spazio insufficiente causa un errore visibile: non viene dichiarato un salvataggio inesistente.

Quattro dimensioni distinte:

| Dimensione | Stati |
|---|---|
| Operativa | BOZZA, COMPLETO (dichiarato), PARZIALE, IMPEDITO |
| GPS | COMPATIBILE, INCERTA, NON_COMPATIBILE, NON_DISPONIBILE |
| Invio | SALVATO_LOCALMENTE, IN_ATTESA, INVIO_IN_CORSO, RICEVUTO_SERVER, ERRORE |
| Revisione documentale | NON_ESAMINATA, VERIFICATA_DOCUMENTALMENTE, INTEGRAZIONE_RICHIESTA |

Una scheda completa può avere GPS incerto; una visita impedita può avere GPS compatibile. `RICEVUTO_SERVER` non significa verificato documentalmente.

## Invio

WorkManager: lavoro singolo su completamento/avvio/comando manuale, rete richiesta, ritentativo esponenziale; lavoro periodico di supporto ogni 15 minuti. Il sistema Android decide quando eseguire: nessuna promessa di invio immediato in background. Nessun permesso di localizzazione in background.

L'invio usa `POST /api/sync`:

```json
{"operation_id":"UUID","inspection":{"id":"UUID","revision":1,"...":"schema OpenAPI"}}
```

Il server valida autore, dispositivo, ambito, versione cartografica, collegamenti degli eventi, regola e scheda. Nella transazione registra visita, revisione, eventi, anomalie e ricevuta. Un vincolo univoco sull'UUID operazione e il digest SHA-256 della rappresentazione canonica impediscono duplicati:

- stesso UUID + stesso payload validato: stessa ricevuta;
- stesso UUID + contenuto diverso: 409;
- revisione concorrente/storico incoerente: 409;
- errore di contenuto: 422;
- accesso scaduto: tentativo di rinnovo, poi sospensione con dati conservati;
- errore transitorio o risposta persa: stesso UUID e stessi byte logici al ritentativo.

L'app elimina dalla coda solo dopo aver verificato identificativo operazione e revisione nella ricevuta. Una coda in invio viene ripresa dopo riavvio. Due worker locali sono serializzati; il server rimane idempotente anche per richieste concorrenti.

## Conflitti, rettifiche e recupero

409/403/422 bloccano quella operazione e mostrano il motivo: nessun ciclo infinito, nessuna fusione o sovrascrittura silenziosa. Le altre operazioni possono proseguire. Non è implementato un editor generico per risolvere automaticamente conflitti: serve il referente.

Un 401 non risolto dal rinnovo mette l'operazione in attesa di autenticazione; il nuovo login dello stesso account riabilita gli invii. Dopo una correzione amministrativa o recupero controllato, **Account → Riprova invii dopo verifica del referente** riaccoda gli stessi payload e UUID, senza alterarli. Un conflitto ancora presente resta un conflitto.

Per correggere un controllo ricevuto: aprirlo e scegliere **Crea revisione motivata**. L'app conserva la copia precedente e invia la nuova revisione con motivo. Gli eventi, il manufatto, la versione cartografica, l'inizio e il completamento originario restano immutabili; il nuovo completamento è `revised_at`. Le revisioni del server e le verifiche documentali rimangono separate. Per correggere una visita attribuita al manufatto sbagliato: integrazione documentale e nuova visita corretta, senza trasferire il vecchio evento GPS a un altro pozzetto.

Recupero amministrativo dei dati non trasmessi:

1. Non disinstallare/cancellare l'app, non azzerare la coda e preservare il dispositivo.
2. Accedere nuovamente allo stesso server e stesso UUID account; il nome utente deve corrispondere all'account originale, non a uno ricreato.
3. Se l'account è revocato, l'amministratore può riabilitarlo con motivazione tracciata mediante `/api/users/{id}/enable`, per la finestra controllata di recupero; rinnovare i permessi territoriali necessari secondo autorizzazione.
4. Per guasti/401 ripristinare connessione/sessione e usare `Sincronizza ora`.
5. Per 409/422 il referente esamina errore, payload e storico: conservare la registrazione locale; la correzione deve mantenere una traccia dell'originale. Il tool amministrativo di recupero da file è descritto nelle operazioni del server.

Non si promette recupero di un telefono fisicamente perso, di dati cancellati dall'utente o di chiavi Keystore perdute. Il pilota deve verificare tempi e frequenza di trasmissione appropriati.

## Aggiornamenti

Room v1 esporta lo schema; non è abilitata `fallbackToDestructiveMigration`. Un aggiornamento futuro richiede migrazione esplicita e test prima di aumentare la versione. I pacchetti territoriali sono inserti versionati indipendenti da visite/outbox; la nuova versione viene installata solo dopo verifiche. Nella v0.1 non si elimina automaticamente nessuna versione locale.

Lo storico personale può essere riscaricato insieme all'area. La sincronizzazione di storico non sostituisce bozze o registrazioni locali pendenti. Per tutela dei ruoli, il telefono scarica lo storico dell'account, il portale autorizzato può vedere tutte le visite dell'ambito.
