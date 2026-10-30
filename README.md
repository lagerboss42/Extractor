# Extractor

**Version:** `0.3.10a` (alpha). Bump the last number by 1 per change. Keep the `a` suffix until the app is stable. In-app UI stays English.

Private single-user Android app that captures **Opter Driver** (`com.opter.driver`) traffic (plain HTTP on **port 81**), parses stops, applies cleaning rules, remembers entry codes, geocodes addresses, and exports an Excel file ready for import into **Spoke**.

Non-root. Uses a local `VpnService` + userspace TCP relay.

Package: **`com.majk.extractor`**

---

## Features

- **VPN capture** – Restricted to **`com.opter.driver`** when installed
- **Parsers** – DEL load-scan (preferred), binary TLV list-load
- **Cleaning** – Port K/F/D, Cloetta consolidations; carrier shortening
- **Entry-code memory** – SQLite PK per address; `{code}` when filled from DB
- **Visit log** – per address/day shipments and weight
- **Kkböj** – toggleable fixed stops
- **Geocoding** – Nominatim + optional Lantmäteriet
- **Excel** – Spoke-oriented columns; pickup rows light blue; Time frame column
- **Night-only UI** – dark grey, neon green `#00FF41`

## Excel columns

| Column | Content |
|--------|---------|
| **Time frame** | `08-14` or `08:15-14:30` from DEL window; empty if unknown |
| **Adress line 1** | Company / site name |
| **Adress line 2** | Street (empty for Port*/Cloetta) |
| **zip** / **city** | Postcode and city |
| **lat** / **lon** | Coordinates |
| **Info** | colli / weight / pk / other / carrier |

## Build

GitHub Actions: **Actions → Build APK → Run workflow** (or push to `main`).

Local: Android Studio, `sdk.dir` in `local.properties`, Gradle 8.13, AGP 8.13.2, Kotlin 1.9.24, minSdk 26, targetSdk 35.

See `CHANGELOG.md` for version history.
