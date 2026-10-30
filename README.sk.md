# Tankomat

Android appka, ktorá scrapuje **ceny palív v ČR** (zo zdroja `mbenzin.cz`)
a porovnáva ich s **referenčnou cenou na SK** (zo zdroja `dalioil.sk`,
prípadne ručne zadanou). Pre každú CZ pumpu spočíta **úsporu na natankovaný
objem** (default **10 l**, meniteľné v Nastaveniach) po odpočítaní paliva
minutého na cestu tam-späť.

Hore na obrazovke je veľký **verdikt** — zelené `OPLATÍ SA ✅` s údajom
koľko ušetríš, alebo červené `NEOPLATÍ SA ❌` s tým, **o koľko by si
prerobil** a **od koľkých litrov** by sa cesta začala oplácať. Odpoveď na
„šetrím alebo míňam?" na prvý pohľad; pod ním nasleduje rebríček pumpov,
kde je pri každej buď `Úspora`, alebo `Strata` aj s dôvodom.

Zameraná na osobu žijúcu v **domovské mesto** — defaultne sleduje
Brumov-Bylnice, Slavičín, Valašské Klobouky a Bojkovice (najbližšie CZ
mestá s pokrytím v `mbenzin.cz`).

## Ako sa stavia

Potrebuješ **Android Studio Koala (2024.1.1)** alebo novšie + JDK 17.

```bash
# z koreňa repozitára
./gradlew :app:assembleDebug
# alebo otvor v Android Studio a stlač Run
```

Wrapper súbory (`gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`)
sú už vygenerované (Gradle 8.10.2), takže stačí:

```bash
./gradlew :app:assembleDebug
```

Hotové APK je v `app/build/outputs/apk/debug/tankomat.apk` — výstup je
zámerne premenovaný z generického `app-debug.apk` na pevné meno, aby sa
dal rovno nahrať na NAS bez ručného premenovania (sedí s konvenciou
v sekcii *Self-update* nižšie).
`compileSdk`/`targetSdk` = **35** (build-tools 35.0.0).

## Spustenie testov

```bash
./gradlew :app:testDebugUnitTest
```

Testy bežia na JVM (bez emulátora aj bez kľúča) a overujú parsery proti
uloženým odpovediam v `app/src/test/resources/fixtures/` (vzorka dát mapy
mbenzin.cz, stránka dalioil.sk). Aktuálne ich je **69**.

## Architektúra

```
sk.lukac.tankomat
├── data
│   ├── model        – CzStation, FuelType, UserConfig, UiState …
│   ├── remote       – HttpClient, MbenzinScraper, DalioilScraper, CnbExchangeRate
│   └── repository   – FuelRepository (paralelný refresh + in-memory cache)
├── domain
│   └── SavingsCalculator
├── settings         – UserSettings (DataStore Preferences + JSON serializácia)
├── di               – AppContainer (manuálny DI bez Hilt-u)
├── viewmodel        – MainViewModel
├── ui               – AppNavigation, MainScreen, SettingsScreen
│   ├── components   – StationCard
│   └── theme        – Material 3 + benzínovo-zelená paleta
└── TankomatApp + MainActivity
```

### Vzorec úspory

```
vzdialenosť_one_way = nameraná cestná vzdialenosť k tejto pumpe
hrubá_úspora        = (cena_SK − cena_CZ_v_EUR) × objem_tankovania
náklady_cesta       = 2 × vzdialenosť_one_way_km / 100 × spotreba × cena_SK
čistá_úspora        = hrubá − náklady
```

### Odkiaľ sú kilometre

