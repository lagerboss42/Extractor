# Extractor

**Version:** `0.4.7a` (alpha). Bump the last number by 1 when there is 1 major change or 3 minor changes. Keep the `a` suffix until the app is stable. In-app UI stays English.

Private single-user Android app that captures **Opter Driver** (`com.opter.driver`) traffic, parses stops, applies cleaning rules, remembers entry codes, geocodes addresses, and exports an Excel file ready for import into **Spoke**.

Non-root. Uses a local `VpnService` + userspace TCP relay. VPN **fails closed** if Opter is not installed (no full tunnel).

Package: **`com.majk.extractor`**

---

## Features

- **VPN capture** – Restricted to **`com.opter.driver`**. Refuses to start if the package is missing. TCP relay uses modular sequence numbers, a reorder buffer, window advertisement, and a FIN retransmit guard. Idle timeout is 120 s. Parse is debounced per session; buffers are capped.
- **Parsers** – Tab-separated `DEL` load-scan records (preferred), binary TLV list-load, embedded address blocks
- **Classification** – delivery vs pickup via status and sender/recipient roles (Alltransport = terminal)
- **Cleaning** – carrier shortening, company-only names, address spacing, weight rounding, place-name street recovery (no invented house numbers)
- **Port K / F / D (Mjölby)** – Toyota/BT/TMH-style stops merged; **Adress line 1** = Port name, **Adress line 2** empty, coords in lat/lon
- **Cloetta** – Cloetta stops merged; **Adress line 1** = Cloetta Godsmottagning, **Adress line 2** empty, coords in lat/lon
- **Entry-code memory** – SQLite DB saves PK per address; fills missing PK on later manifests as `{code}`
- **Visit log** – per address/day: shipments, total weight, date
- **Database tab** – searchable Entry codes and Visits (English UI)
- **Kkböj fixed stops** – toggleable recurring stops with Info = `kkböj` (skipped if the same address already came from Opter)
- **Geocoding** – Nominatim (primary) + optional Lantmäteriet API
- **Excel export** – share/open via FileProvider
- **Capture persistence** – parsed stops saved to disk (survive crash); deduped stop count
- **Excel without Apache POI** – minimal XLSX writer (avoids common Android crashes)
- **Night-only UI** – dark grey background, neon green controls, white body text, black text on buttons (Material 3)

---

## Excel columns (Spoke-oriented)

| Column | Content |
|--------|---------|
| **Time frame** | Delivery window from Opter `EARLIESTDELIVERYTIME` / `LATESTDELIVERYTIME` (DEL timestamps). Format `08-14` when minutes are `:00`, otherwise `08:15-14:30`. Empty if unknown. **Pickup rows** use light-blue cell background |
| **Adress line 1** | Company / recipient name (e.g. `Port K`, `Cloetta Godsmottagning`). **Blank** when carrier is Region, unless several rows share the address and the recipients differ |
| **Adress line 2** | Street + number. **Empty** for Port K / Port F / Port D / Cloetta (location via lat/lon only) |
| **zip** | 5-digit postcode |
| **city** | City (stray trailing digits removed) |
| **lat** / **lon** | Last column, no header. Capture coords preferred; Nominatim only fills this column. |
| **Info** | `colli / weight / pk / other / carrier` (empty parts omitted). Status words and package IDs are stripped. |

Type (`delivery` / `pickup`) is **not** exported (Spoke ignores it). Pickup rows are highlighted in blue instead.

### Info examples

```
4 / 56 kg / TNT
2 / 12 kg / PK 1540 / Fullmakt ytterdörr / Region
1 / 5 kg / {4821} / TNT
kkböj
```

- Live PK from Opter → `PK 1540`
- PK filled from the address database → `{4821}`

---

## Classification rules

| Signal | Type |
|--------|------|
| Status **Lastad** / **Hämtad** / scanned `DEL` | **delivery** |
| Status **Bekräftad** | **pickup** |
| **Avsändare** = Alltransport (terminal) | **delivery** (stop = recipient) |
| **Mottagare** = Alltransport (terminal) | **pickup** (stop = sender) |

Terminal markers: Köpetorpsgatan, Kromgatan, name containing Alltransport.

Prefer **`DEL` scan fields** when present (colli, weight, carrier, roles). List-load TLV is a fallback for stops never scanned.

---

## Carrier shortening

| Pattern | Output |
|---------|--------|
| TNT* | TNT |
| Region* | Region |
| Närkefrakt* | Närke |
| Freja* | Freja |
| Dachser* | Dachser |
| Solar* + F7 / F12 | Solar F7 / Solar F12 |
| Apoteket Nationella* | APONAT |
| TWS* | TWS |
| Packoplock* | Packoplock |
| kkböj | kkböj (unchanged) |

---

## Special consolidations

### Port K / Port F / Port D (Mjölby)

| Port | Match | Adress line 1 | lat / lon | zip |
|------|--------|---------------|-----------|-----|
| **Port K** | Mjölby + `BT`/`TMH`/`TMHS`/`Port K`/`Gate K`/`Toyota`/`Material Handling`/`Hallevad` — **excluding** Port F, Gate F, Truckhuset, Truckgatan 2 | `Port K` | `58.319078` / `15.102488` | 59535 |
| **Port F** | `Port F` / `Gate F` / `Truckhuset`, or address **Truckgatan 2** | `Port F` | `58.3214745` / `15.1031435` | 59535 |
| **Port D** | `Port D` / `Gate D` | `Port D` | `58.3174964` / `15.1119756` | 59535 |

