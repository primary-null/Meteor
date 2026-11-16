package com.meteor.app.data

import java.util.Locale

/*
 * VM settings: the synchronous journaled write (METEOR-VM-SETTINGS.md).
 *
 * Every earlier write is an `Async.*` call that returns a task. A settings change is a synchronous setter
 * (`VM.set_name_label`, `VM.set_memory_limits`, ...) that has no task: its reply is its only answer, and when the
 * reply is lost there is nothing to look up. So the proof is always a read-back of the field, and the record keeps
 * everything needed to judge one: the value read just before sending, the requested value and the send time.
 *
 * One confirmed edit is one journal record (`action` = "VM_SETTINGS", never a PowerAction or TargetAction name, so
 * 1.10 keeps it as a locked foreign record and can close it through its existing VM review). Its `settings` key lists
 * the setter calls as journaled sub-steps, in the order they are sent. Each is journaled SUBMITTING (with `sentAt`)
 * before its single send. Meteor goes on to the next step only after a clean reply and a read-back of the requested
 * value; anything else stops the run, and steps not yet sent are never sent automatically.
 */

/** Every setter Meteor sends. The enum name is the journal key; never rename an entry. */
enum class SettingKind(val label: String, val xapiMethod: String) {
    NAME("Name", "VM.set_name_label"),
    DESCRIPTION("Description", "VM.set_name_description"),
    TAG_ADD("Tag", "VM.add_tags"),
    TAG_REMOVE("Tag", "VM.remove_tags"),
    VCPUS_MAX("Maximum vCPUs", "VM.set_VCPUs_max"),
    VCPUS_AT_STARTUP("vCPUs at start-up", "VM.set_VCPUs_at_startup"),
    VCPUS_LIVE("vCPUs", "VM.set_VCPUs_number_live"),
    MEMORY_LIMITS("Memory", "VM.set_memory_limits"),
    MEMORY_DYNAMIC("Memory", "VM.set_memory_dynamic_range"),
    AUTO_POWERON_ADD("Start with the host", "VM.add_to_other_config"),
    AUTO_POWERON_REMOVE("Start with the host", "VM.remove_from_other_config"),
    BOOT_PARAMS("Boot order", "VM.set_HVM_boot_params"),
    HA_PRIORITY("HA restart priority", "VM.set_ha_restart_priority"),
}

/**
 * PENDING: not sent. SUBMITTING: journaled just before its single send; until a later state is saved it may or may not
 * have reached the server. DONE: the read-back showed the requested value. REJECTED: an answer that proves nothing was
 * applied. NOT_APPLIED: the call is over (an ambiguous answer, or a lost reply after the quiet period) and the read-back
 * shows the old value. OVERRIDDEN: the server accepted it, but the read-back shows a third value (changed meanwhile by
 * someone else). UNKNOWN: anything that cannot be decided; kept for Check status and manual review.
 */
enum class SettingState { PENDING, SUBMITTING, DONE, REJECTED, NOT_APPLIED, OVERRIDDEN, UNKNOWN }

/**
 * One journaled setter call. [before] and [requested] are canonical values of what the step changes (see
 * [SettingsRules.value]); [argument] is the tag of a tag step. [observed] is the last value read back, [code] the
 * XAPI error code when one was returned, [sentAt] the phone time journaled just before the send.
 */
data class SettingStep(
    val kind: SettingKind, val argument: String?, val before: String, val requested: String,
    val state: SettingState = SettingState.PENDING, val observed: String? = null, val code: String? = null, val sentAt: Long? = null,
) {
    init { require(kind !in SettingsRules.TAG_KINDS || !argument.isNullOrBlank()) { "A tag step needs its tag." } }
}

/** A fresh read of everything the editor shows and the proof needs, bound to its pool and VM. */
data class VmSettings(
    val authority: String, val uuid: String, val name: String, val description: String, val tags: List<String>,
    val power: String, val allowed: Set<String>, val busy: Boolean,
    val vcpusMax: Long, val vcpusAtStartup: Long, val vcpusLive: Long?,
    val memoryStaticMin: Long, val memoryDynamicMin: Long, val memoryDynamicMax: Long, val memoryStaticMax: Long,
    /** other_config:auto_poweron, or null when the key is absent. */
    val autoPowerOn: String?, val poolAutoPowerOn: Boolean,
    val hvm: Boolean, val firmware: String, val bootParams: Map<String, String>,
    val haEnabled: Boolean, val haPriority: String,
    /** Free memory of the host the VM runs on, or the largest free memory of any host while it is halted. Context only. */
    val hostFreeMemory: Long?, val hostFreeMemoryOnResident: Boolean,
    /** The guest's `feature-vcpu-hotplug` report, or null when the guest tools do not say. */
    val guestVcpuHotplug: Boolean?,
    val observedAt: Long,
) {
    val autoPowerOnEnabled get() = autoPowerOn == "true"
    /** Everything that must be unchanged between the confirmation and the send. */
    val fence: List<Any?> get() = listOf(name, description, tags.sorted(), power, allowed.sorted(), busy, vcpusMax, vcpusAtStartup, vcpusLive,
        memoryStaticMin, memoryDynamicMin, memoryDynamicMax, memoryStaticMax, autoPowerOn, poolAutoPowerOn, hvm, firmware,
        bootParams.toSortedMap(), haEnabled, haPriority)
}

