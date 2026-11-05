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
        val list = (0 until array.length()).map { index ->
            val j = array.getJSONObject(index)
            PowerOperation(j.getString("id"), j.getString("profile"), j.getString("origin"), j.getString("authority"),
                j.getString("vm"), j.getString("name"), PowerAction.valueOf(j.getString("action")),
                j.optString("beforeBoot").takeIf { it.isNotBlank() }, PowerPhase.valueOf(j.getString("phase")),
                j.getLong("createdAt"), j.getLong("updatedAt"), j.optString("taskRef").takeIf { it.isNotBlank() },
                j.optString("taskUuid").takeIf { it.isNotBlank() }, j.getString("message"),
                if (j.has("manualResolution")) j.getJSONObject("manualResolution").let { review ->
                    ManualResolution(review.getLong("reviewedAt"), review.getString("origin"), review.getString("power"),
                        review.optString("bootTime").takeIf { it.isNotBlank() })
                } else null)
        }
        require(list.map { it.id }.distinct().size == list.size)
        list
    }
    override suspend fun put(operation: PowerOperation) = withContext(Dispatchers.IO) {
        val previous = load().filterNot { it.id == operation.id }
        val all = previous + operation
        val keep = all.filter { it.unresolved } + all.filterNot { it.unresolved }.sortedByDescending { it.updatedAt }.take(50)
        require(keep.size <= 100)
        val root = JSONObject().put("version", 1).put("operations", JSONArray().apply { keep.forEach { op ->
            val item = JSONObject().put("id", op.id).put("profile", op.profileId).put("origin", op.origin).put("authority", op.authority)
                .put("vm", op.vmUuid).put("name", op.vmName).put("action", op.action.name).put("beforeBoot", op.beforeBoot.orEmpty())
                .put("phase", op.phase.name).put("createdAt", op.createdAt).put("updatedAt", op.updatedAt)
                .put("taskRef", op.taskRef.orEmpty()).put("taskUuid", op.taskUuid.orEmpty()).put("message", op.message)
            op.manualResolution?.let { review -> item.put("manualResolution", JSONObject()
                .put("reviewedAt", review.reviewedAt).put("origin", review.origin).put("power", review.power)
                .put("bootTime", review.bootTime.orEmpty())) }
            put(item)
        } })
        val stream = file.startWrite()
        try { stream.write(root.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