**Adress line 2** is empty. Location is only via **lat** / **lon**.

### Cloetta

If name/address/sender contains **`Cloetta`**:

| Field | Value |
|-------|--------|
| Adress line 1 | `Cloetta Godsmottagning` |
| Adress line 2 | *(empty)* |
| lat / lon | `58.5130703` / `15.5011890` |
| zip / city | `58580` / `LJUNGSBRO` |

Colli/weight summed; distinct carriers joined in Info (e.g. `TNT, Packoplock`).

### Other fixed sites

Matched by recipient (ASM also by Kyrkogatan / Magasinsgatan in Mjölby). Address is the plus code.

| Site | Address |
|------|---------|
| ASM Foods | `84CJ+J3R Mjölby` |
| Nelleman | `93QW+43H Skänninge` |
| GE | `93QV+9M2 Skänninge` |
| Universal Power | `93PW+J25 Skänninge` |
| Runsven | `93JX+Q98 Skänninge` |
| Vestas | `93QR+5H8 Skänninge` |
| Aebi | `93PV+H3J Skänninge` |

### Same company + same address

Multiple consignments merged: colli and weight summed; company-only recipient.

### Missing street / place in name

If street is blank or digits-only and the name looks place-like (`gård`, `torp`, …), the place part of the name is used as Adress. **House numbers are never invented.**

---

## Entry codes & visits (SQLite)

Database file: `/storage/emulated/0/Extractor/extractor.db` (falls back to the app-private file if external storage is not writable). Survives uninstall.

### Entry codes (`pk_codes`)

- Saved when a code is found on generate (PK field or notes matching `PK` / `portkod` / `kod`)
- Allowed charset: letters, digits, `*`, `#` · length **4–10**
- Tied to normalized **address + zip**
- On later generates: if the stop has no PK, the saved code is applied and shown as `{code}`

### Visits (`address_log`)

- One row per address per calendar day: **address, shipments, total weight, date**
- Regenerating the same day **overwrites** that day’s figures (no double-count)
- `kkböj` rows are not recorded

### Database tab

**Manifest** | **Database**  
Database → **Entry codes** / **Visits**, searchable by address, zip, city, or code.

---

## Kkböj (manual fixed stops)

Not required to be in Opter. Toggle chips (green = active). Appended on Excel generate with **Info = kkböj** only, and **only if** that address is not already present from Opter.

| Section | Stops |
|---------|--------|
| **Malmslätt** | AniCura Jägarvallen, Kärna Vårdby, Vårdbostäder, Apoteket Humlan |
| **Ljungsbro** | Ekhult, Kronans Apotek Ljungsbro, Vretaliden, Härnebacken |
| **Vreta Kloster** | Kanalgården |
| **Skänninge** | Anstalten Skänninge (`93XV+5HF Skänninge`), Änggården, Apoteket Skänninge, Eldslukargränd |

---

## How to use

1. Install **Opter Driver** and **Extractor** in the **same Android profile** (personal, not work-vs-personal split)
2. **Start VPN** → accept system VPN permission  
3. Open **Opter Driver** → load/refresh stops (scan packages when you need colli/weight)  
4. **Stop VPN** (optional; can generate while capturing)  
5. Optionally toggle **kkböj** stops  
6. **Generate Excel from Capture**  
7. **Share / Open Excel** → import into Spoke  
8. Use the **Database** tab to search saved entry codes and visit history  

Share the traffic log if a parse looks wrong.

---

## Project structure

```
app/src/main/java/com/majk/extractor/
├── MainActivity.kt                 # Manifest | Database tabs
├── ui/
│   ├── MainScreen.kt               # VPN, Excel, kkböj, Lantmäteriet
│   └── DatabaseScreen.kt           # Searchable entry codes & visits
├── vpn/
│   ├── LocalVpnService.kt          # Tunnel + TCP relay + port-81 log/parse
│   ├── CaptureRepository.kt
│   ├── PacketUtils.kt / TcpSession.kt
│   └── VpnStatus.kt
├── data/
│   ├── parser/
│   │   ├── OpterHttpParser.kt      # DEL + embedded + roles
│   │   └── OpterBinaryParser.kt    # TLV list-load
│   ├── cleaner/ManifestCleaner.kt  # Port K, Cloetta, merges
│   ├── db/AddressStore.kt          # SQLite PK + visit log
│   ├── excel/ExcelGenerator.kt
│   ├── geocode/Geocoder.kt
│   └── model/
│       ├── Shipment.kt
│       ├── FixedStops.kt
│       └── ManifestResult.kt
└── util/
    ├── AddressUtils.kt
    ├── CarrierNames.kt
    └── WeightUtils.kt
```

---

## Build (Android Studio)

1. Open the project folder in Android Studio  
2. Set SDK path in `local.properties`:
   ```
   sdk.dir=/path/to/Android/sdk
   ```
3. Sync Gradle  
4. Run on a **physical device** (VPN needs real networking)  

Debug APK after build:

```
app/build/outputs/apk/debug/app-debug.apk
```

Minimum SDK **26** · Target SDK **35** · Jetpack Compose · Apache POI  
Gradle **8.13** · AGP **8.13.2** · Kotlin **1.9.24**

Uninstall older package IDs before upgrading if the applicationId changed (`com.yourname.opterextractor` → `com.majk.extractor`).

---

## Privacy

Single-user private tool. When Opter is installed, only **`com.opter.driver`** is routed through the VPN. No cloud upload. Entry codes and visit stats stay on-device in SQLite.
