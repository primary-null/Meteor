package com.meteor.app.data

import com.meteor.app.data.VmSettingsFixtures.GIB
import com.meteor.app.data.VmSettingsFixtures.MIB
import com.meteor.app.data.VmSettingsFixtures.NOW
import com.meteor.app.data.VmSettingsFixtures.draft
import com.meteor.app.data.VmSettingsFixtures.halted
import com.meteor.app.data.VmSettingsFixtures.profile
import com.meteor.app.data.VmSettingsFixtures.running
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** VM settings: validation, gating, the planned setter calls, the confirmation scale, error classes and wording. */
class VmSettingsRulesTest {
    // --- validation, as the person types ---

    @Test fun nameAndDescriptionProblemsAreSpecific() {
        val s = running()
        assertEquals("Enter a name.", SettingsRules.problems(s, draft(s).copy(name = "  "))[SettingField.NAME])
        assertEquals("Use at most 255 characters.", SettingsRules.problems(s, draft(s).copy(name = "x".repeat(256)))[SettingField.NAME])
        assertEquals("Remove line breaks and control characters.", SettingsRules.problems(s, draft(s).copy(name = "a\nb"))[SettingField.NAME])
        assertEquals("Use at most 1000 characters.", SettingsRules.problems(s, draft(s).copy(description = "x".repeat(1001)))[SettingField.DESCRIPTION])
        assertTrue(SettingsRules.problems(s, draft(s).copy(description = "")).isEmpty())
    }

    @Test fun tagsAreCheckedOneByOne() {
        val tags = listOf("web")
        assertEquals("Enter a tag.", SettingsRules.tagProblem(" ", tags))
        assertEquals("Remove spaces at the start or end.", SettingsRules.tagProblem(" db", tags))
        assertEquals("This tag is already set.", SettingsRules.tagProblem("web", tags))
        assertEquals("Use at most 64 characters.", SettingsRules.tagProblem("x".repeat(65), tags))
        assertEquals("Remove line breaks and control characters.", SettingsRules.tagProblem("a\tb", tags))
        assertNull(SettingsRules.tagProblem("db", tags))
        val s = running()
        assertEquals("A tag is listed twice.", SettingsRules.problems(s, draft(s).copy(tags = listOf("a", "a")))[SettingField.TAGS])
    }

    @Test fun haltedVcpusNeedStartUpWithinTheMaximum() {
        val s = halted()
        assertEquals("Start-up vCPUs cannot exceed the maximum (2).", SettingsRules.problems(s, draft(s).copy(vcpusMax = "2", vcpusAtStartup = "3"))[SettingField.VCPUS_AT_STARTUP])
        assertEquals("Use at least 1.", SettingsRules.problems(s, draft(s).copy(vcpusMax = "0"))[SettingField.VCPUS_MAX])
        assertEquals("Use a whole number.", SettingsRules.problems(s, draft(s).copy(vcpusAtStartup = "two"))[SettingField.VCPUS_AT_STARTUP])
        assertEquals("Enter the maximum.", SettingsRules.problems(s, draft(s).copy(vcpusMax = ""))[SettingField.VCPUS_MAX])
        assertEquals("Use at most 256.", SettingsRules.problems(s, draft(s).copy(vcpusMax = "999", vcpusAtStartup = "1"))[SettingField.VCPUS_MAX])
    }

    @Test fun runningVcpusStayWithinTheCurrentMaximum() {
        val s = running()
        assertEquals("Use at most 4, the maximum. Shut the VM down to raise the maximum.",
            SettingsRules.problems(s, draft(s).copy(vcpusLive = "5"))[SettingField.VCPUS_LIVE])
        assertTrue(SettingsRules.problems(s, draft(s).copy(vcpusLive = "4")).isEmpty())
        // A running VM never validates the halted-only fields, whatever the draft holds.
        assertTrue(SettingsRules.problems(s, draft(s).copy(vcpusMax = "x", memoryStaticMin = "x")).isEmpty())
    }

