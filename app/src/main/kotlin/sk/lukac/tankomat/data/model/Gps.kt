package sk.lukac.tankomat.data.model

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Súradnice a vzdušná čiara — **nikdy ako vzdialenosť, s ktorou sa počíta**.
 *
 * Vzdušná čiara slúži len na predvýber pumpov z celého Česka: cesta autom
 * nikdy nie je kratšia než vzdušná čiara, takže pumpa za okruhom vzdušnou
 * čiarou nemôže byť v okruhu ani po ceste. Kilometre na cestu dáva výhradne
 * [StationDistances] (tabuľka, trasa z OSRM, ručný zápis). Do 1.24 tu bol
 * prevod vzdušnej čiary na cestu (× 1,25) pre pumpy mimo tabuľky — od 1.25
 * je preč, takú pumpu appka nechá trasovať po cestách.
 */
object Gps {
    /** Vo verejnej kópii vymyslený bod — skutočný by prezradil, kde autor býva. */
    const val DOMA_LAT = 49.0
    const val DOMA_LNG = 18.0

    /** Bod na mape; [kluc] na 5 desatinných miest (~1 m) slúži ako kľúč cache trás. */
    data class Bod(val lat: Double, val lng: Double) {
        val kluc: String get() = String.format(Locale.ROOT, "%.5f,%.5f", lat, lng)
    }

    val DOMOV = Bod(DOMA_LAT, DOMA_LNG)

    fun km(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Vzdušná čiara od domu — len na predvýber (viď popis objektu). */
    fun odDomu(lat: Double, lng: Double): Double = km(DOMA_LAT, DOMA_LNG, lat, lng)
}
