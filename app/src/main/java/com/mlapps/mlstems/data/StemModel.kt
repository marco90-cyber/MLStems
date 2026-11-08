package com.mlapps.mlstems.data

/**
 * Descrizione di un modello di separazione.
 * speed / quality: da 1 (peggiore) a 5 (migliore), usati nella lista di scelta.
 * I file da scaricare (indirizzi, checksum, versione) stanno in models.json nel repository.
 */
data class StemModel(
    val id: String,
    val name: String,
    val description: String,
    val stems: List<String>,
    val sizeMb: Int,
    val speed: Int,
    val quality: Int,
    val fileName: String,
    val segmentSamples: Int = 343_980,   // lunghezza blocco (htdemucs: 7,8 s a 44,1 kHz)
    val overlap: Float = 0.25f,
    val normalize: Boolean = true,
)

/** Ordinati dal più veloce al più qualitativo. Le dimensioni sono indicative. */
object ModelCatalog {
    val all = listOf(
        StemModel(
            id = "spleeter-2", name = "Spleeter 2 stem",
            description = "Velocissimo. Voce / strumentale, qualità base.",
            stems = listOf("Voce", "Strumentale"),
            sizeMb = 40, speed = 5, quality = 2,
            fileName = "spleeter2.onnx", segmentSamples = 441_000,
        ),
        StemModel(
            id = "spleeter-4", name = "Spleeter 4 stem",
            description = "Veloce. Voce, batteria, basso, altro.",
            stems = listOf("Voce", "Batteria", "Basso", "Altro"),
            sizeMb = 79, speed = 4, quality = 2,
            fileName = "spleeter4_vocals.onnx", segmentSamples = 441_000,
        ),
        StemModel(
            id = "mdx-net", name = "MDX-Net",
            description = "Buon compromesso. Voce / strumentale pulito.",
            stems = listOf("Voce", "Strumentale"),
            sizeMb = 70, speed = 3, quality = 3,
            fileName = "mdxnet.onnx", segmentSamples = 352_800,
        ),
        StemModel(
            id = "htdemucs", name = "HT-Demucs 4 stem",
            description = "Alta qualità. Batteria, basso, altro, voce.",
            stems = listOf("Batteria", "Basso", "Altro", "Voce"),
            sizeMb = 91, speed = 2, quality = 4,
            fileName = "htdemucs.onnx",
        ),
        StemModel(
            id = "htdemucs-6s", name = "HT-Demucs 6 stem",
            description = "Aggiunge chitarra e pianoforte. Più lento.",
            stems = listOf("Batteria", "Basso", "Altro", "Voce", "Chitarra", "Pianoforte"),
            sizeMb = 110, speed = 2, quality = 4,
            fileName = "htdemucs_6s.onnx",
        ),
        StemModel(
            id = "htdemucs-ft", name = "HT-Demucs Fine-Tuned",
            description = "Qualità massima. Il più pesante e lento.",
            stems = listOf("Batteria", "Basso", "Altro", "Voce"),
            sizeMb = 675, speed = 1, quality = 5,
            fileName = "htdemucs_ft.json",
        ),
    )

    fun byId(id: String) = all.first { it.id == id }
}