    @Test fun memoryOrderIsValidatedBeforeSending() {
        val s = halted()
        fun problem(d: SettingsDraft) = SettingsRules.problems(s, d)
        assertEquals("Static minimum cannot exceed the static maximum (1 GiB).", problem(draft(s).copy(memoryStaticMin = "2048", memoryStaticMax = "1024"))[SettingField.MEMORY_STATIC_MIN])
        assertEquals("Dynamic minimum must be at least the static minimum (512 MiB).", problem(draft(s).copy(memoryDynamicMin = "256"))[SettingField.MEMORY_DYNAMIC_MIN])
        assertEquals("Dynamic maximum must be at least the dynamic minimum (1 GiB).", problem(draft(s).copy(memoryDynamicMax = "768"))[SettingField.MEMORY_DYNAMIC_MAX])
        assertEquals("Dynamic maximum cannot exceed the static maximum (2 GiB).", problem(draft(s).copy(memoryDynamicMax = "4096"))[SettingField.MEMORY_DYNAMIC_MAX])
        assertEquals("Use a whole number of MiB.", problem(draft(s).copy(memoryDynamicMax = "1.5"))[SettingField.MEMORY_DYNAMIC_MAX])
        assertEquals("Enter a size in MiB.", problem(draft(s).copy(memoryStaticMax = ""))[SettingField.MEMORY_STATIC_MAX])
        // Running: the static range is fixed and the dynamic range is checked against it.
        val r = running()
        assertEquals("Dynamic maximum cannot exceed the static maximum (2 GiB).",
            SettingsRules.problems(r, draft(r).copy(memoryDynamicMax = "3072"))[SettingField.MEMORY_DYNAMIC_MAX])
    }

    @Test fun bootOrderAndHaChoicesAreValidated() {
        assertEquals("Enter at least one device.", SettingsRules.bootOrderProblem(""))
        assertEquals("Use c (disk), d (CD drive) and n (network).", SettingsRules.bootOrderProblem("cx"))
        assertEquals("List each device once.", SettingsRules.bootOrderProblem("cc"))
        assertNull(SettingsRules.bootOrderProblem("dcn"))
        assertEquals("CD drive, then Disk, then Network", SettingsRules.bootOrderText("dcn"))
        val ha = running().copy(haEnabled = true)
        assertEquals("Choose restart, best effort or do not restart.", SettingsRules.problems(ha, draft(ha).copy(haPriority = "always"))[SettingField.HA_PRIORITY])
    }

    // --- gating on power state and allowed_operations, with plain reasons ---

    @Test fun eachGroupIsGatedWithAPlainReason() {
        val r = running(); val h = halted()
        assertNull(SettingsRules.unavailableReason(r, SettingGroup.VCPUS)); assertNull(SettingsRules.unavailableReason(h, SettingGroup.VCPUS))
        assertNull(SettingsRules.unavailableReason(r, SettingGroup.MEMORY)); assertNull(SettingsRules.unavailableReason(h, SettingGroup.MEMORY))
        assertEquals("The server does not allow changing vCPUs while this VM runs. Shut it down to change them.",
            SettingsRules.unavailableReason(r.copy(allowed = emptySet()), SettingGroup.VCPUS))
        assertEquals("The server does not allow changing memory for this VM now.", SettingsRules.unavailableReason(h.copy(allowed = setOf("start")), SettingGroup.MEMORY))
        assertEquals("Change vCPUs while the VM is halted or running.", SettingsRules.unavailableReason(r.copy(power = "Suspended"), SettingGroup.VCPUS))
        assertEquals("Change memory while the VM is halted or running.", SettingsRules.unavailableReason(r.copy(power = "Paused"), SettingGroup.MEMORY))
        assertEquals("Another VM operation is active. Try again when it finishes.", SettingsRules.unavailableReason(r.copy(busy = true), SettingGroup.GENERAL))
        assertEquals("Boot order applies to HVM guests only.", SettingsRules.unavailableReason(r.copy(hvm = false), SettingGroup.BOOT_ORDER))
        assertEquals("Boot order applies to BIOS guests. A UEFI guest keeps its boot order in its firmware.",
            SettingsRules.unavailableReason(r.copy(firmware = "UEFI"), SettingGroup.BOOT_ORDER))
        assertEquals("Not applicable: high availability is off for this pool.", SettingsRules.unavailableReason(r, SettingGroup.HA_PRIORITY))
        assertNull(SettingsRules.unavailableReason(r.copy(haEnabled = true), SettingGroup.HA_PRIORITY))
        assertNull(SettingsRules.unavailableReason(r, SettingGroup.AUTO_POWERON))
    }

