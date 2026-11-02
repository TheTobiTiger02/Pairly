package dev.pairly.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every English string has a German translation and vice versa, with the same placeholders. */
class StringsParityTest {
    private val res = File("src/main/res")

    private fun strings(folder: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        File(res, folder).listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            for (tag in listOf("string", "plurals")) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    result[node.attributes.getNamedItem("name").nodeValue] = node.textContent
                }
            }
        }
        return result
    }

    private fun placeholders(text: String) = Regex("%\\d\\$[sd]").findAll(text).map { it.value }.toSortedSet()

    @Test
    fun germanCoversEnglish() {
        val english = strings("values")
        val german = strings("values-de")
        val missing = english.keys - german.keys
        val extra = german.keys - english.keys
        assertTrue("Missing German strings: $missing", missing.isEmpty())
        assertTrue("German strings without English: $extra", extra.isEmpty())
        val mismatched = english.filter { (key, value) -> placeholders(value) != placeholders(german.getValue(key)) }.keys
        assertTrue("Placeholders differ: $mismatched", mismatched.isEmpty())
    }
}
