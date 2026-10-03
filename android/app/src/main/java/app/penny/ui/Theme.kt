package app.penny.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6F50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F2CD),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357),
    tertiary = Color(0xFFB07D16),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6B2),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Color(0xFFA7F2CD),
    secondary = Color(0xFFB4CCBD),
    tertiary = Color(0xFFF4C95D),
)

@Composable
fun PennyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

object AmountColors {
    val income: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF7FD8A0) else Color(0xFF1B7A43)

    val expense: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface
}
