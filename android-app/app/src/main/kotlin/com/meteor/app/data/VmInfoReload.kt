package com.meteor.app.data

/**
 * When the read-only "Identity and configuration" section (A3's [VmInfo]) must be read again. It is on demand, so
 * without these rules it kept showing the time of its first read after a migration, a restart or a re-open.
 *
 * The triggers are deliberately few so requests stay bounded: a changed power state, resident host or boot marker
 * between two detail reads, an operation on the open VM closing, and the user opening or refreshing the details.
 * An unchanged five-second poll never triggers a read.
 */
object VmInfoReload {
    /** True when [after] shows a different power state, resident host or boot marker than the [before] read of the same VM. */
    fun markersChanged(before: VmDetails?, after: VmDetails): Boolean =
        before != null && before.uuid == after.uuid && before.authority == after.authority &&
            (before.power != after.power || before.residentHostUuid != after.residentHostUuid || before.bootTime != after.bootTime)

    /** True when [operation] has closed (verified, failed or manually closed) and belongs to the VM that is open. */
    fun operationClosed(openUuid: String?, openAuthority: String?, operation: PowerOperation): Boolean =
        openUuid != null && openAuthority != null && !operation.unresolved &&
            operation.vmUuid == openUuid && operation.authority == openAuthority
}