/** What the person typed or chose. Text fields keep the raw text so validation can name the problem. Memory is MiB. */
data class SettingsDraft(
    val name: String, val description: String, val tags: List<String>,
    val vcpusMax: String, val vcpusAtStartup: String, val vcpusLive: String,
    val memoryStaticMin: String, val memoryDynamicMin: String, val memoryDynamicMax: String, val memoryStaticMax: String,
    val autoPowerOn: Boolean, val bootOrder: String, val haPriority: String,
) {
    /** Saved instance state: a flat list of strings (tags cannot contain line breaks). */
    fun toSaved(): ArrayList<String> = arrayListOf(name, description, tags.joinToString(TAG_SEPARATOR), vcpusMax, vcpusAtStartup, vcpusLive,
        memoryStaticMin, memoryDynamicMin, memoryDynamicMax, memoryStaticMax, if (autoPowerOn) "1" else "0", bootOrder, haPriority)
    companion object {
        /** A line feed; tags never contain control characters. */
        private const val TAG_SEPARATOR = "\n"
        fun fromSaved(saved: List<String>): SettingsDraft? = if (saved.size != 13) null else SettingsDraft(saved[0], saved[1],
            saved[2].split(TAG_SEPARATOR).filter { it.isNotEmpty() }, saved[3], saved[4], saved[5], saved[6], saved[7], saved[8], saved[9],
            saved[10] == "1", saved[11], saved[12])
        fun from(s: VmSettings) = SettingsDraft(s.name, s.description, s.tags, s.vcpusMax.toString(), s.vcpusAtStartup.toString(),
            (s.vcpusLive ?: s.vcpusAtStartup).toString(), mib(s.memoryStaticMin), mib(s.memoryDynamicMin), mib(s.memoryDynamicMax), mib(s.memoryStaticMax),
            s.autoPowerOnEnabled, s.bootParams["order"].orEmpty(), s.haPriority)
        private fun mib(bytes: Long) = if (bytes % SettingsRules.MIB == 0L) (bytes / SettingsRules.MIB).toString() else String.format(Locale.ROOT, "%.2f", bytes / SettingsRules.MIB.toDouble())
    }
}

/** Editor fields, for validation messages and disabled reasons. */
enum class SettingField { NAME, DESCRIPTION, TAGS, VCPUS_MAX, VCPUS_AT_STARTUP, VCPUS_LIVE, MEMORY_STATIC_MIN, MEMORY_DYNAMIC_MIN,
    MEMORY_DYNAMIC_MAX, MEMORY_STATIC_MAX, AUTO_POWERON, BOOT_ORDER, HA_PRIORITY }
enum class SettingGroup { GENERAL, VCPUS, MEMORY, AUTO_POWERON, BOOT_ORDER, HA_PRIORITY }

/** A settings confirmation, bound to the connection identity and the fresh read the plan was built from. */
data class SettingsConfirmation(val profileId: String, val origin: String, val settings: VmSettings, val draft: SettingsDraft,
    val steps: List<SettingStep>, val typedName: String? = null)

/** How a failed setter call ended. */
enum class SettingFailure { NOT_SENT, REJECTED, AMBIGUOUS, LOST }

object SettingsRules {
    const val MIB = 1024L * 1024L
    const val CONFIRMATION_MILLIS = 30_000L
    /** After a lost reply, an old-value read-back proves "not applied" only when made this long after the send. */
    const val QUIET_MILLIS = 5 * 60_000L
    /** After a successful reply, the read-back may take this many reads (spaced by [SETTLE_PAUSE_MILLIS]) to show the value. */
    const val SETTLE_READS = 3
    const val SETTLE_PAUSE_MILLIS = 1_500L
    const val MAX_NAME = 255
    const val MAX_DESCRIPTION = 1000
    const val MAX_TAG = 64
    const val MAX_TAGS = 50
    const val MAX_VCPUS = 256L
    val TAG_KINDS = setOf(SettingKind.TAG_ADD, SettingKind.TAG_REMOVE)
    const val ABSENT = "-"
    val HA_CHOICES = listOf("restart", "best-effort", "")

