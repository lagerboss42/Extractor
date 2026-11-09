# Extractor — LLM Handoff Document

**Project:** Extractor (Opter Extractor)  
**Platform:** Android (Kotlin, Jetpack Compose)  
**Repo:** GitHub `lagerboss42/Extractor` (private, GitHub Actions builds APK)  
**Drive:** `/pub/Extractor` (testing tree; GitHub is live)  
**Current version:** 0.4.6a  
**Date:** 2026-11-09

---

## 1. What the app does

Extractor is a local VPN + parser Android app that intercepts Opter Driver traffic, extracts delivery/pickup stops from binary TLV and DEL records, and generates a Spoke-compatible Excel manifest. It also has a local SQLite database for remembered portkods and visit history, and a set of fixed "kkböj" stops that can be toggled into the manifest.

Key capabilities:
- Local VPN restricted to `com.opter.driver` (fails closed if the package is missing — no full tunnel)
- Userspace TCP relay with modular sequence numbers, reorder buffer, window advertisement, and FIN retransmit guard
- Debounced parse (one parse per session after a quiet period); parse buffers capped at 2 MB
- DEL records preferred; binary/embedded used only as fallback or when they carry a new package ID
- Load capture file (PCAP/PCAPNG/log) from the UI
- Excel manifest generation with strict formatting rules
- Fixed-site consolidation (Cloetta, Port K/D/F, ASM, Nelleman, GE, Universal Power, Runsven, Vestas, Aebi)
- kkböj fixed stops (recurring non-Opter stops)
- Address database with long-press modify/remove
- External storage with internal fallback + MANAGE_EXTERNAL_STORAGE prompt

---

## 2. Excel manifest rules (strict)

### Columns (in order)
1. **Time frame** — `hh:mm-hh:mm` from DEL columns 48 and 57 (both bounds required; empty if either missing).
2. **Recipient** (Address line 1) — header is blank. Region rows are blank only when the stop was consolidated.
3. **Address** (Address line 2) — header is blank.
4. **zip**
5. **city**
6. **Info** — `colli / weight kg / PK / info / carrier`. Empty parts omitted. PK from the shipment is `PK 1202`; a remembered code is `{1202}`. Info is free text only: package IDs and Opter status words (`Lastad`, `Lossad`, `Makulerad`, `Nekad`, `Bekräftad`) are stripped. kkböj stops append `/ kkböj`.
7. **Lat/lon** — last column, **no header**. Format: `58.123456, 15.123456` (dot decimal, comma-space separator). 4–8 decimals.

### Formatting rules
- **Blue cell background** on pickup rows (light blue fill).
- **Weights always round up** to nearest integer.
- **Never dupe names** in Recipient.
- **Region deliveries have no names** in Recipient only when the stop was consolidated. Otherwise keep the recipient.
- **Only same-type merges**: pickups with pickups, deliveries with deliveries. Never merge pick with del.
- **Carrier shortening**:
  - TNT* → TNT
  - Region* → Region
  - TWS* → TWS
  - Onninen / Kesko → Kesko
  - Solar* + F7 → Solar F7
  - Solar* + F12 → Solar F12
  - Packoplock → Packoplock
  - kkböj stays as kkböj
- **Info field** must include access codes and other free-text info parsed from the shipment when present.
- **Auto-width columns**.

### Coordinate rules
- Prefer capture-sourced coordinates over street address when present — put them in the Address field.
- **Never** put Nominatim-geocoded coordinates in the Address field. Nominatim is for lat/lon column only, and only when no capture coords exist.
- Hardcoded plus codes go in the Address field for fixed consolidations.
- Coordinate format: `58.123456, 15.123456` (dot decimal, comma-space). Also accept `58,123456, 15,123456` and `58,123456 15,123456` on input.
- Area of interest for filtering: lat 56.2–59.9, lon 11.1–19.3.

---

## 3. Fixed-site consolidations (hardcoded)

| Site | Recipient | Address (plus code) | Zip | City |
|------|-----------|---------------------|-----|------|
| Cloetta pickup | `Cloetta` | `GF7X+3V Ljungsbro` | 58580 | Ljungsbro |
| Cloetta godsmottagning | `Cloetta Godsmottagning` | `GG72+6F Ljungsbro` | 58580 | Ljungsbro |
| Port K | `Port K` | `8493+J2P Mjölby` | 59535 | Mjölby |
| Port D | `Port D` | `8496+2RC Mjölby` | 59535 | Mjölby |
| Port F | `Port F` | `84C3+H7H Mjölby` | 59535 | Mjölby |
| ASM Foods | `ASM Foods` | `84CJ+J3R Mjölby` | 59530 | Mjölby |
| Nelleman | `Nelleman` | `93QW+43H Skänninge` | 59631 | Skänninge |
| GE | `GE` | `93QV+9M2 Skänninge` | 59631 | Skänninge |
| Universal Power | `Universal Power` | `93PW+J25 Skänninge` | 59631 | Skänninge |
| Runsven | `Runsven` | `93JX+Q98 Skänninge` | 59631 | Skänninge |
| Vestas | `Vestas` | `93QR+5H8 Skänninge` | 59631 | Skänninge |
| Aebi | `Aebi` | `93PV+H3J Skänninge` | 59631 | Skänninge |