Vzdialenosti sú **namerané po ceste** z domovské mesto — adresy geokódované cez Nominatim (OpenStreetMap),
trasa autom cez OSRM. Tabuľka je v
[`StationDistances.kt`](app/src/main/kotlin/sk/lukac/tankomat/data/model/StationDistances.kt).
Poradie: ručne zadaná vzdialenosť → pumpa z tabuľky → **trasa autom z OSRM**
k súradniciam pumpy (od 1.25, pre pumpy mimo tabuľky; drží sa natrvalo
v `files/trasy.json`, každá pumpa sa pýta raz) → cesta do jej obce z tabuľky →
cesta do mesta z konfigurácie. Karta pumpy ukazuje „≈ … (odhad)" pri
posledných dvoch prípadoch. **Vzdušná čiara sa ako vzdialenosť nepoužije
nikdy** — keď nezabere nič z toho, pumpa sa do porovnania nezaráta a appka
napíše, koľko takých je.

Od 1.22 prídu pumpy z dát mapy mbenzin.cz (jeden dopyt = celé Česko pre
jedno palivo, aj so súradnicami). Appka si nechá tie v okruhu z Nastavení
(**po ceste**, predvolene 50 km; vzdušná čiara slúži len na predvýber z celého
Česka — cesta nie je nikdy kratšia, takže nič v okruhu nevypadne); obec a ulicu vyčíta z adresy
detailu pumpy.

### Čerstvosť cien

Mapa mbenzin.cz dáva len deň poslednej nahlásenej ceny („Dnes", „Včera",
dátum). Appka z neho robí tri farby (prahy podľa farieb na stránke Brumova
28. 10. 2026):

| Posledné nahlásenie | Farba | Správanie appky |
|---|---|---|
| do 5 dní | zelená | normálne počítaná, dátum na karte |
| 6–14 dní alebo bez dátumu | oranžová | počítaná, ⚠ oranžové varovanie s dátumom |
| staršie | červená | **defaultne úplne vylúčená** (aj z verdiktu); prepínač *Ignorovať neaktuálne ceny* ju vie pustiť späť — potom je označená červeným ⚠ s dátumom |

Úmyselne nezahŕňa amortizáciu/čas/mýto — sú to konštanty, ktoré nemenia
poradie pumpov medzi sebou. Užívateľ to vie kompenzovať cez
*Min. úspora pre zobrazenie* v Nastaveniach.

## Konfigurácia (v appke, Nastavenia)

| Pole | Default | Poznámka |
|---|---|---|
| Druh paliva | Nafta | NATURAL_95 / DIESEL / LPG / CNG |
| Spotreba (l/100 km) | 6.5 | priemer kombi diesel |
| Koľko litrov počítať | 10 | rýchle predvoľby 5/10/20/30/40/50 + vlastná hodnota |
| Override SK ceny (€/l) | — | prázdne ⇒ scrape z dalioil.sk |
| Zobraziť aj stratové pumpy | zap. | vypni, ak chceš vidieť len tie, kde si v pluse |
| Ignorovať neaktuálne ceny | zap. | červené (dávno nenahlásené) ceny sa vôbec nepočítajú |
| CZ mestá | 4 najbližšie | pridaj/odoberaj voľne |
| Aktualizácie (NAS cez SMB) | `Android/Tankomat/tankomat.apk` | adresa, share, priečinok, meno, heslo — viď nižšie |

CZ mesto je obec, ku ktorej poznáš vzdialenosť od domu po ceste (jednosmerne,
v km). Pumpa v takom meste, ktorú tabuľka nameraných vzdialeností nepozná,
dostane toto číslo, keď sa trasu z OSRM nepodarí zistiť. Ktoré pumpy sa
vôbec zobrazia, určuje polomer hľadania (po ceste, predvolene 50 km).

## Self-update z NAS-u (SMB)

Appka sa vie sama aktualizovať z domáceho NAS-u — netreba Play Store.

**Ako to nastaviť:** *Nastavenia → Aktualizácie (NAS cez SMB)* — zadaj adresu,
zdieľaný priečinok a prihlasovacie meno + heslo.