    /**
     * XAPI answers that prove the setter changed nothing: the session, the object handle, the method and its arguments,
     * permissions, the power state and the value checks are all made before a setter writes anything (the lab showed
     * VM_BAD_POWER_STATE, INVALID_VALUE, MEMORY_CONSTRAINT_VIOLATION_ORDER, MAP_DUPLICATE_KEY and HANDLE_INVALID answers
     * to setters). Every other code, INTERNAL_ERROR in particular (the lab answered a malformed number with it), is
     * ambiguous: the reply came, but only a read-back can say what happened.
     */
    val DEFINITE = setOf("SESSION_INVALID", "HANDLE_INVALID", "UUID_INVALID", "MESSAGE_PARAMETER_COUNT_MISMATCH", "MESSAGE_METHOD_UNKNOWN",
        "RBAC_PERMISSION_DENIED", "PERMISSION_DENIED", "VM_BAD_POWER_STATE", "OPERATION_NOT_ALLOWED", "OTHER_OPERATION_IN_PROGRESS",
        "VM_IS_TEMPLATE", "INVALID_VALUE", "MEMORY_CONSTRAINT_VIOLATION", "MEMORY_CONSTRAINT_VIOLATION_ORDER", "MEMORY_CONSTRAINT_VIOLATION_MAXPIN",
        "MAP_DUPLICATE_KEY", "FIELD_TYPE_ERROR", "LICENCE_RESTRICTION", "VM_HVM_REQUIRED", "VALUE_NOT_SUPPORTED")

    fun failure(error: Throwable): SettingFailure = when {
        error is NotSent -> SettingFailure.NOT_SENT
        error is RpcFailure && error.explicitRejection && error.code in DEFINITE -> SettingFailure.REJECTED
        error is RpcFailure && error.explicitRejection -> SettingFailure.AMBIGUOUS
        // A generic fault, a transport failure or an unreadable reply: the request may still have been processed.
        else -> SettingFailure.LOST
    }

    // Canonical values. A tag step's value is "present"/"absent"; a map value is percent-escaped `k=v&k=v` in key order.

    fun value(kind: SettingKind, argument: String?, s: VmSettings): String? = when (kind) {
        SettingKind.NAME -> s.name
        SettingKind.DESCRIPTION -> s.description
        SettingKind.TAG_ADD, SettingKind.TAG_REMOVE -> if (argument in s.tags) "present" else "absent"
        SettingKind.VCPUS_MAX -> s.vcpusMax.toString()
        SettingKind.VCPUS_AT_STARTUP -> s.vcpusAtStartup.toString()
        SettingKind.VCPUS_LIVE -> s.vcpusLive?.toString()
        SettingKind.MEMORY_LIMITS -> limits(s.memoryStaticMin, s.memoryStaticMax, s.memoryDynamicMin, s.memoryDynamicMax)
        SettingKind.MEMORY_DYNAMIC -> limits(s.memoryDynamicMin, s.memoryDynamicMax)
        SettingKind.AUTO_POWERON_ADD, SettingKind.AUTO_POWERON_REMOVE -> s.autoPowerOn?.let { "=$it" } ?: ABSENT
        SettingKind.BOOT_PARAMS -> map(s.bootParams)
        SettingKind.HA_PRIORITY -> s.haPriority
    }
    fun limits(vararg values: Long) = values.joinToString(",")
    private fun esc(text: String) = text.replace("%", "%25").replace("&", "%26").replace("=", "%3D")
    private fun unesc(text: String) = text.replace("%3D", "=").replace("%26", "&").replace("%25", "%")
    fun map(values: Map<String, String>) = values.toSortedMap().entries.joinToString("&") { esc(it.key) + "=" + esc(it.value) }
    fun parseMap(text: String): Map<String, String> = if (text.isEmpty()) emptyMap() else text.split("&").associate {
        val (key, item) = it.split("=", limit = 2).let { parts -> parts[0] to parts.getOrElse(1) { "" } }
        unesc(key) to unesc(item)
    }

    /** Arguments after the session reference. [vm] is the reference resolved at send time. Integers travel as strings. */
    fun xapiArguments(step: SettingStep, vm: String): List<Any?> = when (step.kind) {
        SettingKind.NAME, SettingKind.DESCRIPTION, SettingKind.VCPUS_MAX, SettingKind.VCPUS_AT_STARTUP, SettingKind.VCPUS_LIVE,
        SettingKind.HA_PRIORITY -> listOf(vm, step.requested)
        SettingKind.TAG_ADD, SettingKind.TAG_REMOVE -> listOf(vm, requireNotNull(step.argument))
        SettingKind.MEMORY_LIMITS, SettingKind.MEMORY_DYNAMIC -> listOf(vm) + step.requested.split(",")
        SettingKind.AUTO_POWERON_ADD -> listOf(vm, "auto_poweron", step.requested.removePrefix("="))
        SettingKind.AUTO_POWERON_REMOVE -> listOf(vm, "auto_poweron")
        SettingKind.BOOT_PARAMS -> listOf(vm, parseMap(step.requested))
    }