    @Test fun unavailableGroupsNeverProduceASetter() {
        val r = running().copy(allowed = emptySet())
        assertEquals(emptyList<SettingStep>(), SettingsRules.plan(r, draft(r).copy(vcpusLive = "3", memoryDynamicMax = "1536", haPriority = "restart")))
        val uefi = running().copy(firmware = "UEFI")
        assertEquals(emptyList<SettingStep>(), SettingsRules.plan(uefi, draft(uefi).copy(bootOrder = "dc")))
    }

    // --- the plan: one setter per change, in a safe order, never a no-op ---

    @Test fun anUnchangedDraftPlansNothing() {
        for (s in listOf(running(), halted())) assertEquals(emptyList<SettingStep>(), SettingsRules.plan(s, draft(s)))
        assertEquals(emptyList<String>() to "No changes yet.", SettingsRules.preview(running(), draft(running())))
        assertEquals(emptyList<String>() to "Fix the highlighted settings first.", SettingsRules.preview(running(), draft(running()).copy(name = "")))
    }

    @Test fun generalChangesRecordTheirBeforeAndRequestedValues() {
        val s = running()
        val steps = SettingsRules.plan(s, draft(s).copy(name = "Web", description = "Front end", tags = listOf("prod")))
        assertEquals(listOf(SettingStep(SettingKind.NAME, null, "Guest", "Web"), SettingStep(SettingKind.DESCRIPTION, null, "", "Front end"),
            SettingStep(SettingKind.TAG_REMOVE, "web", "present", "absent"), SettingStep(SettingKind.TAG_ADD, "prod", "absent", "present")), steps)
        assertTrue(steps.all { it.before != it.requested })
        assertEquals(listOf("Name: “Guest” → “Web”", "Description: none → “Front end”", "Tag removed: web", "Tag added: prod"), steps.map(SettingsRules::change))
    }

    @Test fun haltedVcpuOrderKeepsStartUpWithinTheMaximumAfterEveryCall() {
        val s = halted() // max 4, start-up 2
        assertEquals(listOf(SettingKind.VCPUS_MAX, SettingKind.VCPUS_AT_STARTUP),
            SettingsRules.plan(s, draft(s).copy(vcpusMax = "8", vcpusAtStartup = "6")).map { it.kind })
        assertEquals(listOf(SettingKind.VCPUS_AT_STARTUP, SettingKind.VCPUS_MAX),
            SettingsRules.plan(s, draft(s).copy(vcpusMax = "1", vcpusAtStartup = "1")).map { it.kind })
        // Lowering the maximum while raising start-up within it still sends start-up first.
        val steps = SettingsRules.plan(s.copy(vcpusAtStartup = 1), draft(s.copy(vcpusAtStartup = 1)).copy(vcpusMax = "3", vcpusAtStartup = "3"))
        assertEquals(listOf(SettingKind.VCPUS_AT_STARTUP, SettingKind.VCPUS_MAX), steps.map { it.kind })
        // Simulate each call: 0 < at_startup <= max holds after every one.
        var max = 4L; var start = 1L
        for (step in steps) { if (step.kind == SettingKind.VCPUS_MAX) max = step.requested.toLong() else start = step.requested.toLong(); assertTrue(start in 1..max) }
    }

