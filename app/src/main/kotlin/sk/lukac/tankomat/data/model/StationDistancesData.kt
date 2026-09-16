package sk.lukac.tankomat.data.model

/**
 * DÁTA — namerané cestné vzdialenosti z domu k pumpám (jednosmerne, km).
 *
 * **Tento súbor sa generuje.** Vyrába ho `tools/generuj_vzdialenosti.py`
 * a pri každom behu ho prepíše celý — ručné úpravy sa stratia. Logika
 * vyhľadávania (normalizácia mien, poradie záloh, ručné prepisy) je vedľa
 * v `StationDistances.kt`, ktorého sa generátor nedotýka.
 *
 * Vygenerované 20. 08. 2026 zo stránok mbenzin.cz: adresy geokódované cez
 * Nominatim (OpenStreetMap), dĺžka trasy autom cez OSRM. Nie sú to
 * vzdušné čiary — je to dĺžka cesty, ktorou tam naozaj pôjdeš.
 *
 * Východiskový bod: vo verejnej kópii **vymyslený** — čísla nižšie sú
 * náhodné, skutočné by prezradili, kde autor býva. Vlastnú tabuľku vyrobí
 * `tools/generuj_vzdialenosti.py`.
 *
 * Presnosť je pri každom riadku: `adresa` = trafená ulica pumpy,
 * `obec` = stred obce (pumpa býva pár sto metrov inde, na výsledku to nič
 * nemení pri vzdialenostiach 20–60 km).
 */
internal object StationDistancesData {