    // Gating. Each group is enabled exactly when there is no reason to disable it.

    fun unavailableReason(s: VmSettings, group: SettingGroup): String? {
        if (s.busy) return "Another VM operation is active. Try again when it finishes."
        return when (group) {
            SettingGroup.GENERAL, SettingGroup.AUTO_POWERON -> null
            SettingGroup.VCPUS -> when (s.power) {
                "Halted" -> if ("changing_VCPUs" in s.allowed) null else "The server does not allow changing vCPUs for this VM now."
                "Running" -> if ("changing_VCPUs_live" in s.allowed) null
                    else "The server does not allow changing vCPUs while this VM runs. Shut it down to change them."
                else -> "Change vCPUs while the VM is halted or running."
            }
            SettingGroup.MEMORY -> when (s.power) {
                "Halted" -> if ("changing_memory_limits" in s.allowed) null else "The server does not allow changing memory for this VM now."
                "Running" -> if ("changing_dynamic_range" in s.allowed) null
                    else "The server does not allow changing memory while this VM runs. Shut it down to change it."
                else -> "Change memory while the VM is halted or running."
            }
            SettingGroup.BOOT_ORDER -> when {
                !s.hvm -> "Boot order applies to HVM guests only."
                s.firmware != "BIOS" -> "Boot order applies to BIOS guests. A UEFI guest keeps its boot order in its firmware."
                else -> null
            }
            SettingGroup.HA_PRIORITY -> if (s.haEnabled) null else "Not applicable: high availability is off for this pool."
        }
    }
    /** Fields that are only shown, with the reason, in the current power state. */
    fun readOnlyReason(s: VmSettings, field: SettingField): String? = when (field) {
        SettingField.VCPUS_MAX, SettingField.VCPUS_AT_STARTUP -> if (s.power == "Halted") null else "Shut down the VM to change this."
        SettingField.MEMORY_STATIC_MIN, SettingField.MEMORY_STATIC_MAX -> if (s.power == "Halted") null else "Shut down the VM to change this."
        else -> null
    }

    // Validation, as the person types. Messages are specific and short.

    private val control = Regex("[\\p{Cntrl}]")
    private fun count(text: String, field: String, min: Long, max: Long): Pair<Long?, String?> {
        val value = text.trim().toLongOrNull() ?: return null to (if (text.isBlank()) "Enter $field." else "Use a whole number.")
        return when {
            value < min -> value to "Use at least $min."
            value > max -> value to "Use at most $max."
            else -> value to null
        }
    }
    /** Memory in MiB from the editor; null when not a positive whole number. */
    fun mibToBytes(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it in 1..(Long.MAX_VALUE / MIB) }?.times(MIB)

    fun tagProblem(tag: String, existing: List<String>): String? = when {
        tag.isBlank() -> "Enter a tag."
        tag != tag.trim() -> "Remove spaces at the start or end."
        tag.length > MAX_TAG -> "Use at most $MAX_TAG characters."
        control.containsMatchIn(tag) -> "Remove line breaks and control characters."
        tag in existing -> "This tag is already set."
        existing.size >= MAX_TAGS -> "Use at most $MAX_TAGS tags."
        else -> null
    }

