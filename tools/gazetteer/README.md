# The offline list of places

Satori names the place where a recording starts ("Save the place" in Settings) from a list of
places bundled with the app, `data/src/main/assets/places.bin`, so the name needs no network. The
list is built from [GeoNames](https://www.geonames.org) data, licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) (credited on the app's Licences screen),
by `build_places.py`, which documents what it keeps and the file format.

- Germany, its neighbours (AT, CH, NL, BE, LU, FR, DK, PL, CZ) and India: every place of
  `cities500` (more than 500 people, or a seat of an administrative division).
- Everywhere else: every place of `cities15000` (more than 15,000 people, or a capital).
- Without historical, abandoned and destroyed places.

The file in the repository was built from the GeoNames dump of 2026-10-12: 78,416 places,
1,887,530 bytes, about 1.1 MB once the APK compresses it.

## Build it again

GeoNames changes a little every day; there is no need to rebuild unless places are missing. Python
3 with its standard library is all it takes. Download the two files into an empty folder of their
own and pass their paths, from the repository's root:

```sh
mkdir -p /tmp/geonames && cd /tmp/geonames
curl -O https://download.geonames.org/export/dump/cities500.zip
curl -O https://download.geonames.org/export/dump/cities15000.zip
cd -
python3 -I tools/gazetteer/build_places.py \
    --cities500 /tmp/geonames/cities500.zip \
    --cities15000 /tmp/geonames/cities15000.zip \
    --out data/src/main/assets/places.bin
```

The same input always gives the same file. `PlaceFileTest` in the data module reads the bundled
file and looks up a few places in it; run `./gradlew :data:testDebugUnitTest` afterwards.
