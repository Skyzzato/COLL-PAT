# Collettori v0.12 — prerelease

Corregge il crash immediato della v0.11 e introduce un'identità grafica coerente, conservando dataset, ispezioni e mappa ampia.

## Installazione

Scaricare **Collettori-v0.12-demo.apk** dalla [prerelease](https://github.com/Skyzzato/Collettori/releases/tag/v0.12). È la versione autonoma con dataset sintetico Trento, senza account o server. Android 8/API 26 o successivo; Google Play services per la localizzazione. OSM richiede rete.

Aggiornare sopra la demo precedente per conservare i dati; non disinstallare l'app. ApplicationId `it.pat.collettori.pilot.demo`, versionCode 12, versionName `0.12-demo`. Firma con la medesima chiave di test usata per le demo precedenti. Non è una release firmata con chiave produttiva.

SHA-256 APK: `32e5bfdf9e88d7423590dc93a64afea0757cc41b8101d0d877d47f67182034c8`.

## Modifiche

- MapLibre inizializzato prima della configurazione HTTP: risolta la catena `ExceptionInInitializerError` → `MapLibreConfigurationException` in `PilotApplication.onCreate`.
- Ripristinato INTERNET nella demo; lifecycle mappa senza doppia attivazione.
- GPS assente, negato o disattivato e accuratezza mancante gestiti esplicitamente.
- Testo dell'editor preservato durante l'autosalvataggio.
- Nome Collettori, tubo verde originale pixel art, adaptive icon e splash; pulsanti e icone operative coerenti senza ridurre la mappa.

**MIGRAZIONE NECESSARIA: NO.** Schema Room v1 invariato, nessuna cancellazione dati. Il pilota con server richiede il backend aggiornato che accetta app_version 0.12 e mantiene 0.1/0.11.

## Verifiche e limiti

Crash originale riprodotto su installazione pulita, aggiornamento, debug e release. Correzione collaudata su emulatore Android 35 con installazione, riavvio, permessi, rete/GPS spenti, selezione pozzetti, ispezione e foto. 42 test unitari Android e 5 test strumentali superati; build/lint completati, 0 errori e 28 warning documentati.

Nessun collaudo su telefono fisico/OEM o Android 8. Foto solo locali, export JSON senza immagini, cartografia di base offline non garantita. Dati sintetici: non utilizzare per interventi reali. Dettagli nel [report](VALIDATION-v0.12.md).

## Asset pubblicati

- APK demo: unico APK proposto per l'installazione.
- `SHA256SUMS.txt`: integrità degli allegati.
- `VALIDATION-v0.12.md`: report dettagliato.
- `Collettori-v0.12-evidenze-pubbliche.zip`: screenshot sintetici, stack trace applicativo e riepilogo test. I Logcat integrali e le build debug/release di verifica rimangono locali.

Codice applicativo collaudato nel commit `ae641b8`; il tag v0.12 include il successivo riordino della documentazione. Pubblicazione senza sovrascrivere i tag precedenti.