    /** Kľúč je `CzStation.identity`, teda "meno|obec|ulica". */
    val STATION_KM: Map<String, Double> = mapOf(
        "AB Oil|Hustopeče nad Bečvou|" to 76.6,   // obec
        "AH|Valašská Bystřice|" to 46.2,   // obec
        "APH In|Jablůnka|Jablůnka 599" to 88.4,   // adresa
        "AVIA|Bílovice 497|" to 45.4,   // obec
        "AVIA|Fryšták|Zlínská 392" to 53.2,   // adresa
        "AVIA|Lípa|4. května 806" to 56.0,   // adresa
        "Alitron|Bojkovice|Luhačovická 655" to 84.4,   // adresa
        "Alitron|Držková|u Dřevařských závodů Držková" to 20.2,   // obec
        "Alitron|Lípa 269|" to 85.8,   // obec
        "Alitron|Neubuz|Greinerova 156" to 84.8,   // adresa
        "Alitron|Vizovice|Razov 1253" to 66.2,   // adresa
        "Alitron|Zlín|K majáku 5001" to 80.6,   // adresa
        "Alitron|Zlín|areál Svit-Rybníky III" to 25.9,   // adresa
        "Alitron|Želechovice nad Dřevnicí 615|areál AGAS a.s." to 42.0,   // obec
        "Autocentrum Lukáš|Valašské Meziříčí|Masarykova 752" to 60.7,   // adresa
        "Autoplyn|Želechovice nad Dřevnicí|" to 40.4,   // obec
        "Autoservis Bobrky|Vsetín|Bobrky" to 51.4,   // adresa
        "Autoservis Jiří Hrachovec|Zubří|Pod Obecníkem 124" to 76.5,   // adresa
        "Autosprint Jan Hruboš|Veletiny 219|" to 75.1,   // obec
        "Benzinpumpa|Ratiboř 318|" to 71.2,   // obec
        "Blok|Bojkovice|Nádražní 953" to 56.5,   // adresa
        "CAR OK STK s.r.o.|Vsetín|Bobrky 2270" to 23.9,   // adresa
        "CNGUH|Uherské Hradiště|Za Olšávkou 380" to 54.3,   // adresa
        "Cafro Plus|Valašské Meziříčí - Poličná|" to 30.4,   // obec
        "Combo LPG|Otrokovice|Objízdná 1242" to 35.9,   // adresa
        "Dekra CZ|Zlín Malenovice|třída 3. května 1147" to 65.4,   // adresa
        "Dobrá benzínka|Horní Lhota|" to 89.3,   // obec
        "Elko Oil|Huslenky|Huslenky 662" to 33.1,   // adresa
        "EuroOil|Buchlovice|Hřbitovní 168" to 64.5,   // adresa
        "EuroOil|Hluk|Hluk 1263, silnice 498" to 85.8,   // obec
        "EuroOil|Horní Lideč|Horní Lideč 295, silnice 49" to 23.2,   // obec
        "EuroOil|Kunovice|třída Vítězství 841" to 66.2,   // adresa
        "EuroOil|Napajedla|Kvítkovická 1383" to 52.2,   // adresa
        "EuroOil|Slavičín|Luhačovská 1352" to 36.4,   // adresa
        "EuroOil|Valašské Klobouky|Cyrilometodějská 666" to 40.9,   // adresa
        "EuroOil|Vsetín|Dolní Jasenka 283" to 74.1,   // adresa
        "Eurobit|Otrokovice|Zlínská 314" to 64.7,   // adresa
        "Eurobit|Uherské Hradiště - Jarošov|Pivovarská 553" to 57.0,   // adresa
        "Eurobit|Veselí nad Moravou|tř.Masarykova 762" to 29.0,   // adresa
        "FastOil|Blatnice pod Sv. Antonínkem|857" to 52.4,   // obec
        "Foto Morava|Štramberk|Nádražní 976" to 36.1,   // adresa
        "G.A.S. Petroleum|Vsetín|Rokytnice 462" to 61.3,   // adresa
        "Glass Car|Zlín|" to 27.1,   // obec
        "Hespo s.r.o.|Otrokovice|Letiště 1894" to 63.0,   // adresa
        "Innogy Energo|Zlín|Třída 3. května 1198" to 54.0,   // adresa
        "Javorník|Štítná nad Vláří 414|" to 26.2,   // obec
        "KKS reality|Zlín|Příluky Cecilka" to 35.6,   // adresa
        "Kotrla|Vsetín|Rokytnice 190" to 52.8,   // adresa
        "LPG Janíček|Uherský Brod|Vazová 2382" to 60.1,   // adresa
        "LPG Miloš Bittner|Hodslavice|Hodslavice 34" to 64.7,   // obec
        "LPG Movita Fryšták|Fryšták|Doktora Absolona 132" to 48.4,   // obec
        "LPG SÚS|Zlín|" to 25.9,   // obec
        "LPG Tomegas|Frenštát pod Radhoštěm|Rožnovská 1715" to 41.5,   // adresa
        "LPG Včelary|Bílovice|Včelary" to 27.6,   // adresa
        "LPG|Zlín|Šrámkova" to 74.7,   // adresa
        "LXM|Nový Jičín|Jičínská 97" to 37.1,   // adresa
        "LXM|Šenov u Nového Jičína|Malostranská 579" to 50.1,   // adresa
        "Limitoo|Bystřička 354|" to 88.6,   // obec
        "Limitoo|Bystřička|Bystřička 352" to 21.1,   // adresa
        "Limitoo|Dolní Bečva|Dolní Bečva 589" to 82.9,   // adresa
        "Limitoo|Strání|U Hlavní" to 70.2,   // adresa
        "Limitoo|Valašské Meziříčí|Hranická 783" to 19.4,   // adresa
        "Limitoo|Vigantice|" to 22.3,   // obec
        "MK GAS|Slavičín|" to 34.1,   // obec
        "MOL|Frenštát pod Radhoštěm|silnice 58" to 69.3,   // obec
        "MOL|Kunovice|třída Vítězství" to 53.8,   // adresa
        "MOL|Napajedla|Zábraní 1538" to 29.1,   // adresa
        "MOL|Polešovice|silnice 427" to 43.3,   // obec
        "MOL|Valašská Polanka|silnice 57" to 68.0,   // obec
        "MOL|Zlín|třída Tomáše Bati" to 64.5,   // adresa
        "Makro|Zlín|třída 3. května" to 24.0,   // adresa
        "Nikey|Nový Jičín|Palackého 25" to 39.0,   // adresa
        "OMV|Napajedla|Kvítkovická 1569" to 50.1,   // adresa
        "OMV|Rožnov pod Radhoštěm|Meziříčská 2303" to 22.4,   // adresa
        "OMV|Starý Hrozenkov|Starý Hrozenkov 8" to 28.3,   // adresa
        "OMV|Uherske Hradiště|Tř. Maršála Malinovského, směr centrum" to 79.0,   // obec
        "OMV|Uherský Brod|U Korečnice" to 31.2,   // adresa
        "OMV|Zlín|Výletní" to 21.1,   // adresa
        "OMV|Zlín|třída Tomáše Bati" to 36.9,   // adresa
        "Orlen Express|Močidla 1|Uherský Brod" to 82.4,   // adresa
        "Orlen|Drslavice|silnice E50" to 27.8,   // obec
        "Orlen|Kelč|Kelč 271, silnice 439" to 87.1,   // obec
        "Orlen|Luhačovice|Uherskobrodská 981" to 65.5,   // adresa
        "Orlen|Napajedla|Nádražní" to 19.0,   // adresa
        "Orlen|Otrokovice|tř.Tomáše Bati 1601" to 47.4,   // adresa
        "Orlen|Rožnov pod Radhoštěm|1.máje 2637" to 28.5,   // adresa
        "Orlen|Rožnov pod Radhoštěm|Horní Paseky 2270" to 57.2,   // adresa
        "Orlen|Slušovice|Školní 633" to 32.8,   // adresa
        "Orlen|Staré Město|Nádražní 1944" to 27.1,   // adresa
        "Orlen|Uherské Hradiště|Sokolovská 1395" to 65.0,   // adresa
        "Orlen|Uherské Hradiště|Tř. Maršála Malinovského, směr Hodonín" to 83.3,   // obec
        "Orlen|Valašské Klobouky|Cyrilometodějská 947" to 89.5,   // adresa
        "Orlen|Valašské Meziříčí|Hřbitovní" to 74.5,   // adresa
        "Orlen|Valašské Meziříčí|Rožnovská" to 70.2,   // adresa
        "Orlen|Vizovice|Zlínská" to 34.8,   // adresa
        "Orlen|Vsetín|Generála Klapálka" to 77.1,   // adresa
        "Orlen|Zlín|Okružní 14" to 58.5,   // adresa
        "Orlen|Zlín|Příluky" to 57.4,   // adresa
        "Orsák TK s.r.o.|Valašské Klobouky|Brumovská 279" to 53.2,   // adresa
        "PH-Mobil|Bohuslavice u Zlína|Bohuslavice u Zlína 226, silnice 497" to 56.2,   // obec
        "Petrocard|Štramberk|Libotín 315" to 26.2,   // adresa
        "Plynárna PB|Zlín|U Slanice 684" to 69.1,   // adresa
        "Prim|Bludovice 110|" to 40.5,   // obec
        "Prim|Tlumačov|Brněnská 14" to 34.7,   // obec
        "Prim|Uherské Hradiště|Solná cesta 341" to 28.4,   // adresa
        "Prim|Zlín|Tyršovo nábřeží" to 59.2,   // adresa
        "Pumpa u čápa|Vlachovice 381|silnice 494" to 24.2,   // obec
        "RAJ Fuel|Vsetín|Lázky 698" to 27.1,   // adresa
        "Recada|Rožnov pod Radhoštěm|Hasičská 2630" to 79.9,   // adresa
        "Robin Oil|Tlumačov|Skály" to 21.5,   // adresa
        "Robin Oil|Zlín|Broučkova 5294" to 28.4,   // adresa
        "SAR Kvasice|Kvasice|Tlumačovská 692" to 82.8,   // adresa
        "STK Araver|Uherský Brod|U Korečnice 2834" to 57.7,   // adresa
        "STK Centrum Vizovice|Vizovice|Zlínská ulice 1160" to 40.3,   // obec
        "STK Rožnov, s.r.o.|Rožnov pod Radhoštěm|Tvarůžkova 2491" to 26.9,   // adresa
        "STK Uherské Hradiště|Uherské Hradiště|Sokolovská 1792" to 62.6,   // adresa
        "Shell|Hulín|Záhlinická 1280" to 45.8,   // adresa
        "Shell|Lutonina|silnice 69" to 57.9,   // obec
        "Shell|Napajedla|Kvítkovická 1658" to 39.1,   // adresa
        "Shell|Nový Jičín|Zborovská 42" to 41.6,   // adresa
        "Shell|Staré Město|Hradišťská 1947" to 61.5,   // adresa
        "Shell|Uherský Ostroh|Osvobození 268" to 66.1,   // obec
        "Shell|Valašské Meziříčí|Masarykova 35" to 72.6,   // adresa
        "Shell|Vsetín|Mostecká" to 47.1,   // adresa
        "Shell|Zlín|Fryštácká 470" to 29.8,   // adresa
        "Shell|Zlín|třída Tomáše Bati 258" to 78.4,   // adresa
        "Silmet|Karolinka|Vsetínská 653" to 86.5,   // adresa
        "Silmet|Lidečko|" to 22.3,   // obec
        "Suboil|Uherský Brod|" to 48.5,   // obec
        "TAM Autohof|Vsetín|Bobrky 457" to 37.3,   // adresa
        "TM stav|Vsetín|Jasenice 729" to 66.5,   // adresa
        "TM stav|Vsetín|Jasenická 2191" to 49.5,   // adresa
        "Tank ONO|Spytihněv 572|silnice 55" to 26.0,   // obec
        "Tank ONO|Řasnice|Zádveřice 427" to 47.6,   // adresa
        "TankujAuto|Šenov u Nového Jičína|Suvorovova 34" to 78.2,   // adresa
        "Tempex|Uherský Brod|Vazová 2366" to 41.7,   // adresa
        "Tesco|Valašské Meziříčí|Masarykova 873" to 50.6,   // adresa
        "The Jolly Shopper|Moravský Písek|Revoluční 762" to 73.2,   // adresa
        "Tomegas|Napajedla|Kvítkovická 1623" to 51.0,   // adresa
        "Tomegas|Rožnov pod Radhoštěm|Dukelských hrdinů 2269" to 78.4,   // adresa
        "Tomegas|Uherský Ostroh|Osvobození 247" to 28.0,   // obec
        "Torol|Životice u Nového Jičína 203|" to 58.7,   // obec
        "Torrol|Kunčice pod Ondřejníkem|Kunčice pod Ondřejníkem 761" to 88.1,   // adresa
        "Trako|Brumov|Kloboucká 1397" to 17.8,   // adresa
        "Trako|Slavičín|Hradecká 160" to 34.2,   // adresa
        "Valatrans|Liptál|Liptál 483, silnice 69" to 84.6,   // obec
        "Vena Trade|Buchlovice|Hřbitovní" to 38.8,   // adresa
        "Vena Trade|Valašské Meziříčí|Železničního vojska 1384" to 81.1,   // adresa
        "ZDV Fryšták|Fryšták|Holešovská 166" to 85.6,   // adresa
        "ZOD Delta Štípa|Zlín|Velíkovská" to 69.4,   // adresa
        "Zaris|Frenštát pod Radhoštěm|Papratná" to 86.1,   // adresa
        "Zaris|Veselí nad Moravou|Blatnická 1673" to 49.5,   // adresa
        "Zevos|Staré Město|Luční čtvrť 1824" to 19.7,   // adresa
        "ČS Gold|Horní Bečva|Horní Bečva 436" to 45.8,   // adresa
        "ČS Podlesí|Valašské Meziříčí|Podlesi 522, areál JZD" to 50.1,   // obec
        "ČS Saba|Valašské Klobouky|Cyrilometodějská" to 63.8,   // adresa
        "ČS Salaš|Bohuslavice u Zlína|Salaš" to 23.7,   // obec
        "ČSAD Diesel|Zlín|tř. Tomáše Bati 258" to 36.9,   // adresa
        "ČSAD|Slavičín-Hrádek|Nádražní 206" to 56.1,   // adresa
        "ČSAD|Uherský Brod|Šumická 1725" to 41.3,   // adresa
        "Častulík|Vysoké Pole|" to 58.4,   // obec
        "Čerpací stanice Hluk|Hluk|Antonínská 1597" to 62.2,   // adresa
        "Červenka Pavel|Horní Lapač 50|Holešov" to 43.1,   // obec
    )

