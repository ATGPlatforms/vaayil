# Third-party notices

## Schibsted Grotesk

Bundled in `core/ui/src/main/res/font/` (latin, weights 400–700):
`schibsted_grotesk_regular.ttf` (400), `schibsted_grotesk_medium.ttf` (500),
`schibsted_grotesk_semibold.ttf` (600), `schibsted_grotesk_bold.ttf` (700).
Static instances served by the Google Fonts CSS API (v7), downloaded 2026-09-20.

Copyright 2023 The Schibsted-Grotesk Project Authors
(https://github.com/schibsted/schibsted-grotesk)

This Font Software is licensed under the SIL Open Font License, Version 1.1.
Full license: https://openfontlicense.org/open-font-license-official-text/
(upstream licence file: https://github.com/google/fonts/blob/main/ofl/schibstedgrotesk/OFL.txt)

## tnum verification record

Requirement:
each bundled weight must carry `tnum` in its `GSUB` feature list, or telemetry
digits jitter. Verified with fontTools 4.65.0 (`ttx -t GSUB`); outputs verbatim
(distinct `FeatureTag` values in the `GSUB` FeatureList, one `tnum` FeatureRecord
per weight), plus the `OS/2` weight class confirming each file matches its name.

```
== schibsted_grotesk_regular.ttf ==
-- distinct GSUB feature tags --
      1 calt
      2 ccmp
      1 dnom
      1 frac
      1 liga
      8 locl
      1 numr
      1 pnum
      1 tnum
-- tnum FeatureRecord count --
1

== schibsted_grotesk_medium.ttf ==
-- distinct GSUB feature tags --
      1 calt
      2 ccmp
      1 dnom
      1 frac
      1 liga
      8 locl
      1 numr
      1 pnum
      1 tnum
-- tnum FeatureRecord count --
1

== schibsted_grotesk_semibold.ttf ==
-- distinct GSUB feature tags --
      1 calt
      2 ccmp
      1 dnom
      1 frac
      1 liga
      8 locl
      1 numr
      1 pnum
      1 tnum
-- tnum FeatureRecord count --
1

== schibsted_grotesk_bold.ttf ==
-- distinct GSUB feature tags --
      1 calt
      2 ccmp
      1 dnom
      1 frac
      1 liga
      8 locl
      1 numr
      1 pnum
      1 tnum
-- tnum FeatureRecord count --
1
```

File identity (`OS/2.usWeightClass` / name table):

```
regular: usWeightClass=400 family='Schibsted Grotesk' subfamily='Regular' full='Schibsted Grotesk Regular'
medium: usWeightClass=500 family='Schibsted Grotesk Medium' subfamily='Regular' full='Schibsted Grotesk Medium'
semibold: usWeightClass=600 family='Schibsted Grotesk SemiBold' subfamily='Regular' full='Schibsted Grotesk SemiBold'
bold: usWeightClass=700 family='Schibsted Grotesk' subfamily='Bold' full='Schibsted Grotesk Bold'
```

All four weights pass: `tnum` present in every bundled file.