    @Test fun memoryIsOneCallInEitherState() {
        val h = halted()
        assertEquals(listOf(SettingStep(SettingKind.MEMORY_LIMITS, null, "${512 * MIB},${2 * GIB},$GIB,$GIB", "${512 * MIB},${2 * GIB},$GIB,${2 * GIB}")),
            SettingsRules.plan(h, draft(h).copy(memoryDynamicMax = "2048")))
        val r = running()
        val step = SettingsRules.plan(r, draft(r).copy(memoryDynamicMin = "768")).single()
        assertEquals(SettingStep(SettingKind.MEMORY_DYNAMIC, null, "$GIB,$GIB", "${768 * MIB},$GIB"), step)
        assertEquals("Memory: 1 GiB → 768 MiB–1 GiB", SettingsRules.change(step))
        assertEquals(listOf(step.requested.split(",")[0], step.requested.split(",")[1]), SettingsRules.xapiArguments(step, "OpaqueRef:vm").drop(1))
    }

    @Test fun autoPowerOnUsesTheOtherConfigKeyAndRemovesAForeignValueFirst() {
        val s = running()
        assertEquals(listOf(SettingStep(SettingKind.AUTO_POWERON_ADD, null, "-", "=true")), SettingsRules.plan(s, draft(s).copy(autoPowerOn = true)))
        val on = s.copy(autoPowerOn = "true")
        assertEquals(listOf(SettingStep(SettingKind.AUTO_POWERON_REMOVE, null, "=true", "-")), SettingsRules.plan(on, draft(on).copy(autoPowerOn = false)))
        // add_to_other_config refuses an existing key (MAP_DUPLICATE_KEY on the lab), so "false" is removed before "true" is added.
        val odd = s.copy(autoPowerOn = "false")
        assertEquals(listOf(SettingKind.AUTO_POWERON_REMOVE, SettingKind.AUTO_POWERON_ADD), SettingsRules.plan(odd, draft(odd).copy(autoPowerOn = true)).map { it.kind })
        assertEquals(listOf("OpaqueRef:vm", "auto_poweron", "true"), SettingsRules.xapiArguments(SettingStep(SettingKind.AUTO_POWERON_ADD, null, "-", "=true"), "OpaqueRef:vm"))
        assertEquals("Start with the host: off → on", SettingsRules.change(SettingStep(SettingKind.AUTO_POWERON_ADD, null, "-", "=true")))
    }

    @Test fun bootOrderKeepsTheOtherBootParameters() {
        val s = running().copy(bootParams = mapOf("order" to "cd", "firmware" to "bios"))
        val step = SettingsRules.plan(s, draft(s).copy(bootOrder = "dc")).single()
        assertEquals(mapOf("firmware" to "bios", "order" to "dc"), SettingsRules.xapiArguments(step, "OpaqueRef:vm")[1])
        assertEquals("Boot order: Disk, then CD drive → CD drive, then Disk", SettingsRules.change(step))
        assertEquals(mapOf("a&b" to "c=d%", "" to ""), SettingsRules.parseMap(SettingsRules.map(mapOf("a&b" to "c=d%", "" to ""))))
    }

    @Test fun haPriorityIsPlannedOnlyWithPoolHa() {
        val off = running()
        assertEquals(emptyList<SettingStep>(), SettingsRules.plan(off, draft(off).copy(haPriority = "restart")))
        val on = running().copy(haEnabled = true)
        assertEquals(listOf(SettingStep(SettingKind.HA_PRIORITY, null, "", "restart")), SettingsRules.plan(on, draft(on).copy(haPriority = "restart")))
    }

    // --- confirmation scale ---

