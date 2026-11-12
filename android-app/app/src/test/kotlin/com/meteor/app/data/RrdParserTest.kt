package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class RrdParserTest {
    private val host = "11111111-1111-1111-1111-111111111111"
    private val vm = "22222222-2222-2222-2222-222222222222"
    private val other = "33333333-3333-3333-3333-333333333333"
    private val legend = listOf("AVERAGE:host:$host:cpu_avg", "AVERAGE:vm:$vm:cpu0", "AVERAGE:vm:$vm:memory", "AVERAGE:vm:$other:cpu0")
    private val rows = listOf(
        1_700_000_010L to listOf("0.50", "NaN", "2048", "0.9"),
        1_700_000_005L to listOf("0.25", "0.10", "1024", "0.8"),
        1_700_000_000L to listOf("0.20", "0.05", "1024", "0.7"),
    )

    private fun xml(legend: List<String> = this.legend, rows: List<Pair<Long, List<String>>> = this.rows, step: Long = 5,
        start: Long = 1_700_000_000, end: Long = 1_700_000_010, columns: Int? = legend.size, extra: String = "") =
        ("<?xml version=\"1.0\"?><xport><meta><start>$start</start><step>$step</step><end>$end</end><rows>${rows.size}</rows>" +
            (columns?.let { "<columns>$it</columns>" } ?: "") + "<legend>" + legend.joinToString("") { "<entry>$it</entry>" } + "</legend></meta>" +
            "<data>" + rows.joinToString("") { (t, values) -> "<row><t>$t</t>" + values.joinToString("") { "<v>$it</v>" } + "</row>" } +
            "</data>$extra</xport>").toByteArray()
    private fun json(legend: List<String> = this.legend, rows: List<Pair<Long, List<String>>> = this.rows, step: Long = 5,
        start: Long = 1_700_000_000, end: Long = 1_700_000_010, columns: Int? = legend.size, data: String? = null) =
        ("{\"meta\":{\"start\":$start,\"step\":$step,\"end\":$end,\"rows\":${rows.size}" + (columns?.let { ",\"columns\":$it" } ?: "") +
            ",\"legend\":[" + legend.joinToString(",") { "\"$it\"" } + "]},\"data\":" +
            (data ?: ("[" + rows.joinToString(",") { (t, values) -> "{\"t\":$t,\"values\":[" + values.joinToString(",") + "]}" } + "]")) + "}").toByteArray()
    private fun forVm(body: ByteArray) = RrdParser.parse(body) { it.kind == "vm" && it.uuid == vm }
    private fun rejected(body: ByteArray, wanted: (RrdLegend) -> Boolean = { true }) =
        runCatching { RrdParser.parse(body, wanted) }.exceptionOrNull() != null

    @Test fun xmlRowsArriveNewestFirstAndAreReturnedOldestFirstWithMissingValuesPreserved() {
        val update = forVm(xml())
        assertEquals(listOf(1_700_000_000L, 1_700_000_005L, 1_700_000_010L), update.timestamps)
        assertEquals(5L, update.step); assertEquals(1_700_000_000L, update.start); assertEquals(1_700_000_010L, update.end)
        assertEquals(4, update.legendCount)
        assertEquals(listOf("cpu0", "memory"), update.columns.map { it.legend.name })
        val cpu = update.columns[0].values
        assertEquals(0.05, cpu[0], 0.0); assertEquals(0.10, cpu[1], 0.0)
        assertTrue("NaN stays missing, never zero", cpu[2].isNaN())
        assertEquals(listOf(1024.0, 1024.0, 2048.0), update.columns[1].values)
    }

    @Test fun jsonFormIsEquivalentToTheXmlForm() {
        val ascending = rows.reversed().map { (t, values) -> t to values.map { if (it == "NaN") "null" else it } }
        assertEquals(forVm(xml()), forVm(json(rows = ascending)))
        // XAPI's OCaml writer can emit a bare NaN token, and the wrapper form mirrors the XML `data/row/v` shape.
        val bare = forVm(json(rows = rows.reversed()))
        assertTrue(bare.columns[0].values[2].isNaN())
        val wrapped = "{\"row\":[{\"t\":1700000000,\"v\":[0.2,0.05,1024,0.7]},{\"t\":1700000005,\"v\":[\"NaN\",\"inf\",\"-inf\",\"\"]}]}"
        val update = forVm(json(rows = emptyList(), data = wrapped))
        assertEquals(listOf(1_700_000_000L, 1_700_000_005L), update.timestamps)
        assertTrue(update.columns.flatMap { it.values.drop(1) }.all { it.isNaN() })
    }

    @Test fun everyNonFiniteSpellingBecomesMissingAndOnlyRealNumbersSurvive() {
        val tokens = listOf("NaN", "nan", "-nan", "inf", "-inf", "Infinity", "-Infinity", "", "0", "-1.5", "1e3")
        val body = xml(legend = tokens.indices.map { "AVERAGE:vm:$vm:v$it" }, rows = listOf(1L to tokens), columns = null)
        val values = RrdParser.parse(body) { true }.columns.map { it.values.single() }
        assertTrue(values.take(8).all { it.isNaN() })
        assertEquals(listOf(0.0, -1.5, 1000.0), values.drop(8))
    }

    @Test fun legendEntriesParseIntoConsolidationKindUuidAndName() {
        val entry = RrdLegend.parse("AVERAGE:VM:${vm.uppercase()}:vbd_xvda_read")!!
        assertEquals(RrdLegend("AVERAGE", "vm", vm, "vbd_xvda_read"), entry)
        assertEquals("a:b", RrdLegend.parse("AVERAGE:host:$host:a:b")!!.name)
        assertEquals("MAX", RrdLegend.parse("MAX:host:$host:cpu_avg")!!.consolidation)
        listOf("AVERAGE:vm:$vm", "average:vm:$vm:cpu0", "AVERAGE::$vm:cpu0", "AVERAGE:vm:not a uuid:cpu0", "", "AVERAGE:vm:$vm:", "AVERAGE:vm:$vm:${"x".repeat(400)}")
            .forEach { assertNull(it, RrdLegend.parse(it)) }
    }

    @Test fun onlyWantedColumnsAreRetainedAndMissingColumnsAreAbsentRatherThanZero() {
        val update = RrdParser.parse(xml()) { it.name == "cpu_avg" }
        assertEquals(listOf("cpu_avg"), update.columns.map { it.legend.name })
        assertEquals(4, update.legendCount)
        assertTrue(RrdParser.parse(xml()) { it.name == "memory_internal_free" }.columns.isEmpty())
        assertEquals(3, RrdParser.parse(xml()) { false }.timestamps.size)
        // An unparsable legend entry is a column nobody can select; the row layout still has to match.
        val odd = RrdParser.parse(xml(legend = listOf("bogus", "AVERAGE:vm:$vm:cpu0"), rows = listOf(1L to listOf("9", "0.4")))) { true }
        assertEquals(0.4, odd.columns.single().values.single(), 0.0)
    }

    @Test fun stepComesFromTheServerAndTimestampsMustBeUniqueAndPositive() {
        assertEquals(60L, forVm(xml(step = 60)).step)
        assertEquals(3600L, forVm(json(step = 3600)).step)
        assertTrue(rejected(xml(rows = rows + (1_700_000_005L to listOf("0", "0", "0", "0")))))
        assertTrue(rejected(xml(rows = listOf(0L to listOf("0", "0", "0", "0")))))
        assertTrue(rejected(xml(step = 0)))
        assertTrue(rejected(xml(step = 86_401)))
        assertTrue(rejected(json(step = 0)))
    }

    @Test fun malformedXmlIsRejected() {
        val prolog = "<?xml version=\"1.0\"?>"
        assertTrue(rejected((prolog + "<!DOCTYPE xport [<!ENTITY a \"b\">]>" + String(xml()).removePrefix(prolog)).toByteArray()))
        assertTrue(rejected(String(xml()).replace("<v>0.9</v>", "").toByteArray()))
        assertTrue(rejected(String(xml()).replace("<v>0.9</v>", "<v>0.9</v><v>1</v>").toByteArray()))
        assertTrue(rejected(String(xml()).replace("<v>2048</v>", "<v>lots</v>").toByteArray()))
        assertTrue(rejected(xml(columns = 3)))
        assertTrue(rejected(String(xml()).replace("<meta>", "<meta></meta><meta>").toByteArray()))
        assertTrue(rejected(String(xml()).replace("<xport>", "<other>").replace("</xport>", "</other>").toByteArray()))
        assertTrue(rejected(String(xml()).removeSuffix("</xport>").toByteArray()))
        assertTrue(rejected("<xport><data></data></xport>".toByteArray()))
        assertTrue(rejected("<xport><meta><start>1</start><step>5</step><end>2</end><legend><entry>AVERAGE:vm:$vm:cpu0</entry></legend></meta></xport>".toByteArray()))
        assertTrue(rejected(String(xml()).replace("<t>1700000005</t>", "").toByteArray()))
        assertTrue(rejected(xml(legend = emptyList(), rows = emptyList(), columns = null)))
    }

    @Test fun malformedJsonIsRejected() {
        val good = String(json())
        assertTrue(rejected(good.dropLast(1).toByteArray()))
        assertTrue(rejected("$good trailing".toByteArray()))
        assertTrue(rejected(good.replace("\"meta\":{", "\"meta\":{\"step\":9,").toByteArray()))
        assertTrue(rejected(good.replace("\"values\":[0.20,", "\"values\":[").toByteArray()))
        assertTrue(rejected(good.replace("0.50", "\"lots\"").toByteArray()))
        assertTrue(rejected(good.replace("\"data\":[", "\"data\":{\"x\":[").replace("}]}", "}]}}").toByteArray()))
        assertTrue(rejected("{\"data\":[]}".toByteArray()))
        assertTrue(rejected("[]".toByteArray()))
        assertTrue(rejected(json(legend = legend, data = "[{\"t\":1,\"values\":[1,2,3,4,5]}]")))
        assertTrue(rejected(json(data = "[" .repeat(40) + "]".repeat(40))))
        assertTrue(rejected(good.replace("\"AVERAGE:host:$host:cpu_avg\"", "7").toByteArray()))
        assertTrue(rejected(json(columns = 9)))
        assertTrue(rejected(("{\"meta\":{\"start\":1,\"step\":5,\"end\":2,\"legend\":[" + (0..20_000).joinToString(",") { "\"AVERAGE:vm:$vm:c$it\"" } + "]},\"data\":[]}").toByteArray()))
    }

    @Test fun inputOfTheWrongSizeOrShapeIsRejectedBeforeParsing() {
        assertTrue(rejected(ByteArray(0)))
        assertTrue(rejected("   \n".toByteArray()))
        assertTrue(rejected("plain text".toByteArray()))
        assertTrue(rejected(ByteArray(RrdParser.MAX_BYTES + 1) { '<'.code.toByte() }))
        val many = (0..RrdParser.MAX_ROWS).map { (it + 1L) to listOf("0", "0", "0", "0") }
        assertTrue(rejected(xml(rows = many)))
        assertEquals(RrdParser.MAX_ROWS, forVm(xml(rows = many.take(RrdParser.MAX_ROWS))).timestamps.size)
    }

    @Test fun aLeadingByteOrderMarkAndWhitespaceAreTolerated() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(forVm(xml()), forVm(bom + "  \n".toByteArray() + xml()))
        assertEquals(forVm(json()), forVm(bom + json()))
    }

    @Test fun jsonReaderHandlesEscapesNumbersAndBareNonFiniteTokens() {
        val parsed = JsonLite.parse("""{"a":"x\n\u0041\"\\/","b":[1,-2,3.5,1e2,true,false,null,NaN,Infinity,-Infinity],"c":{}}""") as Map<*, *>
        assertEquals("x\nA\"\\/", parsed["a"])
        val list = parsed["b"] as List<*>
        assertEquals(listOf<Any?>(1L, -2L, 3.5, 100.0, true, false, null), list.take(7))
        assertTrue((list[7] as Double).isNaN()); assertEquals(Double.POSITIVE_INFINITY, list[8]); assertEquals(Double.NEGATIVE_INFINITY, list[9])
        assertTrue((parsed["c"] as Map<*, *>).isEmpty())
        listOf("{\"a\":1,}", "[1 2]", "{\"a\" 1}", "\"open", "01", "1.", "[", "{\"a\":1,\"a\":2}", "\"\\x\"", "\"\u0001\"", "tru").forEach {
            assertTrue(it, runCatching { JsonLite.parse(it) }.isFailure)
        }
    }
}