    /** Záloha pre pumpu, ktorá v tabuľke ešte nie je — podľa jej obce. */
    val TOWN_KM: Map<String, Double> = mapOf(
        "Blatnice pod Sv. Antonínkem" to 71.5,
        "Bludovice" to 29.9,
        "Bohuslavice u Zlína" to 45.1,
        "Bojkovice" to 42.6,
        "Brumov" to 19.7,
        "Buchlovice" to 42.1,
        "Bystřička" to 24.8,
        "Bílovice" to 50.3,
        "Dolní Bečva" to 58.1,
        "Drslavice" to 68.8,
        "Držková" to 25.0,
        "Frenštát pod Radhoštěm" to 60.8,
        "Fryšták" to 48.5,
        "Hluk" to 58.2,
        "Hodslavice" to 18.2,
        "Horní Bečva" to 45.1,
        "Horní Lapač" to 70.9,
        "Horní Lhota" to 33.0,
        "Horní Lideč" to 35.9,
        "Hulín" to 50.1,
        "Huslenky" to 41.0,
        "Hustopeče nad Bečvou" to 56.0,
        "Jablůnka" to 73.5,
        "Karolinka" to 55.8,
        "Kelč" to 38.5,
        "Kunovice" to 49.2,
        "Kunčice pod Ondřejníkem" to 42.1,
        "Kvasice" to 76.6,
        "Lidečko" to 58.4,
        "Liptál" to 32.6,
        "Luhačovice" to 18.6,
        "Lutonina" to 75.4,
        "Lípa" to 51.3,
        "Moravský Písek" to 62.8,
        "Močidla" to 53.1,
        "Napajedla" to 18.0,
        "Neubuz" to 52.4,
        "Nový Jičín" to 35.6,
        "Otrokovice" to 73.5,
        "Polešovice" to 66.3,
        "Ratiboř" to 46.3,
        "Rožnov pod Radhoštěm" to 68.7,
        "Slavičín" to 35.5,
        "Slavičín-Hrádek" to 36.0,
        "Slušovice" to 41.1,
        "Spytihněv" to 77.4,
        "Starý Hrozenkov" to 19.2,
        "Strání" to 71.2,
        "Tlumačov" to 40.9,
        "Uherske Hradiště" to 77.6,
        "Uherské Hradiště" to 57.6,
        "Uherské Hradiště - Jarošov" to 35.3,
        "Uherský Brod" to 28.7,
        "Uherský Ostroh" to 52.0,
        "Valašská Bystřice" to 54.7,
        "Valašská Polanka" to 68.7,
        "Valašské Klobouky" to 31.2,
        "Valašské Meziříčí" to 62.8,
        "Valašské Meziříčí - Poličná" to 25.5,
        "Veletiny" to 30.5,
        "Veselí nad Moravou" to 66.0,
        "Vigantice" to 63.0,
        "Vizovice" to 60.1,
        "Vlachovice" to 29.4,
        "Vsetín" to 33.0,
        "Vysoké Pole" to 45.9,
        "Zlín" to 42.8,
        "Zlín Malenovice" to 58.2,
        "Řasnice" to 38.1,
        "Šenov u Nového Jičína" to 48.1,
        "Štramberk" to 70.7,
        "Štítná nad Vláří" to 60.6,
        "Želechovice nad Dřevnicí" to 29.3,
        "Životice u Nového Jičína" to 28.7,
    )
}
