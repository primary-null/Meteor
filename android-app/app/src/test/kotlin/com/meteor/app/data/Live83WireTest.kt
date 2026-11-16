package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test

/**
 * The app's XML-RPC writer and reader, event parser, alert mapper, update-check parser and inventory mapper against
 * answers recorded from a real XCP-ng 8.3.0 host. Fixtures: `live-xcpng-8.3/`. Findings: METEOR-LIVE-FINDINGS.md.
 */
class Live83WireTest {
    private fun refs(name: String) = Regex("<string>(OpaqueRef:[^<]+)</string>").findAll(String(Live83.requestBody(name))).map { it.groupValues[1] }.toList()
    private fun failure(name: String) = runCatching { Live83.value(name) }.exceptionOrNull() as? RpcFailure ?: fail("$name was not a failure").let { throw AssertionError() }

    // ---- what the app sends --------------------------------------------------------------------------------------------

    @Test fun theAppsRequestBodiesAreByteIdenticalToTheOnesRecorded() {
        for (cls in listOf("pool", "host", "host_metrics", "VM", "VM_metrics", "VM_guest_metrics", "SR", "PBD", "VBD", "VDI", "VIF", "PIF",
            "PIF_metrics", "network", "task", "message", "console")) {
            val name = "$cls.get_all_records"
            assertArrayEquals(name, Live83.requestBody(name), XmlRpc.request(name, listOf(refs(name).single())))
        }
        val begin = "event.from.begin"
        assertArrayEquals(Live83.requestBody(begin), XmlRpc.request("event.from", listOf(refs(begin).single(), listOf("pool"), "", 5.0)))
        val follow = "event.from.follow-1"
        val token = Regex("<string>(\\d{20},\\d{20})</string>").find(String(Live83.requestBody(follow)))!!.groupValues[1]
        assertArrayEquals(Live83.requestBody(follow), XmlRpc.request("event.from", listOf(refs(follow).single(), EventPolicy.CLASSES, token, 30.0)))
        val plugin = "host.call_plugin.check_update"
        val (session, host) = refs(plugin)
        assertArrayEquals(Live83.requestBody(plugin), XmlRpc.request("host.call_plugin", listOf(session, host, HostUpdates.PLUGIN, HostUpdates.FUNCTION, emptyMap<String, String>())))
        val filter = "VM.get_all_records_where"
        assertArrayEquals(Live83.requestBody(filter), XmlRpc.request(filter, listOf(refs(filter).single(), PoolSummaries.VM_FILTER)))
        val since = "message.get_since.30d"
        val stamp = Regex("<dateTime.iso8601>([^<]+)</dateTime.iso8601>").find(String(Live83.requestBody(since)))!!.groupValues[1]
        assertArrayEquals(Live83.requestBody(since), XmlRpc.request("message.get_since", listOf(refs(since).single(), XmlRpcDateTime(ActivityMapper.time(stamp)!!))))
    }

    @Test fun theAppsRequestHeadersAreTheOnesRecorded() {
        val headers = (Live83.entry("VM.get_all_records")["requestHeaders"] as List<*>).map { it.toString() }
        assertTrue(headers.any { it.startsWith("User-Agent: Meteor-Spark/") })
        assertTrue(headers.contains("Content-Type: text/xml; charset=utf-8"))
        assertEquals("POST / HTTP/1.1", Live83.entry("VM.get_all_records")["requestLine"])
    }

    // ---- what the server answers ---------------------------------------------------------------------------------------

    @Test fun everyRecordedReadAnswerParsesWithTheAppsXmlRpcReaderExceptTheDataSourceLists() {
        val skip = setOf("VM.get_data_sources", "host.get_data_sources", "VM.get_data_sources.dom0", "system.listMethods")
        val failed = Live83.index.filter { it["kind"] == "xmlrpc" && it["status"] == 200L && !(it["expectFailure"] == true) }
            .map { it["name"] as String }.filter { it !in skip && !it.startsWith("introspect") }
            .filter { runCatching { Live83.value(it) }.exceptionOrNull() != null }
        assertEquals("answers the reader rejects: $failed", emptyList<String>(), failed)
    }

