package com.meteor.app.data

/**
 * A2 operations: journal record format, independent of Android's JSON classes so it runs on the JVM.
 *
 * Version 1 is unchanged. Records of the 1.9 kinds (START, SHUTDOWN, REBOOT) keep exactly the keys
 * 1.9 wrote and are read with the same strictness: a malformed known record fails the whole load,
 * which preserves the file and locks power controls. New kinds add only the optional keys
 * `targetHost` and `targetHostName`, written when present.
 *
 * A record whose `action` or `phase` this version does not know is "foreign": it is kept with its
 * raw fields, never observed, verified or resent, and written back unchanged. A foreign record is
 * unresolved (blocking new actions) unless it was stored as SUCCEEDED or FAILED or carries a manual
 * resolution. The only change this version can make to one is a manual-review closure.
 *
 * B2 storage/host: a record whose `action` names a [TargetAction] also carries `targetKind` and
 * `target`, plus optional `targetName`, `argument`, `argumentName`, `marker` and `steps`. Those
 * names are never PowerAction names, so an A2-era reader keeps such a record as foreign (locked,
 * never observed or resent). This version reads them strictly, except that a record lacking the
 * B2 identity keys, naming an unknown target kind or sub-step, or carrying an unknown phase was
 * not written by this version and is kept as foreign too.
 */
object JournalCodec {
    const val FOREIGN_MESSAGE = "Recorded by a different Meteor version. Review it manually; Meteor will not check or resend it."
    private val knownActions = PowerAction.entries.filter { it.requestable }.associateBy { it.name }
    private val knownPhases = PowerPhase.entries.associateBy { it.name }

    fun decode(fields: Map<String, Any?>): PowerOperation {
        // --- B2 storage/host ---
        targetActions[fields["action"] as? String]?.let { return decodeTarget(fields, it) }
        // --- end B2 storage/host ---
        val action = knownActions[fields["action"] as? String]
        val phase = knownPhases[fields["phase"] as? String]
        if (action != null && phase != null) return PowerOperation(
            text(fields, "id"), text(fields, "profile"), text(fields, "origin"), text(fields, "authority"),
            text(fields, "vm"), text(fields, "name"), action, optional(fields, "beforeBoot"), phase,
            number(fields, "createdAt"), number(fields, "updatedAt"), optional(fields, "taskRef"), optional(fields, "taskUuid"),
            text(fields, "message"), fields["manualResolution"]?.let(::resolution),
            targetHostUuid = optional(fields, "targetHost"), targetHostName = optional(fields, "targetHostName"))
        return foreign(fields, action, phase)
    }