    @Test fun onlyAReductionOnARunningVmNeedsTheTypedName() {
        val r = running()
        assertFalse(SettingsRules.needsTypedName(r, SettingsRules.plan(r, draft(r).copy(vcpusLive = "3", memoryDynamicMax = "1536", name = "x"))))
        assertTrue(SettingsRules.needsTypedName(r, SettingsRules.plan(r, draft(r).copy(vcpusLive = "1"))))
        assertTrue(SettingsRules.needsTypedName(r, SettingsRules.plan(r, draft(r).copy(memoryDynamicMin = "768"))))
        val h = halted()
        assertFalse(SettingsRules.needsTypedName(h, SettingsRules.plan(h, draft(h).copy(vcpusMax = "1", vcpusAtStartup = "1", memoryDynamicMin = "512", memoryDynamicMax = "512"))))
    }

    @Test fun confirmationChecksIdentityAgeFencePlanAndTypedName() {
        val s = running()
        val d = draft(s).copy(vcpusLive = "1")
        val ok = VmSettingsFixtures.confirm(s, d, typed = "Guest")
        SettingsRules.confirm(profile, ok, s, NOW + 1000)
        fun refused(current: VmSettings, c: SettingsConfirmation = ok, at: Long = NOW + 1000) =
            assertTrue(runCatching { SettingsRules.confirm(profile, c, current, at) }.exceptionOrNull() is IllegalArgumentException)
        refused(s.copy(uuid = "other"))
        refused(s, at = NOW + 31_000)
        refused(s.copy(vcpusLive = 3))
        refused(s, ok.copy(typedName = "guest"))
        refused(s, ok.copy(steps = emptyList()))
        assertTrue(runCatching { SettingsRules.confirm(profile.copy(certificateSha256 = "b".repeat(64)), ok, s, NOW + 1000) }.isFailure)
    }

    // --- error classes ---

    @Test fun onlyAnswersThatProveNothingChangedAreDefinite() {
        for (code in listOf("VM_BAD_POWER_STATE", "INVALID_VALUE", "MEMORY_CONSTRAINT_VIOLATION_ORDER", "MAP_DUPLICATE_KEY", "HANDLE_INVALID", "SESSION_INVALID", "RBAC_PERMISSION_DENIED"))
            assertEquals(code, SettingFailure.REJECTED, SettingsRules.failure(RpcFailure(code)))
        for (code in listOf("INTERNAL_ERROR", "XENOPS_INTERNAL_ERROR", "SOMETHING_NEW")) assertEquals(code, SettingFailure.AMBIGUOUS, SettingsRules.failure(RpcFailure(code)))
        assertEquals(SettingFailure.LOST, SettingsRules.failure(RpcFailure("RPC_FAULT", explicitRejection = false)))
        assertEquals(SettingFailure.LOST, SettingsRules.failure(IOException("reset")))
        assertEquals(SettingFailure.LOST, SettingsRules.failure(GatewayError("http-error")))
        assertEquals(SettingFailure.NOT_SENT, SettingsRules.failure(NotSent("cancelled")))
    }

    @Test fun everySetterIsAWriteForSessionReuse() {
        for (kind in SettingKind.entries) assertFalse(kind.xapiMethod, XapiMethods.isRead(kind.xapiMethod))
    }

    // --- reconciliation rule ---

    @Test fun checkStatusRuleSeparatesProofFromDoubt() {
        val sent = SettingStep(SettingKind.NAME, null, "Old", "New", SettingState.UNKNOWN, sentAt = NOW)
        assertEquals(SettingState.DONE, SettingsRules.reconcile(sent, "New", false, NOW + 1).first)
        assertEquals(SettingState.UNKNOWN, SettingsRules.reconcile(sent, "Old", false, NOW + SettingsRules.QUIET_MILLIS - 1).first)
        assertEquals(SettingState.NOT_APPLIED, SettingsRules.reconcile(sent, "Old", false, NOW + SettingsRules.QUIET_MILLIS).first)
        assertEquals(SettingState.UNKNOWN, SettingsRules.reconcile(sent, "Old", true, NOW + SettingsRules.QUIET_MILLIS).first) // an operation is running
        assertEquals(SettingState.UNKNOWN, SettingsRules.reconcile(sent.copy(sentAt = null), "Old", false, NOW + 10 * SettingsRules.QUIET_MILLIS).first)
        assertEquals(SettingState.UNKNOWN, SettingsRules.reconcile(sent, "Third", false, NOW + 10 * SettingsRules.QUIET_MILLIS).first)
        assertEquals(SettingState.UNKNOWN, SettingsRules.reconcile(sent, null, false, NOW + 10 * SettingsRules.QUIET_MILLIS).first)
        // A step whose value already equalled the request is never offered; if one is ever met, it is never proven.
        assertEquals(SettingState.UNKNOWN, SettingsRules.reconcile(sent.copy(before = "New"), "New", false, NOW + 1).first)
    }