    @Test fun dataSourceListsParseWithTheAppsXmlRpcReader() {
        for (name in listOf("VM.get_data_sources", "host.get_data_sources", "VM.get_data_sources.dom0")) assertTrue(name, Live83.value(name) is List<*>)
    }

    @Test fun theServersInfinitySpellingsAreReadAsInfinity() {
        for (name in listOf("VM.get_data_sources", "host.get_data_sources", "VM.get_data_sources.dom0")) {
            val xml = Live83.response(name)
            assertTrue(name, xml.contains("<double>inf</double>"))
            val sources = Live83.value(name) as List<*>
            assertTrue(name, sources.any { (it as Map<*, *>)["max"] == Double.POSITIVE_INFINITY })
        }
        assertTrue(Live83.response("host.get_data_sources").contains("<double>-inf</double>"))
        assertTrue((Live83.value("host.get_data_sources") as List<*>).any { (it as Map<*, *>)["min"] == Double.NEGATIVE_INFINITY })
    }

    @Test fun everyDoubleSpellingARealServerOrAnOcamlWriterUsesIsRead() {
        fun double(text: String) = XmlRpc.response("<methodResponse><params><param><value><struct><member><name>Status</name><value>Success</value></member>" +
            "<member><name>Value</name><value><double>$text</double></value></member></struct></value></param></params></methodResponse>") as Double
        assertEquals(Double.POSITIVE_INFINITY, double("inf"), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, double("+inf"), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, double("-inf"), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, double("Infinity"), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, double(" -infinity "), 0.0)
        assertTrue(double("nan").isNaN()); assertTrue(double("NaN").isNaN()); assertTrue(double("-nan").isNaN())
        assertEquals(0.0, double("0"), 0.0); assertEquals(1.0, double("1"), 0.0); assertEquals(0.0054, double("0.0054"), 0.0); assertEquals(1e-3, double("1e-3"), 0.0)
        for (bad in listOf("", "inf inity", "in", "1,5", "abc", "--1")) {
            val error = runCatching { double(bad) }.exceptionOrNull()
            assertEquals(bad, "Invalid XML-RPC number.", error?.message)
        }
    }

    @Test fun xapiWritesStringsAsBareValuesAndIntegersAsStrings() {
        val xml = Live83.response("VM.get_all_records")
        assertTrue(xml.contains("<name>VCPUs_max</name><value>"))   // int64 as bare text
        assertFalse(xml.contains("<i8>"))
        val vm = Live83.guest()
        assertTrue(vm["memory_static_max"] is String)
        assertTrue(vm["is_a_template"] is Boolean)
        assertTrue(Live83.value("event.from.begin").toString().contains("valid_ref_counts"))
        assertTrue(Regex("<name>user</name><value><i4>").containsMatchIn(Live83.response("event.from.begin")))   // counts are <i4>
    }

    @Test fun errorRepliesAreTheFailureEnvelopesTheAppExpects() {
        failure("error-uuid-invalid").let { assertEquals("UUID_INVALID", it.code); assertEquals(listOf("VM", "00000000-0000-0000-0000-000000000000"), it.details) }
        failure("error-handle-invalid").let { assertEquals("HANDLE_INVALID", it.code); assertEquals("VM", it.details.first()) }
        failure("error-message-handle").let { assertEquals("HANDLE_INVALID", it.code); assertEquals("message", it.details.first()) }
        failure("error-method-unknown").let { assertEquals("MESSAGE_METHOD_UNKNOWN", it.code); assertEquals(listOf("VM.no_such_method"), it.details) }
        failure("error-session-invalid").let { assertEquals("SESSION_INVALID", it.code); assertTrue(it.details.single().startsWith("OpaqueRef:")) }
    }

