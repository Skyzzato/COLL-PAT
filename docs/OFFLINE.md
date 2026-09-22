# Offline e sincronizzazione — v0.14

La sessione persistente consente di lavorare sui dati disponibili nel proprio archivio senza rete. I dati di altri account restano isolati. La demo è un archivio distinto, avviato esplicitamente dalla schermata iniziale.

Le bozze e le foto si salvano subito sul telefono. Per gli account autenticati il worker trasmette le modifiche appena possibile, conservando un payload immutabile per ogni tentativo. Una ricevuta valida chiude solo la modifica corrispondente; modifiche successive rimangono in attesa. Il pulsante Sincronizza effettua anche un aggiornamento del catalogo e del riepilogo ispezioni. Lo storico completo viene richiesto entrando nella pagina Ispezioni; l’export richiede soltanto il semestre.

Un conflitto di revisione non sostituisce i dati locali. La scheda spiega il problema e permette di conservare una copia di recupero prima di aprire la versione server. Dopo la risoluzione l’operatore decide come riportare le proprie modifiche: nessuna sovrascrittura automatica della bozza remota.

Le fotografie remote mostrano i metadati condivisi anche prima del download. Per vederle senza rete occorre averle aperte e memorizzate almeno una volta. Il nome Storage rimane lo stesso durante i retry. Originali locali e audit rimangono privati.

Le soglie GPS vengono incluse nella rilevazione. Modificare le preferenze non cambia la qualità storica. Il controllo completo richiede accuracy e distanza entro soglia, coordinate valide, permesso preciso, misura non simulata e non troppo vecchia all’acquisizione. L’impedimento può conservare un tentativo fallito con motivazione.

La mappa usa l’unica cache MapLibre preesistente da 200 MiB; non è un pacchetto cartografico garantito. Le aree mai caricate possono mancare. Nessun download preventivo dai server OSM standard.

Una policy server già nota di versione obsoleta blocca l’app anche offline. In assenza di rete e di una policy nota, la cache rimane utilizzabile. Dopo arresto forzato i lavori riprendono alla riapertura.

Le operazioni v0.13 ancora in attesa sono conservate nell’archivio di recupero, senza inventare autori o soglie GPS. [Documentazione v0.13](history/v0.13/OFFLINE.md).
