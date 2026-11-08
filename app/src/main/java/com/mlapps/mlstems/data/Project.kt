package com.mlapps.mlstems.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StemTrack(
    val name: String,
    val file: String,            // nome file dentro la cartella del progetto
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
)

@Serializable
data class Project(
    val id: String,
    val title: String,
    val sourceHash: String,      // SHA-256 del brano originale: evita di rielaborare lo stesso file
    val modelId: String,
    val stems: List<StemTrack>,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Ogni progetto è una cartella: project.json + un FLAC per ogni stem. */
class ProjectRepository(private val context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun list(): List<Project> =
        root.listFiles().orEmpty()
            .mapNotNull { runCatching { load(it.name) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun load(id: String): Project =
        json.decodeFromString(File(dir(id), "project.json").readText())

    fun save(project: Project) {
        File(dir(project.id), "project.json").writeText(json.encodeToString(project))
    }

    fun findByHash(hash: String, modelId: String): Project? =
        list().firstOrNull { it.sourceHash == hash && it.modelId == modelId }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        com.mlapps.mlstems.engine.StemCache.clear(context, id)
    }
}
