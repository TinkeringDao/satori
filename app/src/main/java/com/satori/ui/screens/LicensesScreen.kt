package com.satori.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.model.LicenseEntry
import com.satori.presentation.licenses.LicensesViewModel
import com.satori.ui.R
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTopBar
import com.satori.ui.design.Hairline
import com.satori.ui.design.scrollCue

/**
 * Third-party libraries, AI models and fonts, as a hairline list. Each entry expands to its
 * licence text, drawn as paragraphs that wrap to the screen ([LicenceParagraphs]); Gemma and
 * Parakeet start expanded because their terms ask for visible attribution, and show their licence
 * and model card addresses once, with no text repeating them. A tap on an address opens it in the
 * phone's browser, another app, only on the owner's tap (he said yes, 2026-10-15); the text stays
 * selectable, to copy. The list scrolls, with the line that says so, so it fits the cover screen
 * as well.
 */
@Composable
fun LicensesScreen(
    onNavigateBack: () -> Unit,
    viewModel: LicensesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        GrilleTopBar(
            title = stringResource(R.string.g_settings_licences),
            onBack = onNavigateBack,
            backDescription = stringResource(R.string.g_back)
        )
        val list = rememberLazyListState()
        LazyColumn(
            state = list,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .scrollCue(list),
            contentPadding = PaddingValues(bottom = GrilleSpace.xl)
        ) {
            item(key = LICENCES_INTRO_KEY) {
                Text(
                    text = stringResource(R.string.g_settings_licences_intro),
                    style = Grille.type.body,
                    color = Grille.colors.ash,
                    modifier = Modifier.padding(
                        start = GrilleSpace.margin,
                        end = GrilleSpace.margin,
                        top = GrilleSpace.s,
                        bottom = GrilleSpace.m
                    )
                )
            }
            items(uiState.licenses, key = { it.id }) { entry ->
                LicenceItem(
                    entry = entry,
                    expanded = entry.id in uiState.expandedIds,
                    onToggle = { viewModel.toggleExpanded(entry.id) }
                )
                Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            }
        }
    }
}

@Composable
private fun LicenceItem(entry: LicenseEntry, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GrilleSpace.touch)
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.g_settings_licences_hide else R.string.g_settings_licences_show
                    ),
                    role = Role.Button,
                    onClick = onToggle
                )
                .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = entry.componentName, style = Grille.type.body, color = Grille.colors.ink)
                Text(text = entry.licenseType, style = Grille.type.caption, color = Grille.colors.ash)
            }
            Icon(
                imageVector = GrilleIcons.Expand,
                contentDescription = null,
                tint = Grille.colors.ash,
                modifier = Modifier
                    .padding(start = GrilleSpace.m)
                    .size(22.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        if (expanded) {
            // Selectable, so an address or a line can be copied; an address opens on a tap.
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, bottom = GrilleSpace.l),
                    verticalArrangement = Arrangement.spacedBy(GrilleSpace.m)
                ) {
                    if (entry.shortNotice.isNotBlank()) {
                        Text(text = entry.shortNotice, style = Grille.type.caption, color = Grille.colors.ink)
                    }
                    LicenceAddress(label = stringResource(R.string.g_settings_licences_url_licence), url = entry.primaryUrl)
                    entry.secondaryUrl?.let { url ->
                        LicenceAddress(label = stringResource(R.string.g_settings_licences_url_model), url = url)
                    }
                    entry.githubUrl?.let { url ->
                        LicenceAddress(label = stringResource(R.string.g_settings_licences_url_source), url = url)
                    }
                    // An entry made of its addresses alone has no text that would repeat them.
                    if (entry.fullText.isNotBlank()) {
                        val paragraphs = remember(entry.fullText) { LicenceParagraphs.of(entry.fullText) }
                        Text(text = paragraphs, style = Grille.type.caption, color = Grille.colors.ash)
                    }
                }
            }
        }
    }
}

/**
 * A web address under a short label, underlined as a link: a tap opens it in the browser, one
 * touch target of at least 48 dp with its label. A phone without a browser leaves it as text.
 */
@Composable
private fun LicenceAddress(label: String, url: String) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GrilleSpace.touch)
            .clickable(onClickLabel = stringResource(R.string.g_settings_licences_open), role = Role.Button) {
                openAddress(context, url)
            },
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = label, style = Grille.type.caption, color = Grille.colors.ash)
        Text(
            text = url,
            style = Grille.type.caption.copy(textDecoration = TextDecoration.Underline),
            color = Grille.colors.ink
        )
    }
}

/** Opens [url] in the phone's browser; with none to open it, nothing happens. */
private fun openAddress(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // No browser: the address stays readable and selectable.
    }
}

private const val LICENCES_INTRO_KEY = "licences_intro"
