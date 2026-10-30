package sk.lukac.tankomat.data.model

import kotlinx.serialization.Serializable
import sk.lukac.tankomat.BuildConfig

/** Druh paliva sledovaný appkou. */
enum class FuelType(val labelSk: String, val labelCz: String) {
    NATURAL_95("Natural 95", "Benzín"),
    DIESEL("Nafta", "Nafta"),
    LPG("LPG", "LPG"),
    CNG("CNG", "CNG"),
}

/**
 * Ako veľmi sa dá cene veriť — podľa veku poslednej nahlásenej ceny
 * (prahy v `MbenzinScraper`, odvodené z farieb na mbenzin.cz).
 */
enum class PriceFreshness {
    /** Zelená: „Cena je aktuální (k …)". */
    CURRENT,
    /** Oranžová: „Cenou si nejsme úplně jistí." */
    UNCERTAIN,
    /** Červená: „Aktuální cenu už nám tady dlouho nikdo nenahlásil." */
    STALE,
}

/** Jedna cena paliva vrátane dátumu a čerstvosti. */
@Serializable
data class FuelPrice(
    val priceCzk: Double,
    /** Deň nahlásenia ceny „D.M.YYYY" — mbenzin.cz hodinu neuvádza; null ak chýba aj deň. */
    val updatedOn: String?,
    val freshness: PriceFreshness,
)