    @Test fun parameterCountMismatchCountsExcludeTheSession() {
        // [method, expected, given], both counts WITHOUT the session reference (the simulator counts it).
        failure("error-arity-read").let { assertEquals(XapiArity.MISMATCH, it.code); assertEquals(listOf("VM.get_name_label", "1", "0"), it.details) }
        assertEquals(listOf("host.disable", "1", "0"), failure("arity-host.disable").details)
        assertEquals(listOf("host.enable", "1", "0"), failure("arity-host.enable").details)
        assertEquals(listOf("host.evacuate", "1", "0"), failure("arity-host.evacuate").details)
        assertEquals(listOf("VM.snapshot", "2", "0"), failure("arity-VM.snapshot").details)
        assertEquals(listOf("VM.pool_migrate", "3", "0"), failure("arity-VM.pool_migrate").details)
        // The shorter forms the app sends first are exactly these counts: vm | vm, new_name | host | vm, host, options.
        assertEquals(1, TargetAction.HOST_DISABLE.xapiArguments("ref", null).size)
        assertEquals(1, TargetAction.HOST_EVACUATE.xapiArguments("ref", null).size)
        assertEquals(2, TargetAction.SNAPSHOT_TAKE.xapiArguments("ref", "name").size)
    }

    @Test fun anInvalidEventTokenIsNotEventsLostOrInvalidValue() {
        val error = failure("error-bad-token")
        assertEquals("EVENT_FROM_TOKEN_PARSE_FAILURE", error.code)
        // A token the server cannot parse is handled like lost events: take a fresh token and read everything again.
        assertEquals(EventFailureKind.EVENTS_LOST, EventFailures.classify(error))
    }

    @Test fun introspectionListsEveryMethodTheAppCallsButNotTheAsyncForms() {
        val listed = Regex("<value>([^<]+)</value>").findAll(Live83.response("system.listMethods")).map { it.groupValues[1] }.toSet()
        val used = listOf("session.login_with_password", "session.logout", "session.get_is_local_superuser", "session.get_subject", "subject.get_roles",
            "role.get_name_label", "VM.get_all_records_where", "VM.get_data_sources", "host.get_data_sources", "VM.query_data_source", "host.query_data_source",
            "host.get_servertime", "host.call_plugin",
            "event.from", "message.get_since", "message.destroy", "message.get_record", "VM.get_consoles", "console.get_record",
            "host.get_vms_which_prevent_evacuation", "VM.get_by_uuid", "host.get_by_uuid", "SR.get_by_uuid", "task.get_uuid", "task.get_by_uuid")
        assertEquals(emptyList<String>(), used.filter { it !in listed })
        assertTrue(listed.none { it.startsWith("Async.") })
        assertFalse("system.methodSignature is not available", listed.contains("system.methodSignature"))
        assertEquals("MESSAGE_METHOD_UNKNOWN", failure("introspect2-methodSignature-host.disable").code)
    }

    @Test fun malformedRequestsGetAnHttp500HtmlPageNotAnXmlRpcFault() {
        val entry = Live83.entry("error-malformed-xml")
        assertEquals(500L, entry["status"])
        assertTrue(Live83.text("error-malformed-xml.response.html").startsWith("<html>"))
    }

    // ---- events ----------------------------------------------------------------------------------------------------------

    private val tokenForm = Regex("\\d{20},\\d{20}")

    @Test fun theFirstEventCallReturnsTheTokenAndOnePoolSnapshotEvent() {
        val batch = EventParser.batch(Live83.value("event.from.begin"))
        assertTrue(tokenForm.matches(batch.token))
        assertEquals(listOf("add"), batch.events.map { it.operation })
        assertEquals(listOf("pool"), batch.events.map { it.cls })
        assertTrue(batch.events.single().ref.startsWith("OpaqueRef:"))
        val raw = Live83.value("event.from.begin") as Map<*, *>
        assertEquals(setOf("events", "valid_ref_counts", "token"), raw.keys)
        assertTrue((raw["valid_ref_counts"] as Map<*, *>)["vm"] is Long)
    }