    fun problems(s: VmSettings, d: SettingsDraft): Map<SettingField, String> {
        val out = linkedMapOf<SettingField, String>()
        when {
            d.name.isBlank() -> out[SettingField.NAME] = "Enter a name."
            d.name.length > MAX_NAME -> out[SettingField.NAME] = "Use at most $MAX_NAME characters."
            control.containsMatchIn(d.name) -> out[SettingField.NAME] = "Remove line breaks and control characters."
        }
        when {
            d.description.length > MAX_DESCRIPTION -> out[SettingField.DESCRIPTION] = "Use at most $MAX_DESCRIPTION characters."
            control.containsMatchIn(d.description) -> out[SettingField.DESCRIPTION] = "Remove line breaks and control characters."
        }
        if (d.tags.size > MAX_TAGS) out[SettingField.TAGS] = "Use at most $MAX_TAGS tags."
        else d.tags.firstNotNullOfOrNull { tag -> tagProblem(tag, emptyList())?.let { "“${tag.take(20)}”: $it" } }?.let { out[SettingField.TAGS] = it }
        if (d.tags.distinct().size != d.tags.size) out[SettingField.TAGS] = "A tag is listed twice."
        if (unavailableReason(s, SettingGroup.VCPUS) == null) {
            if (s.power == "Halted") {
                val (max, maxProblem) = count(d.vcpusMax, "the maximum", 1, MAX_VCPUS)
                val (start, startProblem) = count(d.vcpusAtStartup, "the start-up count", 1, MAX_VCPUS)
                maxProblem?.let { out[SettingField.VCPUS_MAX] = it }
                startProblem?.let { out[SettingField.VCPUS_AT_STARTUP] = it }
                if (max != null && start != null && maxProblem == null && startProblem == null && start > max)
                    out[SettingField.VCPUS_AT_STARTUP] = "Start-up vCPUs cannot exceed the maximum ($max)."
            } else if (s.power == "Running") {
                val (live, problem) = count(d.vcpusLive, "the number of vCPUs", 1, MAX_VCPUS)
                when {
                    problem != null -> out[SettingField.VCPUS_LIVE] = problem
                    live != null && live > s.vcpusMax -> out[SettingField.VCPUS_LIVE] = "Use at most ${s.vcpusMax}, the maximum. Shut the VM down to raise the maximum."
                }
            }
        }
        if (unavailableReason(s, SettingGroup.MEMORY) == null) {
            val fields = if (s.power == "Halted") listOf(SettingField.MEMORY_STATIC_MIN to d.memoryStaticMin, SettingField.MEMORY_DYNAMIC_MIN to d.memoryDynamicMin,
                SettingField.MEMORY_DYNAMIC_MAX to d.memoryDynamicMax, SettingField.MEMORY_STATIC_MAX to d.memoryStaticMax)
                else listOf(SettingField.MEMORY_DYNAMIC_MIN to d.memoryDynamicMin, SettingField.MEMORY_DYNAMIC_MAX to d.memoryDynamicMax)
            val parsed = fields.associate { (field, text) -> field to mibToBytes(text) }
            fields.forEach { (field, text) -> if (parsed[field] == null) out[field] = if (text.isBlank()) "Enter a size in MiB." else "Use a whole number of MiB." }
            if (parsed.values.all { it != null }) {
                val smin = parsed[SettingField.MEMORY_STATIC_MIN] ?: s.memoryStaticMin
                val smax = parsed[SettingField.MEMORY_STATIC_MAX] ?: s.memoryStaticMax
                val dmin = parsed.getValue(SettingField.MEMORY_DYNAMIC_MIN)!!
                val dmax = parsed.getValue(SettingField.MEMORY_DYNAMIC_MAX)!!
                when {
                    smin > smax -> out[SettingField.MEMORY_STATIC_MIN] = "Static minimum cannot exceed the static maximum (${size(smax)})."
                    dmin < smin -> out[SettingField.MEMORY_DYNAMIC_MIN] = "Dynamic minimum must be at least the static minimum (${size(smin)})."
                    dmax < dmin -> out[SettingField.MEMORY_DYNAMIC_MAX] = "Dynamic maximum must be at least the dynamic minimum (${size(dmin)})."
                    dmax > smax -> out[SettingField.MEMORY_DYNAMIC_MAX] = "Dynamic maximum cannot exceed the static maximum (${size(smax)})."
                }
            }
        }
        if (unavailableReason(s, SettingGroup.BOOT_ORDER) == null && d.bootOrder != s.bootParams["order"].orEmpty()) bootOrderProblem(d.bootOrder)?.let { out[SettingField.BOOT_ORDER] = it }
        if (unavailableReason(s, SettingGroup.HA_PRIORITY) == null && d.haPriority !in HA_CHOICES && d.haPriority != s.haPriority)
            out[SettingField.HA_PRIORITY] = "Choose restart, best effort or do not restart."
        return out
    }
    fun bootOrderProblem(order: String): String? = when {
        order.isEmpty() -> "Enter at least one device."
        order.any { it !in "cdn" } -> "Use c (disk), d (CD drive) and n (network)."
        order.toSet().size != order.length -> "List each device once."
        else -> null
    }
    fun bootOrderText(order: String): String = order.map { when (it) { 'c' -> "Disk"; 'd' -> "CD drive"; 'n' -> "Network"; else -> "?" } }.joinToString(", then ")

