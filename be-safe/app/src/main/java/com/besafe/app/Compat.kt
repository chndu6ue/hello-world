package com.besafe.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter

@Composable
fun Image(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier
    )
}

fun Iterable<Finding>.sumOf(selector: (Finding) -> Int): Int =
    fold(0) { acc, finding -> acc + selector(finding) }
