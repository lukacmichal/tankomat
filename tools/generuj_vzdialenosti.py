#!/usr/bin/env python3
"""Vyrobí `StationDistancesData.kt` — namerané cestné vzdialenosti k pumpám.

Spúšťa sa RUČNE, keď na mbenzin.cz pribudne pumpa alebo sa zmení domov.
Appka za behu nič nemeria: kilometre sú v nej zapečené, aby nezáviseli na
sieti ani na dostupnosti cudzej služby vo chvíli, keď stojíš pri aute.

    python tools\\generuj_vzdialenosti.py

Zdroje:
  * zoznam pumpov  — mbenzin.cz, tie isté stránky, aké číta appka
  * súradnice      — Nominatim (OpenStreetMap), max 1 požiadavka/s
  * dĺžka trasy    — OSRM (profil driving)

Píše **iba dáta** (`StationDistancesData.kt`). Vyhľadávacia logika je vedľa
v ručne písanom `StationDistances.kt` a tento skript sa jej nedotkne — kým
boli obe v jednom súbore, každé pregenerovanie prepísalo aj logiku.

Rovnomenné obce sú pasca: „Lípa", „Vlachovice" a „Drslavice" existujú v Česku
viackrát a Nominatim bez okresu trafil obec o 200–400 km ďalej. Preto
[UPRESNENIE] a kontrola rozsahu na konci — čokoľvek nad 150 km je zlý geokód,
nie ďaleká pumpa.

⚠ **Neznámy slug nevráti chybu.** mbenzin.cz naň odpovie HTTP 200 a pošle
celoštátny zoznam pumpov z celého Česka. Bez kontroly by sa do tabuľky dostali
pumpy od Písku po Ústí a `MAX_ROZUMNYCH_KM` by ich síce vyhodilo, ale až po
stovkách zbytočných geokódov. Preto [over_stranku] — rovnaká kontrola, akú robí
appka v `MbenzinScraper`.
"""
from __future__ import annotations

import datetime
import json
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from pathlib import Path

KOREN = Path(__file__).resolve().parent.parent
CIEL = (KOREN / "app/src/main/kotlin/sk/lukac/tankomat/data/model/StationDistancesData.kt")

DOMOV = "domovské mesto, Slovensko"

# Mestá, ktorých stránky sa čítajú. Nie sú to len tie, čo má používateľ
# v nastaveniach: každá stránka nesie pumpy z okolia (~30 ks) a čím širší
# záber, tým menej pumpov spadne na odhad. Zoznam pokojne rozšír — neznámy
# slug sa sám vyhodí v [over_stranku].
MESTA = [
    "Brumov-Bylnice", "Slavicin", "Valasske-Klobouky", "Bojkovice",
    "Vsetin", "Vizovice", "Zlin", "Luhacovice", "Uhersky-Brod",
    "Valasske-Mezirici", "Slusovice", "Otrokovice", "Napajedla",
    "Roznov-pod-Radhostem", "Uherske-Hradiste",
]

UA = {"User-Agent": "TankomatDistances/1.0 (michal@example.com)"}
MAX_ROZUMNYCH_KM = 150

# Obce, ktoré sa v Česku volajú rovnako viackrát — okres ich určí jednoznačne.
UPRESNENIE = {
    "Lípa": "Lípa, okres Zlín",
    "Drslavice": "Drslavice, okres Uherské Hradiště",
    "Vlachovice": "Vlachovice, okres Zlín",
    "Lidečko": "Lidečko, okres Vsetín",
    "Salaš": "Bohuslavice u Zlína",
    "Brumov": "Brumov-Bylnice",
    "Močidla": "Uherský Brod",
    "Řasnice": "Zádveřice-Raková",
    "Rokytnice": "Vsetín",
    "Bobrky": "Vsetín",
    "Jasenice": "Vsetín",
    "Lázky": "Vsetín",
    "Dolní Jasenka": "Vsetín",
    "Bystřička": "Bystřička, okres Vsetín",
    "Ratiboř": "Ratiboř, okres Vsetín",
    "Jablůnka": "Jablůnka, okres Vsetín",
    "Poličná": "Poličná, okres Vsetín",
    "Podlesí": "Valašské Meziříčí",
    "Lhota": "Lhota u Vsetína",
}

