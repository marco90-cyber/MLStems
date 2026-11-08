# MLStems (bozza)

App Android per separare un brano in stem (voce, batteria, basso, altro, chitarra, pianoforte), tutto offline.

## Cosa c'è
- Catalogo modelli dal più veloce al più qualitativo (`data/StemModel.kt`), con download dentro l'app o import da file.
- Motore ONNX Runtime a blocchi con sovrapposizione (`engine/StemSeparator.kt`), in un foreground service (`SeparationService`).
- Progetti salvati su disco (cartella con `project.json` + WAV per stem). Il brano originale è riconosciuto via SHA-256: se è già stato elaborato con lo stesso modello il progetto si riapre subito.
- Mixer/player a più tracce con volume, mute, solo (`player/MultiStemPlayer.kt`).
- Esportazione mix e stem in `Download/MLStems`.
- UI Compose con Material You, solo schermate base.

## Build
- **Android Studio**: apri la cartella, sync Gradle, Run (minSdk 29).
- **GitHub Actions**: ogni push su `main` compila l'APK di debug (scarica `MLStems-debug-apk` dagli artifact).
  Con un tag `v*` (es. `v0.1`) l'APK viene anche allegato alla release.

## Da fare prima del primo avvio
1. **Modelli ONNX**: il codice assume modelli con forma d'onda in ingresso e in uscita
   (`[1,2,N]` → `[1,stem,2,N]`, STFT dentro il grafo). Va preparata l'esportazione dei modelli
   (Demucs ha una STFT che l'export ONNX standard non include) oppure scritto un adattatore per modello
   che implementi `StemSeparator` (Spleeter e MDX-Net lavorano su spettrogrammi).
2. **URL di download**: compilare `url` nel catalogo (es. release GitHub) e verificare licenze dei pesi.
3. Dettagli sui modelli in `docs/MODELLI.md`.

## Idee successive
- Stem salvati in FLAC invece di WAV (meno spazio: 6 stem WAV di 4 minuti ≈ 250 MB).
- Esecuzione con NNAPI/GPU, scelta del numero di thread.
- Playback in background con notifica media, loop A-B, velocità/tonalità.
- Tema condiviso MLTheme al posto di `Theme.kt`.
