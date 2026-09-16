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
        najdi(identity, town)?.first

    /**
     * Hľadanie od najpresnejšieho po najhrubšie. Vracia aj to, ktorá úroveň
     * zabrala — bez toho by sa odhad podľa obce tváril ako meranie.
     */
    private fun najdi(identity: String, town: String): Pair<Double, DistanceSource>? {
        STATION_KM[identity]?.let { return it to DistanceSource.MEASURED }

        val casti = identity.split('|')
        val meno = casti.getOrElse(0) { "" }
        val obecZKluca = casti.getOrElse(1) { "" }
        val ulica = casti.getOrElse(2) { "" }

        podlaKluca[norm(identity)]?.let { return it to DistanceSource.MEASURED }
        podlaMenaAUlice["${norm(meno)}|${norm(ulica)}"]
            ?.let { return it to DistanceSource.MEASURED }
        podlaMenaAUlicky["${norm(meno)}|${norm(bezCislaPopisneho(ulica))}"]
            ?.let { return it to DistanceSource.MEASURED }
        podlaMenaAObce["${norm(meno)}|${norm(bezCislaPopisneho(obecZKluca))}"]
            ?.let { return it to DistanceSource.MEASURED }

        // Pumpu nepoznáme — ale jej obec áno. To je stále poctivé číslo,
        // len o pár sto metrov vedľa; označí sa ako odhad.
        val obec = bezCislaPopisneho(town.ifBlank { obecZKluca })
        obceNormalizovane[norm(obec)]?.let { return it to DistanceSource.VILLAGE }
        // „Slavičín-Hrádek" → „Slavičín": mestská časť spadne na mesto.
        obceNormalizovane[norm(obec.substringBefore('-'))]
            ?.let { return it to DistanceSource.VILLAGE }
        return null
    }

    /**
     * Kompletná odpoveď pre jednu pumpu vrátane ručného prepisu.
     *
     * Ručne zadaná hodnota má prednosť pred všetkým ostatným — je to posledné
     * slovo človeka, ktorý tú cestu naozaj jazdí, proti tabuľke, ktorú nikto
     * nedokáže držať večne aktuálnu.
     */
    fun resolve(station: CzStation, manualKm: Map<String, Double>): ResolvedDistance {
        manualKm[station.manualKey]?.let {
            return ResolvedDistance(it, DistanceSource.MANUAL)
        }
        val najdene = najdi(station.identity, station.town)
        return if (najdene != null) {
            ResolvedDistance(najdene.first, najdene.second)
        } else {
            ResolvedDistance(station.townDistanceFromHomeKm, DistanceSource.CITY_FALLBACK)
        }
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

    /** Zmeraná cesta priamo k tejto pumpe. */
    MEASURED,

    /** Zmeraná cesta do jej obce — pumpu samu tabuľka nepozná. */
    VILLAGE,

    /**
     * Posledná záloha: vzdialenosť mesta, ktorého stránku appka čítala.
     * Stránka mesta nesie aj pumpy 30 km ďalej, takže toto číslo býva
     * výrazne mimo. Práve preto sa dá prepísať ručne.
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
