# Tankomat

**Is it worth driving across the border to fill up?** Tankomat scrapes fuel
prices at Czech stations near the Slovak border, compares them with the Slovak
reference price, subtracts the fuel burned on the round trip, and answers with
a number.

The top of the screen is a single verdict: green **"WORTH IT"** with the amount
saved, or red **"NOT WORTH IT"** with how much you would lose *and* the number
of litres at which the trip would start to pay off. Below it, every station is
ranked with its own saving or loss and the reason for it.

Android · Kotlin · Jetpack Compose · minSdk 26 · 55 unit tests

---

## Why this exists

Fuel is cheaper in Czechia, the border is 20–50 km away, and the usual advice
("it's cheaper over there") ignores that you burn fuel getting there. For a
10-litre top-up the trip can easily cost more than it saves — but for a full
tank it may not. The threshold depends on the distance, your consumption and
today's price gap, which is exactly the kind of arithmetic nobody does at
the pump.

## How the saving is computed

```
one_way_distance = measured road distance to that station
gross_saving     = (price_SK − price_CZ_in_EUR) × litres
trip_cost        = 2 × one_way_distance / 100 × consumption × price_SK
net_saving       = gross_saving − trip_cost
```

Vehicle depreciation, tolls and the value of your time are deliberately left
out: they are constants that shift every station by the same amount and never
change their order. If you want a margin, raise *Minimum saving to display*
in the settings.

## Where the kilometres come from

This is the part that took the longest to get right.

Distances are **measured road distances**, not straight lines: addresses are
geocoded through Nominatim (OpenStreetMap) and routed by car through OSRM by
[`tools/generuj_vzdialenosti.py`](tools/generuj_vzdialenosti.py), which
generates the lookup table. The reference point is a fixed home address. In
this public copy the table holds made-up numbers — the real ones would give
away where I live. Run the script with your own address to get real ones.

Looking a station up is not a dictionary hit, because the key
(`name|municipality|street`) is written by the scraped site and moves under
your hands. One station was listed under municipality `Zádveřice`; four days
later the same station on the same street was listed under `Řasnice`. So the
lookup goes from precise to coarse and **always reports which level answered**
(`DistanceSource`), so an estimate never masquerades as a measurement:

| Level | Key | Shown as |
|---|---|---|
| 1 | station from the table | measured |
| 2 | its municipality from the table | measured |
| 3 | the configured city | `≈ … (estimate)` |

### A bug worth documenting

Until 16 Aug 2026 the distance was computed as `city_distance + st-km`, where
`st-km` is a number printed next to each station on the source page — on the
assumption that it meant "distance from the city centre". **It does not.** A
station headquartered in Brumov-Bylnice shows 11.5 km on the Brumov-Bylnice
page; a station in Slavičín shows 6.6 km on the Slavičín page. If the number
were a distance from the centre, both would be near zero.

The effect was systematic overestimation by tens of percent (one station came
out about one and a half times farther than it is), which inflated the fuel burned on the trip
and talked the user out of trips that actually paid off. `st-km` is no longer
part of the calculation.

## Price freshness

The source marks prices in three colours and the app carries that through
rather than flattening it:

| Source colour | Meaning | App behaviour |
|---|---|---|
| green | price is current | counted normally, date on the card |
| orange | source is unsure | counted, orange ⚠ with the date |
| red | nobody has reported in a long time | **excluded by default**, including from the verdict; a toggle lets it back in, marked red |

## Surviving a redesign

`mbenzin.cz` changed its HTML three times in 2026. The scraper therefore reads
the station block and address through Schema.org markup
(`itemtype=LocalBusiness`, `itemprop`) rather than CSS classes, which survives
a redesign better.

When the format changes anyway, the failure is explicit instead of silent: the
repository detects "the fetch succeeded but produced zero stations", raises
`ScrapeFormatException`, and the UI says the source loaded but no prices could
be read, with a retry button. An empty screen that looks like "no results" is
the one outcome worth engineering against.

