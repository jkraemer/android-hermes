package net.jkraemer.hermes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import net.jkraemer.hermes.R

@RunWith(RobolectricTestRunner::class)
class HermesThemeTest {
    @get:Rule val compose = createComposeRule()

    private fun themeBackground(): Color {
        var background = Color.Unspecified
        compose.setContent { HermesTheme { background = MaterialTheme.colorScheme.background; Text("x") } }
        compose.waitForIdle()
        return background
    }

    @Test
    @Config(qualifiers = "night")
    fun theDarkSchemeIsUsedWhenTheSystemIsDark() = assertEquals(HermesDarkColors.background, themeBackground())

    @Test
    @Config(qualifiers = "notnight")
    fun theLightSchemeIsUsedWhenTheSystemIsLight() = assertEquals(HermesLightColors.background, themeBackground())

    @Test
    fun theSchemesDiffer() = assertNotEquals(HermesLightColors.background, HermesDarkColors.background)

    // The window shows this color until Compose draws, so it has to be the scheme's background.
    @Test
    @Config(qualifiers = "night")
    fun theWindowBackgroundMatchesTheDarkSchemeBeforeComposeDraws() =
        assertEquals(HermesDarkColors.background.toArgb(), RuntimeEnvironment.getApplication().getColor(R.color.window_background))

    @Test
    @Config(qualifiers = "notnight")
    fun theWindowBackgroundMatchesTheLightSchemeBeforeComposeDraws() =
        assertEquals(HermesLightColors.background.toArgb(), RuntimeEnvironment.getApplication().getColor(R.color.window_background))
}
