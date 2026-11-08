package com.mlapps.mlstems.data

/** Famiglia del motore che esegue il modello. */
enum class Family { DEMUCS, SPLEETER4 }

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
    val family: Family,
)

/** Ordinati dal più veloce al più qualitativo. Le dimensioni sono indicative. */
object ModelCatalog {
    val all = listOf(
        StemModel(
            id = "spleeter-4", name = "Spleeter 4 stem",
            description = "Il più veloce. Voce, batteria, basso, altro. Qualità base.",
            stems = listOf("Voce", "Batteria", "Basso", "Altro"),
            sizeMb = 79, speed = 4, quality = 2,
            fileName = "spleeter4_vocals.onnx", family = Family.SPLEETER4,
        ),
        StemModel(
            id = "htdemucs", name = "HT-Demucs 4 stem",
            description = "Alta qualità. Batteria, basso, altro, voce.",
            stems = listOf("Batteria", "Basso", "Altro", "Voce"),
            sizeMb = 91, speed = 2, quality = 4,
            fileName = "htdemucs.onnx", family = Family.DEMUCS,
        ),
        StemModel(
            id = "htdemucs-6s", name = "HT-Demucs 6 stem",
            description = "Aggiunge chitarra e pianoforte. Un po' più lento.",
            stems = listOf("Batteria", "Basso", "Altro", "Voce", "Chitarra", "Pianoforte"),
            sizeMb = 110, speed = 2, quality = 4,
            fileName = "htdemucs_6s.onnx", family = Family.DEMUCS,
        ),
        StemModel(
            id = "htdemucs-ft", name = "HT-Demucs Fine-Tuned",
            description = "Qualità massima. Quattro reti in sequenza: il più pesante e lento.",
            stems = listOf("Batteria", "Basso", "Altro", "Voce"),
            sizeMb = 675, speed = 1, quality = 5,
            fileName = "htdemucs_ft.json", family = Family.DEMUCS,
        ),
    )

    fun byId(id: String) = all.first { it.id == id }
}
