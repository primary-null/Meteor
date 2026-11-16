package com.meteor.app.data

import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

// --- vault recovery ---
// Pure decisions behind "the saved connections could not be opened": why it failed, what a reset sets aside, and what happens
// to connections added while the vault was unreadable. Nothing here touches the Android Keystore, so it is tested on the JVM.

/**
 * Why the encrypted connection file could not be read. [permanent] causes will not go away by themselves; a reset is the only
 * way back to saving. [TEMPORARY] is anything else that failed (storage or Keystore not answering): worth trying again.
 */
enum class VaultFailure(val permanent: Boolean) {
    /** The encryption key is gone or can never be used again (new phone, wiped or invalidated key). */
    KEY_UNAVAILABLE(true),
    /** The file is damaged, or does not unlock with the phone's key. */
    CORRUPT(true),
    /** The file unlocks (or carries Meteor's marker) but was written in a format this version does not understand. */
    UNKNOWN_FORMAT(true),
    /** An I/O error or another failure that is not known to be permanent. */
    TEMPORARY(false),
}

/** A vault failure whose cause is known. The message never contains vault contents. */
class VaultException(val failure: VaultFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

object VaultFailures {
    /** Marks a vault that is not in the format this version writes: Meteor's magic prefix "MET" with another version byte. */
    const val MAGIC = 0x4d455431
    fun isNewerMagic(magic: Int) = magic != MAGIC && magic ushr 8 == MAGIC ushr 8

    fun classify(error: Throwable): VaultFailure {
        var cause: Throwable? = error
        repeat(6) { // a short chain: callers may wrap
            when (val current = cause) {
                null -> return VaultFailure.TEMPORARY
                is VaultException -> return current.failure
                is BadPaddingException, is IllegalBlockSizeException -> return VaultFailure.CORRUPT // includes AEADBadTagException
                is UnrecoverableKeyException, is InvalidKeyException -> return VaultFailure.KEY_UNAVAILABLE // includes KeyPermanentlyInvalidatedException
                is IOException -> return VaultFailure.TEMPORARY
                else -> cause = current.cause
            }
        }
        return VaultFailure.TEMPORARY
    }

    /** "Start over" is offered for permanent causes; for a temporary one only after Try again has failed as well. */
    fun canStartOver(failure: VaultFailure, failedAttempts: Int) = failure.permanent || failedAttempts >= 2
}

/**
 * Setting an unreadable vault aside. The files are renamed inside the app's private storage, never deleted by a reset and never read
 * again by the app. Only the [KEEP] most recent sets are kept; older ones are deleted so repeated resets cannot fill the phone.
 */
object VaultQuarantine {
    const val KEEP = 2
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    // The atomic-write leftovers go first: a surviving ".bak" would otherwise be restored as the live file.
    private val SUFFIXES = listOf(".new", ".bak", "")

    private fun pattern(fileName: String) = Regex("^" + Regex.escape(fileName) + "\\.unreadable-(\\d{8}-\\d{6})(\\.bak|\\.new)?$")

    /**
     * Renames [fileName] and its atomic-write leftovers to `<fileName>.unreadable-<UTC time>[suffix]`. Returns false when there was
     * nothing to set aside. On any failure everything already moved is put back and [IOException] is thrown, so the vault is unchanged.
     */
    fun setAside(directory: File, fileName: String, nowMillis: Long,
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }): Boolean {
        val time = stamp.format(Instant.ofEpochMilli(nowMillis))
        val moves = SUFFIXES.map { File(directory, fileName + it) to File(directory, "$fileName.unreadable-$time$it") }
            .filter { it.first.exists() }
        if (moves.isEmpty()) return false
        if (moves.any { it.second.exists() }) throw IOException("A set-aside copy with this time already exists.")
        val moved = ArrayList<Pair<File, File>>()
        try {
            for (move in moves) {
                if (!rename(move.first, move.second)) throw IOException("The unreadable file could not be set aside.")
                moved += move
            }
        } catch (error: IOException) {
            moved.asReversed().forEach { rename(it.second, it.first) }
            throw error
        }
        prune(directory, fileName, KEEP)
        return true
    }

    /** The set-aside files of [fileName], newest set first. */
    fun setAsideFiles(directory: File, fileName: String): List<File> {
        val pattern = pattern(fileName)
        return directory.listFiles().orEmpty().mapNotNull { file -> pattern.matchEntire(file.name)?.let { it.groupValues[1] to file } }
            .sortedWith(compareByDescending<Pair<String, File>> { it.first }.thenBy { it.second.name }).map { it.second }
    }

    /** Deletes all but the [keep] most recent sets. Returns the deleted file names. */
    fun prune(directory: File, fileName: String, keep: Int = KEEP): List<String> {
        val pattern = pattern(fileName)
        val sets = directory.listFiles().orEmpty().mapNotNull { file -> pattern.matchEntire(file.name)?.let { it.groupValues[1] to file } }
            .groupBy({ it.first }, { it.second })
        val removed = ArrayList<String>()
        sets.keys.sortedDescending().drop(keep).forEach { time -> sets.getValue(time).forEach { if (it.delete()) removed += it.name } }
        return removed
    }
}

data class VaultMerge(val document: ProfileDocument, val added: Int, val skipped: Int) {
    companion object {
        /**
         * The connections added while the vault was unreadable ([session]) join the readable [stored] document, so what the person
         * sees stays. A session connection for a server and username that [stored] already has is dropped (the stored one wins, with
         * its certificate pin), and so is any beyond the 50-connection cap. The selection follows to the stored twin.
         */
        fun merge(stored: ProfileDocument, session: List<Profile>, selectedId: String?): VaultMerge {
            var profiles = stored.profiles
            var skipped = 0
            for (profile in session) {
                if (profiles.any { it.id == profile.id }) continue
                if (profiles.any { it.endpoint == profile.endpoint && it.username == profile.username } ||
                    profiles.size >= ProfileDocument.MAX_PROFILES) { skipped++; continue }
                profiles = profiles + profile
            }
            val wanted = session.firstOrNull { it.id == selectedId }
            val chosen = profiles.firstOrNull { it.id == selectedId }
                ?: wanted?.let { twin -> profiles.firstOrNull { it.endpoint == twin.endpoint && it.username == twin.username } }
            val selected = (chosen?.id ?: stored.selectedId)?.takeIf { id -> profiles.any { it.id == id } }
            return VaultMerge(ProfileDocument(profiles, selected), profiles.size - stored.profiles.size, skipped)
        }
    }
}
// --- end vault recovery ---
