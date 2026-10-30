package sk.lukac.tankomat.data.model

import java.text.Normalizer

/**
 * Ako ďaleko je pumpa — a hlavne **odkiaľ to appka vie**.
 *
 * Dáta (namerané kilometre) sú vedľa v generovanom [StationDistancesData];
 * tu je len vyhľadávanie. Delenie vzniklo 20. 8. 2026: kým bolo oboje
 * v jednom súbore, každé pregenerovanie tabuľky prepísalo aj logiku.
 *
 * ## Prečo tu vôbec je toľko úrovní
 *
 * Kľúč pumpy je „meno|obec|ulica" tak, ako to napíše mbenzin.cz — a to sa
 * mení pod rukami. Pumpa `Tank ONO` mala pri generovaní tabuľky obec
 * `Zádveřice`, o štyri dni neskôr už `Řasnice` (tá istá pumpa, tá istá ulica
 * `Zádveřice 427`). Presné porovnanie kľúča preto zlyhalo a appka spadla až
 * na poslednú zálohu — **vzdialenosť mesta, ktorého stránku práve čítala**.
 * Výsledok: polovičná vzdialenosť a verdikt postavený na polovičnej
 * spotrebe na cestu.
 *
 * Rovnaká diera zožrala celý Vsetín: pumpy z jeho stránky (`G.A.S. Petroleum`
 * a spol.) v tabuľke neboli vôbec, tak všetky dostali paušál — hodnotu, ktorú
 * si používateľ kedysi zapísal k mestu. Skutočnosť bola oveľa ďalej.
 *
 * Preto sa hľadá v poradí od najpresnejšieho po najhrubšie a **vždy je vidieť,
 * ktorá úroveň zabrala** ([DistanceSource]) — odhad sa už netvári ako meranie.
 */
object StationDistances {

    private val STATION_KM: Map<String, Double> get() = StationDistancesData.STATION_KM
    private val TOWN_KM: Map<String, Double> get() = StationDistancesData.TOWN_KM

