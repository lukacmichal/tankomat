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

Testy bežia na JVM (bez emulátora) a overujú scrapery proti uloženým HTML
fixtúram v `app/src/test/resources/fixtures/`. Aktuálne ich je **55**.

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
Poradie: pumpa z tabuľky → jej obec z tabuľky → mesto z konfigurácie.
Karta pumpy ukazuje „≈ … (odhad)" iba pri tom poslednom prípade.

> **Oprava 16. 8. 2026.** Predtým sa vzdialenosť rátala ako
> `vzdialenosť_mesta + st-km zo stránky`, v domnení, že `st-km` je vzdialenosť
> pumpy od centra scrapovaného mesta. **Nie je** — na stránke Brumova-Bylnice
> má pumpa `Trako` so sídlom priamo v Brumove hodnotu 11,5 km a `EuroOil`
> v Slavičíne má na stránke Slavičína 6,6 km; pri vzdialenosti od centra by
> boli obe takmer nula. Kilometre tak boli nadhodnotené o desiatky percent
> (Trako 36,5 namiesto 23,0; EuroOil Valašské Klobouky 48,3 namiesto 28,6),
> appka počítala väčšiu spotrebu na cestu a odrádzala od ciest, ktoré sa
> oplácajú. `st-km` sa už do výpočtu nezapočítava.

Tá istá pumpa býva na stránkach viacerých miest — appka ju dedupuje. Od tejto
opravy dostane v oboch prípadoch tú istú vzdialenosť, takže už nezáleží na tom,
z ktorej stránky sa načítala.

### Čerstvosť cien

mbenzin.cz značí ceny trojfarebne a appka to preberá:

| Farba na webe | Význam | Správanie appky |
|---|---|---|
| zelená | „Cena je aktuální (k …)" | normálne počítaná, dátum na karte |
| oranžová | „Cenou si nejsme úplně jistí" | počítaná, ⚠ oranžové varovanie s dátumom |
| červená | „…dlouho nikdo nenahlásil" | **defaultne úplne vylúčená** (aj z verdiktu); prepínač *Ignorovať neaktuálne ceny* ju vie pustiť späť — potom je označená červeným ⚠ s dátumom |

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

CZ mesto sa zadáva ako **slug pre URL** `mbenzin.cz/Ceny-benzinu-a-nafty/{slug}`
+ vzdialenosť od domu jednosmerne v km.

Príklady slugov, ktoré fungujú:
- `Brumov-Bylnice`
- `Slavicin`
- `Valasske-Klobouky`
- `Bojkovice`
- `Horni-Lidec`
- `Uhersky-Brod`

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
| mbenzin.cz | `/Ceny-benzinu-a-nafty/{mesto}` | len ručné ↻ | bloky `div.st-row`, ceny `div.st-price` (⚠ stránka menila HTML v 2026 už 3×) |
| dalioil.sk | `/{mesto}/` | len ručné ↻ | ceny v `<img alt="">` |
| ČNB | `denni_kurz.txt` | len ručné ↻ | pipe-delimited plain text |
| NAS (SMB) | `\\{host}\{share}` | len ručné tlačidlo | self-update, knižnica SMBJ |

Posledný úspešný sken sa ukladá na disk (`filesDir/last_snapshot.json`) —
po otvorení appky sa hneď zobrazí s časom „Posledný sken: …". Nič sa
nesťahuje samo; nové dáta až po stlačení ↻.

**Odolnosť voči zmene HTML:** mbenzin.cz mení štruktúru často, preto blok
pumpy aj adresu čítame cez Schema.org značky (`itemtype=LocalBusiness`,
`itemprop`), ktoré redizajn prežijú lepšie než CSS triedy. Keď sa aj tak
formát zmení natoľko, že sa nenačíta nič, `FuelRepository` to rozpozná
(fetch prešiel, ale 0 pumpov → `ScrapeFormatException`) a appka ukáže
hlášku *„Zdroj mbenzin.cz sa načítal, ale nedali sa prečítať žiadne ceny —
asi zmenil formát"* s tlačidlom **Skúsiť znova**, namiesto tichého prázdna.
Posledný uložený sken zostáva na disku, takže po reštarte appky staré dáta
uvidíš. **Úplne tomu zabrániť sa nedá** — scrapujeme cudzí web bez API;
riešenie je rýchla detekcia + aktualizácia scrapera (viď pamäť projektu).

Žiadne API kľúče, žiadne CAPTCHA, žiadny JS rendering. User-Agent simulácia
mobilného Chrome je v `HttpClient`.

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
