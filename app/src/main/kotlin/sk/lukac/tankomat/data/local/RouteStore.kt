package sk.lukac.tankomat.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import sk.lukac.tankomat.data.model.Gps
import java.io.File

/**
 * Trasy k pumpám na disku (JSON v privátnom úložisku): kľúč „domov>pumpa"
 * (súradnice na 5 desatinných miest) → km po ceste.
 *
 * Bez expirácie — cesty sa nemenia. Keď sa pumpa presunie alebo sa zmení
 * domov, zmení sa kľúč a trasa sa spýta nanovo.
 */
class RouteStore(private val file: File) {

    private val serializer = MapSerializer(String.serializer(), Double.serializer())

    /** Prázdna mapa = zatiaľ nič (alebo nečitateľný súbor — trasy sa spýtajú znova). */
    suspend fun nacitaj(): Map<String, Double> = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            emptyMap()
        } else {
            runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
        }
    }

    suspend fun uloz(trasy: Map<String, Double>): Unit = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(serializer, trasy))
        }
        Unit
    }

    companion object {
        fun kluc(odkial: Gps.Bod, kam: Gps.Bod): String = "${odkial.kluc}>${kam.kluc}"
    }
}
