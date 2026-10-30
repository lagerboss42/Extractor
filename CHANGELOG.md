# Changelog

All notable changes to **Extractor** (`com.majk.extractor`).  
Version scheme: `0.x.ya` — bump the last number by 1 per change; keep the `a` suffix until stable.

---

## Pre-versioning (memo)

Early work before numbered releases:

- Cleaned a Swedish Opter-style transport manifest (image/PNG) into a structured Excel table (address, zip, city, colli, comment).
- Defined the goal: a **non-root** Android app that captures **Opter Driver** (`com.opter.driver`) plain HTTP on **port 81**, parses stops, and exports Spoke-ready Excel — no manual retyping.
- First capture approach: `VpnService` + userspace TCP relay (protected sockets) so traffic can be sniffed without root.
- Initial parsers for tab-separated **DEL** load-scan records and binary **TLV** list-load; role-based delivery vs pickup (Alltransport / terminal streets).
- Basic cleaning: carrier shortening, company-only recipient names, address spacing, weight rounding.
- First consolidations: Toyota/BT/TMH-style **Port K** (Mjölby) and **Cloetta** merges with fixed coordinates.
- Optional **kkböj** fixed stops (toggleable recurring stops).
- SQLite **entry-code (PK) memory** and visit log; searchable Database UI tab.
- Night-only Material 3 UI (dark grey, neon green controls, white body text).
- Apache **POI** dropped after Android crashes (XMLBeans/stax); replaced with a minimal OOXML (Zip + XML) XLSX writer.
- Capture **dedupe** and **JSON persistence** so stop counts stay stable and data survives crashes.

---

## [0.2.3a]

- Early packaged source snapshot of the VPN capture + parse + Excel pipeline.
- Baseline project layout under `com.majk.extractor`.

---

## [0.3.0a]

- Versioned 0.3.x line starts.
- VPN restricted to Opter Driver when the package is present (full-tunnel fallback otherwise).
- DEL preferred over TLV; embedded address blocks as additional source.
- English in-app UI strings.

---

## [0.3.1a]

- Incremental fixes to parsing, cleaning, and export after live capture tests.
- Packaging and build hygiene for alpha installs.

---

## [0.3.4a]

- Further consolidation and cleaner Excel output after additional PCAP/screenshot manifests.
- kkböj list expanded with more recurring stops (e.g. AniCura and area stops).

---

## [0.3.5a]

- Database tab improvements (entry codes + visits, searchable).
- PK remember/fill behaviour refined (`PK nnnn` live vs `{code}` from DB).

---

## [0.3.6a]

- Night-only UI polish (dark grey, neon green `#00FF41`, white text, black text on buttons).
- Capture persistence and dedupe hardened so re-parsed packets do not inflate stop counts.

---

## [0.3.7a]

- **Port K** consolidation exclusions: rows containing Port F / Gate F / Truckhuset (and Truckgatan 2) are not merged into Port K.
- **Port F** consolidation: Port F / Gate F / Truckhuset / Truckgatan 2 → fixed site (`58.3214745`, `15.1031435`), zip `59535` Mjölby.
- **Port D** consolidation: Port D / Gate D → fixed site (`58.3174964`, `15.1119756`), zip `59535` Mjölby.
- Global coordinate extraction from address/comment/recipient into **lat** / **lon** columns.
- Port K / Cloetta style: coordinates written into the address field when consolidating (later revised).

---

## [0.3.8a]

- Spoke-oriented Excel headers:
  - **Recipient** → **Adress line 1**
  - **Adress** → **Adress line 2**
  - **lat** / **lon** placed immediately before **Info**
- Column order: `Type | Adress line 1 | Adress line 2 | zip | city | lat | lon | Info`
- Type still written as `delivery` / `pickup` (Spoke often ignores Type and treats all as deliveries).

---

## [0.3.9a]

- **Adress line 1** = site/company name for fixed consolidations (`Port K`, `Port F`, `Port D`, `Cloetta Godsmottagning`).
- **Adress line 2** = **empty** for Port K / F / D and Cloetta (location only via **lat** / **lon**).
- Cloetta coordinates updated to **58.5130703**, **15.5011890**.

---

## [0.3.10a]

- **Type** column removed from Excel export.
- New first column **Time frame** (in place of Type):
  - From DEL load-scan timestamps (fields used as earliest/latest delivery window).
  - Format `08-14` when both times have minutes `:00`; otherwise `08:15-14:30`.
  - Empty when no window is present.
- **Pickup** rows use light-blue cell background (`#BBDEFB`) on the whole row.
- Time window fields stored on `Shipment`, merged on consolidations (widest window), persisted with capture JSON.

---

## Upcoming

Starting with **0.3.11a**, each release should append a section here before the versioned zip is produced.
