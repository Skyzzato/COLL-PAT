# Collettori v0.11 — pre-release

- Nuova UX demo: Mappa, Pozzetti, Ispezioni, Altro; scheda pozzetto a comparsa.
- Mappa ampliata con MapLibre Native e OpenStreetMap online, attribuzione e posizione/accuratezza.
- Collettore sintetico a Trento, 10 pozzetti PZ-001…PZ-010; nessun dato infrastrutturale PAT.
- Ispezione con riepilogo, valori regolari preimpostati, anomalie evidenziate, note, bozze e revisioni.
- Foto reali da fotocamera/galleria, miniatura e rimozione, salvataggio locale. Upload non configurato.
- Predisposizione software NFC/HF, RFID esterno e QR; nessuna scansione simulata.
- Candidati GPS basati su distanza, precisione e freschezza; conferma manuale necessaria.
- Documentazione architettura, modello dati, analisi funzionale e roadmap.
- MIGRAZIONE SQL NECESSARIA: NO. Aggiornare l'API pilota per accettare app_version 0.11.

APK demo installabile firmato con chiave debug, separato dal pilota. Collaudo fisico ancora richiesto: nessun dispositivo/emulatore disponibile nell'ambiente. OSM richiede rete, fotografie ed ispezioni rimangono locali. Export JSON non include file fotografici. Non usare dati sintetici per interventi reali.
