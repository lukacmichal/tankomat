package sk.lukac.tankomat.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import sk.lukac.tankomat.data.repository.FuelRepository
import java.io.File

/**
 * Perzistencia posledného úspešného skenu na disk (JSON súbor v privátnom
 * úložisku appky). Vďaka tomu appka po otvorení hneď ukáže posledné známe
 * ceny — nové sa stiahnu až keď užívateľ ručne stlačí obnoviť.
 */
class SnapshotStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Null = zatiaľ žiadny sken (prvé spustenie) alebo nečitateľný súbor. */
    suspend fun load(): FuelRepository.Snapshot? = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            null
        } else {
            runCatching {
                json.decodeFromString(FuelRepository.Snapshot.serializer(), file.readText())
            }.getOrNull()
        }
    }

    suspend fun save(snapshot: FuelRepository.Snapshot): Unit = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(FuelRepository.Snapshot.serializer(), snapshot))
        }
        Unit
    }
}