BLOK = re.compile(r'itemtype[^>]*LocalBusiness.*?(?=itemtype[^>]*LocalBusiness|\Z)', re.S)
NAME = re.compile(r'itemprop="name"[^>]*>([^<]+)', re.S)
LOC = re.compile(r'itemprop="addressLocality"[^>]*>([^<]+)', re.S)
STREET = re.compile(r'itemprop="streetAddress"[^>]*>([^<]+)', re.S)
H1 = re.compile(r"<h1[^>]*>(.*?)</h1>", re.S)


def stiahni(url: str) -> str:
    return urllib.request.urlopen(
        urllib.request.Request(url, headers=UA), timeout=40).read().decode("utf-8", "replace")


def bez_diakritiky(s: str) -> str:
    rozlozene = unicodedata.normalize("NFD", s)
    return "".join(c for c in rozlozene if unicodedata.category(c) != "Mn").lower()


def over_stranku(html: str, slug: str) -> bool:
    """Je to naozaj stránka toho mesta, alebo celoštátny zoznam?"""
    nadpis = H1.search(html)
    if not nadpis:
        return True                        # zmena formátu; nech to rieši človek
    text = bez_diakritiky(re.sub(r"<[^>]+>", "", nadpis.group(1)).strip())
    hladane = bez_diakritiky(slug.replace("-", " "))
    return hladane in text or bez_diakritiky(slug) in text


def geokoduj(dopyt: str):
    time.sleep(1.1)                       # pravidlá Nominatim: max 1 req/s
    url = ("https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
           + urllib.parse.quote(dopyt))
    try:
        data = json.loads(stiahni(url))
    except Exception as exc:              # noqa: BLE001 — jeden výpadok nezhodí beh
        print(f"    geokód zlyhal ({exc})", file=sys.stderr)
        return None
    return (float(data[0]["lat"]), float(data[0]["lon"])) if data else None


def trasa_km(odkial, kam) -> float | None:
    url = (f"https://router.project-osrm.org/route/v1/driving/"
           f"{odkial[1]},{odkial[0]};{kam[1]},{kam[0]}?overview=false")
    try:
        data = json.loads(stiahni(url))
    except Exception as exc:              # noqa: BLE001
        print(f"    trasa zlyhala ({exc})", file=sys.stderr)
        return None
    if data.get("code") != "Ok" or not data.get("routes"):
        return None
    return round(data["routes"][0]["distance"] / 1000.0, 1)


def obec_bez_cisla(obec: str) -> str:
    """„Vlachovice 381" → „Vlachovice"; číslo popisné Nominatimu prekáža."""
    return re.sub(r"\s+\d+$", "", obec).strip()


def zoznam_pumpov() -> dict[str, tuple[str, str, str]]:
    out: dict[str, tuple[str, str, str]] = {}
    for mesto in MESTA:
        try:
            html = stiahni(f"https://www.mbenzin.cz/Ceny-benzinu-a-nafty/{mesto}")
        except Exception as exc:          # noqa: BLE001
            print(f"  ! {mesto}: stránka sa nestiahla ({exc})", file=sys.stderr)
            continue
        if not over_stranku(html, mesto):
            print(f"  ! {mesto}: mbenzin.cz slug nepozná (vrátil celoštátny "
                  f"zoznam) — vynechávam", file=sys.stderr)
            continue
        pribudlo = 0
        for blok in BLOK.findall(html):
            meno = NAME.search(blok)
            if not meno:
                continue
            obec = LOC.search(blok)
            ulica = STREET.search(blok)
            kluc = (f"{meno.group(1).strip()}|{obec.group(1).strip() if obec else ''}"
                    f"|{ulica.group(1).strip() if ulica else ''}")
            if kluc not in out:
                pribudlo += 1
            out[kluc] = (meno.group(1).strip(),
                         obec.group(1).strip() if obec else "",
                         ulica.group(1).strip() if ulica else "")
        print(f"  {mesto}: +{pribudlo} nových (spolu {len(out)})")
    return out


