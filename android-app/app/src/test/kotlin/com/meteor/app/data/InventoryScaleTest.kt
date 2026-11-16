package com.meteor.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.management.ManagementFactory

// --- inventory scale ---
/**
 * Pools far larger than the lab, built from the real XCP-ng 8.3.0 records by [LargePool], through the production reader,
 * transport and gateway. Each scale test asserts correctness and a bound on the work: element ticks, characters kept, and
 * bytes allocated by the reading thread (HotSpot's per-thread counter).
 */
class InventoryScaleTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private fun <T> allocating(block: () -> T): Pair<T, Long> {
        val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
        val result = block()
        return result to threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
    }
    /** Heap still in use after a full collection: an estimate of what a result keeps alive (printed, not asserted). */
    private fun usedAfterGc(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }
    /**
     * Bytes the reading thread may allocate per byte on the wire. Measured ~5 on the JVM, where kxml2 makes a new String for
     * every element name; Android's platform KXmlParser pools names, so this bounds the phone from above. All of it is
     * short-lived: what stays is bounded by the kept characters asserted separately.
     */
    private val allocationPerWireByte = 8
    private val authority get() = Live83.pool()["uuid"] as String
    private fun normal(vm: Map<String, Any?>) = vm["is_a_template"] != true && vm["is_control_domain"] != true && vm["is_a_snapshot"] != true

    // ---- the generator ---------------------------------------------------------------------------------------------

    @Test fun theGeneratorKeepsTheRealRecordFieldsAndSizes() {
        val pool = LargePool(guests = 3, snapshots = 2)
        val tree = XmlRpc.response(pool.answer(null).readBytes().toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals("control domain + 39 templates + 3 guests + 2 snapshots", 45, tree.size)
        val guest = Live83.guest()
        val generated = tree.values.map { it as Map<*, *> }.filter { it["is_a_snapshot"] == false && it["is_a_template"] == false && it["is_control_domain"] == false }
        assertEquals(3, generated.size)
        generated.forEach { assertEquals(guest.keys, it.keys) }
        val snapshots = tree.values.map { it as Map<*, *> }.filter { it["is_a_snapshot"] == true }
        assertEquals(2, snapshots.size); assertTrue(snapshots.all { it["power_state"] == "Halted" && (it["snapshot_of"] as String).startsWith("OpaqueRef:") })
        // Only identities change: a generated guest record is the real one within a few bytes (name length).
        val real = LargePool.guestXml.toByteArray().size
        val one = (pool.answerBytes(XapiQuery.GUESTS) - LargePool.HEAD.length - LargePool.TAIL.length) / 3
        assertTrue("generated $one vs real $real", kotlin.math.abs(one - real) < 80)
        println("SCALE generator: guest record ${real} B, ${tree.size} records in a ${pool.answerBytes(null)} B full answer")
    }

    // ---- the streaming reader keeps the tree reader's meaning --------------------------------------------------------

    @Test fun theStreamReaderKeepsExactlyWhatTheTreeReaderReadsForEveryRecordedAnswer() {
        val classes = listOf("pool", "host", "host_metrics", "VM", "VM_metrics", "VM_guest_metrics", "SR", "PBD", "VBD", "VDI", "VIF", "PIF",
            "PIF_metrics", "network", "task", "message", "console")
        for (cls in classes) {
            val tree = Live83.records(cls)
            val fields = tree.values.flatMap { it.keys }.toSet()
            val read = XmlRpcRecords.collect(Live83.response("$cls.get_all_records").byteInputStream(), Projection(fields))
            assertEquals(cls, tree, read.records)
            assertEquals(cls, tree.size, read.total)
            // A projection keeps only what it names.
            val one = XmlRpcRecords.collect(Live83.response("$cls.get_all_records").byteInputStream(), Projection(setOf("uuid")))
            assertTrue(cls, one.records.values.all { it.keys.size <= 1 })
        }
    }

    @Test fun theStreamReaderRejectsWhatTheTreeReaderRejectsWithTheSameErrors() {
        for (name in listOf("error-session-invalid", "error-handle-invalid", "error-uuid-invalid", "error-method-unknown", "error-message-handle")) {
            val tree = runCatching { Live83.value(name) }.exceptionOrNull() as RpcFailure
            val stream = runCatching { XmlRpcRecords.collect(Live83.response(name).byteInputStream(), Projections.GUEST) }.exceptionOrNull() as RpcFailure
            assertEquals(name, tree.code, stream.code); assertEquals(name, tree.details, stream.details); assertTrue(stream.explicitRejection)
        }
        val fault = "<?xml version=\"1.0\"?><methodResponse><fault><value><string>x</string></value></fault></methodResponse>"
        val faulted = runCatching { XmlRpcRecords.collect(fault.byteInputStream(), Projections.GUEST) }.exceptionOrNull() as RpcFailure
        assertEquals("RPC_FAULT", faulted.code); assertFalse(faulted.explicitRejection)
        val dtd = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"b\">]><methodResponse></methodResponse>"
        assertEquals("DTD and entities are not supported.", runCatching { XmlRpcRecords.collect(dtd.byteInputStream(), Projections.GUEST) }.exceptionOrNull()?.message)
        val truncated = Live83.response("VM.get_all_records").let { it.substring(0, it.length / 2) }
        assertTrue(runCatching { XmlRpcRecords.collect(truncated.byteInputStream(), Projections.GUEST) }.isFailure)
    }

    @Test fun aHostileAnswerIsStoppedBeforeItIsKept() {
        val huge = LargePool.HEAD + "<member><name>OpaqueRef:x</name>" + LargePool.set(LargePool.guestXml, "name_label", "n".repeat(200_000)) +
            "</member>" + LargePool.TAIL
        assertEquals("Response is too large.", runCatching { XmlRpcRecords.collect(huge.byteInputStream(), Projections.GUEST) }.exceptionOrNull()?.message)
        // ...but a skipped field may be long: it is never kept.
        val skipped = LargePool.HEAD + "<member><name>OpaqueRef:x</name>" + LargePool.set(LargePool.guestXml, "name_description", "d".repeat(200_000)) +
            "</member>" + LargePool.TAIL
        assertEquals(1, XmlRpcRecords.collect(skipped.byteInputStream(), Projections.GUEST).records.size)
        val deep = LargePool.HEAD + "<member><name>OpaqueRef:x</name><value><struct><member><name>uuid</name>" + "<value><array><data>".repeat(60) +
            "</data></array></value>".repeat(60) + "</member></struct></value></member>" + LargePool.TAIL
        assertTrue(runCatching { XmlRpcRecords.collect(deep.byteInputStream(), Projections.GUEST) }.exceptionOrNull()?.message.orEmpty().contains("too deep"))
        // Many records that each keep little still cannot add up past the total kept-text limit.
        val tight = StreamLimits.DEFAULT.copy(maxKeptChars = 5_000)
        assertEquals("Response is too large.", runCatching { XmlRpcRecords.collect(LargePool(100, 0, templates = false).answer(null), Projections.GUEST, limits = tight) }.exceptionOrNull()?.message)
        assertEquals("Response is too large.", runCatching { XmlRpcRecords.newest(LargePool(100, 0, templates = false).answer(null), Projections.GUEST, 100, { null }, tight) }.exceptionOrNull()?.message)
        val limits = StreamLimits.DEFAULT.copy(maxBytes = 100_000)
        assertTrue(runCatching { XmlRpcRecords.collect(LargePool(30, 0).answer(null), Projections.GUEST, limits = limits) }.exceptionOrNull()!!.isResponseLimit())
    }

    @Test fun readsAreClassifiedForSessionReuseAndOnlyReadsAreStreamed() {
        for (method in listOf("VM.get_all_records_where", "VBD.get_all_records_where", "message.get_all_records", "message.get_since", "VM.get_all_records"))
            assertTrue(method, XapiMethods.isRead(method))
        assertFalse(XapiMethods.isRead("Async.VM.snapshot"))
        val transport = XapiTransport(Profile("p", "p", "https://127.0.0.1:9/", "u", "p", "0".repeat(64)), keepAlive = true)
        assertTrue(runCatching { transport.postStream("message.destroy", emptyList(), 1000, null, 1000) { } }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun serverFiltersAreBuiltOnlyFromSafeLiterals() {
        assertEquals("field \"_ref\"=\"OpaqueRef:a\" or field \"_ref\"=\"OpaqueRef:b\"", XapiQuery.anyOf("_ref", listOf("OpaqueRef:a", "OpaqueRef:b", "OpaqueRef:a")).single())
        assertEquals(3, XapiQuery.anyOf("_ref", List(250) { "OpaqueRef:$it" }).size)
        assertTrue(runCatching { XapiQuery.eq("name__label", "a\" or true or \"") }.isFailure)
        assertTrue(runCatching { XapiQuery.eq("bad field", "x") }.isFailure)
    }

    // ---- 300, 1,000 and 5,000 VM records -------------------------------------------------------------------------------

    @Test fun aPoolWithThreeHundredVmRecordsLoadsThroughBothReaders() {
        // 300 VM-like records: the control domain, 39 templates, 200 guests and 60 snapshots.
        val pool = LargePool(guests = 200, snapshots = 60)
        assertEquals(300, pool.items.size)
        val full = pool.answer(null).readBytes()
        assertTrue("the tree reader now takes it (it failed at 222)", XmlRpc.response(full.toString(Charsets.UTF_8)) is Map<*, *>)
        val (read, allocated) = allocating { XmlRpcRecords.collect(full.inputStream(), Projections.GUEST, accept = ::normal) }
        assertEquals(200, read.records.size); assertEquals(200, read.total); assertEquals(full.size.toLong(), read.stats.bytes)
        assertTrue("ticks ${read.stats.nodes}", read.stats.nodes <= 300L * 700)
        assertTrue("kept ${read.stats.keptChars} chars", read.stats.keptChars <= 200L * 200)
        println("SCALE 300 records: ${full.size} B, ${read.stats.nodes} ticks (${full.size / read.stats.nodes} B/tick), ${read.stats.keptChars} chars kept, ${allocated / 1024} KiB allocated")
        LargePoolServer(pool).use { server ->
            val inventory = runBlocking { XapiGateway { 42L }.inventory(server.profile) }
            assertEquals(pool.expectedGuestUuids(), inventory.guests.map { it.uuid }.toSet())
            assertEquals(42L, inventory.observedAtMillis) // the injected clock, not the system clock
            assertTrue(inventory.notLoaded.isEmpty())
        }
    }

    @Test fun aThousandVmsWithThirtyNineTemplatesAndThreeThousandSnapshotsLoad() {
        val pool = LargePool(guests = 1000, snapshots = 3000)
        val fullBytes = pool.answerBytes(null)
        val filteredBytes = pool.answerBytes(XapiQuery.GUESTS)
        assertTrue("the whole list is far beyond one tree", fullBytes > XmlRpc.MAX_TREE_BYTES * 4)
        LargePoolServer(pool).use { server ->
            val (inventory, millis) = timed { runBlocking { XapiGateway().inventory(server.profile) } }
            assertEquals(1000, inventory.guests.size)
            assertEquals(pool.expectedGuestUuids(), inventory.guests.map { it.uuid }.toSet())
            assertEquals(500, inventory.guests.count { it.power == "Running" })
            assertTrue(inventory.guests.filter { it.power == "Running" }.all { it.hostUuid == inventory.hosts.single().uuid })
            assertTrue(inventory.notLoaded.isEmpty())
            assertEquals(1, server.calls.count { it == "VM.get_all_records_where" }); assertFalse("VM.get_all_records" in server.calls)
            assertEquals("only the 1,000 guests cross the wire", filteredBytes, server.vmBytes.get())
            println("SCALE 1000 VMs + 39 templates + 3000 snapshots: get_all_records would be ${fullBytes / 1024} KiB in one answer; " +
                "the filtered read moved ${filteredBytes / 1024} KiB in one round trip (${filteredBytes / 1000} B per VM), inventory in $millis ms on loopback")
        }
        // The fallback for a server without get_all_records_where: the whole list, parsed as it streams, nothing but guests kept.
        val (_, generator) = allocating { pool.answerBytes(null) }
        val (read, allocated) = allocating { XmlRpcRecords.collect(pool.answer(null), Projections.GUEST, accept = ::normal) }
        val reader = allocated - generator
        assertEquals(1000, read.records.size); assertEquals(1000, read.total); assertEquals(fullBytes, read.stats.bytes)
        assertTrue("ticks ${read.stats.nodes}", read.stats.nodes <= pool.items.size * 700L)
        assertTrue("kept ${read.stats.keptChars}", read.stats.keptChars <= 5040L * 200) // every record's projected fields are read; only guests are kept
        assertTrue("reader allocated ${reader / 1024} KiB for ${fullBytes / 1024} KiB", reader <= fullBytes * allocationPerWireByte)
        println("SCALE fallback over the whole ${pool.items.size}-record list: ${read.stats.nodes} ticks, " +
            "reader allocated ${reader / 1024} KiB (${"%.2f".format(reader.toDouble() / fullBytes)} B per wire byte)")
    }

    @Test fun fiveThousandVmRecordsStreamWithBoundedWork() {
        val pool = LargePool(guests = 5000, snapshots = 0, templates = false)
        val bytes = pool.answerBytes(XapiQuery.GUESTS)
        val (generated, generatorAllocation) = allocating { pool.answerBytes(XapiQuery.GUESTS) }
        assertEquals(bytes, generated)
        val (read, allocated) = allocating { XmlRpcRecords.collect(pool.answer(XapiQuery.GUESTS), Projections.GUEST) }
        assertEquals(5000, read.records.size); assertEquals(5000, read.total)
        assertEquals(pool.expectedGuestUuids(), read.records.values.map { it["uuid"] }.toSet())
        val sample = read.records.getValue(LargePool.ref(1, 4321))
        assertEquals(pool.guestNameOf(4321), sample["name_label"]); assertEquals("Halted", sample["power_state"]); assertEquals(false, sample["is_a_template"])
        assertEquals(Projections.GUEST.fields, sample.keys)
        assertTrue("ticks ${read.stats.nodes}", read.stats.nodes <= 5000L * 700)
        assertTrue("kept ${read.stats.keptChars}", read.stats.keptChars <= 5000L * 200)
        val reader = allocated - generatorAllocation
        assertTrue("reader allocated ${reader / 1024} KiB for ${bytes / 1024} KiB", reader <= bytes * allocationPerWireByte)
        val baseline = usedAfterGc()
        var held: RecordsRead? = XmlRpcRecords.collect(pool.answer(XapiQuery.GUESTS), Projections.GUEST)
        val retained = usedAfterGc() - baseline
        assertEquals(5000, held!!.records.size); held = null
        println("SCALE 5000 VMs: ${bytes / 1024} KiB on the wire, ${read.stats.nodes} ticks, ${read.stats.keptChars} chars kept " +
            "(${read.stats.keptChars / 5000} per VM); reader allocated ${reader / 1024} KiB (${"%.2f".format(reader.toDouble() / bytes)} B per wire byte); " +
            "the kept result is ~${retained / 1024} KiB of heap (${retained / 5000} B per VM, JVM estimate)")
        LargePoolServer(pool).use { server ->
            val (inventory, millis) = timed { runBlocking { XapiGateway().inventory(server.profile) } }
            assertEquals(5000, inventory.guests.size); assertTrue(inventory.notLoaded.isEmpty())
            println("SCALE 5000 VMs end to end over loopback TLS: $millis ms")
        }
    }

    // ---- beyond the limits: honest degradation ---------------------------------------------------------------------------

    @Test fun beyondTheRecordCapTheInventoryShowsWhatItKeptAndSaysHowManyMore() {
        LargePoolServer(LargePool(guests = 300, snapshots = 10)).use { server ->
            val gateway = XapiGateway().apply { streamLimits = StreamLimits.DEFAULT.copy(maxRecords = 250) }
            val inventory = runBlocking { gateway.inventory(server.profile) }
            assertEquals(250, inventory.guests.size)
            assertEquals(listOf("Showing 250 of 300 VMs (in server order): Meteor keeps at most 250 VMs in one inventory."), inventory.notLoaded)
        }
    }

    @Test fun beyondTheByteOrNodeLimitTheInventoryStillShowsHostsAndStorageAndSaysWhy() {
        LargePoolServer(LargePool(guests = 300, snapshots = 0)).use { server ->
            for ((limits, reason) in listOf(StreamLimits.DEFAULT.copy(maxBytes = 1024 * 1024) to "MiB",
                StreamLimits.DEFAULT.copy(maxNodes = 60_000) to "more elements")) {
                val inventory = runBlocking { XapiGateway().apply { streamLimits = limits }.inventory(server.profile) }
                assertTrue(inventory.guests.isEmpty())
                assertEquals(1, inventory.hosts.size); assertTrue(inventory.repositories.isNotEmpty()); assertEquals(authority, inventory.authority)
                assertTrue(inventory.notLoaded.single(), inventory.notLoaded.single().startsWith("VMs could not be loaded") && reason in inventory.notLoaded.single())
            }
        }
    }

    // ---- the tree reader's limit and cost ---------------------------------------------------------------------------------

    @Test fun theTreeReaderLimitLetsTheByteLimitBindFirstForRealRecords() {
        val templates = Live83.splitRecords(Live83.response("VM.get_all_records")).values.toList()
        fun answer(count: Int) = buildString {
            append(LargePool.HEAD); repeat(count) { append("<member><name>OpaqueRef:t-").append(it).append("</name>").append(templates[it % templates.size]).append("</member>") }
            append(LargePool.TAIL)
        }
        // Just under the byte limit, the node count is still under its limit.
        var count = 1
        while (answer(count * 2).length < XmlRpc.MAX_TREE_BYTES) count *= 2
        while (answer(count + 50).length < XmlRpc.MAX_TREE_BYTES) count += 50
        val xml = answer(count)
        val baseline = usedAfterGc()
        val (value, allocated) = allocating { XmlRpc.response(xml) }
        val retained = usedAfterGc() - baseline
        assertEquals(count, (value as Map<*, *>).size)
        assertEquals("Response is too large.", runCatching { XmlRpc.response(answer(count + 60)) }.exceptionOrNull()?.message)
        println("SCALE tree reader: $count real VM records (${xml.length / 1024} KiB) fit under the byte limit and its ${XmlRpc.MAX_TREE_NODES}-tick limit; " +
            "parsing allocated ${allocated / 1024} KiB (${"%.1f".format(allocated.toDouble() / xml.length)} B per byte) and the tree keeps ~${retained / 1024} KiB (JVM estimate)")
    }

    // ---- templates ---------------------------------------------------------------------------------------------------------

    @Test fun templatesAreReadOnDemandWithOnlyWhatAPickerNeeds() = runBlocking {
        val server = Live83Server()
        try {
            val catalog = XapiGateway { 7L }.templates(server.profile, authority)
            assertEquals(39, catalog.templates.size); assertEquals(39, catalog.total); assertFalse(catalog.truncated)
            assertTrue(catalog.templates.all { it.builtIn && it.name.isNotBlank() && it.uuid.isNotBlank() && it.canClone })
            assertTrue(catalog.templates.all { it.description.length <= 300 && (it.memoryBytes ?: 0) > 0 && (it.vcpus ?: 0) > 0 })
            assertEquals(catalog.templates.sortedBy { it.name.lowercase() }, catalog.templates)
            assertEquals(1, server.calls.count { it == "VM.get_all_records_where" })
            server.assertReadOnly()
        } finally { server.close() }
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> { val start = System.nanoTime(); val value = block(); return value to (System.nanoTime() - start) / 1_000_000 }
}
// --- end inventory scale ---
