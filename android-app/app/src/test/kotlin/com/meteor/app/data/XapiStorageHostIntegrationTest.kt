package com.meteor.app.data

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * B2 storage/host operations through the production TLS pin, HTTP, XML-RPC, gateway and
 * PowerService paths, against a stateful loopback XAPI model. No lab or network service is used.
 * The model imitates the documented effects only; live XCP-ng behavior is confirmed separately.
 */
class XapiStorageHostIntegrationTest {
    companion object {
        private lateinit var directory: Path
        private lateinit var tls: SSLContext
        private lateinit var pin: String

        @JvmStatic @BeforeClass fun createTemporaryCertificate() {
            directory = Files.createTempDirectory("meteor-b2-fixture-")
            val storeFile = directory.resolve("fixture.p12")
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toFile()
                .takeIf { it.exists() } ?: Path.of(System.getProperty("java.home"), "bin", "keytool").toFile()
            val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-storetype", "PKCS12", "-keystore", storeFile.toString(),
                "-storepass", "test-fixture-only", "-keypass", "test-fixture-only", "-noprompt")
                .redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            check(process.waitFor() == 0) { "Temporary TLS fixture generation failed." }
            val store = KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(storeFile).use { load(it, "test-fixture-only".toCharArray()) }
            }
            pin = ServerTrust.fingerprint(store.getCertificate("fixture") as X509Certificate)
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, "test-fixture-only".toCharArray())
            }
            tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        }

        @JvmStatic @AfterClass fun removeTemporaryCertificate() {
            if (::directory.isInitialized) {
                Files.deleteIfExists(directory.resolve("fixture.p12"))
                Files.deleteIfExists(directory)
            }
        }
        private const val NULL = "OpaqueRef:NULL"
    }

    private val calls = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val executor = Executors.newCachedThreadPool { action -> Thread(action, "meteor-b2-fixture").apply { isDaemon = true } }
    private lateinit var server: HttpsServer
    private lateinit var profile: Profile

    // Server-side model. Every request is handled under [lock].
    private val lock = Any()
    private val classes = mutableMapOf<String, LinkedHashMap<String, MutableMap<String, Any>>>()
    private fun table(type: String) = classes.getOrPut(type) { linkedMapOf() }
    private val arity = mutableMapOf<String, Int>()
    private val rpcErrors = mutableMapOf<String, String>()
    private var blockers = mapOf<String, List<String>>()
    @Volatile private var dropAsyncResponse = false
    private var counter = 0
    private val destroyed = mutableListOf<String>()

    private fun seed() {
        table("pool")["pool-ref"] = mutableMapOf("uuid" to "pool-id", "name_label" to "Fixture pool", "master" to "host-a-ref")
        fun host(ref: String, uuid: String, name: String, metrics: String, resident: List<String>, boot: String) {
            table("host")[ref] = mutableMapOf("uuid" to uuid, "name_label" to name, "enabled" to true, "metrics" to metrics,
                "resident_VMs" to resident.toMutableList(), "allowed_operations" to listOf("evacuate", "reboot", "shutdown", "provision"),
                "current_operations" to emptyMap<String, String>(), "other_config" to mutableMapOf("boot_time" to boot, "agent_start_time" to "1700000100."))
            table("host_metrics")[metrics] = mutableMapOf("uuid" to "$metrics-uuid", "live" to true)
        }
        host("host-a-ref", "host-a", "Host A", "metrics-a", listOf("dom0-a-ref", "vm-ref"), "1700000000.5")
        host("host-b-ref", "host-b", "Host B", "metrics-b", listOf("dom0-b-ref"), "1700000001.5")
        fun dom0(ref: String, host: String) { table("VM")[ref] = vm(ref, "$ref-uuid", "Control domain", "Running", host).apply { put("is_control_domain", true) } }
        dom0("dom0-a-ref", "host-a-ref"); dom0("dom0-b-ref", "host-b-ref")
        table("VM")["vm-ref"] = vm("vm-ref", "vm-id", "Fixture VM", "Running", "host-a-ref").apply {
            put("VBDs", mutableListOf("vbd-disk-ref", "vbd-cd-ref")); put("allowed_operations", listOf("snapshot", "clean_shutdown", "hard_shutdown"))
        }
        table("SR")["sr-local-ref"] = sr("sr-local", "Local storage", "user")
        table("SR")["sr-iso-ref"] = sr("sr-iso", "ISO library", "iso")
        table("SR")["sr-nfs-ref"] = sr("sr-nfs", "Shared NFS", "user")
        table("VDI")["vdi-live-ref"] = vdi("vdi-live", "Fixture disk", "sr-local-ref", false, "vbd-disk-ref")
        table("VDI")["iso-1-ref"] = vdi("iso-1", "debian-12.iso", "sr-iso-ref", false)
        table("VDI")["iso-2-ref"] = vdi("iso-2", "guest-tools.iso", "sr-iso-ref", false)
        table("VBD")["vbd-disk-ref"] = vbd("vbd-disk", "vm-ref", "Disk", "0", "vdi-live-ref")
        table("VBD")["vbd-cd-ref"] = vbd("vbd-cd", "vm-ref", "CD", "3", NULL)
        table("PBD")["pbd-a-ref"] = mutableMapOf("uuid" to "pbd-a", "SR" to "sr-nfs-ref", "host" to "host-a-ref", "currently_attached" to true)
        table("PBD")["pbd-b-ref"] = mutableMapOf("uuid" to "pbd-b", "SR" to "sr-nfs-ref", "host" to "host-b-ref", "currently_attached" to false)
    }
    private fun vm(ref: String, uuid: String, name: String, power: String, host: String): MutableMap<String, Any> = mutableMapOf("uuid" to uuid,
        "name_label" to name, "name_description" to "", "power_state" to power, "resident_on" to host, "is_control_domain" to false,
        "is_a_template" to false, "is_a_snapshot" to false, "snapshot_of" to NULL, "snapshots" to mutableListOf<String>(), "parent" to NULL,
        "snapshot_time" to "", "suspend_VDI" to NULL, "VBDs" to mutableListOf<String>(), "VIFs" to emptyList<String>(),
        "allowed_operations" to emptyList<String>(), "current_operations" to emptyMap<String, String>(), "metrics" to NULL, "guest_metrics" to NULL)
    private fun sr(uuid: String, name: String, content: String): MutableMap<String, Any> = mutableMapOf("uuid" to uuid, "name_label" to name,
        "content_type" to content, "current_operations" to emptyMap<String, String>())
    private fun vdi(uuid: String, name: String, sr: String, snapshot: Boolean, vararg vbds: String): MutableMap<String, Any> = mutableMapOf("uuid" to uuid,
        "name_label" to name, "SR" to sr, "is_a_snapshot" to snapshot, "missing" to false, "VBDs" to vbds.toMutableList())
    private fun vbd(uuid: String, vm: String, type: String, device: String, vdi: String): MutableMap<String, Any> = mutableMapOf("uuid" to uuid,
        "VM" to vm, "type" to type, "userdevice" to device, "empty" to (vdi == NULL), "VDI" to vdi, "currently_attached" to true,
        "allowed_operations" to if (type == "CD") listOf(if (vdi == NULL) "insert" else "eject") else emptyList(), "current_operations" to emptyMap<String, String>())

    @Suppress("UNCHECKED_CAST") private fun MutableMap<String, Any>.list(key: String) = this[key] as MutableList<String>
    @Suppress("UNCHECKED_CAST") private fun MutableMap<String, Any>.struct(key: String) = this[key] as MutableMap<String, String>

    // Effects of the async calls, modeled on the documented XAPI behavior.
    private fun async(method: String, args: List<String>): String {
        val task = "OpaqueRef:task-${++counter}"
        when (method) {
            "Async.VM.snapshot" -> {
                val live = table("VM").getValue(args[1]); val n = counter
                val snap = vm("snap-$n-ref", "snap-$n", args[2], "Halted", NULL).apply {
                    put("is_a_snapshot", true); put("snapshot_of", args[1]); put("snapshot_time", "20260101T12:00:0${n % 10}Z"); put("allowed_operations", listOf("revert", "destroy", "copy"))
                }
                live.list("VBDs").forEachIndexed { i, ref ->
                    val original = table("VBD").getValue(ref)
                    val copyRef = "svbd-$n-$i-ref"
                    var disk = original["VDI"] as String
                    if (original["type"] == "Disk" && disk != NULL) {
                        val source = table("VDI").getValue(disk)
                        disk = "svdi-$n-$i-ref"
                        table("VDI")[disk] = vdi("svdi-$n-$i", "${source["name_label"]} snapshot", source["SR"] as String, true, copyRef)
                    } else if (disk != NULL) table("VDI").getValue(disk).list("VBDs") += copyRef
                    table("VBD")[copyRef] = vbd("svbd-$n-$i", "snap-$n-ref", original["type"] as String, original["userdevice"] as String, disk)
                    snap.list("VBDs") += copyRef
                }
                table("VM")["snap-$n-ref"] = snap
                live.list("snapshots") += "snap-$n-ref"; live["parent"] = "snap-$n-ref"
            }
            "Async.VM.revert" -> {
                val snap = table("VM").getValue(args[1]); val live = table("VM").getValue(snap["snapshot_of"] as String)
                if (live["power_state"] == "Running") table("host").getValue(live["resident_on"] as String).list("resident_VMs") -= snap["snapshot_of"] as String
                live["power_state"] = "Halted"; live["resident_on"] = NULL; live["parent"] = args[1]
                live.list("VBDs").map { table("VBD").getValue(it) }.filter { it["type"] == "Disk" && it["VDI"] != NULL }.forEach { disk ->
                    val old = disk["VDI"] as String
                    val clone = "rvdi-$counter-ref"
                    table("VDI")[clone] = vdi("rvdi-$counter", "Fixture disk", "sr-local-ref", false, live.list("VBDs").first())
                    table("VDI").remove(old); disk["VDI"] = clone
                }
            }
            "Async.VDI.destroy" -> {
                val disk = table("VDI").remove(args[1]) ?: error("missing VDI")
                destroyed += disk["uuid"] as String
                disk.list("VBDs").forEach { ref -> table("VBD").remove(ref)?.let { table("VM")[it["VM"]]?.list("VBDs")?.remove(ref) } }
            }
            "Async.VM.destroy" -> {
                val snap = table("VM").getValue(args[1])
                check(snap["is_a_snapshot"] == true) { "The fixture refuses to destroy a VM that is not a snapshot." }
                table("VM").remove(args[1]); destroyed += snap["uuid"] as String
                snap.list("VBDs").forEach { ref -> table("VBD").remove(ref)?.let { b -> table("VDI")[b["VDI"]]?.list("VBDs")?.remove(ref) } }
                table("VM")[snap["snapshot_of"]]?.list("snapshots")?.remove(args[1])
            }
            "Async.VBD.insert" -> table("VBD").getValue(args[1]).apply { put("empty", false); put("VDI", args[2]); put("allowed_operations", listOf("eject")) }
            "Async.VBD.eject" -> table("VBD").getValue(args[1]).apply { put("empty", true); put("VDI", NULL); put("allowed_operations", listOf("insert")) }
            "Async.host.disable" -> table("host").getValue(args[1])["enabled"] = false
            "Async.host.enable" -> table("host").getValue(args[1])["enabled"] = true
            "Async.host.evacuate" -> {
                val source = table("host").getValue(args[1]); val other = table("host").keys.first { it != args[1] }
                source.list("resident_VMs").filter { table("VM").getValue(it)["is_control_domain"] == false }.forEach { ref ->
                    source.list("resident_VMs") -= ref; table("host").getValue(other).list("resident_VMs") += ref; table("VM").getValue(ref)["resident_on"] = other
                }
            }
            "Async.host.reboot" -> table("host").getValue(args[1]).struct("other_config")["boot_time"] = "1700009999.25"
            "Async.host.shutdown" -> table("host_metrics").getValue(table("host").getValue(args[1])["metrics"] as String)["live"] = false
            "Async.PBD.plug" -> table("PBD").getValue(args[1])["currently_attached"] = true
            else -> error("Unexpected fixture write: $method")
        }
        return task
    }
    private fun result(method: String, args: List<String>): Any {
        val type = method.substringBefore('.')
        return when {
            method == "session.login_with_password" -> "OpaqueRef:fixture-session"
            method == "session.logout" -> ""
            method.endsWith(".get_all_records") -> table(type)
            method.endsWith(".get_by_uuid") && type != "task" -> table(type).entries.firstOrNull { it.value["uuid"] == args[1] }?.key ?: throw Rejected("UUID_INVALID")
            method.endsWith(".get_record") && type != "task" -> table(type)[args[1]] ?: throw Rejected("HANDLE_INVALID")
            method == "host.get_vms_which_prevent_evacuation" -> blockers
            method == "task.get_uuid" -> "uuid-${args[1]}"
            method == "task.get_by_uuid" -> args[1].removePrefix("uuid-")
            method == "task.get_record" -> mapOf("uuid" to "uuid-${args[1]}", "status" to "success", "progress" to "1.0", "error_info" to emptyList<String>())
            method.startsWith("Async.") -> async(method, args)
            else -> error("Unexpected fixture request: $method")
        }
    }
    private class Rejected(val code: String) : Exception(code)
    private fun params(body: String): List<String> = body.split("<param>").drop(1).map { it.substringBefore("</param>") }.map { param ->
        when {
            param.startsWith("<value><string>") -> param.removePrefix("<value><string>").substringBefore("</string>").replace("&amp;", "&")
            param.startsWith("<value><boolean>") -> "bool:" + param.removePrefix("<value><boolean>").substringBefore("</boolean>")
            param.startsWith("<value><array>") -> "array"
            else -> "other"
        }
    }

    @Before fun startFixture() {
        seed()
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(tls)
            this.executor = this@XapiStorageHostIntegrationTest.executor
        }
        server.createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = body.substringAfter("<methodName>").substringBefore("</methodName>")
                calls += method; bodies += body
                val args = params(body)
                val xml = synchronized(lock) {
                    try {
                        rpcErrors[method]?.let { throw Rejected(it) }
                        arity[method]?.let { if (args.size != it) throw Rejected("MESSAGE_PARAMETER_COUNT_MISMATCH") }
                        response(mapOf("Status" to "Success", "Value" to result(method, args)))
                    } catch (rejected: Rejected) {
                        response(mapOf("Status" to "Failure", "ErrorDescription" to listOf(rejected.code, method)))
                    }
                }
                if (dropAsyncResponse && method.startsWith("Async.")) exchange.sendResponseHeaders(500, -1)
                else {
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "text/xml; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        server.start()
        profile = Profile("fixture", "Fixture", "https://127.0.0.1:${server.address.port}/", "fixture-user", "fixture-password", pin)
    }
    @After fun stopFixture() {
        if (::server.isInitialized) server.stop(0)
        executor.shutdownNow()
    }
    private fun value(input: Any): String = when (input) {
        is Map<*, *> -> "<struct>" + input.entries.joinToString("") { "<member><name>${it.key}</name><value>${value(requireNotNull(it.value))}</value></member>" } + "</struct>"
        is Boolean -> "<boolean>${if (input) 1 else 0}</boolean>"
        is List<*> -> "<array><data>" + input.joinToString("") { "<value>${value(requireNotNull(it))}</value>" } + "</data></array>"
        else -> "<string>${input.toString().replace("&", "&amp;").replace("<", "&lt;")}</string>"
    }
    private fun response(envelope: Map<String, Any>) = "<methodResponse><params><param><value>${value(envelope)}</value></param></params></methodResponse>"

    private class Store : OperationStore {
        val records = linkedMapOf<String, PowerOperation>()
        override suspend fun load() = records.values.map { JournalCodec.decode(JournalCodec.encode(it)) }
        override suspend fun put(operation: PowerOperation) { records[operation.id] = operation }
    }
    private val gateway = XapiGateway()
    private val store = Store()
    private val service by lazy { PowerService(gateway, store, System::currentTimeMillis, {}, 3) }
    private suspend fun read(kind: TargetKind, uuid: String) = gateway.targetState(profile, "pool-id", kind, uuid)
    private suspend fun run(action: TargetAction, state: TargetState, name: String? = null, iso: IsoChoice? = null): PowerOperation =
        service.executeTarget(profile, TargetConfirmation(profile.id, profile.origin(), action, state, iso, name,
            TargetRules.confirmationPhrase(action, state), if (state is SnapshotTarget) TargetRules.deletePlan(state) else emptyList()))
    private fun asyncCalls() = calls.filter { it.startsWith("Async.") }
    private fun asyncBodies(method: String) = bodies.filter { it.contains("<methodName>$method</methodName>") }

    @Test fun snapshotTakeRevertAndDeleteEndToEndNeverTouchTheLiveDisks() = runBlocking {
        // An ISO in the live CD drive is copied into the snapshot's CD slot; delete must leave it alone.
        val drive = (read(TargetKind.VM, "vm-id") as VmTarget).cdDrives.single()
        assertEquals(PowerPhase.SUCCEEDED, run(TargetAction.CD_INSERT, drive, iso = gateway.isoCatalog(profile, "pool-id").isos.first()).phase)
        val vm = read(TargetKind.VM, "vm-id") as VmTarget
        assertEquals(listOf("vdi-live"), vm.diskVdis); assertTrue(vm.snapshots.isEmpty())

        val taken = run(TargetAction.SNAPSHOT_TAKE, vm, name = "Before upgrade")
        assertEquals(taken.message, PowerPhase.SUCCEEDED, taken.phase)
        assertEquals("snap-2", taken.argumentUuid)
        val take = asyncBodies("Async.VM.snapshot").single()
        assertEquals(3, params(take).size) // session, VM, name: the form every XAPI version accepts
        assertTrue(take.contains("<string>vm-ref</string>") && take.contains("<string>Before upgrade</string>"))
        val after = read(TargetKind.VM, "vm-id") as VmTarget
        assertEquals(listOf("Before upgrade"), after.snapshots.map { it.name }); assertEquals("snap-2", after.parentUuid)

        val snapshot = read(TargetKind.SNAPSHOT, "snap-2") as SnapshotTarget
        assertEquals("Fixture VM", snapshot.vmName); assertEquals(listOf("vdi-live"), snapshot.liveDisks)
        val reverted = run(TargetAction.SNAPSHOT_REVERT, snapshot)
        assertEquals(reverted.message, PowerPhase.SUCCEEDED, reverted.phase)
        val afterRevert = read(TargetKind.VM, "vm-id") as VmTarget
        assertEquals("Halted", afterRevert.power); assertEquals("snap-2", afterRevert.parentUuid)
        assertNotEquals(listOf("vdi-live"), afterRevert.diskVdis)

        val toDelete = read(TargetKind.SNAPSHOT, "snap-2") as SnapshotTarget
        val plan = TargetRules.deletePlan(toDelete)
        assertEquals(listOf(StepKind.VDI_DESTROY to "svdi-2-0", StepKind.VM_DESTROY to "snap-2"), plan.map { it.kind to it.uuid })
        assertTrue(toDelete.disks.any { it.type == "CD" && it.vdiUuid == "iso-1" })
        val deleted = run(TargetAction.SNAPSHOT_DELETE, toDelete)
        assertEquals(deleted.message, PowerPhase.SUCCEEDED, deleted.phase)
        assertEquals(listOf("svdi-2-0", "snap-2"), destroyed)
        assertTrue(read(TargetKind.SNAPSHOT, "snap-2") is MissingTarget)
        val finalVm = read(TargetKind.VM, "vm-id") as VmTarget
        assertEquals(afterRevert.diskVdis, finalVm.diskVdis); assertTrue(finalVm.snapshots.isEmpty())
        assertEquals("iso-1", finalVm.cdDrives.single().vdiUuid)
        assertTrue(table("VDI").containsKey("iso-1-ref"))
        assertEquals(1, asyncBodies("Async.VDI.destroy").size)
        assertTrue(asyncBodies("Async.VDI.destroy").single().contains("<string>svdi-2-0-ref</string>"))
        assertEquals(listOf("Async.VBD.insert", "Async.VM.snapshot", "Async.VM.revert", "Async.VDI.destroy", "Async.VM.destroy"), asyncCalls())
    }

    @Test fun sendTimeChecksRefuseToDestroyAnythingButSnapshotObjects() = runBlocking {
        gateway.powerConnection(profile) {
            for (step in listOf(OperationStep(StepKind.VDI_DESTROY, "vdi-live", "Fixture disk"), OperationStep(StepKind.VM_DESTROY, "vm-id", "Fixture VM"),
                OperationStep(StepKind.VDI_DESTROY, "iso-1", "debian-12.iso"), OperationStep(StepKind.VDI_DESTROY, "missing", "gone"))) {
                assertTrue("$step", runCatching { submitStep("pool-id", step) }.exceptionOrNull() is NotSent)
            }
            // A snapshot VDI that a live VM also uses is refused too.
            synchronized(lock) {
                table("VDI")["vdi-live-ref"]!!["is_a_snapshot"] = true
            }
            assertTrue(runCatching { submitStep("pool-id", OperationStep(StepKind.VDI_DESTROY, "vdi-live", "Fixture disk")) }.exceptionOrNull() is NotSent)
            assertFalse(absent("pool-id", OperationStep(StepKind.VDI_DESTROY, "vdi-live", "x")))
            assertTrue(absent("pool-id", OperationStep(StepKind.VDI_DESTROY, "missing", "x")))
        }
        assertTrue(asyncCalls().isEmpty()); assertTrue(destroyed.isEmpty())
    }

    @Test fun arityFallbackIsSentOnceOnlyAfterAnExplicitParameterCountRejection() = runBlocking {
        synchronized(lock) { arity["Async.VM.snapshot"] = 4; arity["Async.host.evacuate"] = 4; arity["Async.host.disable"] = 3 }
        val taken = run(TargetAction.SNAPSHOT_TAKE, read(TargetKind.VM, "vm-id"), name = "Newer API")
        assertEquals(taken.message, PowerPhase.SUCCEEDED, taken.phase)
        val sent = asyncBodies("Async.VM.snapshot")
        assertEquals(listOf(3, 4), sent.map { params(it).size })
        assertTrue(sent[1].contains("<array><data></data></array>")) // ignore_vdis = []
        assertEquals(1, table("VM").getValue("vm-ref").list("snapshots").size)

        assertEquals(PowerPhase.SUCCEEDED, run(TargetAction.HOST_DISABLE, read(TargetKind.HOST, "host-a")).phase)
        assertTrue(asyncBodies("Async.host.disable")[1].contains("<boolean>1</boolean>")) // auto_enable = true
        val evacuated = run(TargetAction.HOST_EVACUATE, read(TargetKind.HOST, "host-a"))
        assertEquals(evacuated.message, PowerPhase.SUCCEEDED, evacuated.phase)
        assertEquals(listOf("session", "host", "OpaqueRef:NULL", "0").size, params(asyncBodies("Async.host.evacuate")[1]).size)
        assertTrue(asyncBodies("Async.host.evacuate")[1].contains("<string>OpaqueRef:NULL</string>") && asyncBodies("Async.host.evacuate")[1].contains("<string>0</string>"))

        // Any other explicit rejection is final: one call, FAILED, nothing resent.
        synchronized(lock) { rpcErrors["Async.VM.snapshot"] = "SR_OPERATION_NOT_SUPPORTED" }
        val refused = run(TargetAction.SNAPSHOT_TAKE, read(TargetKind.VM, "vm-id"), name = "Refused")
        assertEquals(PowerPhase.FAILED, refused.phase); assertTrue(refused.message.contains("SR_OPERATION_NOT_SUPPORTED"))
        assertEquals(3, asyncBodies("Async.VM.snapshot").size)
    }

    @Test fun cdInsertAndEjectEndToEnd() = runBlocking {
        val catalog = gateway.isoCatalog(profile, "pool-id")
        assertEquals(listOf("debian-12.iso", "guest-tools.iso"), catalog.isos.map { it.name }); assertFalse(catalog.truncated)
        assertTrue(catalog.isos.none { it.uuid == "vdi-live" })
        val drive = (read(TargetKind.VM, "vm-id") as VmTarget).cdDrives.single()
        assertTrue(drive.empty); assertEquals("3", drive.device); assertEquals("vm-id", drive.vmUuid)
        val inserted = run(TargetAction.CD_INSERT, drive, iso = catalog.isos[1])
        assertEquals(inserted.message, PowerPhase.SUCCEEDED, inserted.phase)
        val body = asyncBodies("Async.VBD.insert").single()
        assertTrue(body.contains("<string>vbd-cd-ref</string>") && body.contains("<string>iso-2-ref</string>"))
        val full = read(TargetKind.VBD, "vbd-cd") as CdDrive
        assertFalse(full.empty); assertEquals("guest-tools.iso", full.vdiName)
        assertNotNull(TargetRules.unavailableReason(full, TargetAction.CD_INSERT))
        val ejected = run(TargetAction.CD_EJECT, full)
        assertEquals(ejected.message, PowerPhase.SUCCEEDED, ejected.phase)
        assertTrue((read(TargetKind.VBD, "vbd-cd") as CdDrive).empty)
        assertEquals(listOf("Async.VBD.insert", "Async.VBD.eject"), asyncCalls())
    }

    @Test fun hostMaintenanceDisableEvacuateEnableWithBlockers() = runBlocking {
        val hostA = read(TargetKind.HOST, "host-a") as HostState
        assertTrue(hostA.master); assertEquals(listOf("Fixture VM"), hostA.residentVms); assertEquals(emptyList<EvacuationBlocker>(), hostA.blockers)
        assertEquals("1700000000.5", hostA.bootMarker)
        assertEquals(PowerPhase.SUCCEEDED, run(TargetAction.HOST_DISABLE, hostA).phase)
        // The server reports a blocker: evacuate is disabled with the reason.
        synchronized(lock) { blockers = mapOf("vm-ref" to listOf("VM_REQUIRES_SR", "vm-ref", "sr-local-ref")) }
        val blocked = read(TargetKind.HOST, "host-a") as HostState
        assertEquals(listOf(EvacuationBlocker("Fixture VM", "VM_REQUIRES_SR")), blocked.blockers)
        assertFalse(TargetRules.eligible(blocked, TargetAction.HOST_EVACUATE))
        assertTrue(TargetRules.unavailableReason(blocked, TargetAction.HOST_REBOOT)!!.contains("1 remains"))
        synchronized(lock) { blockers = emptyMap() }
        val evacuated = run(TargetAction.HOST_EVACUATE, read(TargetKind.HOST, "host-a"))
        assertEquals(evacuated.message, PowerPhase.SUCCEEDED, evacuated.phase)
        assertEquals("host-b-ref", table("VM").getValue("vm-ref")["resident_on"])
        val empty = read(TargetKind.HOST, "host-a") as HostState
        assertTrue(empty.residentVms.isEmpty()); assertTrue(TargetRules.eligible(empty, TargetAction.HOST_REBOOT))
        assertEquals(PowerPhase.SUCCEEDED, run(TargetAction.HOST_ENABLE, empty).phase)
        assertTrue((read(TargetKind.HOST, "host-a") as HostState).enabled)
        assertEquals(listOf("Async.host.disable", "Async.host.evacuate", "Async.host.enable"), asyncCalls())
        assertEquals(1, params(asyncBodies("Async.host.evacuate").single()).size - 1)
    }

    @Test fun memberRebootIsVerifiedByAChangedBootMarkerAndShutdownByLiveness() = runBlocking {
        assertEquals(PowerPhase.SUCCEEDED, run(TargetAction.HOST_DISABLE, read(TargetKind.HOST, "host-b")).phase)
        val hostB = read(TargetKind.HOST, "host-b") as HostState
        assertFalse(hostB.master); assertTrue(hostB.residentVms.isEmpty())
        val rebooted = run(TargetAction.HOST_REBOOT, hostB)
        assertEquals(rebooted.message, PowerPhase.SUCCEEDED, rebooted.phase)
        assertEquals("1700000001.5", rebooted.beforeBoot)
        assertEquals("1700009999.25", (read(TargetKind.HOST, "host-b") as HostState).bootMarker)
        val down = run(TargetAction.HOST_SHUTDOWN, read(TargetKind.HOST, "host-b"))
        assertEquals(down.message, PowerPhase.SUCCEEDED, down.phase)
        assertFalse((read(TargetKind.HOST, "host-b") as HostState).live)
        assertEquals(listOf("Async.host.disable", "Async.host.reboot", "Async.host.shutdown"), asyncCalls())
    }

    @Test fun pbdPlugRepairsAnUnattachedConnection() = runBlocking {
        val pbds = gateway.poolPbds(profile, "pool-id")
        assertEquals(listOf("Host A" to true, "Host B" to false), pbds.map { it.hostName to it.attached })
        assertTrue(pbds.all { it.srName == "Shared NFS" })
        val broken = pbds.single { !it.attached }
        val plugged = run(TargetAction.PBD_PLUG, read(TargetKind.PBD, broken.uuid))
        assertEquals(plugged.message, PowerPhase.SUCCEEDED, plugged.phase)
        assertTrue((read(TargetKind.PBD, "pbd-b") as PbdState).attached)
        assertTrue(asyncBodies("Async.PBD.plug").single().contains("<string>pbd-b-ref</string>"))
    }

    @Test fun aLostReplyIsUnknownAndIsNeverResent() = runBlocking {
        dropAsyncResponse = true
        val lost = run(TargetAction.PBD_PLUG, read(TargetKind.PBD, "pbd-b"))
        assertEquals(PowerPhase.UNKNOWN, lost.phase); assertTrue(lost.unresolved)
        dropAsyncResponse = false
        assertEquals(PowerPhase.UNKNOWN, service.reconcile(profile, lost).phase)
        assertTrue(runCatching { run(TargetAction.HOST_DISABLE, read(TargetKind.HOST, "host-b")) }.isFailure)
        // The server applied it, but without a task ticket Meteor never claims success; review closes it.
        assertTrue((read(TargetKind.PBD, "pbd-b") as PbdState).attached)
        val review = service.reviewTarget(profile, store.load().single())
        val closed = service.resolveTargetAfterReview(profile, review, true)
        assertEquals(PowerPhase.UNKNOWN, closed.phase); assertFalse(closed.unresolved); assertEquals("Attached", closed.manualResolution?.power)
        assertEquals(listOf("Async.PBD.plug"), asyncCalls())
        assertEquals("session.logout", calls.last())
    }
}