    /** Kľúč bez diakritiky a interpunkcie: „Slavičín-Hrádek" → „slavicin hradek". */
    internal fun norm(s: String): String = Normalizer
        .normalize(s.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /** „Vlachovice 381" → „Vlachovice"; mbenzin.cz píše obce s číslom popisným. */
    internal fun bezCislaPopisneho(s: String): String =
        s.trim().replace(Regex("\\s+\\d+$"), "").trim()

    /**
     * Slová s diakritikou z tabuľky (obce aj ulice): „valasske" → „Valašské".
     * Pri dvoch tvaroch toho istého slova vyhrá ten s viac diakritikou —
     * tabuľka má aj „Uherske Hradiště" vedľa „Uherské Hradiště".
     */
    private val slovaSDiakritikou: Map<String, String> by lazy {
        val zdroje = STATION_KM.keys.flatMap { it.split('|') } +
            TOWN_KM.keys + DefaultTowns.map { it.displayName }
        zdroje
            .flatMap { it.split(Regex("[^\\p{L}\\p{N}]+")) }
            .filter { it.isNotBlank() }
            .groupBy { norm(it) }
            .mapValues { (_, tvary) -> tvary.maxBy { tvar -> tvar.count { it.code > 127 } } }
    }

    /**
     * „Valasske Klobouky" → „Valašské Klobouky", slovo po slove. Dáta mapy
     * mbenzin.cz nesú obec a ulicu len v adrese stránky, bez diakritiky.
     * Slovo, ktoré tabuľka nepozná, ostane, ako prišlo.
     */
    fun sDiakritikou(text: String): String =
        text.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { slovo ->
            val tvar = slovaSDiakritikou[norm(slovo)] ?: return@joinToString slovo
            if (slovo.first().isUpperCase()) {
                tvar.replaceFirstChar { it.uppercaseChar() }
            } else {
                tvar.replaceFirstChar { it.lowercaseChar() }
            }
        }

    /**
     * Register postavený nad tabuľkou. Rovnaký kľúč s dvoma rôznymi
     * vzdialenosťami sa **zahodí** — dve pumpy toho istého reťazca na tej
     * istej ulici sa inak nedajú rozlíšiť a tipovať by znamenalo ukázať
     * číslo, ktoré patrí tej druhej.
     */
    private class Register(dvojice: List<Pair<String, Double>>) {
        private val mapa: Map<String, Double>

        init {
            val zoskupene = dvojice.groupBy({ it.first }, { it.second })
            mapa = zoskupene.mapNotNull { (kluc, hodnoty) ->
                val min = hodnoty.min()
                val max = hodnoty.max()
                // Do 1 km je to tá istá pumpa zapísaná dvakrát, nie dve pumpy.
                if (max - min <= 1.0) kluc to hodnoty.average() else null
            }.toMap()
        }

        operator fun get(kluc: String): Double? =
            if (kluc.isBlank()) null else mapa[kluc]
    }

    private val rozlozene: List<Triple<String, String, String>> by lazy {
        STATION_KM.keys.map { kluc ->
            val casti = kluc.split('|')
            Triple(
                casti.getOrElse(0) { "" },
                casti.getOrElse(1) { "" },
                casti.getOrElse(2) { "" },
            )
        }
    }

    /** Celý kľúč, len znormalizovaný — chytí zmenu diakritiky či medzier. */
    private val podlaKluca: Register by lazy {
        Register(STATION_KM.map { (k, v) -> norm(k) to v })
    }

    /** Meno + ulica; obec sa ignoruje. Toto je tá vetva, čo zachránila Tank ONO. */
    private val podlaMenaAUlice: Register by lazy {
        Register(
            STATION_KM.entries.zip(rozlozene).mapNotNull { (zaznam, casti) ->
                val (meno, _, ulica) = casti
                if (ulica.isBlank()) null else "${norm(meno)}|${norm(ulica)}" to zaznam.value
            },
        )
    }

    /** Meno + ulica bez čísla popisného — pumpa prežije prečíslovanie adresy. */
    private val podlaMenaAUlicky: Register by lazy {
        Register(
            STATION_KM.entries.zip(rozlozene).mapNotNull { (zaznam, casti) ->
                val (meno, _, ulica) = casti
                val ulicka = norm(bezCislaPopisneho(ulica))
                if (ulicka.isBlank()) null else "${norm(meno)}|$ulicka" to zaznam.value
            },
        )
    }

    /** Meno + obec; pre pumpy, ktoré ulicu vôbec neuvádzajú. */
    private val podlaMenaAObce: Register by lazy {
        Register(
            STATION_KM.entries.zip(rozlozene).map { (zaznam, casti) ->
                val (meno, obec, _) = casti
                "${norm(meno)}|${norm(bezCislaPopisneho(obec))}" to zaznam.value
            },
        )
    }

    private val obceNormalizovane: Register by lazy {
        Register(TOWN_KM.map { (k, v) -> norm(bezCislaPopisneho(k)) to v })
    }

    /**
     * Nameraná vzdialenosť k pumpe, alebo null keď o nej ani o jej obci nič
     * nevieme. Zachovaný tvar pre testy a staršie volania — plnú odpoveď aj
     * s pôvodom dáva [resolve].
     */
    fun oneWayKm(identity: String, town: String): Double? =
        zTabulky(identity) ?: zObce(identity, town)

    /** Nameraná cesta priamo k tejto pumpe; null = tabuľka pumpu nepozná. */
    fun zTabulky(station: CzStation): Double? = zTabulky(station.identity)

    /** Hľadanie pumpy v tabuľke od najpresnejšieho kľúča po najhrubší. */
    private fun zTabulky(identity: String): Double? {
        STATION_KM[identity]?.let { return it }

        val casti = identity.split('|')
        val meno = casti.getOrElse(0) { "" }
        val obecZKluca = casti.getOrElse(1) { "" }
        val ulica = casti.getOrElse(2) { "" }

        return podlaKluca[norm(identity)]
            ?: podlaMenaAUlice["${norm(meno)}|${norm(ulica)}"]
            ?: podlaMenaAUlicky["${norm(meno)}|${norm(bezCislaPopisneho(ulica))}"]
            ?: podlaMenaAObce["${norm(meno)}|${norm(bezCislaPopisneho(obecZKluca))}"]
    }

    /**
     * Pumpu nepoznáme — ale jej obec áno. To je stále cesta po cestách, len
     * o pár sto metrov vedľa; označí sa ako odhad.
     */
    private fun zObce(identity: String, town: String): Double? {
        val obecZKluca = identity.split('|').getOrElse(1) { "" }
        val obec = bezCislaPopisneho(town.ifBlank { obecZKluca })
        // „Slavičín-Hrádek" → „Slavičín": mestská časť spadne na mesto.
        return obceNormalizovane[norm(obec)]
            ?: obceNormalizovane[norm(obec.substringBefore('-'))]
    }

    /**
     * Kompletná odpoveď pre jednu pumpu vrátane ručného prepisu — od
     * najpresnejšieho po najhrubšie, a **vždy je vidieť, ktorá úroveň zabrala**.
     *
     * Ručne zadaná hodnota má prednosť pred všetkým ostatným — je to posledné
     * slovo človeka, ktorý tú cestu naozaj jazdí, proti tabuľke, ktorú nikto
     * nedokáže držať večne aktuálnu.
     *
     * Null = cestu k pumpe nepoznáme. Vzdušnú čiaru appka **nikdy** nepodstrčí
     * ako náhradu (do 1.24 to robila: × 1,25) — taká pumpa sa radšej nezaráta.
     */
    fun resolve(station: CzStation, manualKm: Map<String, Double>): ResolvedDistance? {
        manualKm[station.manualKey]?.let { return ResolvedDistance(it, DistanceSource.MANUAL) }
        zTabulky(station.identity)?.let { return ResolvedDistance(it, DistanceSource.MEASURED) }
        station.roadKm?.let { return ResolvedDistance(it, DistanceSource.ROUTED) }
        zObce(station.identity, station.town)?.let { return ResolvedDistance(it, DistanceSource.VILLAGE) }
        station.townDistanceFromHomeKm?.let { return ResolvedDistance(it, DistanceSource.CITY_FALLBACK) }
        return null
    }

    /** Koľko pumpov má presnú vzdialenosť — pre testy a diagnostiku. */
    val knownStations: Int get() = STATION_KM.size

    /** Koľko obcí má nameranú vzdialenosť — pre testy a diagnostiku. */
    val knownTowns: Int get() = TOWN_KM.size
}

/** Odkiaľ sa vzala vzdialenosť, s ktorou appka počíta. */
enum class DistanceSource {
    /** Ručne prepísané v appke. Nad tým už niet. */
    MANUAL,

    /** Zmeraná cesta priamo k tejto pumpe (tabuľka). */
    MEASURED,

    /**
     * Cesta autom k súradniciam pumpy, ktorú appka dopočítala cez OSRM
     * (od 1.25) — pumpu tabuľka nepozná. Je to meranie, nie odhad.
     */
    ROUTED,

    /** Zmeraná cesta do jej obce — pumpu samu tabuľka nepozná a trasa chýba. */
    VILLAGE,

    /**
     * Posledná záloha: cesta do mesta z Nastavení. Býva mimo o kilometre,
     * preto sa dá prepísať ručne. Vzdušná čiara to od 1.25 nikdy nie je.
     */
    CITY_FALLBACK,
}

/** Vzdialenosť aj s tým, akú váhu jej možno dať. */
data class ResolvedDistance(
    val oneWayKm: Double,
    val source: DistanceSource,
) {
    /** True = číslo je odhad, nie meranie k tejto konkrétnej pumpe. */
    val isEstimate: Boolean
        get() = source == DistanceSource.VILLAGE || source == DistanceSource.CITY_FALLBACK
}
