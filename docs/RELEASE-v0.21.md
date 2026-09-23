# COLL-PAT v0.21 — prerelease

Aggiornamento Android della v0.2: eliminazione definitiva con anteprima e coda, reimportazione con nuovi UUID, collegamenti ordinati, aspetto per oggetto, coordinate su mappa, GPS con stabilizzazione/media e mancato rilievo motivato, note unificate, condizione sotto asfalto persistente e coda di sincronizzazione consultabile.

**Il server remoto non espone ancora le nuove RPC (404/PGRST202). Le migrazioni 008 e 009 e la pulizia degli archivi non sono state eseguite sul progetto remoto per mancanza di privilegi amministrativi. Applicare le istruzioni allegate prima del collaudo operativo; la versione annunciata dal server resta 0.2.**

- VersionName 0.21, versionCode 21, application ID `it.pat.collettori.pilot`; stessa firma della v0.2, Room 3 invariata.
- 108 test Kotlin, 117 test backend/SQL nella suite completa e 16 test SQL v0.21 dopo gli ultimi controlli; 35 test Android, con ripetizione mirata delle 9 prove di cancellazione dopo l'ultima modifica dei conteggi. Lint e build superati.
- Upgrade -r ha conservato 3 righe catalogo, 24 visite, 39 operazioni in coda, 12 audit e 6 fotografie; installazione pulita verificata su AVD separato.
- Rapporti di collaudo ed ergonomia allegati, con screenshot sintetici. Non provati GPS sul campo, due telefoni fisici, upload Storage remoto o il percorso completo del picker import/cancella/reimport.

Il pacchetto `MIGRATIONS-v0.21.zip` contiene le due migrazioni, la procedura amministrativa e lo script di manutenzione con anteprima read-only. `UX-SCREENSHOTS-v0.21.zip` contiene solo dati sintetici. I test controllati non equivalgono a prove sul deployment remoto.

APK SHA-256: `11fe3ac6b2ec64cc5c7c5ed85eff27b4c9b446a3727ea38b247208693d0a98c9`.

Consulta VALIDATION-v0.21.md per i risultati, i limiti e la diagnosi; UX-REVIEW-v0.21.md per i problemi corretti e le proposte successive.
