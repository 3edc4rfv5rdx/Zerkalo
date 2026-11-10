package xx.zerkalo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentSchemeTest {
    /** WCAG contrast between two colours, 1 to 21. */
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun everyAccentColoursTheTonalButtonInBothThemes() {
        for (dark in listOf(false, true)) {
            val containers = AccentPalette.map { accentScheme(dark, it).secondaryContainer }
            assertEquals("each accent its own Cancel", AccentPalette.size, containers.toSet().size)
            for (accent in AccentPalette) {
                val scheme = accentScheme(dark, accent)
                assertEquals(accent, scheme.primary)
                assertEquals(accent, scheme.secondary)
                assertEquals(accent, scheme.tertiary)
                assertEquals(scheme.secondaryContainer, scheme.primaryContainer)
            }
        }
    }

    @Test
    fun aTonalButtonsTextReadsOnItAndTheButtonOnTheDialog() {
        for (dark in listOf(false, true)) {
            for (accent in AccentPalette) {
                val scheme = accentScheme(dark, accent)
                val label = contrast(scheme.onSecondaryContainer, scheme.secondaryContainer)
                assertTrue("label on $accent, dark=$dark: $label", label >= 4.5f)
                assertNotEquals(scheme.surfaceContainerHigh, scheme.secondaryContainer)
            }
        }
    }
}
