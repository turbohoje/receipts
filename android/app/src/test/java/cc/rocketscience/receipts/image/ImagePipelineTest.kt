package cc.rocketscience.receipts.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePipelineTest {

    @Test
    fun `sample size never decodes below the target edge`() {
        // Halving 4032 gives 2016, already under the 2048 cap, so no halving is allowed:
        // the exact resize is left to the scale step, which keeps quality up.
        assertEquals(1, ImagePipeline.sampleSizeFor(4032, 3024, 2048))
        assertEquals(1, ImagePipeline.sampleSizeFor(2048, 1536, 2048))
        assertEquals(1, ImagePipeline.sampleSizeFor(800, 600, 2048))
    }

    @Test
    fun `decoded edge always stays at or above the cap`() {
        for ((w, h) in listOf(4032 to 3024, 9000 to 6000, 20000 to 15000, 6000 to 8000)) {
            val sample = ImagePipeline.sampleSizeFor(w, h, 2048)
            val decodedEdge = maxOf(w, h) / sample
            assert(decodedEdge >= 2048) { "$w x $h sampled by $sample gives $decodedEdge" }
        }
    }

    @Test
    fun `sample size grows with very large sources`() {
        assertEquals(4, ImagePipeline.sampleSizeFor(9000, 6000, 2048))
        assertEquals(8, ImagePipeline.sampleSizeFor(20000, 15000, 2048))
    }

    @Test
    fun `sample size is orientation-agnostic`() {
        assertEquals(
            ImagePipeline.sampleSizeFor(4032, 3024, 2048),
            ImagePipeline.sampleSizeFor(3024, 4032, 2048),
        )
    }
}
