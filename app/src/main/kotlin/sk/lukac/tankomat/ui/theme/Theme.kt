package sk.lukac.tankomat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = PetrolGreen,
    onPrimary = Sand,
    primaryContainer = PetrolGreenLight,
    onPrimaryContainer = PetrolDark,
    background = Sand,
    onBackground = Charcoal,
    surface = Sand,
    onSurface = Charcoal,
)

/**
 * Tmava schema. `background` a `surface` su CIERNE, nie predvolene tmavosive
 * z Material3 (#1C1B1F).
 *
 * Dovod je vydrz baterie na OLED displeji: cierny pixel sa nerozsvieti vobec,
 * kym tmavosivy svieti takmer ako svetly. Karty su o kusok nad ciernou
 * (`surfaceVariant`), aby bolo vidiet ich hranicu, ale plocha za nimi je
 * zhasnuta.
 */
private val DarkColors = darkColorScheme(
    primary = PetrolGreenLight,
    onPrimary = PetrolDark,
    primaryContainer = PetrolDark,
    onPrimaryContainer = PetrolGreenLight,
    background = Cierna,
    onBackground = SandDim,
    surface = Cierna,
    onSurface = SandDim,
    surfaceVariant = TmavaKarta,
    onSurfaceVariant = SandDim,
)

@Composable
fun TankomatTheme(
    useDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = TankomatTypography,
        content = content,
    )
}