    // --- wording and saved state ---

    @Test fun summaryListsEveryOutcomeWithoutHidingPartialResults() {
        val steps = listOf(SettingStep(SettingKind.NAME, null, "a", "b", SettingState.DONE),
            SettingStep(SettingKind.MEMORY_DYNAMIC, null, "$GIB,$GIB", "${2 * GIB},${2 * GIB}", SettingState.REJECTED, code = "MEMORY_CONSTRAINT_VIOLATION_ORDER"),
            SettingStep(SettingKind.BOOT_PARAMS, null, "order=c", "order=dc"))
        assertEquals("Saved: Name: “a” → “b”. Refused by the server: Memory: 1 GiB → 2 GiB (MEMORY_CONSTRAINT_VIOLATION_ORDER). " +
            "Not sent: Boot order: Disk → CD drive, then Disk.", SettingsRules.summary(steps))
        assertEquals("Uncertain: Name: “a” → “b”, now reads “c”.", SettingsRules.summary(listOf(SettingStep(SettingKind.NAME, null, "a", "b", SettingState.UNKNOWN, observed = "c"))))
    }

    @Test fun theDraftSurvivesSavedInstanceState() {
        val d = draft(running()).copy(tags = listOf("a", "b c"), autoPowerOn = true, description = "")
        assertEquals(d, SettingsDraft.fromSaved(d.toSaved()))
        assertNull(SettingsDraft.fromSaved(emptyList()))
    }

    // --- long texts show the part that changed (D4 of the first phone run) ---

    private val longText = "This guest runs the build agent for the lab. Contact the lab owner before restarting it. Disks are on the shared NFS store."

    @Test fun aMarkerAppendedToALongDescriptionIsVisibleOnTheAfterSide() {
        val step = SettingStep(SettingKind.DESCRIPTION, null, longText, "$longText [marker 1]", SettingState.PENDING)
        val (before, after) = SettingsRules.excerpts(step.before, step.requested)
        assertNotEquals(before, after)
        assertTrue(after, after.contains("[marker 1]")); assertFalse(before, before.contains("marker"))
        assertTrue(after.startsWith("…")); assertTrue("the end was not cut, so it is not marked", !after.endsWith("…"))
        val line = SettingsRules.change(step)
        assertTrue(line, line.contains("[marker 1]”"))
        assertTrue(line.length < 200)
    }

    @Test fun aChangeInTheMiddleKeepsContextAroundItAndMarksBothCutEnds() {
        val after = longText.replace("restarting", "rebooting")
        val (b, a) = SettingsRules.excerpts(longText, after)
        assertNotEquals(b, a)
        assertTrue(b, b.contains("restarting")); assertTrue(a, a.contains("rebooting"))
        assertTrue(b.startsWith("…")); assertTrue(a.startsWith("…")); assertTrue(b.endsWith("…")); assertTrue(a.endsWith("…"))
        assertTrue(b.length <= SettingsRules.EXCERPT_LENGTH + 2 && a.length <= SettingsRules.EXCERPT_LENGTH + 2)
    }

    @Test fun aChangeNearTheEndKeepsTheRealEndWithoutAMark() {
        val (b, a) = SettingsRules.excerpts(longText, longText.replace("NFS", "iSCSI"))
        assertTrue(b.startsWith("…") && a.startsWith("…")); assertTrue(!b.endsWith("…") && !a.endsWith("…")); assertTrue(a.endsWith("store."))
    }

