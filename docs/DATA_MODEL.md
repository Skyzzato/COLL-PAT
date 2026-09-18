# Modello dati — v0.11

| Entità logica | Implementazione attuale | Relazioni |
|---|---|---|
| Collector | collectors nel dataset JSON; segmenti GeoJSON | uno → molti pozzetti/segmenti |
| Manhole | points: UUID, code, latitude, longitude, chainage_m, state, last_inspection, collector_id | ID stabile indipendente dal tag |
| Inspection | Room Visit, body JSON, owner, datasetId, revision, stato, ricevuta | pozzetto → molte visite; dataset storico conservato |
| InspectionPhoto | data class e settings photos:inspectionId, file privato | photoId, inspectionId, manholeId, localUri, remoteUrl nullable, uploadStatus LOCAL_ONLY, createdAt |
| IdentificationEvent | settings identification:inspectionId | metodo GPS, conferma, manholeId, evento GPS con precisione/timestamp e risultato |
| TagAssociation | data class; array tag_associations nel punto demo, inizialmente vuoto | uid, type, associatedAt, status, replacedBy; non sostituisce manholeId |
| Operator | SessionStore + owner, user_id nella visita | operatore demo fisso; identità individuale nel pilota |

Lo stato/ultima ispezione del dataset sono iniziali. L'interfaccia deriva l'ultima registrazione dalle visite locali, senza riscrivere il pacchetto immutabile. I tag possono essere dismessi e sostituiti preservando UID precedenti e ID logico. La loro associazione sarà amministrativa, separata dalla scheda di controllo.

Le coordinate dell'operatore e accuracy_m stanno nell'evento originale della visita; coordinate del manufatto nel pacchetto. Le foto sono entità distinte, non base64 nel JSON delle ispezioni. Metadati owner-scoped nei settings Room esistenti; creazione/rimozione protetta da transazione e controllo bozza. Snapshot metadati foto per revisione. La UI mostra allegati della revisione corrente; consultazione dei media delle vecchie revisioni rinviata.

**MIGRAZIONE SQL NECESSARIA: NO.** Nessuna modifica a Room v1 o schema SQL backend/Supabase. Il contenitore settings già esiste e i nuovi campi del pacchetto sono JSON. Nessun seed della rete fittizia nel database server: solo asset demo. Non eseguire SQL nuovo per questa versione. Su un server nuovo applicare soltanto le migrazioni iniziali previste dal deployment scelto.
