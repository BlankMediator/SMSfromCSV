package com.blankmediator.smsfromcsv

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreparerTest {
    @Test
    fun samplesPanoramicImagesBeforeBitmapDecode() {
        assertEquals(64, ImagePreparer.calculateInSampleSize(100_000, 100, 1_280, 1_280))
        assertEquals(64, ImagePreparer.calculateInSampleSize(100, 100_000, 1_280, 1_280))
    }

    @Test
    fun leavesOrdinaryImagesAtFullDecodeSize() {
        assertEquals(1, ImagePreparer.calculateInSampleSize(1_920, 1_080, 1_280, 1_280))
    }
}
