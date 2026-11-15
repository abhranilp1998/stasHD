package com.abhranil.stashd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abhranil.stashd.ui.theme.StashdRadii
import com.abhranil.stashd.ui.theme.StashdThemeExt

/** Gradient bar from the tokens: 4 dp file, 6 dp item, 12 dp overall. */
@Composable
fun GradientProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp, color: Color? = null) {
    val shape = RoundedCornerShape(height)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val fill = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(shape)
        if (color != null) Box(fill.background(color)) else Box(fill.background(StashdThemeExt.colors.progressBrush))
    }
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, container: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(StashdRadii.iconTile).background(container),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = StashdThemeExt.colors.muted) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun PlaceholderNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StashdThemeExt.colors.warningContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = StashdThemeExt.colors.warning)
    }
}
