# Changelog

All notable changes to **Extractor** (`com.majk.extractor`).  
Version scheme: `0.x.ya` — bump only per project guidelines (see GUIDELINES.md).

---

## [0.4.0a]

- **Removed OCR** (ML Kit image import) and **AccessibilityService**. Screenshot share intent kept as fallback.
- **Database moved** to external storage: `/storage/emulated/0/extractor/extractor.db` (persistent across app reinstalls on most devices).
- Free-text coordinate extraction from message fields.

---

## Pre-versioning (memo)

Early work before numbered releases: VPN capture, DEL/TLV parsers, Port/Cloetta consolidations, kkböj, SQLite PK memory, night UI, OOXML Excel (no POI), capture persistence.

---

## [0.2.3a] through [0.3.10a]

See prior Drive history (Port K/F/D, Cloetta, Time frame, pickup row colour, Spoke headers).

---

## [0.3.11a]

- **UI safe area:** system bars padding.
- **Share Excel / log:** FileProvider external-files-path; no crash on share.

---

## [0.3.12a]

- **DEL parse fix:** robust load-scan, encoding fold for terminal streets.
- **VPN:** keep stops across Start (log still resets).