/** Pumpa nachádzajúca sa v ČR (zo zdroja mbenzin.cz). */
@Serializable
data class CzStation(
    val name: String,
    val town: String,
    val address: String,
    /**
     * Hodnota `st-km` zo stránky mesta.
     *
     * POZOR — **nie je to vzdialenosť pumpy od centra toho mesta**, hoci sa tak
     * do 16. 8. 2026 čítala a pripočítavala k vzdialenosti mesta. Dôkaz priamo
     * zo zdroja: na stránke Brumova-Bylnice má pumpa `Trako` so sídlom priamo
     * v Brumove hodnotu 11,5 km a `EuroOil` v Slavičíne má na stránke Slavičína
     * 6,6 km — pri vzdialenosti od centra by boli obe takmer nula.
     *
     * Do výpočtu preto **nevstupuje**; ostáva len ako údaj zo zdroja, nech je
     * vidieť, čo stránka hlási. Skutočné vzdialenosti sú v [StationDistances].
     */
    val distanceFromCityCenterKm: Double?,
    /** Mapa palivo → cena + dátum + čerstvosť. */
    val prices: Map<FuelType, FuelPrice>,
    /** Slug mesta, ktoré sme scrapeli (kvôli vzdialenosti od domu). */
    val townSlug: String,
    /**
     * Cesta do mesta z Nastavení (km), keď v ňom pumpa leží; null = mesto
     * v Nastaveniach nie je. Do 1.24 tu pri neznámom meste bola vzdušná
     * čiara × 1,25 — od 1.25 nikdy.
     */
    val townDistanceFromHomeKm: Double? = null,
    /** Číslo pumpy na mbenzin.cz; prázdne pri skenoch zo stránok miest. */
    val id: String = "",
    /** Súradnice pumpy z mapy mbenzin.cz; null pri starých skenoch. */
    val lat: Double? = null,
    val lng: Double? = null,
    /**
     * Cesta autom od domu k súradniciam pumpy (OSRM), keď ju tabuľka
     * nameraných vzdialeností nepozná. Dopĺňa [TrasyKPumpam].
     */
    val roadKm: Double? = null,
) {
    /** Kľúč pumpy v rámci jedného skenu. */
    val kluc: String get() = id.ifBlank { identity }

    /** Súradnice, ak ich zdroj dal. */
    val bod: Gps.Bod? get() = if (lat != null && lng != null) Gps.Bod(lat, lng) else null

    /**
     * Jednosmerná vzdialenosť od domu (km) — v tomto poradí:
     *
     *  1. **skutočná cestná vzdialenosť** k tejto pumpe ([StationDistances]),
     *  2. **trasa autom** k jej súradniciam ([roadKm]), keď pumpa v tabuľke nie je,
     *  3. cestná vzdialenosť do jej **obce**, keď nie je ani trasa,
     *  4. vzdialenosť **mesta z konfigurácie**, keď nepoznáme ani obec.
     *
     * Všetko sú vzdialenosti po ceste, nikdy vzdušná čiara. Null = appka
     * cestu k pumpe nepozná a pumpa sa do porovnania nezaráta.
     *
     * Do 16. 8. 2026 sa tu k vzdialenosti mesta pripočítavalo
     * `distanceFromCityCenterKm` — a keďže to nie je vzdialenosť od centra
     * (viď tam), vychádzali čísla nadhodnotené o desiatky percent. Appka tak
     * odrádzala od ciest, ktoré sa oplácajú: `Trako` v Brumove mala o polovicu viac,
     * než je skutočnosť, a `EuroOil` vo Valašských Kloboukoch podobne.
     */
    val estimatedOneWayKm: Double?
        get() = StationDistances.resolve(this, emptyMap())?.oneWayKm

    /**
     * True = vzdialenosť je cesta **k tejto pumpe** (tabuľka alebo trasa).
     *
     * Odhad podľa obce sa sem zámerne neráta, hoci je tiež nameraný: pumpa
     * býva pár kilometrov od stredu obce a číslo, ktoré appka ukazuje ako
     * fakt, musí byť fakt.
     */
    val hasMeasuredDistance: Boolean
        get() = StationDistances.resolve(this, emptyMap())?.isEstimate == false

    /** Identita pumpy naprieč stránkami miest (tá istá pumpa býva na viacerých). */
    val identity: String
        get() = "$name|$town|$address"

    /**
     * Kľúč, pod ktorým sa drží **ručne zadaná vzdialenosť** ([UserConfig.manualKm]).
     *
     * Zámerne to nie je [identity]: mbenzin.cz pumpe mení obec pod rukami
     * (`Tank ONO` mala v auguste 2026 raz `Zádveřice`, o štyri dni `Řasnice`)
     * a používateľ by o svoje opravené kilometre prišiel pri každej takej
     * zmene. Meno + ulica prežije aj presun pumpy medzi stránkami miest;
     * keď ulica chýba, ostáva obec — inak by všetky bezulicové pumpy
     * jedného reťazca zdieľali jeden kľúč.
     */
    val manualKey: String
        get() {
            val meno = StationDistances.norm(name)
            val zvysok = StationDistances.norm(address.ifBlank { town })
            return "$meno|$zvysok"
        }
}

/** Referenčná cena na slovenskej strane (cena „doma"). */
@Serializable
data class SkReferencePrice(
    val source: String,
    val pricesEur: Map<FuelType, Double>,
    val updatedAt: Long, // epoch ms
)

/** Vypočítaný výsledok pre jednu CZ pumpu pri konkrétnom palive. */
data class StationSavings(
    val station: CzStation,
    val fuelType: FuelType,
    val skPriceEur: Double,
    val czPriceCzk: Double,
    val czPriceEur: Double,
    val tankVolumeLiters: Double,
    /** Jednosmerná vzdialenosť od domu, s ktorou sa počítalo. */
    val oneWayKm: Double,
    /** Odkiaľ tá vzdialenosť je — meranie, odhad podľa obce, alebo ručný zápis. */
    val distanceSource: DistanceSource,
    val roundTripKm: Double,
    val extraFuelCostEur: Double,
    val grossSavingsEur: Double,
    val netSavingsEur: Double,
    /** Kedy bola CZ cena naposledy aktualizovaná („D.M.YYYY"). */
    val priceUpdatedOn: String?,
    val priceFreshness: PriceFreshness,
    /**
     * Od koľkých litrov sa cesta na túto pumpu vôbec začne oplácať.
     * Null = palivo je tam drahšie ako doma, takže sa neoplatí pri žiadnom objeme.
     */
    val breakEvenLiters: Double?,
) {
    /** True = na tomto tankovaní by si prerobil. */
    val isLoss: Boolean get() = netSavingsEur < 0

    /** Koľko € by si stratil (kladné číslo). 0 ak si v pluse. */
    val lossEur: Double get() = if (isLoss) -netSavingsEur else 0.0
}

