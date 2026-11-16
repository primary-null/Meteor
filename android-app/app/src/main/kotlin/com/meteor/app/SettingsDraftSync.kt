package com.meteor.app

import com.meteor.app.data.SettingsDraft
import com.meteor.app.data.VmSettings

/**
 * The settings editor's working state: what the person is editing ([draft]), the server values that draft started from
 * ([base]) and the save round it belongs to ([round], see [VmSettingsView.generation]). Saveable, so it survives
 * rotation and folding. Display-only: the service checks everything again before anything is sent.
 */
data class EditorDraft(val draft: SettingsDraft, val base: SettingsDraft, val round: Int) {
    fun toSaved(): ArrayList<String> = ArrayList<String>(27).also { it.add(round.toString()); it.addAll(draft.toSaved()); it.addAll(base.toSaved()) }
    companion object {
        fun fromSaved(saved: List<String>): EditorDraft? {
            if (saved.size != 27) return null
            val round = saved[0].toIntOrNull() ?: return null
            val draft = SettingsDraft.fromSaved(saved.subList(1, 14)) ?: return null
            val base = SettingsDraft.fromSaved(saved.subList(14, 27)) ?: return null
            return EditorDraft(draft, base, round)
        }
    }
}

/**
 * How the editor's draft follows the server's values. One explicit rule, on the JVM:
 *
 *  1. A different [VmSettingsView.generation] (a save finished, or a status check resolved a record, and the server was
 *     read again) restarts the draft from that read, with no pending change. Typing from before the save is dropped,
 *     because that same field was just saved or refused and the server value is the truth.
 *  2. Within the same round, a new read (Reload, the confirmation's fresh read, a reconnect) never wipes typing:
 *     a field the person changed keeps their text; a field they did not change takes the new server value.
 *  3. With no draft yet, it starts from the read.
 */
internal object DraftSync {
    fun begin(settings: VmSettings, round: Int): EditorDraft = SettingsDraft.from(settings).let { EditorDraft(it, it, round) }

    fun sync(current: EditorDraft?, settings: VmSettings, round: Int): EditorDraft {
        val server = SettingsDraft.from(settings)
        if (current == null || current.round != round) return EditorDraft(server, server, round)
        if (current.base == server) return current
        val d = current.draft; val b = current.base
        fun <T> pick(mine: T, was: T, now: T) = if (mine == was) now else mine
        val merged = SettingsDraft(
            pick(d.name, b.name, server.name), pick(d.description, b.description, server.description), pick(d.tags, b.tags, server.tags),
            pick(d.vcpusMax, b.vcpusMax, server.vcpusMax), pick(d.vcpusAtStartup, b.vcpusAtStartup, server.vcpusAtStartup),
            pick(d.vcpusLive, b.vcpusLive, server.vcpusLive), pick(d.memoryStaticMin, b.memoryStaticMin, server.memoryStaticMin),
            pick(d.memoryDynamicMin, b.memoryDynamicMin, server.memoryDynamicMin), pick(d.memoryDynamicMax, b.memoryDynamicMax, server.memoryDynamicMax),
            pick(d.memoryStaticMax, b.memoryStaticMax, server.memoryStaticMax), pick(d.autoPowerOn, b.autoPowerOn, server.autoPowerOn),
            pick(d.bootOrder, b.bootOrder, server.bootOrder), pick(d.haPriority, b.haPriority, server.haPriority))
        return EditorDraft(merged, server, round)
    }
}