def kotlin_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main() -> int:
    dom = geokoduj(DOMOV)
    if dom is None:
        print("domov sa nepodarilo geokódovať — končím", file=sys.stderr)
        return 1
    print(f"domov: {DOMOV} -> {dom}")

    pumpy: dict[str, dict] = {}
    obce: dict[str, float] = {}
    body_obci: dict[str, tuple] = {}

    for kluc, (_meno, obec, ulica) in sorted(zoznam_pumpov().items()):
        cista = obec_bez_cisla(obec)
        bod, presnost = None, "adresa"
        if ulica and not ulica.lower().startswith("silnice"):
            bod = geokoduj(f"{ulica}, {UPRESNENIE.get(cista, cista)}, Česko")
        if bod is None:
            presnost = "obec"
            if cista not in body_obci:
                body_obci[cista] = geokoduj(f"{UPRESNENIE.get(cista, cista)}, Česko")
            bod = body_obci[cista]
        km = trasa_km(dom, bod) if bod else None
        if km is None or km > MAX_ROZUMNYCH_KM:
            print(f"  ? {kluc}: {km} km — vynechávam (zlý geokód?)")
            continue
        pumpy[kluc] = {"km": km, "presnost": presnost}
        print(f"  {km:>6.1f} km  ({presnost:6}) {kluc}")

    for obec in sorted({obec_bez_cisla(k.split('|')[1]) for k in pumpy}):
        if obec not in body_obci or body_obci[obec] is None:
            body_obci[obec] = geokoduj(f"{UPRESNENIE.get(obec, obec)}, Česko")
        km = trasa_km(dom, body_obci[obec]) if body_obci[obec] else None
        if km is not None and km <= MAX_ROZUMNYCH_KM:
            obce[obec] = km

    (KOREN / "tools/vzdialenosti.json").write_text(
        json.dumps(pumpy, ensure_ascii=False, indent=1), encoding="utf-8")
    (KOREN / "tools/obce.json").write_text(
        json.dumps(obce, ensure_ascii=False, indent=1), encoding="utf-8")

    riadky_pumpy = "\n".join(
        f"        {kotlin_str(k)} to {v['km']},   // {v['presnost']}"
        for k, v in sorted(pumpy.items()))
    riadky_obce = "\n".join(
        f"        {kotlin_str(o)} to {km}," for o, km in sorted(obce.items()))

    dnes = f"{datetime.date.today():%d. %m. %Y}"
    obsah = f"""package sk.lukac.tankomat.data.model

/**
 * DÁTA — namerané cestné vzdialenosti z domu k pumpám (jednosmerne, km).
 *
 * **Tento súbor sa generuje.** Vyrába ho `tools/generuj_vzdialenosti.py`
 * a pri každom behu ho prepíše celý — ručné úpravy sa stratia. Logika
 * vyhľadávania (normalizácia mien, poradie záloh, ručné prepisy) je vedľa
 * v `StationDistances.kt`, ktorého sa generátor nedotýka.
 *
 * Vygenerované {dnes} zo stránok mbenzin.cz: adresy geokódované cez
 * Nominatim (OpenStreetMap), dĺžka trasy autom cez OSRM. Nie sú to
 * vzdušné čiary — je to dĺžka cesty, ktorou tam naozaj pôjdeš.
 *
 * Východiskový bod: ** * domovské mesto**.
 *
 * Presnosť je pri každom riadku: `adresa` = trafená ulica pumpy,
 * `obec` = stred obce (pumpa býva pár sto metrov inde, na výsledku to nič
 * nemení pri vzdialenostiach 20–60 km).
 */
internal object StationDistancesData {{

    /** Kľúč je `CzStation.identity`, teda "meno|obec|ulica". */
    val STATION_KM: Map<String, Double> = mapOf(
{riadky_pumpy}
    )

    /** Záloha pre pumpu, ktorá v tabuľke ešte nie je — podľa jej obce. */
    val TOWN_KM: Map<String, Double> = mapOf(
{riadky_obce}
    )
}}
"""
    CIEL.write_text(obsah, encoding="utf-8")
    print(f"\nzapisane {CIEL}  ({len(pumpy)} pumpov, {len(obce)} obcí, {dnes})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