/** Konfigurácia jedného CZ mesta na sledovanie. */
@Serializable
data class CzTownConfig(
    /** Slug pre URL mbenzin.cz, napr. "Brumov-Bylnice". */
    val slug: String,
    /** Zobrazovaný názov, napr. "Brumov-Bylnice". */
    val displayName: String,
    /** Vzdialenosť od domu jednosmerne v km. */
    val distanceFromHomeKm: Double,
)

/**
 * Nastavenia self-updatu z NAS-u cez SMB.
 *
 * Na Synology je zdieľaný priečinok `/volume1/Android` viditeľný v sieti
 * ako share **`Android`** (`volume1` je len fyzický zväzok, nie share),
 * preto default `shareName = "Android"`.
 *
 * Každá appka má na NAS-e svoj vlastný podpriečinok (napr. `Tankomat`),
 * v ktorom je vždy jeden súbor s **pevným názvom** (`tankomat.apk`) —
 * pri novom vydaní sa jednoducho prepíše. Appka teda nezisťuje verziu
 * z názvu súboru, ale po stiahnutí si prečíta skutočný `versionCode`
 * priamo z APK (`ApkInstaller.readVersionCode`).
 */
@Serializable
data class UpdateConfig(
    val host: String = BuildConfig.NAS_HOST.ifBlank { "nas.local" },
    val shareName: String = BuildConfig.NAS_SHARE.ifBlank { "Android" },
    /** Podpriečinok appky v rámci share-u; prázdne = koreň share-u. */
    val remotePath: String = "Tankomat",
    /** Pevný názov súboru na NAS-e — vždy sa prepisuje novou verziou. */
    val apkFileName: String = "tankomat.apk",
    /**
     * Zámerne prázdne, hoci meno aj heslo majú predvolbu zo spoločného
     * `nas-credentials.local`. Doplní ich až `UserSettings` pri čítaní —
     * keby stáli ako default tu, `encodeDefaults` by ich zapísal do JSON blobu
     * a pri budúcom čítaní by prebili to, čo si používateľ nastavil ručne.
     */
    val username: String = "",
    val password: String = "",
)

/** Kompletná užívateľská konfigurácia. */
@Serializable
data class UserConfig(
    val fuelType: FuelType = FuelType.DIESEL,
    val consumptionLPer100Km: Double = 6.5,
    val tankVolumeLiters: Double = 10.0,
    /** Ak null, scrape sa z dalioil.sk domovské mesto. */
    val homePriceEurOverride: Double? = null,
    val czTowns: List<CzTownConfig> = DefaultTowns,
    /** True = v zozname sa ukážu aj pumpy, na ktorých by si prerobil. */
    val showLosingStations: Boolean = true,
    /**
     * True = pumpy s dávno nenahlásenou cenou (červené na mbenzin.cz)
     * sa vôbec nepočítajú — nezaradia sa do zoznamu ani do verdiktu.
     */
    val ignoreStalePrices: Boolean = true,
    /**
     * Ručne prepísané vzdialenosti k pumpám: kľúč je [CzStation.manualKey],
     * hodnota jednosmerná vzdialenosť v km.
     *
     * Tabuľka nameraných kilometrov je dobrá, ale nikdy nie úplná — stránka
     * pridá pumpu, premenuje obec, alebo človek jazdí inou cestou než tou
     * najkratšou. Toto je jeho posledné slovo a má prednosť pred všetkým.
     */
    val manualKm: Map<String, Double> = emptyMap(),
    /**
     * Stiahnuť ceny raz denne o [rannaHodina] — **vždy len po Wi-Fi**.
     *
     * Zapnuté je predvolene: appka dovtedy na pozadí nesťahovala nič, takže
     * ráno pri odchode ukazovala včerajšie ceny. Sľub „cez mobilné dáta nič"
     * to neruší — beh čaká na Wi-Fi (`RannaObnova`).
     */
    val rannaObnova: Boolean = true,
    /** Hodina, o ktorej sa ráno sťahuje (0–23). */
    val rannaHodina: Int = 6,
    /** Zapnutá záložka „CZ ceny" — pumpy v okolí s cenami z benzin.cz, bez SK. */
    val showCzPrices: Boolean = false,
    /**
     * Polomer hľadania pumpov okolo domu (km **po ceste**, od 1.25); platí pre
     * všetky záložky. Do 1.24 to bola vzdušná čiara.
     */
    val searchRadiusKm: Double = 50.0,
    val update: UpdateConfig = UpdateConfig(),
)

