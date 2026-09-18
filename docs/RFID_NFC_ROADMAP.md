# RFID / NFC — v0.11

Obiettivo: tag fisico come metodo principale, risoluzione verso UUID pozzetto e confronto con GPS/precisione. Nessuna lettura NFC/RFID è implementata o simulata nella demo.

NFC lavora a 13,56 MHz e appartiene alla famiglia HF RFID. Android con chip NFC può leggere tecnologie supportate dal dispositivo (ad esempio NfcA/NfcB/NfcV e NDEF); non ogni telefono ha NFC e non ogni tag HF è interoperabile. RFID UHF/EPC Gen2 o LF richiede normalmente un lettore esterno e relativo SDK/Bluetooth/USB. Un telefono NFC non sostituisce un lettore UHF.

Valutare tag NFC on-metal idonei a chiusini metallici, acqua e impatti, con fissaggio resistente e codice leggibile. NDEF con ID applicativo evita di dipendere solo da UID fisici; UID non è una credenziale e può non essere stabile/autentico. Testare distanza di lettura e affidabilità sul materiale reale prima di acquistare lotti.

## Predisposto

- ManholeIdentificationService, IdentificationMethod (GPS, NFC_HF, EXTERNAL_RFID, QR, MANUAL), risultato con candidati e distanze.
- TagAssociation separata dall'ID pozzetto: UID, tipo, data, stato, riferimento di sostituzione; array nel dataset.
- GPS con precisione/età, confronto e conferma operatore; eventi persistiti.
- Selezione manuale di riserva e vecchi contratti FUTURE_QR/FUTURE_NFC mantenuti.

## Da implementare

1. Scelta e prova hardware/tag; controllo disponibilità NFC e reader mode Android.
2. Decoder NDEF/UID e resolver verso database. Tag sconosciuto → nessuna associazione automatica.
3. Procedura tecnica autenticata di associazione/sostituzione: dismettere vecchia relazione, creare nuova, conservare audit e UUID.
4. Validazione GPS tenendo conto della precisione: incoerenza significativa quando distanza supera tolleranza operativa più incertezza GPS/cartografica; precisione assente → verifica, mai conferma automatica.
5. QR come fallback; hardware RFID esterno solo se richiesto dal contesto. Nessun pulsante che dichiari letture mai avvenute.
6. API, controllo unicità tag attivo, conflitti offline, collaudo sul campo.

Fonti: [Android NFC overview](https://developer.android.com/develop/connectivity/nfc), [tecnologie tag](https://developer.android.com/develop/connectivity/nfc/advanced-nfc). La compatibilità va verificata sul modello smartphone e tag scelti.