| Pole | Default | Poznámka |
|---|---|---|
| Adresa NAS-u | `nas.local` | IP v lokálnej sieti |
| Zdieľaný priečinok | `Android` | na Synology je `/volume1/Android` v sieti share **`Android`** — `volume1` je len fyzický zväzok, nie share |
| Podpriečinok appky | `Tankomat` | vlastný priečinok tejto appky — až budeš mať appiek viac, každá má svoj |
| Názov súboru | `tankomat.apk` | pevné meno, prepisuje sa pri každom vydaní |
| Meno / Heslo | — | ten SMB účet, čo už máš vytvorený |

Kontrola je **výhradne ručná** — tlačidlo *Skontrolovať a stiahnuť*
v Nastaveniach. Appka sa pri štarte na NAS nepozerá vôbec.

**Ako vydať novú verziu:** na NAS-e nahraď súbor `Android/Tankomat/tankomat.apk`
novým buildom **s rovnakým menom** — netreba nič premenovávať. Appka
nezisťuje verziu z názvu súboru (ten je vždy rovnaký), ale po stiahnutí si
prečíta **skutočný `versionCode` priamo z APK** (cez `PackageManager`) a
porovná ho so svojou nainštalovanou verziou. Ak je vyššia, ponúkne
inštaláciu. Gradle build APK pomenuje rovno na `tankomat.apk`
(`app/build/outputs/apk/debug/tankomat.apk`), takže ho stačí skopírovať na
NAS bez úprav — len pri každom vydaní zvýš `versionCode` v
`app/build.gradle.kts`, inak appka novú verziu neuvidí ako novšiu.

> Keďže overenie vyžaduje stiahnutie súboru (nie len pozretie sa na názov),
> appka pri kontrole vždy stiahne celý APK (cez domáci WiFi je to sekundy).
> Mimo domácej siete SMB spojenie zlyhá rýchlo (5 s timeout) s chybovou
> hláškou — kontrolu spúšťaj doma na WiFi.

Pri prvej inštalácii Android vypýta povolenie *„Inštalovať neznáme aplikácie"* —
appka ťa na tú obrazovku pošle sama.

> **Pozn. k heslu:** ukladá sa v DataStore v privátnom úložisku appky (iné
> aplikácie sa k nemu nedostanú), ale **nie je extra šifrované**. Na účet
> pre domáci NAS to je v pohode; nepoužívaj tam heslo od admin účtu —
> vytvor si radšej samostatný účet s prístupom len k tomuto priečinku.

## Zdroje dát

| Zdroj | URL | Kedy sa volá | Pozn. |
|---|---|---|---|
| mbenzin.cz | `/Mapa-cen/data?fuel=…` | ručné ↻ + ranný beh | JSON mapy cien, celé Česko pre jedno palivo; osobný kľúč v hlavičke |
| dalioil.sk | `/{mesto}/` | len ručné ↻ | ceny v `<img alt="">` |
| ČNB | `denni_kurz.txt` | len ručné ↻ | pipe-delimited plain text |
| OSRM | `/table/v1/driving/…` | pri obnovení, len pre pumpy mimo tabuľky bez uloženej trasy | cesta autom od domu, ~45 B odpovede |
| NAS (SMB) | `\\{host}\{share}` | len ručné tlačidlo | self-update, knižnica SMBJ |

Posledný úspešný sken sa ukladá na disk (`filesDir/last_snapshot.json`) —
po otvorení appky sa hneď zobrazí s časom „Posledný sken: …". Okrem ranného
behu po Wi-Fi (nižšie) sa nič nesťahuje samo; nové dáta až po stlačení ↻.

**Prístupový kľúč k mbenzin.cz (od 28. 10. 2026):** osobný kľúč, ktorý
mbenzin.cz vydal pre túto appku. Podmienka: pri cenách uvádzať
„Ceny: mbenzin.cz" (appka to píše pod cenami). Kľúč **nie je v kóde** —
Gradle ho pri builde načíta z `nas-credentials.local` (`MBENZIN_KLIC`) do
`BuildConfig` a posiela sa len v hlavičke, nikdy v adrese ani v logu.
Pri nadmernom používaní ho mbenzin.cz obmedzí alebo zruší, preto
`HttpClient` drží odpoveď 30 minút a opakované ↻ nič nové nesťahuje.