    private fun foreign(fields: Map<String, Any?>, action: PowerAction?, phase: PowerPhase?): PowerOperation {
        val id = (fields["id"] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Journal record has no identity.")
        // Only a terminal outcome is kept. Any other phase, known or not, becomes an uncertain outcome.
        val storedPhase = phase?.takeIf { it == PowerPhase.SUCCEEDED || it == PowerPhase.FAILED } ?: PowerPhase.UNKNOWN
        val review = if (phase == null || phase == PowerPhase.UNKNOWN) fields["manualResolution"]?.let { raw ->
            runCatching { resolution(raw) }.getOrNull()?.takeIf { it.reviewedAt > 0 && it.origin.matches(Regex("[0-9a-f]{64}")) }
        } else null
        val closed = review != null || storedPhase == PowerPhase.SUCCEEDED || storedPhase == PowerPhase.FAILED
        return PowerOperation(id, loose(fields, "profile"), loose(fields, "origin"), loose(fields, "authority"),
            loose(fields, "vm"), loose(fields, "name").ifBlank { "Unknown object" }, action ?: PowerAction.UNRECOGNIZED,
            optional(fields, "beforeBoot"), storedPhase, looseNumber(fields, "createdAt"), looseNumber(fields, "updatedAt"),
            loose(fields, "taskRef").ifBlank { null }, loose(fields, "taskUuid").ifBlank { null },
            if (closed) loose(fields, "message") else FOREIGN_MESSAGE, review,
            targetHostUuid = loose(fields, "targetHost").ifBlank { null }, targetHostName = loose(fields, "targetHostName").ifBlank { null },
            foreign = fields)
    }

    fun encode(operation: PowerOperation): Map<String, Any?> {
        operation.foreign?.let { raw ->
            val review = operation.manualResolution
            if (review == null || raw.containsKey("manualResolution")) return raw
            return raw + mapOf("phase" to operation.phase.name, "updatedAt" to operation.updatedAt,
                "message" to operation.message, "manualResolution" to resolutionFields(review))
        }
        val item = linkedMapOf<String, Any?>("id" to operation.id, "profile" to operation.profileId, "origin" to operation.origin,
            "authority" to operation.authority, "vm" to operation.vmUuid, "name" to operation.vmName, "action" to operation.action.name,
            "beforeBoot" to operation.beforeBoot.orEmpty(), "phase" to operation.phase.name, "createdAt" to operation.createdAt,
            "updatedAt" to operation.updatedAt, "taskRef" to operation.taskRef.orEmpty(), "taskUuid" to operation.taskUuid.orEmpty(),
            "message" to operation.message)
        operation.targetHostUuid?.let { item["targetHost"] = it }
        operation.targetHostName?.let { item["targetHostName"] = it }
        // --- B2 storage/host --- the `action` key holds the B2 kind; every other key is optional.
        operation.targetAction?.let { kind ->
            item["action"] = kind.name; item["targetKind"] = kind.targetKind.name; item["target"] = operation.targetUuid.orEmpty()
            operation.targetName?.let { item["targetName"] = it }
            operation.argumentUuid?.let { item["argument"] = it }
            operation.argumentName?.let { item["argumentName"] = it }
            operation.marker?.let { item["marker"] = LinkedHashMap(it) }
            operation.steps?.let { steps -> item["steps"] = steps.map(::stepFields) }
        }
        // --- end B2 storage/host ---
        operation.manualResolution?.let { item["manualResolution"] = resolutionFields(it) }
        return item
    }

    // --- B2 storage/host ---
    private val targetActions = TargetAction.entries.associateBy { it.name }
    private fun decodeTarget(fields: Map<String, Any?>, action: TargetAction): PowerOperation {
        val phase = knownPhases[fields["phase"] as? String]
        val kind = TargetKind.entries.firstOrNull { it.name == fields["targetKind"] }
        val target = (fields["target"] as? String)?.takeIf { it.isNotBlank() }
        val rawSteps = fields["steps"]
        val stepsKnown = rawSteps == null || (rawSteps as? List<*>)?.all { raw ->
            val step = raw as? Map<*, *>
            step != null && StepKind.entries.any { it.name == step["kind"] } && StepState.entries.any { it.name == step["state"] }
        } == true
        if (phase == null || kind != action.targetKind || target == null || !stepsKnown) return foreign(fields, null, phase)
        return PowerOperation(
            text(fields, "id"), text(fields, "profile"), text(fields, "origin"), text(fields, "authority"),
            text(fields, "vm"), text(fields, "name"), PowerAction.TARGET, optional(fields, "beforeBoot"), phase,
            number(fields, "createdAt"), number(fields, "updatedAt"), optional(fields, "taskRef"), optional(fields, "taskUuid"),
            text(fields, "message"), fields["manualResolution"]?.let(::resolution),
            targetAction = action, targetUuid = target, targetName = optional(fields, "targetName"),
            argumentUuid = optional(fields, "argument"), argumentName = optional(fields, "argumentName"),
            marker = fields["marker"]?.let(::marker), steps = (rawSteps as? List<*>)?.map(::step))
    }
    private fun marker(value: Any?): Map<String, String> {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid journal marker.")
        return map.entries.associate { (key, item) -> key.toString() to (item as? String ?: throw IllegalArgumentException("Invalid journal marker.")) }
    }
    private fun step(value: Any?): OperationStep {
        val fields = (value as Map<*, *>).entries.associate { it.key.toString() to it.value }
        return OperationStep(StepKind.valueOf(text(fields, "kind")), text(fields, "uuid").also { require(it.isNotBlank()) { "Journal step has no target." } },
            text(fields, "name"), StepState.valueOf(text(fields, "state")), optional(fields, "taskRef"), optional(fields, "taskUuid"), optional(fields, "code"))
    }
    private fun stepFields(step: OperationStep) = linkedMapOf<String, Any?>("kind" to step.kind.name, "uuid" to step.uuid, "name" to step.name,
        "state" to step.state.name, "taskRef" to step.taskRef.orEmpty(), "taskUuid" to step.taskUuid.orEmpty(), "code" to step.code.orEmpty())
    // --- end B2 storage/host ---

    private fun resolutionFields(review: ManualResolution) = linkedMapOf<String, Any?>("reviewedAt" to review.reviewedAt,
        "origin" to review.origin, "power" to review.power, "bootTime" to review.bootTime.orEmpty())
    private fun resolution(value: Any?): ManualResolution {
        val fields = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: throw IllegalArgumentException("Invalid manual resolution.")
        return ManualResolution(number(fields, "reviewedAt"), text(fields, "origin"), text(fields, "power"), optional(fields, "bootTime"))
    }
    // Same coercions as the 1.9 org.json reader: required strings and numbers, optional blank-as-null strings.
    private fun text(fields: Map<String, Any?>, key: String): String = when (val value = fields[key]) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> throw IllegalArgumentException("Journal field $key is missing.")
    }
    private fun number(fields: Map<String, Any?>, key: String): Long = when (val value = fields[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong() ?: throw IllegalArgumentException("Journal field $key is invalid.")
        else -> throw IllegalArgumentException("Journal field $key is missing.")
    }
    private fun optional(fields: Map<String, Any?>, key: String): String? = loose(fields, key).takeIf { it.isNotBlank() }
    private fun loose(fields: Map<String, Any?>, key: String): String = when (val value = fields[key]) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> ""
    }
    private fun looseNumber(fields: Map<String, Any?>, key: String): Long = runCatching { number(fields, key) }.getOrDefault(0L)
}
