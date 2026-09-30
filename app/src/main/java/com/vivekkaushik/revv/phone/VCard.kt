package com.vivekkaushik.revv.phone

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** One vCard's properties in the order the phone sent them, names in upper case. */
class VCard(val properties: List<Property>) {

    /** A line such as `TEL;TYPE=CELL:+91 98100 12345`: name "TEL", parameters ["TYPE=CELL"], and the value. */
    data class Property(val name: String, val parameters: List<String>, val value: String)

    fun first(name: String): Property? = properties.firstOrNull { it.name == name }

    fun all(name: String): List<Property> = properties.filter { it.name == name }

    companion object {

        /** The vCards in [text], version 2.1 or 3.0: folded lines joined and quoted-printable decoded. */
        fun parse(text: String): List<VCard> {
            val cards = mutableListOf<VCard>()
            var card: MutableList<Property>? = null
            for (line in unfold(text)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val head = line.substring(0, colon).split(';')
                // Drops a group prefix, as in "item1.TEL".
                val name = head.first().substringAfter('.').trim().uppercase()
                val parameters = head.drop(1)
                val value = line.substring(colon + 1)
                when {
                    name == "BEGIN" && value.trim().equals("VCARD", ignoreCase = true) -> card = mutableListOf()
                    name == "END" && value.trim().equals("VCARD", ignoreCase = true) -> {
                        card?.let { cards += VCard(it) }
                        card = null
                    }
                    else -> card?.add(Property(name, parameters, decode(value, parameters)))
                }
            }
            return cards
        }

        /** 3.0 text with its backslash escapes undone: `Sharma\, Priya` is "Sharma, Priya". */
        fun unescape(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\\' && i + 1 < text.length) {
                    val next = text[i + 1]
                    out.append(if (next == 'n' || next == 'N') '\n' else next)
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /** A structured value's parts, split at the semicolons that aren't escaped. */
        fun components(value: String): List<String> {
            val parts = mutableListOf<String>()
            val part = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '\\' && i + 1 < value.length -> {
                        part.append(c).append(value[i + 1])
                        i += 2
                        continue
                    }
                    c == ';' -> {
                        parts += unescape(part.toString())
                        part.clear()
                    }
                    else -> part.append(c)
                }
                i++
            }
            parts += unescape(part.toString())
            return parts
        }

        private fun unfold(text: String): List<String> {
            val lines = mutableListOf<String>()
            for (line in text.split("\r\n", "\n")) {
                val last = lines.lastOrNull()
                when {
                    last != null && (line.startsWith(" ") || line.startsWith("\t")) -> lines[lines.lastIndex] = last + line.substring(1)
                    // In 2.1, a quoted-printable value ending in "=" carries on on the next line.
                    last != null && last.endsWith("=") && isQuotedPrintable(last) -> lines[lines.lastIndex] = last.dropLast(1) + line
                    else -> lines += line
                }
            }
            return lines
        }

        private fun isQuotedPrintable(line: String): Boolean =
            line.substringBefore(':').contains("QUOTED-PRINTABLE", ignoreCase = true)

        private fun decode(value: String, parameters: List<String>): String {
            if (parameters.none { it.contains("QUOTED-PRINTABLE", ignoreCase = true) }) return value
            val charset = parameters.firstOrNull { it.startsWith("CHARSET=", ignoreCase = true) }
                ?.let { runCatching { Charset.forName(it.substringAfter('=')) }.getOrNull() }
                ?: Charsets.UTF_8
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val hex = if (value[i] == '=' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                } else {
                    bytes.write(value[i].code and 0xFF)
                    i++
                }
            }
            return String(bytes.toByteArray(), charset)
        }
    }
}