Keď kľúč chýba alebo mbenzin.cz namiesto dát pošle stránku, appka povie, že
kľúč asi neplatí. Keď dáta prídu, ale nedá sa z nich prečítať žiadna pumpa,
`FuelRepository` to rozpozná (`ScrapeFormatException`) a appka ukáže hlášku
s tlačidlom **Skúsiť znova** namiesto tichého prázdna. Posledný uložený sken
zostáva na disku, takže po reštarte appky staré dáta uvidíš.

## Čo to **nerobí**

- **Žiadna geolokácia** — východiskový bod je natvrdo pevná adresa doma
  v domovskom meste. Iný domov ⇒ treba pregenerovať `StationDistances.kt`
  (skript je v poznámkach k úlohe TANKOMAT-1) a upraviť `DefaultTowns`.
- **Nezohľadňuje aktuálnu cestnú premávku** — kilometre sú namerané raz
  a uložené v appke; menia sa len pri novom vydaní.
- **Nová pumpa nemá presnú vzdialenosť** — kým sa tabuľka nepregeneruje,
  dostane vzdialenosť svojej obce a karta to prizná slovom „odhad".
- **Nepublikuje sa nikam** — len osobné použitie. Scraping má rozumný
  User-Agent a okrem jedného ranného behu denne (nižšie) beží výhradne pri
  ručnom obnovení, takže žiadny rate-limit incident.

## Ranné sťahovanie cien (od 7. 9. 2026)

Raz denne o pevnej hodine (predvolene **6:00**, meniteľné v Nastaveniach →
*Sťahovanie*) si appka stiahne nové ceny sama — **výhradne cez Wi-Fi**.
Dovtedy na pozadí nesťahovala nič, takže ráno pri odchode ukazovala včerajšie
čísla; a práve ráno sa človek rozhoduje, či pôjde tankovať do Čiech.

Tri veci, na ktorých to stojí (`RannaObnova.kt`):

1. **`NetworkType.UNMETERED`** nie je šetrenie, je to celý zmysel rozvrhu.
   Keď v tú hodinu Wi-Fi nie je, WorkManager beh **nezahodí ani nepustí cez
   SIM** — počká na najbližšiu Wi-Fi. Cez mobilné dáta sa teda na pozadí
   nestiahne nikdy nič.
2. **Posledný sken leží na disku a platí ďalej.** Čakanie na Wi-Fi nikoho
   neoberá o čísla — appka ukazuje posledné známe ceny aj v aute bez signálu.
3. **Prázdny výsledok sa neuloží.** Keď sa neozve ani jedno mesto,
   `FuelRepository` skončí výnimkou (`NoDataException`) namiesto toho, aby
   prepísal posledné dobré ceny prázdnom. Rovnako pri výpadku `dalioil.sk`:
   použije sa **posledná skutočne stiahnutá** SK cena, nie vymyslený fallback
   (1,50/1,55/0,80) — ten by sa teraz, keď sťahovanie beží aj bez človeka,
   dostal do verdiktu bez toho, aby o tom niekto vedel.

Prenesené bajty sa počítajú aj z ranného behu (`Prenos.odovzdaj` na konci
pracovníka) — inak by v počítadle chýbalo práve to, čo appka stiahla sama.

## Rozšírenia, ktoré dávajú zmysel

- Pridať Veľké Karlovice / Vsetín pre ľudí žijúcich severnejšie.
- Notifikácia, keď cena na obľúbenej pumpe klesne pod prah (WorkManager).
- História cien (jednoduchý SQLite + line chart cez Vico).
- Auto-fetch raz za 24 h cez `PeriodicWorkRequest`.

## Licencia

Tvoje, rob s tým čo chceš.