/** Rýchle predvoľby objemu tankovania (l) v Nastaveniach. */
val VolumePresets: List<Double> = listOf(5.0, 10.0, 20.0, 30.0, 40.0, 50.0)

/**
 * Defaultné mestá pre niekoho z domovského mesta.
 *
 * Vzdialenosti sú namerané po ceste z **domovské mesto** (16. 8. 2026, OSRM). Slúžia už len ako posledná
 * záloha — vzdialenosť konkrétnej pumpy berie [StationDistances]. Vo verejnej
 * kópii sú čísla vymyslené — skutočné by prezradili, kde autor býva.
 */
val DefaultTowns: List<CzTownConfig> = listOf(
    CzTownConfig("Brumov-Bylnice", "Brumov-Bylnice", 19.7),
    CzTownConfig("Slavicin", "Slavičín", 35.5),
    CzTownConfig("Valasske-Klobouky", "Valašské Klobouky", 31.2),
    CzTownConfig("Bojkovice", "Bojkovice", 42.6),
)

/**
 * Jeden riadok záložky „CZ ceny" — pumpa a jej cena bez porovnania so SR.
 * [oneWayKm] a [distanceSource] sú null, keď appka cestu k pumpe nepozná.
 */
data class CzCenaRiadok(
    val station: CzStation,
    val oneWayKm: Double?,
    val distanceSource: DistanceSource?,
    val cena: FuelPrice,
)

/** Stav UI vrstvy. */
sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Ready(
        val results: List<StationSavings>,
        /**
         * Najvýhodnejšia CZ pumpa spomedzi *všetkých* načítaných (pred filtrom
         * stratových). Slúži na verdikt „oplatí sa / neoplatí sa" aj vtedy,
         * keď filter skryje celý zoznam. Null = žiadne dáta.
         */
        val bestOverall: StationSavings?,
        /** Záložka „CZ ceny": tie isté pumpy zoradené podľa vzdialenosti. */
        val czCeny: List<CzCenaRiadok>,
        val fuel: FuelType,
        val skReference: SkReferencePrice,
        val exchangeRate: Double,
        val refreshedAt: Long,
        /**
         * Prečo sa posledné obnovenie nepodarilo; null = podarilo sa. Ceny sú
         * potom z [refreshedAt], nie čerstvé.
         */
        val chybaObnovenia: String? = null,
        /**
         * Pumpy v okolí, ku ktorým appka nepozná cestu (trasa sa nepodarila,
         * obec ani mesto nie sú známe). Do porovnania sa nerátajú — a aby to
         * nebolo potichu, obrazovka povie koľko ich je.
         */
        val bezVzdialenosti: Int = 0,
    ) : UiState
    data class Error(val message: String) : UiState
}
