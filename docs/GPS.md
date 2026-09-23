# GPS — COLL-PAT v0.2

## Aggiornamento v0.21

La v0.21 usa SPHERICAL_MEAN_MEAN_ACCURACY_V2: 3 secondi esclusi, almeno 5 utili/3 campioni, media aritmetica accuracy, motivazione senza coordinate in alternativa al rilievo. Le soglie nuove sono discrete fino a 150/30 metri; il controllo conservativo di corrispondenza resta distinto. Il contratto precedente descritto sotto resta valido solo per gli eventi legacy. [Requisiti correnti](REQUIREMENTS-v0.21.md).

## Documentazione delle versioni precedenti

La v0.2 conserva il contratto GPS introdotto nella v0.15 e già presente nella v0.16.

Le impostazioni esistenti `FieldSettings.maxAccuracy/maxDistance` sono l’unica fonte delle soglie operative. La regola gps-1 fornisce gli altri parametri; ogni evento conserva le soglie applicate. Le vecchie registrazioni mantengono i loro esiti, senza riclassificazione automatica.

Prima della registrazione si controllano permesso preciso, servizio attivo, misura con timestamp monotono recente (massimo 10 secondi), coordinate e accuracy valide. Accuracy maggiore della soglia blocca prima del conto alla rovescia, senza evento/outbox; l’uguaglianza è ammessa. Se manca una misura recente si richiede un aggiornamento corrente e si invita a riprovare: questo aggiornamento non registra eventi.

La finestra dura cinque secondi (`elapsedRealtimeNanos`). Gli aggiornamenti aggiuntivi chiedono frequenza di circa un secondo; callback, duplicati o misure fuori ordine non equivalgono a nuovi campioni. Occorrono almeno tre misure distinte, span almeno due secondi e ultimo campione al massimo due secondi dalla conclusione. Accuratezza peggiorata oltre soglia interrompe il tentativo. Annullamento, background, cambio modello o uscita eliminano il tentativo pendente; le callback aggiuntive vengono rimosse in `finally`. Le condizioni sono centralizzate in AcquisitionPolicy/GpsWindow, con clock/campioni simulabili nei test.

Media spaziale dei vettori unitari sulla sfera geografica, coerente con la distanza haversine già adottata (raggio 6371008,8 m). L’indicatore di accuratezza è il massimo dei campioni, senza divisione per N o √N. `dispersion_m` è la distanza massima dei campioni dalla media; non è accuracy. Nessuno dei due valori è una stima statistica certificata. Le coordinate cartografiche non vengono cambiate.

La media viene valutata con GpsRule: distanza d, accuracy a, incertezza cartografica g e soglia R. `d+a+g <= R` è compatibile; `d-a-g > R` non compatibile; margine, incertezza sconosciuta o più manufatti entro R richiedono verifica. Il server ricalcola sul catalogo attivo e sulle soglie dell’evento; un disaccordo blocca l’invio e conserva la copia locale.

Corrispondenza verificata: un solo evento definitivo. Altrimenti il dialogo consente annullamento oppure Continua con eccezione. La seconda azione porta scroll, focus e tastiera all’unico campo Eccezione GPS; non salva. Una motivazione non vuota e una seconda conferma registrano l’evento con `match_outcome=EXCEPTION`. L’accuratezza oltre soglia non è derogabile. Nessun riuso per altre ispezioni, nessun cambio automatico del pozzetto.

JSON evento conservato in Room/outbox/server: UUID, ispezione, pozzetto, utente, dispositivo, inizio/fine UTC e monotoni, numero e span dei campioni, coordinate medie, accuracy massima, dispersione, distanza/esito, parametri, metodo `SPHERICAL_MEAN_MAX_ACCURACY_V1` e motivazione. Le card mostrano in rosso «Corrispondenza non verificata — eccezione motivata», distinta dal vecchio GPS impreciso/non affidabile.

Bozze e foto condivise non richiedono una nuova rilevazione. Un impedimento senza GPS registra il motivo operativo senza inventare un evento di posizione fallito. Le vecchie evidenze fallite rimangono nello storico. Orologio civile e dichiarazioni dell’operatore non sono certificati; nessuna prova GPS su telefono fisico è implicita nei test simulati.
