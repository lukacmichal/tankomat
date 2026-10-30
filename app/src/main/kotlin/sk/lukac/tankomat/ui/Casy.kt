package sk.lukac.tankomat.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Dni a časy cien tak, ako ich človek číta: „dnes", „včera 6:03", „24.10.2026". */
internal object Casy {
    private val DATUM = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")

    private fun dnes(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    /** „D.M.YYYY" → „dnes" / „včera" / bez zmeny. */
    fun den(updatedOn: String?, dnes: LocalDate = dnes()): String? {
        val (d, m, r) = updatedOn?.let { DATUM.find(it)?.destructured } ?: return updatedOn
        val datum = runCatching { LocalDate.of(r.toInt(), m.toInt(), d.toInt()) }.getOrNull()
            ?: return updatedOn
        return when (datum) {
            dnes -> "dnes"
            dnes.minusDays(1) -> "včera"
            else -> updatedOn
        }
    }

    /** Epoch ms → „dnes 6:03" / „včera 6:03" / „26.10. 6:03". */
    fun cas(epochMs: Long, dnes: LocalDate = dnes()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        val hodiny = "${t.hour}:${t.minute.toString().padStart(2, '0')}"
        val den = when (t.toLocalDate()) {
            dnes -> "dnes"
            dnes.minusDays(1) -> "včera"
            else -> "${t.dayOfMonth}.${t.monthValue}."
        }
        return "$den $hodiny"
    }
}
