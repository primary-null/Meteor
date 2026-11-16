package com.meteor.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The VM settings answers recorded from the real XCP-ng 8.3 lab host (`live-xcpng-8.3-settings/`, sanitized). */
object Live83Settings {
    private const val DIR = "live-xcpng-8.3-settings/"
    fun bytes(name: String): ByteArray = (Live83Settings::class.java.classLoader.getResourceAsStream(DIR + name)
        ?: error("missing fixture $name")).use { it.readBytes() }
    fun response(name: String) = String(bytes("$name.response.xml"), Charsets.UTF_8)
    fun request(name: String) = String(bytes("$name.request.xml"), Charsets.UTF_8)
    @Suppress("UNCHECKED_CAST")
    fun guestBefore(): Map<String, Any?> = XmlRpc.response(response("settings-before.VM.get_record")) as Map<String, Any?>
    private val refs by lazy { Regex("<string>(OpaqueRef:[^<]+)</string>").findAll(request("settings-name.set")).map { it.groupValues[1] }.toList() }
    val session get() = refs[0]
    val vmRef get() = refs[1]
}

/**
 * The production TLS pin, HTTP transport, XML-RPC writer and reader, gateway and PowerService against a loopback XAPI
 * that starts from the lab guest's real record and answers setters with the real server's recorded replies. Request
 * bodies are compared byte for byte with what the lab received. No lab or network service is used.
 */
