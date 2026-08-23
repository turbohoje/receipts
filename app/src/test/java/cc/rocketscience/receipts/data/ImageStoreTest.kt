package cc.rocketscience.receipts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = ImageStore(File(temp.root, "images"))

    private fun ImageStore.put(name: String) = file(name).apply { writeText("jpeg") }

    @Test
    fun `deletes a named image`() {
        val store = store()
        store.put("a.jpg")
        assertTrue(store.exists("a.jpg"))

        assertTrue(store.delete("a.jpg"))
        assertFalse(store.exists("a.jpg"))
    }

    @Test
    fun `deleting a null or missing image is a no-op`() {
        val store = store()
        assertFalse(store.delete(null))
        assertFalse(store.delete("nope.jpg"))
    }

    @Test
    fun `sweep removes unreferenced images and keeps referenced ones`() {
        val store = store()
        store.put("keep-1.jpg")
        store.put("keep-2.jpg")
        store.put("orphan-1.jpg")
        store.put("orphan-2.jpg")

        val removed = store.sweepOrphans(referenced = setOf("keep-1.jpg", "keep-2.jpg"))

        assertEquals(2, removed)
        assertTrue(store.exists("keep-1.jpg"))
        assertTrue(store.exists("keep-2.jpg"))
        assertFalse(store.exists("orphan-1.jpg"))
        assertFalse(store.exists("orphan-2.jpg"))
    }

    @Test
    fun `sweep with nothing referenced empties the directory`() {
        val store = store()
        store.put("a.jpg")
        store.put("b.jpg")

        assertEquals(2, store.sweepOrphans(referenced = emptySet()))
        assertEquals(0, store.ensureDir().listFiles()!!.size)
    }

    @Test
    fun `sweep on a fresh install does nothing`() {
        assertEquals(0, store().sweepOrphans(referenced = emptySet()))
    }

    @Test
    fun `image name is derived from the receipt id`() {
        assertEquals("abc-123.jpg", store().fileNameFor("abc-123"))
    }
}