**Rules:**
- Cloetta pickup and godsmottagning are different places. Never merge pick with del.
- Port K excludes Port F / Gate F / Truckhuset / Truckgatan 2 (those go to Port F).
- Plus codes in Address are preferred over street for these fixed consolidations.
- Capture-sourced coords may still go in Address; Nominatim geocodes must **not**.

---

## 4. kkböj fixed stops

Recurring stops not in Opter. Toggled in UI. Appended to Excel with Info containing `kkböj`.

| ID | Section | Recipient | Address | Zip | City |
|----|---------|-----------|---------|-----|------|
| anicura | Malmslätt | AniCura Jägarvallen | Jägarvallsvägen 2 | 58422 | Linköping |
| karna_vardby | Malmslätt | Kärna Vårdby | Kärnavägen 1 | 58662 | Linköping |
| vardbostader | Malmslätt | Vårdbostäder | Kärna Centrum 1 | 58665 | Linköping |
| apotek_humlan | Malmslätt | Apoteket Humlan | Kärnabrunnsgatan 10 | 58665 | Linköping |
| ekhult_sh | Ljungsbro | Ekhult SH | GFH6+F59 Ljungsbro | | Ljungsbro |
| ekhult_soc | Ljungsbro | Ekhult SOC | GFH5+FXH Ljungsbro | | Ljungsbro |
| kronans | Ljungsbro | Kronans Apotek Ljungsbro | Evastigen 9 | 58571 | Ljungsbro |
| vretaliden | Ljungsbro | Vretaliden | Evastigen 19 | 58571 | Ljungsbro |
| harnebacken | Ljungsbro | Härnebacken | Härnegatan 75 | 58574 | Ljungsbro |
| kanalgarden | Vreta Kloster | Kanalgården | Trossvägen 3 | 58577 | Vreta Kloster |
| anstalten | Skänninge | Anstalten Skänninge | 93XV+5HF Skänninge | 59621 | Skänninge |
| anggarden | Skänninge | Änggården | Villagatan 1 | 59633 | Skänninge |
| apotek_skanninge | Skänninge | Apoteket Skänninge | Östra Kyrkogatan 5 | 59634 | Skänninge |
| eldslukargrand | Skänninge | Eldslukargränd | Eldslukargränd 7 | 59634 | Skänninge |

**Note:** Ekhult was recently split into Ekhult SH and Ekhult SOC with separate plus codes. The old single "Ekhult" entry is gone.

---

## 5. Architecture

### Key files
- `vpn/LocalVpnService.kt` — TUN interface, packet loop, DNS relay, session management
- `vpn/TcpSession.kt` — per-connection TCP relay with stream reassembly
- `vpn/CaptureRepository.kt` — in-memory + disk persistence of parsed shipments, dedupe
- `data/parser/OpterHttpParser.kt` — DEL records + embedded address regex
- `data/parser/OpterBinaryParser.kt` — binary TLV list-load parser
- `data/cleaner/ManifestCleaner.kt` — consolidation (Cloetta, Port K/D/F), same-type merges
- `data/excel/ExcelGenerator.kt` — minimal XLSX writer (no Apache POI)
- `data/model/FixedStops.kt` — kkböj stops
- `data/model/Shipment.kt` — data class
- `data/db/AddressStore.kt` — SQLite for portkods and visits
- `ui/MainScreen.kt` — VPN toggle, generate Excel, fixed stops, re-parse
- `ui/DatabaseScreen.kt` — long-press modify/remove for PK and visits
- `util/CarrierNames.kt` — carrier shortening
- `util/TextEncoding.kt` — Swedish mojibake repair
- `util/AddressUtils.kt` — coordinate parsing and formatting

### Storage
- Preferred: `/storage/emulated/0/Extractor/extractor.db`
- Fallback: internal `getDatabasePath("extractor.db")` if external is not writable
- On first run the internal file is copied across. MANAGE_EXTERNAL_STORAGE is requested on Android 11+.

### VPN notes
- Restricted to `com.opter.driver`. Manifest declares the package in `<queries>` so the check works on Android 11+. Fails closed if Opter is not visible.
- DNS relay for UDP/53
- Sessions cleaned up via `onClosed` callback
- 30s socket timeout to prevent hanging
- Does not close immediately on FIN — lets remote side finish

---