    /**
     * The setter calls for a valid draft, in sending order, with the values read just before. Unchanged settings and
     * settings unavailable in this state produce no step, so a step's requested value always differs from its before
     * value: a write that would change nothing is never offered (its read-back could not prove anything).
     */
    fun plan(s: VmSettings, d: SettingsDraft): List<SettingStep> {
        require(problems(s, d).isEmpty()) { "Fix the highlighted settings first." }
        val steps = mutableListOf<SettingStep>()
        fun add(kind: SettingKind, requested: String, argument: String? = null) {
            val before = requireNotNull(value(kind, argument, s)) { "The current ${kind.label.lowercase()} is not known. Refresh and try again." }
            if (before != requested) steps += SettingStep(kind, argument, before, requested)
        }
        if (unavailableReason(s, SettingGroup.GENERAL) == null) {
            add(SettingKind.NAME, d.name)
            add(SettingKind.DESCRIPTION, d.description)
            (s.tags - d.tags.toSet()).sorted().forEach { add(SettingKind.TAG_REMOVE, "absent", it) }
            (d.tags - s.tags.toSet()).forEach { add(SettingKind.TAG_ADD, "present", it) }
        }
        if (unavailableReason(s, SettingGroup.VCPUS) == null) {
            if (s.power == "Halted") {
                val max = d.vcpusMax.trim().toLong(); val start = d.vcpusAtStartup.trim().toLong()
                // Keep 0 < at_startup <= max true after every call: lower start-up first when lowering the maximum.
                if (max < s.vcpusMax) { add(SettingKind.VCPUS_AT_STARTUP, start.toString()); add(SettingKind.VCPUS_MAX, max.toString()) }
                else { add(SettingKind.VCPUS_MAX, max.toString()); add(SettingKind.VCPUS_AT_STARTUP, start.toString()) }
            } else if (s.power == "Running") add(SettingKind.VCPUS_LIVE, d.vcpusLive.trim().toLong().toString())
        }
        if (unavailableReason(s, SettingGroup.MEMORY) == null) {
            val dmin = mibToBytes(d.memoryDynamicMin)!!; val dmax = mibToBytes(d.memoryDynamicMax)!!
            if (s.power == "Halted") add(SettingKind.MEMORY_LIMITS, limits(mibToBytes(d.memoryStaticMin)!!, mibToBytes(d.memoryStaticMax)!!, dmin, dmax))
            else add(SettingKind.MEMORY_DYNAMIC, limits(dmin, dmax))
        }
        if (d.autoPowerOn != s.autoPowerOnEnabled) {
            if (!d.autoPowerOn) add(SettingKind.AUTO_POWERON_REMOVE, ABSENT)
            else {
                // add_to_other_config refuses an existing key (MAP_DUPLICATE_KEY), so a present non-"true" value is removed first.
                if (s.autoPowerOn != null) steps += SettingStep(SettingKind.AUTO_POWERON_REMOVE, null, "=${s.autoPowerOn}", ABSENT)
                steps += SettingStep(SettingKind.AUTO_POWERON_ADD, null, ABSENT, "=true")
            }
        }
        if (unavailableReason(s, SettingGroup.BOOT_ORDER) == null && d.bootOrder != s.bootParams["order"].orEmpty())
            add(SettingKind.BOOT_PARAMS, map(s.bootParams + ("order" to d.bootOrder)))
        if (unavailableReason(s, SettingGroup.HA_PRIORITY) == null) add(SettingKind.HA_PRIORITY, d.haPriority)
        return steps
    }

    /** What the editor lists before sending: the change lines, or why there are none. */
    fun preview(s: VmSettings, d: SettingsDraft): Pair<List<String>, String?> {
        if (problems(s, d).isNotEmpty()) return emptyList<String>() to "Fix the highlighted settings first."
        val steps = runCatching { plan(s, d) }.getOrElse { return emptyList<String>() to (it.message ?: "Refresh and try again.") }
        return if (steps.isEmpty()) emptyList<String>() to "No changes yet." else steps.map(::change) to null
    }

    /** A memory or vCPU reduction on a running VM can disrupt the guest, so it needs the VM's name typed. */
    fun needsTypedName(s: VmSettings, steps: List<SettingStep>): Boolean = s.power == "Running" && steps.any { step ->
        when (step.kind) {
            SettingKind.VCPUS_LIVE -> step.requested.toLong() < (step.before.toLongOrNull() ?: Long.MAX_VALUE)
            SettingKind.MEMORY_DYNAMIC -> {
                val (oldMin, oldMax) = step.before.split(",").map { it.toLong() }
                val (newMin, newMax) = step.requested.split(",").map { it.toLong() }
                newMin < oldMin || newMax < oldMax
            }
            else -> false
        }
    }
    fun confirmationPhrase(s: VmSettings): String = s.name.ifBlank { s.uuid }

    /** Checks made again against the fresh read just before anything is journaled. */
    fun confirm(profile: Profile, confirmation: SettingsConfirmation, current: VmSettings, now: Long) {
        val original = confirmation.settings
        require(profile.id == confirmation.profileId && profile.origin() == confirmation.origin) { "Connection changed. Review the changes again." }
        require(current.authority == original.authority && current.uuid == original.uuid) { "VM identity changed." }
        require(now - original.observedAt in 0..CONFIRMATION_MILLIS) { "Confirmation expired. Review the changes again." }
        require(current.fence == original.fence) { "The VM changed. Review the changes again." }
        require(confirmation.steps.isNotEmpty()) { "Nothing to change." }
        require(plan(current, confirmation.draft) == confirmation.steps) { "The VM changed. Review the changes again." }
        require(!needsTypedName(current, confirmation.steps) || confirmation.typedName == confirmationPhrase(current)) {
            "The typed VM name does not match. Review the changes again."
        }
    }

