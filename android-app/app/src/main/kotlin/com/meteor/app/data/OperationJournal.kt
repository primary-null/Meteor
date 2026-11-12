package com.meteor.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream

/** Private, backup-excluded metadata only: no credentials, sessions, endpoint addresses or guest IPs. */
class OperationJournal(context: Context, name: String = "operations-v1.json") : OperationStore {
    private val file = AtomicFile(File(context.filesDir, name))
    override suspend fun load(): List<PowerOperation> = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext emptyList()
        val bytes = file.openRead().use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count == -1) break
                require(output.size() + count <= 512 * 1024); output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= 512 * 1024)
        val root = JSONObject(String(bytes, Charsets.UTF_8)); require(root.getInt("version") == 1)
        val array = root.getJSONArray("operations"); require(array.length() <= 100)
        // A2 operations: JournalCodec reads 1.9 records unchanged and keeps unknown kinds as locked foreign records.
        val list = (0 until array.length()).map { index -> JournalCodec.decode(fields(array.getJSONObject(index))) }
        require(list.map { it.id }.distinct().size == list.size)
        list
    }
    override suspend fun put(operation: PowerOperation) = withContext(Dispatchers.IO) {
        val previous = load().filterNot { it.id == operation.id }
        val all = previous + operation
        val keep = all.filter { it.unresolved } + all.filterNot { it.unresolved }.sortedByDescending { it.updatedAt }.take(50)
        require(keep.size <= 100)
        val root = JSONObject().put("version", 1).put("operations", JSONArray().apply { keep.forEach { op ->
            put(json(JournalCodec.encode(op)))
        } })
        val stream = file.startWrite()
        try { stream.write(root.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    // --- A2 operations ---
    private fun fields(item: JSONObject): Map<String, Any?> = item.keys().asSequence().associateWith { plain(item.get(it)) }
    private fun plain(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> fields(value)
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        else -> value
    }
    private fun json(fields: Map<String, Any?>): JSONObject = JSONObject().apply { fields.forEach { (key, value) -> put(key, jsonValue(value)) } }
    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> json(value.entries.associate { it.key.toString() to it.value })
        is List<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        else -> value
    }
    // --- end A2 operations ---
}
