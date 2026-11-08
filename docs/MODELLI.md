# Modelli di MLStems

I modelli non stanno nel repository (sono grandi): si scaricano dentro l'app oppure si importano da file.

## Formato richiesto
File `.onnx` con
- ingresso: `[1, 2, N]` forma d'onda stereo a 44,1 kHz
- uscita: `[1, stem, 2, N]` una forma d'onda per stem, nell'ordine di `data/StemModel.kt`

`N` è `segmentSamples` del modello (HT-Demucs: 343.980).

## Come collegare i modelli
1. Prepara/esporta i `.onnx` (la STFT deve stare dentro il grafo, oppure serve un adattatore per modello
   che implementi `StemSeparator`, come per Spleeter e MDX-Net che usano spettrogrammi).
2. Caricali come asset di una release di questo repository (Releases → New release → allega i file).
3. Copia i link diretti nel campo `url` di ogni modello in `StemModel.kt`.
4. Controlla le licenze dei pesi prima di distribuirli.

I nomi dei file attesi sono nel campo `fileName` del catalogo (es. `htdemucs.onnx`).
