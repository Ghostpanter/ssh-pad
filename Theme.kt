package com.sshtab.pad.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7CDBB6),
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFFB4C5FF),
    background = Color(0xFF101418),
    surface = Color(0xFF161C22),
    surfaceVariant = Color(0xFF243038),
)

private val Light = lightColorScheme(
    primary = Color(0xFF006C53),
    onPrimary = Color.White,
    secondary = Color(0xFF3D5AA8),
    background = Color(0xFFF4F7F5),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2EEE8),
)

@Composable
fun SshPadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