    // Text.

    fun size(bytes: Long): String = when {
        bytes % (1L shl 30) == 0L -> "${bytes shr 30} GiB"
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1L shl 30).toDouble())
        bytes % MIB == 0L -> "${bytes / MIB} MiB"
        else -> "$bytes B"
    }
    /** A canonical value as the person reads it. */
    fun display(kind: SettingKind, value: String?): String {
        if (value == null) return "not known"
        return when (kind) {
            SettingKind.NAME -> "“$value”"
            SettingKind.DESCRIPTION -> if (value.isEmpty()) "none" else "“${value.take(60)}${if (value.length > 60) "…" else ""}”"
            SettingKind.TAG_ADD, SettingKind.TAG_REMOVE -> value
            SettingKind.MEMORY_LIMITS -> value.split(",").mapNotNull { it.toLongOrNull() }.let { v ->
                if (v.size != 4) value else if (v[0] == v[1] && v[2] == v[3] && v[1] == v[3]) size(v[3])
                else "${size(v[2])}–${size(v[3])} (static ${size(v[0])}–${size(v[1])})"
            }
            SettingKind.MEMORY_DYNAMIC -> value.split(",").mapNotNull { it.toLongOrNull() }.let { v ->
                if (v.size != 2) value else if (v[0] == v[1]) size(v[0]) else "${size(v[0])}–${size(v[1])}"
            }
            SettingKind.AUTO_POWERON_ADD, SettingKind.AUTO_POWERON_REMOVE -> when (value) { "=true" -> "on"; ABSENT -> "off"; else -> "off (${value.removePrefix("=").take(20)})" }
            SettingKind.BOOT_PARAMS -> parseMap(value)["order"]?.let { bootOrderText(it) } ?: "not set"
            SettingKind.HA_PRIORITY -> haLabel(value)
            else -> value
        }
    }
    fun haLabel(value: String) = when (value) { "restart" -> "restart"; "best-effort" -> "best effort"; "" -> "do not restart"; else -> value.take(20) }

    /** "Memory: 1 GiB → 2 GiB", "Tag added: web". */
    fun change(step: SettingStep): String = when (step.kind) {
        SettingKind.TAG_ADD -> "Tag added: ${step.argument}"
        SettingKind.TAG_REMOVE -> "Tag removed: ${step.argument}"
        SettingKind.DESCRIPTION -> excerpts(step.before, step.requested).let { (before, after) ->
            "${step.kind.label}: ${quoted(before)} → ${quoted(after)}"
        }
        else -> "${step.kind.label}: ${display(step.kind, step.before)} → ${display(step.kind, step.requested)}"
    }

    /** A text longer than this is shown as an excerpt. */
    const val EXCERPT_LENGTH = 60
    /** Characters kept on each side of the first difference. */
    const val EXCERPT_CONTEXT = 20
    private fun quoted(text: String) = if (text.isEmpty()) "none" else "“$text”"

    /**
     * Both sides of a long text change as excerpts that show the part that changed (D4 of the first phone run). Two short
     * texts are returned whole. Otherwise both excerpts start the same distance before the first difference, so they line
     * up, and end shortly after the last one; a cut end is marked with "…". The two excerpts of different texts always differ.
     */
    fun excerpts(before: String, after: String): Pair<String, String> {
        if (before.length <= EXCERPT_LENGTH && after.length <= EXCERPT_LENGTH) return before to after
        val limit = minOf(before.length, after.length)
        var prefix = 0
        while (prefix < limit && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
        fun cut(text: String, start: Int): String {
            val end = minOf(text.length, text.length - suffix + EXCERPT_CONTEXT, start + EXCERPT_LENGTH)
            return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
        }
        var start = (prefix - EXCERPT_CONTEXT).coerceAtLeast(0)
        // Never begin in the middle of a surrogate pair.
        if (start > 0 && Character.isLowSurrogate(before[start]) && Character.isHighSurrogate(before[start - 1])) start--
        if (start > 0 && Character.isLowSurrogate(after[start]) && Character.isHighSurrogate(after[start - 1])) start--
        return cut(before, start) to cut(after, start)
    }

    /** The whole before and after of a step whose lines above show an excerpt, so they can be read in full before confirming; null when nothing was cut. */
    fun fullValues(step: SettingStep): Pair<String, String>? =
        if (step.kind == SettingKind.DESCRIPTION && (step.before.length > EXCERPT_LENGTH || step.requested.length > EXCERPT_LENGTH)) step.before to step.requested else null
    /** What a record did, step by step, for the card, the history and the review. */
    fun summary(steps: List<SettingStep>): String {
        fun names(vararg states: SettingState) = steps.filter { it.state in states }.joinToString("; ") { change(it) }
        val rejected = steps.filter { it.state == SettingState.REJECTED }.joinToString("; ") { "${change(it)}${it.code?.let { c -> " ($c)" }.orEmpty()}" }
        val overridden = steps.filter { it.state == SettingState.OVERRIDDEN }.joinToString("; ") { "${it.kind.label} now reads ${display(it.kind, it.observed)}" }
        val uncertain = steps.filter { it.state == SettingState.SUBMITTING || it.state == SettingState.UNKNOWN }
            .joinToString("; ") { "${change(it)}${it.observed?.let { o -> ", now reads ${display(it.kind, o)}" }.orEmpty()}" }
        return listOf("Saved" to names(SettingState.DONE), "Refused by the server" to rejected, "Not applied" to names(SettingState.NOT_APPLIED),
            "Changed by someone else" to overridden, "Uncertain" to uncertain, "Not sent" to names(SettingState.PENDING))
            .filter { it.second.isNotBlank() }.joinToString(". ") { "${it.first}: ${it.second}" }.ifBlank { "No changes." } + "."
    }

    /**
     * Check status for one uncertain step, from a fresh read made at [now]. The requested value proves it applied (its
     * before value differed, or the step would never have been planned). The old value proves "not applied" only once
     * the call can no longer be running: at least [QUIET_MILLIS] after the journaled send time and with no VM operation
     * in progress. A third value, or any doubt, stays UNKNOWN for manual review.
     */
    fun reconcile(step: SettingStep, observed: String?, busy: Boolean, now: Long): Pair<SettingState, String> {
        val sentAt = step.sentAt
        val quietFrom = sentAt?.plus(QUIET_MILLIS)
        return when {
            step.before == step.requested -> SettingState.UNKNOWN to "This step could never be proven by a read-back. Review this record."
            observed == step.requested -> SettingState.DONE to "Verified by reading it back."
            observed == step.before && sentAt != null && quietFrom != null && now >= quietFrom && !busy ->
                SettingState.NOT_APPLIED to "It still reads the old value well after it was sent, so it was not applied."
            observed == step.before -> SettingState.UNKNOWN to SettingsText.tooEarly(quietFrom, busy)
            observed == null -> SettingState.UNKNOWN to "The value could not be read. Check status again."
            else -> SettingState.UNKNOWN to "It now reads ${display(step.kind, observed)}, neither the old nor the requested value. Review this record."
        }
    }

    fun withSetting(op: PowerOperation, index: Int, change: (SettingStep) -> SettingStep): PowerOperation =
        op.copy(settings = requireNotNull(op.settings).toMutableList().also { it[index] = change(it[index]) })
}