## Architecture

```
sk.lukac.tankomat
├── data
│   ├── model        – CzStation, FuelType, UserConfig, UiState …
│   ├── remote       – HttpClient, MbenzinScraper, DalioilScraper, CnbExchangeRate
│   └── repository   – FuelRepository (parallel refresh + in-memory cache)
├── domain
│   └── SavingsCalculator
├── settings         – UserSettings (DataStore Preferences + JSON)
├── di               – AppContainer (manual DI, no Hilt)
├── viewmodel        – MainViewModel
└── ui               – navigation, screens, Material 3 theme
```

Prices are never fetched in the background except for one optional morning
refresh, and that one runs **only on Wi-Fi**. Everything else happens when you
press ↻. The last successful scan is persisted to
`filesDir/last_snapshot.json`, so opening the app shows data immediately,
stamped with the time it was taken.

## Data sources

| Source | Endpoint | Notes |
|---|---|---|
| mbenzin.cz | `/Ceny-benzinu-a-nafty/{city}` | Czech pump prices, parsed via Schema.org markup |
| dalioil.sk | `/{city}/` | Slovak reference price, read from `<img alt="">` |
| Czech National Bank | `denni_kurz.txt` | CZK→EUR, pipe-delimited plain text |

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

55 JVM tests, no emulator needed. The scrapers are tested against **saved HTML
fixtures** in `app/src/test/resources/fixtures/` — real captured pages, so a
parser regression shows up as a failing test rather than as an empty screen on
the phone.

## Build

Requires Android Studio Koala (2024.1.1) or newer and JDK 17. The Gradle
wrapper (8.10.2) is committed, so:

```bash
./gradlew :app:assembleDebug
```

The output APK is renamed from the generic `app-debug.apk` to `tankomat.apk`,
which is what the self-update convention below expects.

## Self-update over SMB

The app updates itself from a NAS on the local network — no Play Store, no
distribution account. In *Settings → Updates* you give it a host, a share, a
folder and credentials; the APK lives at `Android/Tankomat/tankomat.apk` and
publishing a new version means overwriting that one file.

The version is never inferred from the filename, which never changes:

1. **On startup** the app reads only `output-metadata.json` next to the APK —
   a few hundred bytes — and compares `versionCode` with the installed one.
   If the NAS is unreachable, which is what "not at home" looks like, nothing
   happens and nothing is said. An app has no business complaining about a
   server the user did not ask it to contact.
2. **If a newer version is there**, it offers a dialog. It never installs on
   its own: you opened the app because you wanted something from it, and a
   restart in the middle of that is worse than waiting.
3. **Only after you agree** does it download the ~20 MB APK, verify the real
   `versionCode` from the file itself (`getPackageArchiveInfo`) and hand it to
   the system installer through a `FileProvider`.

> The SMB password is stored in the app's private DataStore. That keeps it away
> from other apps but it is not separately encrypted, so this should be a
> dedicated NAS account with access to one folder — not an admin account.

## Privacy

No location permission (the starting point is a fixed address), no analytics,
no API keys, no accounts. Outbound traffic is the three sources above, all of
it user-triggered, plus the few hundred bytes of the update check on the local
network. The app tracks how much data it has transferred and shows it split
between Wi-Fi and mobile.

## One of eight

Tankomat is not a standalone app but one of eight built to a written shared
standard: the same header, the same pull-to-refresh, the same settings shape,
the same version footer, the same self-update mechanism, byte-identical icon
files. An English condensation of that standard — including the two
`RemoteViews` traps that silently kill a home-screen widget — is in
[`docs/shared-standard.md`](docs/shared-standard.md), which is what the
`shared-standard.md §2.x` references in the code point to.

## Notes

This is a personal project built for one specific commute, published as a
portfolio piece. Code comments and [`README.sk.md`](README.sk.md) are in
Slovak; this file is the English summary. The local-network defaults, the home
town and the distance table have been replaced with placeholders for
publication.

MIT licensed.
