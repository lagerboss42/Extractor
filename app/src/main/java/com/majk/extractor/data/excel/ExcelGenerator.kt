package com.majk.extractor.data.excel

import android.content.Context
import com.majk.extractor.data.model.Shipment
import com.majk.extractor.data.model.ShipmentType
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a minimal XLSX (Office Open XML) without Apache POI.
 * POI often crashes on Android (XMLBeans / missing stax / large multi-release JARs).
 */
object ExcelGenerator {

    fun generate(context: Context, shipments: List<Shipment>): File {
        val file = File(context.getExternalFilesDir(null), "manifest_cleaned.xlsx")
        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zip ->
                fun put(name: String, body: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                put(
                    "[Content_Types].xml",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
                )
                put(
                    "_rels/.rels",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
                )
                put(
                    "xl/workbook.xml",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Manifest" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""
                )
                put(
                    "xl/_rels/workbook.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
                )
                put("xl/styles.xml", stylesXml())
                put("xl/worksheets/sheet1.xml", sheetXml(shipments))
            }
        }
        return file
    }

    private fun stylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
  </fonts>
  <fills count="4">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF1A237E"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFBBDEFB"/></patternFill></fill>
  </fills>
  <borders count="1"><border/></borders>
  <cellStyleXfs count="1"><xf/></cellStyleXfs>
  <cellXfs count="3">
    <xf xfId="0"/>
    <xf xfId="0" fontId="1" fillId="2" applyFont="1" applyFill="1"/>
    <xf xfId="0" fillId="3" applyFill="1"/>
  </cellXfs>
</styleSheet>"""

    private fun sheetXml(shipments: List<Shipment>): String {
        val headers = listOf(
            "Time frame",
            "Adress line 1",
            "Adress line 2",
            "zip",
            "city",
            "lat",
            "lon",
            "Info"
        )
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        sb.append("""<sheetData>""")

        sb.append("""<row r="1">""")
        headers.forEachIndexed { i, h ->
            val ref = cellRef(i, 0)
            sb.append("""<c r="$ref" t="inlineStr" s="1"><is><t>${xml(h)}</t></is></c>""")
        }
        sb.append("</row>")

        shipments.forEachIndexed { rowIdx, s ->
            val r = rowIdx + 2
            val values = listOf(
                s.toTimeFrameColumn(),
                s.excelRecipient(),
                s.address,
                s.zip,
                s.city,
                s.lat?.toString().orEmpty(),
                s.lon?.toString().orEmpty(),
                s.toInfoColumn()
            )
            val styleAttr = if (s.type == ShipmentType.PICK) """ s="2"""" else ""
            sb.append("""<row r="$r">""")
            values.forEachIndexed { i, v ->
                val ref = cellRef(i, rowIdx + 1)
                sb.append("""<c r="$ref" t="inlineStr"$styleAttr><is><t>${xml(v)}</t></is></c>""")
            }
            sb.append("</row>")
        }

        sb.append("</sheetData>")
        if (shipments.isNotEmpty()) {
            val lastCol = cellRef(headers.size - 1, 0).replace("1", "")
            val lastRow = shipments.size + 1
            sb.append("""<autoFilter ref="A1:${lastCol}$lastRow"/>""")
        }
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun cellRef(col: Int, rowZeroBased: Int): String {
        var c = col
        val name = StringBuilder()
        do {
            name.insert(0, ('A'.code + c % 26).toChar())
            c = c / 26 - 1
        } while (c >= 0)
        return "${name}${rowZeroBased + 1}"
    }

    private fun xml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .map { ch -> if (ch.code in 0..8 || ch.code == 0xB || ch.code == 0xC || ch.code in 0xE..0x1F) ' ' else ch }
            .joinToString("")
}
