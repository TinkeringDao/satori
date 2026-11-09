package com.satori.ui.components

import androidx.compose.runtime.Composable
import com.satori.domain.model.RecordingTitle
import com.satori.ui.design.modeLabel

/**
 * What a recording is called in a list: its name, else the name of its category, else the name of
 * its mode ("Voice note"). The mode name is the last resort, as it names the kind of recording,
 * not this one.
 */
@Composable
fun recordingTitleText(title: RecordingTitle): String =
    title.name ?: title.categoryName ?: modeLabel(title.mode)
