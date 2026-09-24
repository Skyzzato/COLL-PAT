# COLL-PAT v0.22 — prerelease

Ereditarietà dell'aspetto aggiornata senza perdere la posizione della mappa; collegamenti espliciti fra pozzetti con tronchi schematici persistenti e retry senza duplicati. Il posizionamento mostra il collettore locale e una bandierina indipendente.

Card compatte, dettagli richiudibili, importazione lazy, filtri unificati, Slider GPS e portrait. CSV per ultimo trimestre civile concluso, anno o intero storico, con recupero paginato e marcatura esplicita dell'export solo locale.

Tre ruoli coerenti, simulazione senza nuove scritture e gestione utenti applicativi online con audit e protezione dell'ultimo amministratore. Propagazione corretta della cancellazione coroutine e salvataggi durevoli. Dataset sintetico Barbaniga di 5 km caricabile solo su conferma amministrativa.

- APK: **COLL-PAT-v0.22.apk**, versionName **0.22**, versionCode **22**.
- Application ID `it.pat.collettori.pilot`; stessa firma, aggiornamento dalla v0.21 senza disinstallazione e Room 3 invariata.
- SHA-256: `3f50e1ba4745b38dc3d763b04746c0a1102b18499bc842d54e5f570b7f760e51`.
- Migrazione incrementale **010 applicata e verificata** sul progetto Supabase Collettori. Nessun reset/seed/cambio ruolo operativo eseguito; minimo supportato **0.14** conservato.
- Test: 116 JVM; suite Python completa 125 superati, poi SQL v0.22 8 superati con un caso aggiuntivo; 25 scenari strumentati superati su emulatore e aggiornamento v0.21 → v0.22. [Dettagli e limiti](https://github.com/Skyzzato/COLL-PAT/blob/v0.22/docs/VALIDATION-v0.22.md).

La verifica dei tre ruoli sul database è locale; nessun login end-to-end remoto con tre account. Tablet/pieghevoli e ritorno da app fotocamera/file di produttori diversi non verificati fisicamente. Android 16 può ignorare portrait su grandi schermi: target 36 conservato, nessun blocco universale dichiarato.

[Resoconto, matrice ruoli e dataset](https://github.com/Skyzzato/COLL-PAT/blob/v0.22/docs/REPORT-v0.22.md) · [Migrazione/deployment](https://github.com/Skyzzato/COLL-PAT/blob/v0.22/docs/MIGRATIONS-v0.22.md).