class XapiVmSettingsIntegrationTest {
    private lateinit var fixture: TlsFixture
    private val calls = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val lock = Any()
    private lateinit var vm: MutableMap<String, Any?>
    private val metricsOverride = mutableMapOf<String, Any?>()
    /** A setter method answered with this recorded reply (and not applied) instead of the model's effect. */
    private val scripted = mutableMapOf<String, String>()
    /** Setter methods whose reply is lost (HTTP 500); the value says whether the server applied it first. */
    private val lost = mutableMapOf<String, Boolean>()
    private var offset = 0L
    private val store = object : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        override suspend fun load() = records.values.map { JournalCodec.decode(JournalCodec.encode(it)) }
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation }
    }
    private val gateway = XapiGateway()
    private val service = PowerService(gateway, store, { System.currentTimeMillis() + offset }, {}, 2)
    private val profile get() = fixture.profile()
    private val authority = Live83.pool()["uuid"] as String
    private val uuid get() = vm["uuid"] as String

    @Before fun start() {
        vm = Live83Settings.guestBefore().toMutableMap()
        fixture = TlsFixture()
        fixture.server.createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val method = Regex("<methodName>([^<]+)</methodName>").find(body)!!.groupValues[1]
                calls += method; bodies += body
                val (xml, drop) = synchronized(lock) { answer(method, body) }
                if (drop) exchange.sendResponseHeaders(500, -1) else {
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "text/xml")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            }
        }
        fixture.start()
    }
    @After fun stop() { fixture.close() }

    private fun success(valueXml: String) = "<?xml version=\"1.0\"?><methodResponse><params><param><value><struct><member><name>Status</name>" +
        "<value>Success</value></member><member><name>Value</name>$valueXml</member></struct></value></param></params></methodResponse>"
    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun value(input: Any?): String = when (input) {
        null -> "<value></value>"
        is Map<*, *> -> "<value><struct>" + input.entries.joinToString("") { "<member><name>${esc(it.key.toString())}</name>${value(it.value)}</member>" } + "</struct></value>"
        is List<*> -> "<value><array><data>" + input.joinToString("") { value(it) } + "</data></array></value>"
        is Boolean -> "<value><boolean>${if (input) 1 else 0}</boolean></value>"
        is Double -> "<value><double>$input</double></value>"
        is Long, is Int -> "<value><i4>$input</i4></value>"
        else -> "<value>${esc(input.toString())}</value>"
    }
    private val recordXml = HashMap<String, Map<String, String>>()
    private fun recorded(cls: String) = recordXml.getOrPut(cls) { Live83.splitRecords(Live83.response("$cls.get_all_records")) }
    private val setterSuccess = Live83Settings.response("settings-name.set")

    @Suppress("UNCHECKED_CAST")
    private fun answer(method: String, body: String): Pair<String, Boolean> {
        val args = Regex("<param><value>(.*?)</value></param>").findAll(body).map { it.groupValues[1] }.toList()
        fun text(i: Int) = args[i].removePrefix("<string>").removeSuffix("</string>").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val xml = when {
            method == "session.login_with_password" -> success("<value>${Live83Settings.session}</value>")
            method == "session.logout" -> success("<value></value>")
            method == "pool.get_all_records" || method == "host_metrics.get_all_records" -> Live83.response(method)
            method == "VM.get_by_uuid" -> if (text(1) == uuid) success("<value>${Live83Settings.vmRef}</value>") else Live83.response("error-uuid-invalid")
            method == "VM.get_record" -> if (text(1) == Live83Settings.vmRef) success(value(vm)) else Live83.response("error-handle-invalid")
            method == "VM_metrics.get_record" && metricsOverride.isNotEmpty() ->
                success(value((XmlRpc.response(success(recorded("VM_metrics")[text(1)] ?: "<value><struct></struct></value>")) as Map<String, Any?>) + metricsOverride))
            method.endsWith(".get_record") -> recorded(method.substringBefore('.'))[text(1)]?.let { success(it) } ?: Live83.response("error-handle-invalid")
            method.startsWith("VM.set_") || method.startsWith("VM.add_") || method.startsWith("VM.remove_") -> {
                assertEquals(Live83Settings.vmRef, text(1))
                scripted[method]?.let { return Live83Settings.response(it) to false }
                val dropAfter = lost[method]
                if (dropAfter == false) return "" to true
                apply(method, args, ::text)?.let { return it to false }
                return setterSuccess to (dropAfter == true)
            }
            else -> Live83.response("error-method-unknown")
        }
        return xml to false
    }

    /** The documented effect of each setter on the model; a recorded refusal where the real server refused. */
    @Suppress("UNCHECKED_CAST")
    private fun apply(method: String, args: List<String>, text: (Int) -> String): String? {
        when (method) {
            "VM.set_name_label" -> vm["name_label"] = text(2)
            "VM.set_name_description" -> vm["name_description"] = text(2)
            "VM.add_tags" -> vm["tags"] = ((vm["tags"] as List<String>) + text(2)).distinct()
            "VM.remove_tags" -> vm["tags"] = (vm["tags"] as List<String>) - text(2)
            "VM.set_VCPUs_number_live" -> { vm["VCPUs_at_startup"] = text(2); metricsOverride["VCPUs_number"] = text(2) }
            "VM.set_memory_dynamic_range" -> {
                val (min, max) = text(2).toLong() to text(3).toLong()
                if (min > max || min < (vm["memory_static_min"] as String).toLong()) return Live83Settings.response("settings-memory.dynamic-min-above-max")
                vm["memory_dynamic_min"] = min.toString(); vm["memory_dynamic_max"] = max.toString()
            }
            "VM.set_VCPUs_max", "VM.set_memory_limits" -> if (vm["power_state"] == "Running") return Live83Settings.response("settings-vcpus.max-same")
            "VM.add_to_other_config" -> {
                val config = (vm["other_config"] as Map<String, Any?>)
                if (text(2) in config) return Live83Settings.response("settings-autopoweron.add-duplicate")
                vm["other_config"] = config + (text(2) to text(3))
            }
            "VM.remove_from_other_config" -> vm["other_config"] = (vm["other_config"] as Map<String, Any?>) - text(2)
            "VM.set_HVM_boot_params" -> vm["HVM_boot_params"] = Regex("<member><name>([^<]*)</name><value><string>([^<]*)</string></value></member>")
                .findAll(args[2]).associate { it.groupValues[1] to it.groupValues[2] }
            else -> error("unexpected setter $method")
        }
        return null
    }

    private fun setterCalls() = calls.filter { it.startsWith("VM.set_") || it.startsWith("VM.add_") || it.startsWith("VM.remove_") }
    private fun setterBody(method: String) = bodies.single { it.contains("<methodName>$method</methodName>") }
    private fun read() = runBlocking { gateway.vmSettings(profile, authority, uuid) }
    private fun save(change: (SettingsDraft) -> SettingsDraft, typed: Boolean = false): PowerOperation = runBlocking {
        val s = read()
        val d = change(SettingsDraft.from(s))
        val steps = SettingsRules.plan(s, d)
        service.executeSettings(profile, SettingsConfirmation(profile.id, profile.origin(), s, d, steps, if (typed) SettingsRules.confirmationPhrase(s) else null))
    }

    @Test fun theRecordedGuestReadsAsTheEditorShowsIt() {
        val s = read()
        assertEquals("lab-guest-01", s.name); assertEquals("Running", s.power); assertEquals(emptyList<String>(), s.tags)
        assertEquals(1L, s.vcpusMax); assertEquals(1L, s.vcpusAtStartup)
        assertEquals(listOf(512L shl 20, 1L shl 30, 1L shl 30, 1L shl 30), listOf(s.memoryStaticMin, s.memoryDynamicMin, s.memoryDynamicMax, s.memoryStaticMax))
        assertNull(s.autoPowerOn); assertFalse(s.poolAutoPowerOn); assertFalse(s.haEnabled); assertEquals("", s.haPriority)
        assertTrue(s.hvm); assertEquals("BIOS", s.firmware); assertEquals(mapOf("order" to "c"), s.bootParams)
        // Gating from the real allowed_operations of a running guest.
        assertNull(SettingsRules.unavailableReason(s, SettingGroup.VCPUS)); assertNull(SettingsRules.unavailableReason(s, SettingGroup.MEMORY))
        assertNull(SettingsRules.unavailableReason(s, SettingGroup.BOOT_ORDER))
        assertEquals("Not applicable: high availability is off for this pool.", SettingsRules.unavailableReason(s, SettingGroup.HA_PRIORITY))
        assertEquals("Shut down the VM to change this.", SettingsRules.readOnlyReason(s, SettingField.VCPUS_MAX))
        assertTrue(setterCalls().isEmpty())
        assertTrue(calls.all { XapiMethods.isRead(it) || it.startsWith("session.") })
    }

    @Test fun everySetterBodyIsByteIdenticalToWhatTheLabAccepted() {
        val result = save({ it.copy(name = "lab-renamed-guest", tags = listOf("probe-a"), memoryDynamicMin = "768", autoPowerOn = true, bootOrder = "dc") }, typed = true)
        assertEquals(result.message, PowerPhase.SUCCEEDED, result.phase)
        assertEquals(listOf("VM.set_name_label", "VM.add_tags", "VM.set_memory_dynamic_range", "VM.add_to_other_config", "VM.set_HVM_boot_params"), setterCalls())
        mapOf("VM.set_name_label" to "settings-name.set", "VM.add_tags" to "settings-tags.add", "VM.set_memory_dynamic_range" to "settings-memory.dynamic-lower",
            "VM.add_to_other_config" to "settings-autopoweron.add", "VM.set_HVM_boot_params" to "settings-boot.set")
            .forEach { (method, recorded) -> assertEquals(method, Live83Settings.request(recorded), setterBody(method)) }
        val after = read()
        assertEquals("lab-renamed-guest", after.name); assertEquals(listOf("probe-a"), after.tags); assertEquals(768L shl 20, after.memoryDynamicMin)
        assertEquals("true", after.autoPowerOn); assertEquals("dc", after.bootParams["order"])
    }

    @Test fun theRecordedPowerStateRefusalIsADefiniteRejection() {
        // The VM read as halted, but the server answers as the lab did for a running guest.
        vm["power_state"] = "Halted"; vm["allowed_operations"] = listOf("changing_VCPUs", "changing_memory_limits", "start")
        scripted["VM.set_VCPUs_max"] = "settings-vcpus.max-same"
        val result = save({ it.copy(vcpusMax = "2", vcpusAtStartup = "1") })
        vm["power_state"] = "Running"
        assertEquals(PowerPhase.FAILED, result.phase)
        assertEquals(SettingState.REJECTED, result.settings!!.single().state); assertEquals("VM_BAD_POWER_STATE", result.settings!!.single().code)
        assertEquals(listOf("VM.set_VCPUs_max"), setterCalls())
    }

    @Test fun recordedValueRefusalsAreDefiniteAndStopTheRun() {
        scripted["VM.set_VCPUs_number_live"] = "settings-vcpus.live-above-max" // INVALID_VALUE
        vm["VCPUs_max"] = "2"
        var result = save({ it.copy(vcpusLive = "2", bootOrder = "dc") })
        assertEquals(listOf(SettingState.REJECTED, SettingState.PENDING), result.settings!!.map { it.state })
        assertEquals("INVALID_VALUE", result.settings!![0].code); assertEquals(PowerPhase.FAILED, result.phase)
        assertEquals(listOf("VM.set_VCPUs_number_live"), setterCalls())
        scripted.clear()
        scripted["VM.set_memory_dynamic_range"] = "settings-memory.dynamic-below-static-min" // MEMORY_CONSTRAINT_VIOLATION_ORDER
        result = save({ it.copy(memoryDynamicMax = "900", memoryDynamicMin = "900") }, typed = true)
        assertEquals(SettingState.REJECTED, result.settings!!.single().state); assertEquals("MEMORY_CONSTRAINT_VIOLATION_ORDER", result.settings!!.single().code)
        scripted.clear()
        // MAP_DUPLICATE_KEY: the key appeared on the server between the read and the send.
        scripted["VM.add_to_other_config"] = "settings-autopoweron.add-duplicate"
        result = save({ it.copy(autoPowerOn = true) })
        assertEquals("MAP_DUPLICATE_KEY", result.settings!!.single().code); assertEquals(PowerPhase.FAILED, result.phase)
    }

    @Test fun theRecordedInternalErrorIsAmbiguousAndDecidedByTheReadBack() {
        scripted["VM.set_name_label"] = "settings-vcpus.live-not-a-number" // INTERNAL_ERROR, as the lab answered a malformed number
        val result = save({ it.copy(name = "other") })
        assertEquals(SettingState.NOT_APPLIED, result.settings!!.single().state); assertEquals("INTERNAL_ERROR", result.settings!!.single().code)
        assertEquals(PowerPhase.FAILED, result.phase)
        assertTrue(result.message, result.message.contains("the value did not change"))
    }

    @Test fun aLostReplyIsNeverResentAndCheckStatusDecidesLater() = runBlocking {
        lost["VM.set_name_description"] = false // the request never took effect, and no reply came
        val result = save({ it.copy(description = "edited") })
        assertEquals(PowerPhase.UNKNOWN, result.phase)
        assertEquals(1, setterCalls().size)
        var checked = service.reconcile(profile, store.records.values.single())
        assertEquals(PowerPhase.UNKNOWN, checked.phase)
        offset = SettingsRules.QUIET_MILLIS + 1_000
        checked = service.reconcile(profile, store.records.values.single())
        assertEquals(PowerPhase.FAILED, checked.phase); assertEquals(SettingState.NOT_APPLIED, checked.settings!!.single().state)
        assertEquals(1, setterCalls().size)
    }

    @Test fun aLostReplyAfterTheServerAppliedItIsProvenAndStopsTheRun() {
        lost["VM.set_name_label"] = true
        val result = save({ it.copy(name = "renamed", description = "edited") })
        assertEquals(listOf(SettingState.DONE, SettingState.PENDING), result.settings!!.map { it.state })
        assertEquals(listOf("VM.set_name_label"), setterCalls())
        assertEquals(PowerPhase.FAILED, result.phase)
    }

    @Test fun aSecondStepRefusedAfterTheFirstSucceededIsShownAsPartlySaved() {
        scripted["VM.add_tags"] = "settings-vcpus.startup-above-max" // a recorded INVALID_VALUE
        val result = save({ it.copy(name = "renamed", tags = listOf("t"), bootOrder = "dc") })
        assertEquals(listOf(SettingState.DONE, SettingState.REJECTED, SettingState.PENDING), result.settings!!.map { it.state })
        assertEquals("Partly saved", OperationHistory.status(result))
        assertEquals(listOf("VM.set_name_label", "VM.add_tags"), setterCalls())
        assertEquals("renamed", read().name)
    }

    @Test fun aRemovedVmIsNotSentAnything() {
        val s = read()
        val d = SettingsDraft.from(s).copy(name = "x")
        vm["uuid"] = "00000000-0000-4000-8000-00000000abcd" // the original UUID now answers UUID_INVALID
        val error = runCatching { runBlocking { service.executeSettings(profile, SettingsConfirmation(profile.id, profile.origin(), s, d, SettingsRules.plan(s, d))) } }.exceptionOrNull()
        assertTrue("$error", error is IllegalArgumentException)
        assertTrue(setterCalls().isEmpty()); assertTrue(store.records.isEmpty())
    }
}