/** Wording for the card, the review and a manual closure of a settings record. */
object SettingsText {
    fun tooEarly(quietFrom: Long?, busy: Boolean): String = when {
        busy -> "It still reads the old value, but a VM operation is running. Check status again later."
        quietFrom == null -> "It still reads the old value, but the send time is not recorded. Review this record."
        else -> "It still reads the old value. Meteor can treat that as not applied from ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(quietFrom))} " +
            "(${SettingsRules.QUIET_MILLIS / 60_000} minutes after sending). Check status again then."
    }
    /** The uncertain steps with their current values, e.g. "Memory: 2 GiB (requested 2 GiB, was 1 GiB)". */
    fun reviewState(operation: PowerOperation, current: VmSettings): String = operation.settings.orEmpty()
        .filter { it.state == SettingState.SUBMITTING || it.state == SettingState.UNKNOWN }
        .joinToString("; ") { step ->
            "${step.kind.label}: ${SettingsRules.display(step.kind, SettingsRules.value(step.kind, step.argument, current))} " +
                "(requested ${SettingsRules.display(step.kind, step.requested)}, was ${SettingsRules.display(step.kind, step.before)})"
        }.ifBlank { current.power }
    fun closedAfterReview(operation: PowerOperation, current: VmSettings): String =
        "Closed after manual review. Now: ${reviewState(operation, current)}. ${SettingsRules.summary(operation.settings.orEmpty())} " +
            "The outcome of the uncertain change remains unverified. Nothing was resent."
    fun reviewExplanation(): List<String> = listOf(
        "A settings change has no server task, so this read does not prove what happened to the uncertain change. Compare the current values with what was requested.",
        "Closing records the outcome as unknown, not as success. Nothing is resent; edit the VM again if a value is still wrong.")
}
