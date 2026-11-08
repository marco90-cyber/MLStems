# Modelli di MLStems

I modelli non stanno nel codice: l'app li scarica la prima volta (Modelli → Scarica) e li tiene sul dispositivo.

## Da dove arrivano
- **Prima scelta:** Hugging Face (vedi `tools/sources.json`).
- **Riserva:** copia nelle Releases di questo repository (`models-v1`).
- `models.json` elenca file, versione, dimensione e SHA-256; l'app controlla il checksum e mostra "Aggiorna" quando la versione sale.
- Per rigenerare copie e `models.json`: workflow **Copia modelli** (Actions → Run workflow). Per aggiungere un modello basta inserirlo in `tools/sources.json`.

## Modelli disponibili
| Modello | Stem | Peso | Note |
|---|---|---|---|
| Spleeter 4 stem | voce, batteria, basso, altro | ~79 MB | il più veloce, qualità base |
| HT-Demucs 4 stem | batteria, basso, altro, voce | ~91 MB | alta qualità |
| HT-Demucs 6 stem | + chitarra, pianoforte | ~110 MB | |
| HT-Demucs Fine-Tuned | batteria, basso, altro, voce | ~675 MB | qualità massima, 4 reti in sequenza |

## Come funziona il motore
Modulo `core` (Kotlin puro, testabile su PC):
- **Demucs:** i modelli ONNX contengono solo la rete; la STFT e la sua inversa sono in `DemucsSpectrogram`,
  identiche a `HTDemucs._spec/_ispec`. Brano a blocchi di 7,8 s con 25% di sovrapposizione, dissolvenza triangolare,
  normalizzazione globale come `demucs.apply_model`. Il Fine-Tuned esegue una rete alla volta e prende da ognuna il proprio stem.
- **Spleeter:** quattro U-Net su spettrogramma di ampiezza (STFT 4096/1024), maschera soft-ratio e maschera estesa alle alte
  frequenze con la media per frame. Le quattro maschere sommano a 1.
- Gli stem vengono scritti su disco man mano che sono pronti: la memoria non cresce con la durata del brano.

## Verifica
Workflow **Verifica motore**: scarica il modello vero dalle Releases e controlla su un brano sintetico di 15 s che
la somma degli stem ricostruisca il mix (Spleeter ~144 dB, HT-Demucs 4 stem ~30 dB, 6 stem ~21 dB, Fine-Tuned ~20 dB).

## Licenze
Verificare prima di distribuire l'app: i repository sorgente dichiarano MIT (Demucs) e Apache-2.0 / MIT (Spleeter),
ma alcune descrizioni parlano di pesi non commerciali.