    @Test fun anEventIsIdTimestampClassOperationRefAndSnapshotWithIdsAndTimesAsStrings() {
        val event = ((Live83.value("event.from.during-start-2") as Map<*, *>)["events"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("id", "timestamp", "class", "operation", "ref", "snapshot"), event.keys)
        assertTrue(event["id"] is String)
        assertEquals("0.0", event["timestamp"])
        assertEquals("task", event["class"])
        assertEquals("mod", event["operation"])
        val deleted = ((Live83.value("event.from.all-classes") as Map<*, *>)["events"] as List<*>).single() as Map<*, *>
        assertEquals("del", deleted["operation"])
        assertFalse("a del event has no snapshot", deleted.containsKey("snapshot"))
        assertEquals("task", EventParser.batch(Live83.value("event.from.all-classes")).events.single().cls)
    }

    @Test fun aQuietPollReturnsAnEmptyListAndTheSameTokenAfterTheFullTimeout() {
        val backlog = EventParser.batch(Live83.value("event.from.backlog"))
        val quiet = EventParser.batch(Live83.value("event.from.quiet-1"))
        assertEquals(emptyList<ServerEvent>(), quiet.events)
        assertEquals(backlog.token, quiet.token)
        assertTrue("the answer arrives only when the server timeout (10 s) is over", (Live83.entry("event.from.quiet-1")["elapsedMs"] as Long) >= 10_000)
        assertTrue((Live83.entry("event.from.quiet-4")["elapsedMs"] as Long) in 30_000..31_000)
    }

    @Test fun theFirstFollowUpWithTheFullClassListReplaysExistingObjectsOfTheNewClasses() {
        val backlog = EventParser.batch(Live83.value("event.from.backlog"))
        // Not "events since the token": message, SR, PBD and VBD objects arrive again as add/mod with id "0".
        assertTrue(backlog.events.count { it.cls == "message" && it.operation == "add" } >= 10)
        assertTrue(backlog.events.any { it.cls == "sr" })
        val triggers = EventTriggers.classify(backlog.events)
        assertTrue(triggers.inventory && triggers.alerts && triggers.details)
        assertTrue(triggers.deletedMessages.isEmpty())
    }

    @Test fun eventClassesAreLowerCaseAndNeverTheAppsMixedCaseNames() {
        val all = listOf("event.from.backlog", "event.from.during-start-1", "event.from.follow-1").flatMap { EventParser.batch(Live83.value(it)).events }
        assertTrue(all.all { it.cls == it.cls.lowercase() })
        assertTrue(all.map { it.cls }.toSet().containsAll(setOf("vm", "host", "task", "message", "vbd", "vif", "sr", "pbd")))
    }

    @Test fun theTokenAdvancesWithEveryEventfulBatch() {
        val tokens = listOf("event.from.begin", "event.from.during-start-2", "event.from.during-start-3", "event.from.during-start-4").map { EventParser.batch(Live83.value(it)).token }
        assertEquals(tokens, tokens.sorted())
        assertEquals(4, tokens.toSet().size)
    }

    // ---- alerts ----------------------------------------------------------------------------------------------------------

    @Test fun messageRecordsMapToAlertsWithPrioritiesAsStringsAndIsoTimestamps() {
        val raw = Live83.records("message")
        assertTrue(raw.values.all { it["priority"] is String && it["timestamp"] is String })
        assertEquals(setOf("uuid", "name", "priority", "cls", "obj_uuid", "timestamp", "body"), raw.values.first().keys)
        val alerts = AlertMapper.parse(raw)
        assertEquals(raw.size, alerts.size)
        assertTrue(alerts.all { it.timestamp != null && it.priority != null })
        assertEquals(alerts.sortedWith(AlertMapper.NEWEST_FIRST), alerts)
        val started = alerts.first { it.name == "VM_STARTED" }
        assertEquals(AlertSeverity.INFO, started.severity)
        assertEquals(AlertObjectKind.VM, started.objectKind)
        val login = alerts.first { it.name == "FAILED_LOGIN_ATTEMPTS" }
        assertEquals("Pool", login.objectClass)       // upper-case first letter here, lower case in events
        assertEquals(AlertObjectKind.POOL, login.objectKind)
        assertEquals(AlertSeverity.WARNING, login.severity)
        assertTrue(login.severity.urgent)
    }

    @Test fun messageGetSinceIsInclusiveAtItsBoundaryAndEmptyInTheFuture() {
        assertEquals(Live83.records("message").size, (Live83.value("message.get_since.epoch-2020") as Map<*, *>).size)
        assertEquals(1, (Live83.value("message.get_since.at-newest") as Map<*, *>).size)
        assertEquals(1, (Live83.value("message.get_since.newest-minus-1s") as Map<*, *>).size)
        assertEquals(0, (Live83.value("message.get_since.future") as Map<*, *>).size)
    }

    // ---- updates ---------------------------------------------------------------------------------------------------------

    @Test fun theRealUpdateCheckAnswerIsARecognizedListOfPackages() {
        val raw = Live83.value("host.call_plugin.check_update")
        assertTrue("the plugin returns one JSON text", raw is String)
        val packages = JsonLite.parse(raw as String) as List<*>
        assertEquals(setOf("name", "version", "release", "url", "license", "size", "description", "changelog"), packages.flatMap { (it as Map<*, *>).keys }.toSet())
        val result = HostUpdates.parse(raw)
        assertTrue(result.recognized)
        assertEquals(packages.size, result.total)
        assertTrue(result.updates.none { it.name == "Unnamed package" })
        assertTrue(result.updates.all { it.version != null })
        assertTrue("took several seconds, well inside the 90 s timeout", (Live83.entry("host.call_plugin.check_update")["elapsedMs"] as Long) in 100..60_000)
    }

    // ---- inventory -------------------------------------------------------------------------------------------------------

    @Test fun inventoryNormalizesTheRealPoolHostGuestAndRepositories() {
        val inventory = InventoryMapper.normalize(Live83.records("pool"), Live83.records("host"), Live83.records("host_metrics"), Live83.records("VM"), Live83.records("SR"), 0L)
        assertEquals(Live83.pool()["uuid"], inventory.authority)
        assertEquals(1, inventory.hosts.size)
        assertTrue(inventory.hosts.single().online)
        assertEquals("8.3.0", inventory.hosts.single().softwareVersion)
        assertEquals("only the guest: 39 templates and the control domain are filtered out", 1, inventory.guests.size)
        assertEquals("Running", inventory.guests.single().power)
        assertEquals(Live83.records("SR").size, inventory.repositories.size)
        assertTrue(inventory.storageTotal > 0)
        assertEquals(Live83.records("host").values.single()["uuid"], inventory.guests.single().hostUuid)
    }

    @Test fun theWhereFilterIsAcceptedAndReturnsOnlyTheGuest() {
        val where = (Live83.value("VM.get_all_records_where") as Map<*, *>)
        assertEquals(1, where.size)
        val summary = PoolSummaries.counts(Live83.records("pool"), Live83.records("host"), Live83.records("host_metrics"),
            Live83.records("VM").filterValues { it["uuid"] == Live83.guest()["uuid"] }, null, false, 0L)
        assertEquals(1, summary.vmsTotal); assertEquals(1, summary.vmsRunning); assertEquals(1, summary.hostsLive)
    }

    // ---- tasks -------------------------------------------------------------------------------------------------------------

    @Test fun theRetainedStartTaskMapsToAPoolTaskAndTaskStatusIsABareString() {
        val tasks = ActivityMapper.tasks(Live83.records("task"))
        val task = tasks.items.single()
        assertEquals("Async.VM.start", task.name)
        assertEquals("success", task.status)
        assertEquals(1.0, task.progress!!, 0.0)
        assertNotNull(task.createdAt); assertNotNull(task.finishedAt)
        assertNull(task.errorCode)
        assertEquals("pending", Live83.value("task.get_status.poll-1"))
        assertTrue(setOf("pending", "success").contains((Live83.value("task.get_record.after-start") as Map<*, *>)["status"]))
        // progress is a <double>; result is the literal text "<value></value>" (an XML snippet inside a string).
        val raw = Live83.records("task").values.single()
        assertEquals(1.0, raw["progress"] as Double, 0.0); assertEquals("<value></value>", raw["result"])
    }

    // ---- size limits -----------------------------------------------------------------------------------------------------

    /** A `VM.get_all_records` answer made of [count] records, cycling through the recorded ones, as the app's reader sees it. */
    private fun recordsAnswer(count: Int): String {
        val values = Live83.splitRecords(Live83.response("VM.get_all_records")).values.toList()
        val body = StringBuilder("<?xml version=\"1.0\"?><methodResponse><params><param><value><struct><member><name>Status</name><value>Success</value></member><member><name>Value</name><value><struct>")
        repeat(count) { i -> body.append("<member><name>OpaqueRef:synthetic-").append(i).append("</name>").append(values[i % values.size]).append("</member>") }
        return body.append("</struct></value></member></struct></value></param></params></methodResponse>").toString()
    }
    private fun parses(count: Int) = runCatching { XmlRpc.response(recordsAnswer(count)) }.isSuccess

    // inventory scale: finding 3 is fixed. The tree reader's node limit is sized from these records, so the byte limit binds
    // first; pool-sized lists are streamed and never become one tree (InventoryScaleTest).
    @Test fun aRealFullVmListParsesAndTheByteLimitNowBindsBeforeTheNodeLimit() {
        val xml = Live83.response("VM.get_all_records")
        val records = Live83.splitRecords(xml)
        val templates = records.filterValues { it.contains("<name>is_a_template</name><value><boolean>1</boolean>") }
        val bytes = templates.values.map { it.toByteArray().size }
        println("LIVE83 VM.get_all_records: total=${xml.toByteArray().size} B records=${records.size} templates=${templates.size} " +
            "templateBytes=${bytes.min()}..${bytes.max()} avg=${bytes.average().toInt()} guestBytes=${records.filterValues { it.contains("<name>is_a_template</name><value><boolean>0</boolean>") && it.contains("<name>is_control_domain</name><value><boolean>0</boolean>") }.values.map { it.toByteArray().size }}")
        assertTrue(Live83.value("VM.get_all_records") is Map<*, *>)
        var low = records.size; var high = records.size * 2
        assertTrue(parses(low))
        while (parses(high)) { low = high; high *= 2 }
        while (high - low > 1) { val mid = (low + high) / 2; if (parses(mid)) low = mid else high = mid }
        val bytesAtLimit = recordsAnswer(low).toByteArray().size
        val failure = runCatching { XmlRpc.response(recordsAnswer(low + 1)) }.exceptionOrNull()?.message
        println("LIVE83 limits: the app's XML-RPC tree reader accepts at most $low VM-like records (${bytesAtLimit / 1024} KiB, " +
            "${bytesAtLimit * 100 / (8 * 1024 * 1024)}% of the 8 MiB byte limit); the next one fails with \"$failure\"")
        assertEquals("the byte limit binds, not the node limit", "Response is too large.", failure)
        assertTrue(bytesAtLimit > 8 * 1024 * 1024 * 95 / 100)
        assertTrue(low in 800..1000)
    }

    // inventory scale: fixed (finding 3); was @Ignore("live 8.3 mismatch: ... exceed the reader's 150,000-node limit ...")
    @Test fun aPoolWithThreeHundredVmRecordsStillLoads() {
        assertTrue(parses(300))
    }
}