    @Test fun aChangeAtTheStartIsNotCutAtTheFrontAndTwoCompletelyDifferentLongTextsStillDiffer() {
        val (b, a) = SettingsRules.excerpts("Old $longText", "New $longText")
        assertTrue(b.startsWith("Old ")); assertTrue(a.startsWith("New "))
        assertTrue(b.endsWith("…") && a.endsWith("…"))
        val (x, y) = SettingsRules.excerpts("a".repeat(300), "b".repeat(300))
        assertNotEquals(x, y); assertTrue(x.length <= SettingsRules.EXCERPT_LENGTH + 2)
        // A text deleted down to a short one, and an empty side.
        val (whole, short) = SettingsRules.excerpts(longText, "Short")
        assertTrue(whole.endsWith("…")); assertEquals("Short", short)
        assertEquals("Description: “${longText.take(SettingsRules.EXCERPT_LENGTH)}…” → none",
            SettingsRules.change(SettingStep(SettingKind.DESCRIPTION, null, longText, "", SettingState.PENDING)))
        assertEquals("Description: none → “new”", SettingsRules.change(SettingStep(SettingKind.DESCRIPTION, null, "", "new", SettingState.PENDING)))
    }

    @Test fun shortTextsAreShownWholeAndOnlyCutTextsOfferTheFullValues() {
        assertEquals("Old" to "New", SettingsRules.excerpts("Old", "New"))
        val exactly = "x".repeat(SettingsRules.EXCERPT_LENGTH)
        assertEquals(exactly to exactly, SettingsRules.excerpts(exactly, exactly))
        // One character over the limit is already an excerpt, and the added character is in it.
        assertTrue(SettingsRules.excerpts(exactly, exactly + "y").second.endsWith("y"))
        assertNull(SettingsRules.fullValues(SettingStep(SettingKind.DESCRIPTION, null, "Old", "New", SettingState.PENDING)))
        assertNull(SettingsRules.fullValues(SettingStep(SettingKind.NAME, null, longText, "$longText!", SettingState.PENDING)))
        val long = SettingStep(SettingKind.DESCRIPTION, null, longText, "$longText!", SettingState.PENDING)
        assertEquals(longText to "$longText!", SettingsRules.fullValues(long))
    }

    @Test fun theConfirmationLinesAndThePreviewShowTheChangedPartOfARealPlan() {
        val s = running().copy(description = longText)
        val d = draft(s).copy(description = "$longText [marker 2]")
        val lines = SettingsRules.preview(s, d).first
        assertEquals(1, lines.size); assertTrue(lines[0], lines[0].contains("[marker 2]”"))
        val steps = SettingsRules.plan(s, d)
        assertEquals(lines, steps.map(SettingsRules::change))
        assertEquals(longText to "$longText [marker 2]", SettingsRules.fullValues(steps.single()))
        // The journaled summary of a saved long edit keeps the changed part too.
        val saved = steps.map { it.copy(state = SettingState.DONE, observed = it.requested) }
        assertTrue(SettingsRules.summary(saved), SettingsRules.summary(saved).contains("[marker 2]”"))
    }

    @Test fun anEmojiNearTheCutIsNeverSplit() {
        val text = "a".repeat(30) + "😀" + "b".repeat(80)
        val (b, a) = SettingsRules.excerpts(text, text.replace("😀", "😁"))
        assertNotEquals(b, a)
        listOf(b, a).forEach { e -> e.forEachIndexed { i, c -> if (Character.isHighSurrogate(c)) assertTrue(i + 1 < e.length && Character.isLowSurrogate(e[i + 1])) } }
    }

    @Test fun memoryAndDraftValuesAreReadable() {
        assertEquals("1 GiB", SettingsRules.size(GIB)); assertEquals("768 MiB", SettingsRules.size(768 * MIB)); assertEquals("1.50 GiB", SettingsRules.size(1536 * MIB))
        assertEquals("1024", draft(running()).memoryDynamicMax)
    }
}
