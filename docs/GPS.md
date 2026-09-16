# Regola GPS sperimentale

Fonte iniziale unica: `shared/gps-rule.json`. Il bootstrap la pubblica nel database. L'app scarica la versione ufficiale dal catalogo; i valori non sono liberamente modificabili dall'operaio. Solo un amministratore può pubblicare una nuova versione tramite API/portale. Le vecchie versioni restano immutabili.

| Parametro | Valore iniziale |
|---|---:|
| Raggio operativo R | 20 m |
| Accuratezza dispositivo massima | 15 m |
| Età massima | 10 s |
| Timeout richiesta | 45 s |
| Incertezza cartografica massima | 10 m |

Valori di prova, non soglie contrattuali o scientificamente certificate.

## Acquisizione

`CurrentLocationRequest` con priorità alta, `maxUpdateAgeMillis=0`, durata 45 s e cancellazione. Una singola richiesta corrente con controllo ulteriore dell'età calcolata da `elapsedRealtimeNanos`; non si usa l'ultima posizione nota, né geolocalizzazione IP. L'app conserva il risultato della richiesta, non sceglie la misura più vicina al manufatto. La sincronizzazione non chiama il componente di localizzazione.

Ogni richiesta crea un evento nuovo, anche in caso di permesso negato, servizi disattivati, timeout o assenza del risultato. Il flag simulazione può essere `null`: ciò non equivale a un'attestazione di autenticità. Richiedere alta accuratezza non garantisce che il telefono la ottenga.

## Valutazione riproducibile

Distanza geodetica sferica con formula haversine e raggio medio terrestre `6371008.8 m`, identici in Python e Kotlin. È un'approssimazione dichiarata della distanza terrestre, da valutare nel pilota; non vengono mescolati algoritmi ellissoidali e sferici tra client e server.

1. Coordinate assenti, errore di acquisizione, età assente/negativa o oltre 10 s: `NON_DISPONIBILE`.
2. Permesso approssimativo, accuratezza assente/oltre limite, g sconosciuta/oltre limite, flag mock positivo o ambiguità: `INCERTA`. La distanza viene mantenuta quando calcolabile.
3. Con prerequisiti validi: `d+a+g <= R` → `COMPATIBILE`; `d-a-g > R` → `NON_COMPATIBILE`; altrimenti `INCERTA`.

Ambiguità della regola gps-1: più di un pozzetto della **stessa versione territoriale** entro R dalla misura. È un criterio iniziale riproducibile, non una stima universale dell'ambiguità; verificare i confini delle aree e i manufatti adiacenti nel pilota. Le aree vanno distribuite con adeguato contesto, evitando tagli fra manufatti vicini.

La somma dei margini è una regola prudenziale operativa, non un intervallo di confidenza. g non nota rimane null. GPS debole non aumenta automaticamente R.

Ogni evento conserva dati originari e valutazione locale con parametri. Il server usa la copia ufficiale della **versione del dataset dichiarata dalla visita**, ricalcola distanza/ambiguità/esito con la versione della regola registrata e conserva entrambi gli esiti. Non sostituisce il dato storico con l'ultima anagrafica. L'ultima acquisizione esplicita della visita determina il riscontro riepilogativo; gli eventi precedenti rimangono visibili.

## Orari e limiti

Orari del dispositivo: richiesta, acquisizione, apertura bozza, completamento originario ed eventuale completamento della revisione. Ricezione: orario server distinto. Timestamp ISO 8601 con offset/UTC; presentazione Europe/Rome. L'orologio offline non è certificato. Il riferimento monotono permette di misurare l'età della localizzazione, non certifica la data civile.

Posizioni simulate non segnalate, credenziali condivise, dispositivi compromessi e dichiarazioni non veritiere rimangono rischi residui. Hash del pacchetto e delle operazioni verificano integrità/idempotenza, non veridicità dell'attività. Nessuna penale, accusa, controllo tecnico certificato o apertura verificata è derivata dal GPS.
