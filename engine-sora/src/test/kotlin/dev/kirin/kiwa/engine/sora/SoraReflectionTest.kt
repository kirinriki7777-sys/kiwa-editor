package dev.kirin.kiwa.engine.sora

import io.github.rosemoe.sora.text.ComposingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sora Editor 0.24.6 の反射読取りに必要なフィールドが解決でき、型とアクセス可能性が正しいことを確かめる補助 JVM 試験。
 *
 * View は生成しない（JVM 上で動かすため）。wrapper の動作試験とは扱わない。
 */
class SoraReflectionTest {

    @Test
    fun `SoraImeBoundary の反射フィールドが解決でき型とアクセス可能性が正しい`() {
        val icField = SoraImeBoundary.INPUT_CONNECTION_FIELD
        assertNotNull(icField)
        assertTrue(icField!!.isAccessible)

        val ctField = SoraImeBoundary.COMPOSING_TEXT_FIELD
        assertNotNull(ctField)
        assertTrue(ctField!!.isAccessible)
        assertEquals(ComposingText::class.java, ctField.type)
    }
}
