package com.satori.ui.screens

import androidx.compose.runtime.Composable

/**
 * Release builds: the power key's sheet ([QuickKeySheet]) has no setup from a computer, no adb
 * commands and no word of them (the owner's decision of 2026-10-15), and the APK holds none of their
 * strings, nor the code that makes the commands, which is the debug source set's alone. The debug
 * build's version of this keeps the commands; its signature is the same, so the sheet is one for
 * both builds.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun QuickKeySetup(onCopied: () -> Unit) = Unit
