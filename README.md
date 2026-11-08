# MLStems (bozza)

App Android per separare un brano in stem (voce, batteria, basso, altro, chitarra, pianoforte), tutto offline.

## Cosa c'è
- Catalogo modelli dal più veloce al più qualitativo (`data/StemModel.kt`), con download dentro l'app (servizio in background) o import da file.
- Motori Demucs e Spleeter nel modulo `core` (con test su CI), in un foreground service (`SeparationService`).
- Progetti salvati su disco (cartella con `project.json` + FLAC per stem). Il brano originale è riconosciuto via SHA-256: se è già stato elaborato con lo stesso modello il progetto si riapre subito.
- Mixer/player a più tracce con volume, mute, solo (`player/MultiStemPlayer.kt`).
- Esportazione mix e stem in `Download/MLStems`.
- UI Compose con Material You, solo schermate base.

## Build
- **Android Studio**: apri la cartella, sync Gradle, Run (minSdk 29).
- **GitHub Actions**: ogni push su `main` compila l'APK di debug (scarica `MLStems-debug-apk` dagli artifact).
  Con un tag `v*` (es. `v0.1`) l'APK viene anche allegato alla release.

## Provarla
1. Installa l'APK dalla pagina **Releases** (o dagli artifact di Actions).
2. Apri **Modelli** (icona in alto) e scarica un modello. Il download continua anche a schermo spento.
3. **Nuovo brano** → scegli il file → scegli il modello → Avvia. Puoi spegnere lo schermo: arriva una notifica a fine lavoro.
4. Nel mixer: volume, mute (M), solo (S) per ogni stem; export di mix o stem in `Download/MLStems`.

Tempi indicativi su un PC da CI (4 thread) per 15 s di audio: Spleeter 2,5 s, HT-Demucs 7,5 s, 6 stem 10 s, Fine-Tuned 40 s.
Su telefono aspettati tempi più lunghi (non ancora misurati). Dettagli in `docs/MODELLI.md`.

## Idee successive
- Esecuzione con NNAPI/GPU, scelta del numero di thread.
- Playback in background con notifica media, loop A-B, velocità/tonalità.
- Tema condiviso MLTheme al posto di `Theme.kt`.
