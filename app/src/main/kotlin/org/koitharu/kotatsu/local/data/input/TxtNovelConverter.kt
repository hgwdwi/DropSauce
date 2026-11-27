package org.koitharu.kotatsu.local.data.input

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts a local plain-text novel to an EPUB 3 reading copy. No Android APIs are
 * needed here, so encoding, chapter detection and archive generation are unit-testable.
 * The caller owns the input stream; writeEpub closes the supplied output stream.
 */
internal object TxtNovelConverter {

    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val MAX_CHAPTERS = 10000
    private const val MAX_SECTION_CHARS = 32000

    data class Chapter(val title: String, val body: String)

    private val heading = Regex(
        """^(?:第[零〇一二两三四五六七八九十百千万億亿壹贰叁肆伍陆柒捌玖拾佰仟0-9０-９]+[章节節回卷部篇集](?:[\s　：:、.．\-].*)?|(?:序章|序言|楔子|前言|尾声|尾聲|后记|後記|番外)(?:[\s　：:、.．\-].*)?|Chapter\s+\d+(?:\s+.*)?)$""",
        RegexOption.IGNORE_CASE,
    )

    // Many Chinese web novels omit a separator: 第一章初见. Limit this
    // fallback to short lines without sentence-ending punctuation.
    private val compactHeading = Regex(
        """^第[零〇一二两三四五六七八九十百千万億亿壹贰叁肆伍陆柒捌玖拾佰仟0-9０-９]+[章节節回卷部篇集][^\s　：:、.．\-，。！？；!?;]{1,32}$""",
    )

    private fun isHeading(line: String): Boolean =
        line.length in 1..80 && (heading.matches(line) || compactHeading.matches(line))

    fun read(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (bytes.size() > MAX_INPUT_BYTES - count) throw IOException("TXT exceeds 32 MiB")
            bytes.write(buffer, 0, count)
        }
        return decode(bytes.toByteArray())
    }

    fun decode(bytes: ByteArray): String {
        fun starts(vararg prefix: Int): Boolean =
            bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
        val decoded = when {
            starts(0xef, 0xbb, 0xbf) -> strictDecode(bytes, Charsets.UTF_8, 3)
            starts(0xff, 0xfe) -> strictDecode(bytes, Charsets.UTF_16LE, 2)
            starts(0xfe, 0xff) -> strictDecode(bytes, Charsets.UTF_16BE, 2)
            else -> try {
                strictDecode(bytes, Charsets.UTF_8)
            } catch (_: CharacterCodingException) {
                strictDecode(bytes, Charset.forName("GB18030"))
            }
        }
        if (decoded.indexOf('\u0000') >= 0 || decoded.isBlank()) {
            throw IOException("Not a non-empty text novel")
        }
        return decoded.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset, offset: Int = 0): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()

    fun chapters(text: String): List<Chapter> {
        val result = ArrayList<Chapter>()
        var title = "正文"
        var hasHeading = false
        val body = StringBuilder()
        var hasBodyLine = false

        fun flush() {
            val value = body.toString()
            if (value.isBlank() && hasHeading) {
                if (result.size >= MAX_CHAPTERS) throw IOException("Too many TXT chapters")
                result.add(Chapter(title, value))
            } else if (value.isNotBlank()) {
                var start = 0
                var part = 1
                while (start < value.length) {
                    var end = minOf(value.length, start + MAX_SECTION_CHARS)
                    if (end < value.length) {
                        val newline = value.lastIndexOf('\n', end - 1)
                        if (newline > start + MAX_SECTION_CHARS / 2) end = newline + 1
                        else if (Character.isHighSurrogate(value[end - 1])) end--
                    }
                    if (result.size >= MAX_CHAPTERS) throw IOException("Too many TXT chapters")
                    val label = if (part == 1) title else "$title ($part)"
                    result.add(Chapter(label, value.substring(start, end)))
                    start = end
                    part++
                }
            }
            body.setLength(0)
            hasBodyLine = false
        }

        for (line in text.lineSequence()) {
            val trimmed = line.trim().trim('\u3000')
            if (isHeading(trimmed)) {
                flush()
                title = trimmed
                hasHeading = true
            } else {
                if (hasBodyLine) body.append('\n')
                body.append(line)
                hasBodyLine = true
            }
        }
        flush()
        if (result.isEmpty()) throw IOException("TXT contains no readable chapter body")
        return result
    }

    fun writeEpub(title: String, chapters: List<Chapter>, output: OutputStream) {
        require(chapters.isNotEmpty() && chapters.size <= MAX_CHAPTERS)
        val id = "urn:uuid:" + UUID.randomUUID()
        val safeTitle = xml(title.ifBlank { "未命名小说" })
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val navigation = StringBuilder()
        ZipOutputStream(output).use { zip ->
            val mime = "application/epub+zip".toByteArray(Charsets.UTF_8)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            }
            zip.putNextEntry(mimeEntry)
            zip.write(mime)
            zip.closeEntry()
            zip.putText("META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            chapters.forEachIndexed { index, chapter ->
                val item = "ch" + (index + 1)
                val href = "$item.xhtml"
                val label = xml(chapter.title)
                manifest.append("""<item id="$item" href="$href" media-type="application/xhtml+xml"/>""")
                spine.append("""<itemref idref="$item"/>""")
                navigation.append("""<li><a href="$href">$label</a></li>""")
                val paragraphs = chapter.body.lineSequence().joinToString("") { line ->
                    if (line.isBlank()) "<p>&#160;</p>" else "<p>" + paragraphXml(line) + "</p>"
                }
                zip.putText("EPUB/$href",
                    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh"><head><title>$label</title><meta charset="UTF-8"/><style>p { white-space: pre-wrap; overflow-wrap: break-word; }</style></head><body><h1>$label</h1>$paragraphs</body></html>""")
            }
            zip.putText("EPUB/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh"><head><title>目录</title></head><body><nav epub:type="toc" id="toc"><h1>目录</h1><ol>$navigation</ol></nav></body></html>""")
            val modified = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            zip.putText("EPUB/book.opf",
                """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">$id</dc:identifier><dc:title>$safeTitle</dc:title><dc:language>zh</dc:language><meta property="dcterms:modified">$modified</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""")
        }
    }

    private fun ZipOutputStream.putText(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // Native HtmlCompat collapses runs of ordinary whitespace. Encode only
    // leading ASCII indentation as nonbreaking spaces; preserve full-width
    // spaces and ordinary body text.
    private fun paragraphXml(line: String): String {
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        return buildString {
            for (ch in leading) repeat(if (ch == '\t') 4 else 1) { append("&#160;") }
            append(xml(line.substring(leading.length)))
        }
    }

    private fun xml(value: String): String = buildString {
        var offset = 0
        while (offset < value.length) {
            val cp = value.codePointAt(offset)
            offset += Character.charCount(cp)
            when (cp) {
                38 -> append("&amp;")
                60 -> append("&lt;")
                62 -> append("&gt;")
                34 -> append("&quot;")
                39 -> append("&apos;")
                else -> if (cp == 9 || cp == 10 || cp == 13 ||
                    cp in 0x20..0xd7ff || cp in 0xe000..0xfffd || cp in 0x10000..0x10ffff) {
                    appendCodePoint(cp)
                }
            }
        }
    }
}
