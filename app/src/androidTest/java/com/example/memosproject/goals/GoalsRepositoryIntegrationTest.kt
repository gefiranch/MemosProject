package com.example.memosproject.goals

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.memosproject.data.SupabaseProvider
import com.example.memosproject.data.goals.GoalDraft
import com.example.memosproject.data.goals.GoalImageChange
import com.example.memosproject.data.goals.GoalImageCleanup
import com.example.memosproject.data.goals.GoalImageUpload
import com.example.memosproject.data.goals.SupabaseGoalsRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Opt-in: exercises the real client and logged-in session, and removes its own test row. */
@RunWith(AndroidJUnit4::class)
class GoalsRepositoryIntegrationTest {
    @Test fun saveAndReloadGoalWithExistingSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("goalsIntegration") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = SupabaseProvider.client
        client.auth.awaitInitialization()
        val ownerId = requireNotNull(client.auth.currentUserOrNull()) { "Login di aplikasi sebelum menjalankan uji integrasi Goals." }.id
        val repository = SupabaseGoalsRepository(client, ownerId, GoalImageCleanup(context))
        val id = UUID.randomUUID().toString()
        val draft = GoalDraft("Mome integration test $id", BigDecimal("12345.67"), BigDecimal("123.45"), null)
        try {
            val saved = repository.save(id, null, draft, GoalImageChange.Keep).goal
            assertEquals(id, saved.id)
            assertEquals(0, draft.targetAmount.compareTo(saved.targetAmount))
            assertEquals(0, draft.savedAmount.compareTo(saved.savedAmount))
            assertTrue(repository.list().any { it.id == id })
        } finally {
            // The ID belongs exclusively to this test, including if the request committed before decode failed.
            client.from("goals").delete { filter { eq("id", id); eq("user_id", ownerId) } }
        }
    }

    @Test fun saveEditReplaceRemoveImageAndDeleteGoalWithExistingSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("goalsIntegration") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = SupabaseProvider.client
        client.auth.awaitInitialization()
        val ownerId = requireNotNull(client.auth.currentUserOrNull()).id
        val repository = SupabaseGoalsRepository(client, ownerId, GoalImageCleanup(context))
        val bucket = client.storage.from("goal-images")
        val id = UUID.randomUUID().toString()
        val images = mutableSetOf<String>()
        val draft = GoalDraft("Mome image integration test $id", BigDecimal("100"), BigDecimal("25"), null)
        try {
            val firstImage = image(Color.BLUE)
            val created = repository.save(id, null, draft, GoalImageChange.Replace(firstImage))
            val firstPath = requireNotNull(created.goal.imagePath).also { images.add(it) }
            assertFalse(created.cleanupPending)
            assertArrayEquals(firstImage.bytes, repository.downloadImage(firstPath))
            assertTrue(bucket.list("$ownerId/$id").any { it.name == firstPath.substringAfterLast('/') })

            val nextImage = image(Color.GREEN)
            val edited = repository.save(id, created.goal,
                draft.copy(name = "Updated image integration test $id", savedAmount = BigDecimal("125"), description = "Updated"),
                GoalImageChange.Replace(nextImage))
            val nextPath = requireNotNull(edited.goal.imagePath).also { images.add(it) }
            assertFalse(edited.cleanupPending)
            assertNotEquals(firstPath, nextPath)
            assertEquals(0, BigDecimal("125").compareTo(edited.goal.savedAmount))
            assertEquals(1f, edited.goal.progress, 0f)
            assertArrayEquals(nextImage.bytes, repository.downloadImage(nextPath))
            // Query fresh Storage metadata: a previously downloaded object may still be in HTTP cache.
            assertEquals(setOf(nextPath.substringAfterLast('/')), bucket.list("$ownerId/$id").map { it.name }.toSet())

            val removed = repository.save(id, edited.goal, draft.copy(description = "Image removed"), GoalImageChange.Remove)
            assertNull(removed.goal.imagePath)
            assertFalse(removed.cleanupPending)
            assertTrue(bucket.list("$ownerId/$id").isEmpty())
            assertFalse(repository.delete(removed.goal))
            assertTrue(repository.list().none { it.id == id })
        } finally {
            // Restrict teardown to this test UUID; retain the user's login and all other goals.
            client.from("goals").delete { filter { eq("id", id); eq("user_id", ownerId) } }
            if (images.isNotEmpty()) bucket.delete(images.toList())
            repository.list() // Retry any image cleanup candidate from a failed request.
        }
    }

    private fun image(color: Int): GoalImageUpload {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        return GoalImageUpload(bytes, "image/png", "png")
    }
}
