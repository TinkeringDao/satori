#!/usr/bin/env python3
"""Builds Satori's offline list of places, data/src/main/assets/places.bin, from GeoNames.

Satori names the place where a recording starts without a network, after a place in this list
(PlaceIndex finds the places near it, PlaceNames picks one). See tools/gazetteer/README.md for how
to get the two GeoNames files and run this script. Python 3 standard library only.

What is kept: every place of cities500 (population over 500, or a seat of an administrative
division) in Germany, its neighbours and India, and every place of cities15000 (population over
15,000, or a capital) elsewhere. Historical, abandoned and destroyed places (feature codes PPLH,
PPLQ, PPLW) are left out: a recording is made where people are now. Of each place only the GeoNames
`name` (UTF-8), the position, the country code and the population are kept.

File format, version 1, big-endian, read by PlaceFile.kt:

    "SPLC"               4 bytes, magic
    version              u8, 1
    bands per degree     u8: the places are sorted into latitude bands this many to a degree
    country count C      u16
    country codes        C x 2 bytes ASCII, in index order
    place count N        u32
    latitude             N x i32, degrees x 100,000
    longitude            N x i32, degrees x 100,000
    population           N x i32
    country              N x u8, index into the country codes
    name length          N x u8, bytes of UTF-8
    names                the N names, UTF-8, one after the other

The places are sorted by latitude band (south to north), then by longitude (west to east), so a
lookup reads only the stretch of each band near the point. Columns rather than rows make the file
pack well in the APK, which compresses assets.
"""

import argparse
import io
import struct
import sys
import zipfile

MAGIC = b"SPLC"
VERSION = 1
BANDS_PER_DEGREE = 4
SCALE = 100_000

# Germany and its neighbours, and India: places with more than 500 people. The owner records in
# these countries; elsewhere towns of 15,000 and more are enough.
DETAILED_COUNTRIES = {"DE", "AT", "CH", "NL", "BE", "LU", "FR", "DK", "PL", "CZ", "IN"}

# Historical, abandoned and destroyed populated places.
LEFT_OUT_FEATURES = {"PPLH", "PPLQ", "PPLW"}

# Columns of the GeoNames 'geoname' table (see the readme.txt of the dump).
GEONAME_ID, NAME, LATITUDE, LONGITUDE, FEATURE_CODE, COUNTRY_CODE, POPULATION = 0, 1, 4, 5, 7, 8, 14


def read_places(zip_path):
    """The rows of the one .txt file in a GeoNames zip, as lists of columns."""
    with zipfile.ZipFile(zip_path) as archive:
        texts = [name for name in archive.namelist() if name.endswith(".txt")]
        if len(texts) != 1:
            sys.exit(f"{zip_path}: expected one .txt file, found {texts}")
        with archive.open(texts[0]) as raw:
            for line in io.TextIOWrapper(raw, encoding="utf-8"):
                columns = line.rstrip("\n").split("\t")
                if len(columns) < 19:
                    sys.exit(f"{zip_path}: a row has {len(columns)} columns instead of 19")
                yield columns


def band_of(latitude_e5):
    """The latitude band of a latitude in degrees x 100,000; the north pole is in the last band."""
    last = 180 * BANDS_PER_DEGREE - 1
    return min((latitude_e5 + 90 * SCALE) * BANDS_PER_DEGREE // SCALE, last)


def place_of(columns):
    name = columns[NAME].strip()
    country = columns[COUNTRY_CODE].strip()
    if not name or len(country) != 2 or not country.isascii():
        return None
    latitude = round(float(columns[LATITUDE]) * SCALE)
    longitude = round(float(columns[LONGITUDE]) * SCALE)
    if not (-90 * SCALE <= latitude <= 90 * SCALE and -180 * SCALE <= longitude <= 180 * SCALE):
        return None
    population = int(columns[POPULATION] or 0)
    encoded = name.encode("utf-8")
    if len(encoded) > 255:
        sys.exit(f"The name of GeoNames place {columns[GEONAME_ID]} is longer than 255 bytes")
    if population >= 2 ** 31:
        sys.exit(f"The population of GeoNames place {columns[GEONAME_ID]} does not fit 32 bits")
    return latitude, longitude, population, country, encoded


def select(cities500, cities15000):
    """The kept places: detailed countries from cities500, the rest of the world from cities15000."""
    kept = {}
    for path, keep in ((cities500, lambda c: c in DETAILED_COUNTRIES), (cities15000, lambda c: c not in DETAILED_COUNTRIES)):
        for columns in read_places(path):
            if columns[FEATURE_CODE] in LEFT_OUT_FEATURES or not keep(columns[COUNTRY_CODE]):
                continue
            place = place_of(columns)
            if place is not None:
                kept[columns[GEONAME_ID]] = place
    # Sorted for the lookup, and fully, so the same input always gives the same file.
    return sorted(kept.values(), key=lambda p: (band_of(p[0]), p[1], p[0], -p[2], p[4], p[3]))


def encode(places):
    countries = sorted({p[3] for p in places})
    if len(countries) > 255:
        sys.exit(f"{len(countries)} countries do not fit one byte")
    index = {code: i for i, code in enumerate(countries)}
    out = bytearray()
    out += MAGIC
    out += struct.pack(">BBH", VERSION, BANDS_PER_DEGREE, len(countries))
    for code in countries:
        out += code.encode("ascii")
    out += struct.pack(">I", len(places))
    for column in (0, 1, 2):
        out += struct.pack(f">{len(places)}i", *(p[column] for p in places))
    out += bytes(index[p[3]] for p in places)
    out += bytes(len(p[4]) for p in places)
    for p in places:
        out += p[4]
    return bytes(out), countries


def main():
    parser = argparse.ArgumentParser(description="Build Satori's offline list of places from GeoNames.")
    parser.add_argument("--cities500", required=True, help="path to cities500.zip")
    parser.add_argument("--cities15000", required=True, help="path to cities15000.zip")
    parser.add_argument("--out", required=True, help="the file to write, data/src/main/assets/places.bin")
    args = parser.parse_args()

    places = select(args.cities500, args.cities15000)
    data, countries = encode(places)
    with open(args.out, "wb") as f:
        f.write(data)
    detailed = sum(1 for p in places if p[3] in DETAILED_COUNTRIES)
    print(f"{len(places)} places ({detailed} in {', '.join(sorted(DETAILED_COUNTRIES))}), "
          f"{len(countries)} countries, {len(data)} bytes -> {args.out}")


if __name__ == "__main__":
    main()