## 6. Parsing notes

- Opter wire encoding is often ISO-8859-1 or UTF-8 misinterpreted as Latin-1. `TextEncoding.fixSwedish` repairs single and double mojibake.
- Binary TLV: `0x04 | fieldId_u8 | length_u16_le | bytes`. Address field IDs: 2=name, 6=street, 7=number, 8=zip, 9=city, 11=entry code (`ADR_EntreCode`), 17=info.
- Entry code from field 11 is the portkod. A `PK …` pattern in info is also accepted.
- Time frame comes from DEL columns 48 and 57 (earliest / latest).
- Stops appear as pairs: customer + Alltransport terminal. customer→terminal = pickup; terminal→customer = delivery.
- Terminal streets: Köpetorpsgatan, Kromgatan. Terminal names: Alltransport.
- Free-text coordinates appear in message fields as plain text (e.g. `58.544853, 15.633303`). Attach to nearest shipment lacking coords.
- DEL records are tab-separated with ~30+ fields. Parser looks for `DEL\t` lines.
- Weight = DEL field 39/40. Colli = DEL field 45.
- Package/colli-level IDs (DEL fields 5/7/9) are used as `rawId`. Same address + different package ID = distinct shipments (not deduped).
- Failed-delivery filter: skip only when field 16 is "Nekad" or nearby text contains DEX07/DEX08/DEX17 (or matching Swedish phrases). Never city-based.
- Completeness pass: DEL records are preferred; embedded and binary results are added only when no matching packageId or strong address key already exists. Every unique package identity is kept.

---

## 7. Known issues / recent fixes

- **Sync loop**: Fixed by adding `onClosed` callback so sessions are removed from the map, and a 30s socket timeout. Do not close immediately on FIN.
- **Stop counting**: Binary parser now searches a wider window for colli/weight/carrier. Still imperfect on tightly packed TLV.
- **Regex crash**: PatternSyntaxException from over-escaped hyphens in character classes. Hyphen must be at end of class or escaped correctly in triple-quoted strings.
- **OCR / Accessibility**: Removed in 0.4.0a. Screenshot sharing remains as a fallback.
- **Re-parse button**: Was crashing; fixed by ensuring parser is called on the full log text, not partial buffers.
- **Generate Excel crash**: Related to the same parser issues; fixed.

---

## 8. Workflow conventions

- **GitHub is live.** Drive (`/pub/Extractor`) is testing. When pushing a fix, also update Drive so they don't diverge the wrong way.
- **Only one zip** when providing source: the versioned one (e.g. `Extractor-0.3.10a.zip`), not a second `OpterExtractor.zip`.
- **Version bump rules**: bump the last number by 1 when there is **1 major change or 3 minor changes**. Keep the `a` suffix until stable. Changelog and README updated on version change.
- **Changelog** lives in the base directory. Everything before version numbering is a short memo. From 0.3.11a onward, per-version entries.
- **README, changelog, and guidelines** live on Drive only (not in the GitHub tree for the user-facing docs).
- **No GitHub-specific files** on Drive (no `.github/`, no workflow YAMLs in the Drive tree).

---

## 9. Build

- GitHub Actions builds the APK (no local Android Studio required).
- Workflow uses `android-actions/setup-android` + `sdkmanager` for platform-tools, platforms;android-35, build-tools;35.0.0.
- APK is `app-debug.apk`. Install via a non-Google Files app if Play Protect blocks it.
- Device: Bluebird S20, Android 13.

---

## 10. Database long-press menu

On the database view, long-press a row to open a menu with:
- **Modify** — popup with input for PK, or three fields (times visited, kilos, date) depending on which database.
- **Remove** — confirmation popup; hold the confirm button for 2 seconds to confirm.

---

## 11. What not to do

- Do not merge Cloetta pickups with Cloetta deliveries.
- Do not put Nominatim geocodes in the Address field.
- Do not insert names for Region deliveries.
- Do not use plus codes for non-fixed sites unless they came from the capture.
- Do not provide two zips when updating source.
- Do not bump version for every small fix.
- Do not leave GitHub-specific files in the Drive tree.
- Do not close TCP sessions immediately on FIN (causes sync loops).
- Do not use Apache POI for Excel generation (crashes on Android).

---

## 12. Current state (2026-11-05)

- Version `0.4.0a`.
- Failed-delivery filter is DEX/NEKAD-only (never city-based).
- Distinct colli are preserved via package-level IDs (DEL fields 5/7/9).
- Completeness pass merges DEL + binary/embedded without dropping unique packages.
- VPN session cleanup and timeout fixes are in the local workspace.
- Ekhult SH/SOC split is in the local workspace.
- Re-parse and Generate Excel buttons should no longer crash.
- Storage fallback and MANAGE_EXTERNAL_STORAGE prompt are in place.

