package com.minimallabs.vsync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.R

/** The V-Sync mark, drawn in the label colour (or [color]). */
@Composable
fun Logo(size: Dp = 48.dp, color: Color = MaterialTheme.colorScheme.onBackground, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.vsync_logo), contentDescription = "V-Sync",
        colorFilter = ColorFilter.tint(color), modifier = modifier.size(size),
    )
}
