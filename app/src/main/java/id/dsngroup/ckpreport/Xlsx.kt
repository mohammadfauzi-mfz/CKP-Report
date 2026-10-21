package id.dsngroup.ckpreport

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Foto yang ditanam di sel: data JPEG + ukuran tampil (piksel). */
class XlsxFoto(val jpeg: ByteArray, val tampilW: Int, val tampilH: Int)

/**
 * Penulis file .xlsx minimal (tanpa library): satu sheet, header tebal, angka, persen, dan foto tertanam.
 * Nilai sel: Number -> angka, String -> teks, null/"" -> kosong.
 */
object Xlsx {

    fun kolom(i: Int): String {          // 0 -> A, 25 -> Z, 26 -> AA
        var n = i
        val sb = StringBuilder()
        do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 32 && ch != '\n' && ch != '\t' -> {}       // karakter kontrol tidak sah di XML
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun angka(v: Number): String {
        val d = v.toDouble()
        return if (d.isNaN() || d.isInfinite()) "0" else if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    /**
     * @param headers judul kolom
     * @param rows data per baris (jumlah sel = headers.size)
     * @param persenKolom indeks kolom (0-based) yang berisi pecahan untuk ditampilkan sebagai persen
     * @param fotoKolom judul kolom foto (ditambah di kanan) bila ada foto; null = tanpa kolom foto
     * @param foto foto per baris (indeks sama dengan rows), null = tidak ada
     */
    fun tulis(
        out: OutputStream,
        namaSheet: String,
        headers: List<String>,
        rows: List<List<Any?>>,
        persenKolom: Set<Int> = emptySet(),
        fotoKolom: String? = null,
        foto: List<XlsxFoto?> = emptyList(),
    ) {
        val adaFoto = fotoKolom != null && foto.any { it != null }
        val nKol = headers.size + if (adaFoto) 1 else 0
        val fotoIdx = headers.size

        val sheet = StringBuilder()
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
            .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sheet.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        sheet.append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        sheet.append("<cols>")
        for (c in 0 until nKol) {
            val w = if (c == fotoIdx && adaFoto) 32.0 else {
                val h = headers[c]
                when {
                    h.contains("Link") -> 34.0
                    h.contains("Problem") || h.contains("Perbaikan") -> 38.0
                    else -> (h.length + 4).coerceIn(12, 30).toDouble()
                }
            }
            sheet.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$w\" customWidth=\"1\"/>")
        }
        sheet.append("</cols><sheetData>")

        // header
        sheet.append("<row r=\"1\">")
        for (c in 0 until nKol) {
            val t = if (c < headers.size) headers[c] else (fotoKolom ?: "Foto")
            sheet.append("<c r=\"${kolom(c)}1\" t=\"inlineStr\" s=\"1\"><is><t>${esc(t)}</t></is></c>")
        }
        sheet.append("</row>")

        // baris data
        for ((ri, row) in rows.withIndex()) {
            val r = ri + 2
            val f = foto.getOrNull(ri)
            val ht = if (f != null) " ht=\"${(f.tampilH * 0.75 + 6).toInt()}\" customHeight=\"1\"" else ""
            sheet.append("<row r=\"$r\"$ht>")
            for (c in headers.indices) {
                val v = row.getOrNull(c)
                val ref = "${kolom(c)}$r"
                when {
                    v == null -> {}
                    v is Number -> sheet.append("<c r=\"$ref\"${if (c in persenKolom) " s=\"2\"" else ""}><v>${angka(v)}</v></c>")
                    v.toString().isEmpty() -> {}
                    else -> sheet.append("<c r=\"$ref\" t=\"inlineStr\" s=\"3\"><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData>")
        if (adaFoto) sheet.append("<drawing r:id=\"rId1\"/>")
        sheet.append("</worksheet>")

        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                (if (adaFoto) "<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>" else "") +
                "</Types>")
            put("_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>")
            put("xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"${esc(namaSheet.take(31))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>")
            // s=0 biasa, 1 header tebal, 2 persen 0.00%, 3 teks rata atas
            put("xl/styles.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.00%\"/></numFmts>" +
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
                "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8F5E9\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"4\">" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>" +
                "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>" +
                "</cellXfs>" +
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                "</styleSheet>")
            put("xl/worksheets/sheet1.xml", sheet.toString())

            if (adaFoto) {
                put("xl/worksheets/_rels/sheet1.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/>" +
                    "</Relationships>")
                val dr = StringBuilder()
                val rels = StringBuilder()
                dr.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                    .append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" ")
                    .append("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
                    .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                rels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                    .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                var n = 0
                for ((ri, f) in foto.withIndex()) {
                    if (f == null || ri >= rows.size) continue
                    n++
                    val cx = f.tampilW.toLong() * 9525L
                    val cy = f.tampilH.toLong() * 9525L
                    dr.append("<xdr:oneCellAnchor><xdr:from><xdr:col>$fotoIdx</xdr:col><xdr:colOff>38100</xdr:colOff>")
                        .append("<xdr:row>${ri + 1}</xdr:row><xdr:rowOff>38100</xdr:rowOff></xdr:from>")
                        .append("<xdr:ext cx=\"$cx\" cy=\"$cy\"/>")
                        .append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"${n + 1}\" name=\"Foto $n\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr>")
                        .append("<xdr:blipFill><a:blip r:embed=\"rId$n\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>")
                        .append("<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr>")
                        .append("</xdr:pic><xdr:clientData/></xdr:oneCellAnchor>")
                    rels.append("<Relationship Id=\"rId$n\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/foto$n.jpg\"/>")
                    z.putNextEntry(ZipEntry("xl/media/foto$n.jpg")); z.write(f.jpeg); z.closeEntry()
                }
                dr.append("</xdr:wsDr>")
                rels.append("</Relationships>")
                put("xl/drawings/drawing1.xml", dr.toString())
                put("xl/drawings/_rels/drawing1.xml.rels", rels.toString())
            }
        }
    }
}
