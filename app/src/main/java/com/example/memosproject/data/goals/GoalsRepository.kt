package com.example.memosproject.data.goals

import com.example.memosproject.BuildConfig
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import android.util.Log

interface GoalsRepository {
    suspend fun list(): List<Goal>
    suspend fun save(id: String, original: Goal?, draft: GoalDraft, imageChange: GoalImageChange): GoalSaveResult
    suspend fun delete(goal: Goal): Boolean
    suspend fun downloadImage(path: String): ByteArray
}

class SupabaseGoalsRepository(
    private val supabase: SupabaseClient,
    private val ownerId: String,
    private val cleanup: GoalImageCleanup
) : GoalsRepository {
    private val mutationLock = Mutex()
    private var nameColumn = "name"
    private val bucket get() = supabase.storage.from("goal-images")

    private fun checkSession() {
        check(supabase.auth.currentUserOrNull()?.id == ownerId) { "Sesi berakhir. Silakan login kembali." }
    }

    override suspend fun list(): List<Goal> = mutationLock.withLock {
        checkSession()
        val goals = mutableListOf<Goal>()
        // Avoid silently truncating the grid at PostgREST's default row limit.
        do {
            val page = supabase.from("goals").select {
                filter { eq("user_id", ownerId) }
                order("created_at", Order.DESCENDING)
                order("id", Order.ASCENDING)
                range(goals.size.toLong(), goals.size.toLong() + 99)
            }.decodeList<Goal>()
            goals.addAll(page)
        } while (page.size == 100)
        cleanupSafely()
        goals
    }

    override suspend fun save(
        id: String, original: Goal?, draft: GoalDraft, imageChange: GoalImageChange
    ): GoalSaveResult = mutationLock.withLock {
        checkSession()
        draft.validate()
        require(original == null || (original.userId == ownerId && original.id == id))
        val persisted = supabase.from("goals").select {
            filter { eq("id", id); eq("user_id", ownerId) }
        }.decodeList<Goal>().singleOrNull()
        require(original == null || persisted != null) { "Goal sudah dihapus. Kembali dan muat ulang goals." }
        val newPath = when (imageChange) {
            GoalImageChange.Keep -> persisted?.imagePath
            GoalImageChange.Remove -> null
            is GoalImageChange.Replace -> "$ownerId/$id/${UUID.randomUUID()}.${imageChange.image.extension}"
        }
        // Write candidates BEFORE network calls so a process death cannot lose the cleanup work.
        // The cleanup re-reads each goal before deleting, including after an ambiguous timeout.
        if (newPath != persisted?.imagePath) {
            persisted?.imagePath?.let { enqueue(it) }
            if (newPath != null) enqueue(newPath)
        }
        try {
            if (imageChange is GoalImageChange.Replace) {
                val image = imageChange.image
                require(image.bytes.isNotEmpty() && image.bytes.size <= MAX_GOAL_IMAGE_BYTES)
                require(image.contentType in setOf("image/jpeg", "image/png", "image/webp"))
                bucket.upload(requireNotNull(newPath), image.bytes) {
                    contentType = ContentType.parse(image.contentType)
                    upsert = false
                }
            }
            checkSession()
            val values = buildJsonObject {
                put("id", id)
                put("user_id", ownerId)
                put(nameColumn, draft.name.trim())
                put("target_amount", goalMoneyJson(draft.targetAmount))
                put("saved_amount", goalMoneyJson(draft.savedAmount))
                put("description", draft.description?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
                put("image_path", newPath?.let(::JsonPrimitive) ?: JsonNull)
            }
            val saved = try {
                writeGoal(id, original, values)
            } catch (error: PostgrestRestException) {
                // Older Mome databases call the goal name `title`. PGRST204 rejects
                // the request before writing, so this retry cannot duplicate a goal.
                if (nameColumn != "name" || error.code != "PGRST204" ||
                    !error.error.contains("'name'")) throw error
                val legacyValues = JsonObject(values - "name" + ("title" to requireNotNull(values["name"])))
                writeGoal(id, original, legacyValues).also { nameColumn = "title" }
            }
            GoalSaveResult(saved, cleanupPending = !cleanupSafely())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) {
                val response = error as? RestException
                val code = (error as? PostgrestRestException)?.code.orEmpty()
                // Do not log RestException.message: it contains request headers and URLs.
                Log.e("MomeGoals", "Save failed: ${error.javaClass.simpleName}; code=$code; " +
                    "status=${response?.statusCode}; ${response?.error?.lineSequence()?.firstOrNull().orEmpty()}")
            }
            cleanupSafely()
            throw error
        }
    }

    private suspend fun writeGoal(id: String, original: Goal?, values: JsonObject): Goal {
        val result = if (original == null) {
            // A stable form ID makes retrying a create after a timeout idempotent.
            supabase.from("goals").upsert(values) { select() }
        } else {
            supabase.from("goals").update(values) {
                filter { eq("id", id); eq("user_id", ownerId) }
                select()
            }
        }
        return result.decodeSingle<Goal>()
    }

    override suspend fun delete(goal: Goal): Boolean = mutationLock.withLock {
        checkSession()
        require(goal.userId == ownerId)
        val current = supabase.from("goals").select {
            filter { eq("id", goal.id); eq("user_id", ownerId) }
        }.decodeList<Goal>().singleOrNull()
        current?.imagePath?.let { enqueue(it) }
        supabase.from("goals").delete {
            filter { eq("id", goal.id); eq("user_id", ownerId) }
            select()
        }.decodeSingle<Goal>()
        // Database deletion succeeds independently of a temporary Storage failure.
        !cleanupSafely()
    }

    override suspend fun downloadImage(path: String): ByteArray {
        checkSession()
        require(path.startsWith("$ownerId/"))
        return bucket.downloadAuthenticated(path)
    }

    private suspend fun enqueue(path: String) = withContext(Dispatchers.IO) { cleanup.add(ownerId, path) }

    private suspend fun cleanupSafely(): Boolean {
        try {
            val paths = withContext(Dispatchers.IO) { cleanup.pending(ownerId) }
            for (path in paths) {
                checkSession()
                require(path.startsWith("$ownerId/"))
                val goalId = path.split('/')[1]
                val current = supabase.from("goals").select {
                    filter { eq("id", goalId); eq("user_id", ownerId) }
                }.decodeList<Goal>().singleOrNull()
                if (current?.imagePath != path) bucket.delete(listOf(path))
                withContext(Dispatchers.IO) { cleanup.remove(ownerId, path) }
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the durable queue and retry on the next list/save/delete operation.
            return false
        }
    }
}
