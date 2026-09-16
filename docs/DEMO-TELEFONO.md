# Collettori Demo — prova autonoma sul telefono

Questa variante Android funziona senza PC, server, login o connessione Internet.
Si installa come **Collettori Demo**, accanto al pilota esistente: ha un diverso
identificativo Android (`it.pat.collettori.pilot.demo`) e archivi privati separati.

## Uso

1. Scaricare e installare l'APK demo sul telefono Android (minimo Android 8).
2. Aprire **Collettori Demo**: i dati sintetici vengono preparati automaticamente.
3. In **Pozzetti** selezionare un manufatto, poi **Avvia controllo**.
4. Concedere la posizione per provare il GPS reale, oppure negarla per verificare
   la gestione dell'assenza di posizione. Compilare e salvare la scheda.
5. In **Controlli** riaprire bozze e schede, oppure creare una revisione motivata.
6. Da **Account → Esporta controlli demo** salvare una copia JSON delle schede correnti.

Sono inclusi 16 pozzetti, 15 tratti, una base cartografica sintetica e i font della
mappa. I manufatti si trovano nell'area di Trento ma **non rappresentano opere reali**.
Se ci si trova altrove, la verifica GPS può risultare incompatibile: è previsto.
Per concludere la scheda di prova, inserire una motivazione nell'eccezione GPS.
Per una prova semplice, dichiarare mancata apertura con motivo "Prova demo" e
concludere come parziale. Nessuna apertura fisica è necessaria per provare l'app.

## Limiti

- Nessuna sincronizzazione, Supabase, ricevuta server, verifica amministrativa
  o rapporto trimestrale. Le prove non confluiscono nell'app operativa.
- GPS reale su richiesta, tramite Google Play services; la disponibilità dipende
  da dispositivo, permessi e ricezione satellitare. Nessuna posizione simulata.
- L'APK demo non dichiara il permesso INTERNET. Tutte le risorse della mappa
  sono incluse; nessuna mappa stradale reale viene scaricata.
- Le schede restano sul telefono dopo la chiusura dell'app. Disinstallazione,
  cancellazione dati o perdita del dispositivo possono eliminarle.
- L'esportazione è marcata `collettori-demo-1` e non è un file di recupero
  operativo importabile nel backend. Può contenere coordinate GPS reali.
- Build di prova firmata con chiave debug: non è una distribuzione produttiva.

## Compilazione

```powershell
.\.venv\Scripts\python.exe scripts\build_demo_asset.py
powershell -File scripts\build-android.ps1 -Demo
```

Oppure da `android`: `gradlew.bat :app:assembleDemo :app:testDemoUnitTest`.
APK generato: `android/app/build-pilot/outputs/apk/demo/app-demo.apk`.
La build debug normale conserva il comportamento con login e backend.
