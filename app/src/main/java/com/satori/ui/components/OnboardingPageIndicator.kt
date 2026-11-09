package com.satori.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.satori.ui.R
import com.satori.ui.design.Grille

private val DOT_SIZE = 6.dp
private val DOT_GAP = 6.dp

/**
 * Onboarding page dots: the current page is lit (ink), the others are off
 * ([com.satori.ui.design.GrilleColors.mesh]).
 * Screen readers hear "Step 2 of 4" instead of the dots.
 *
 * @param totalPages Total number of onboarding pages.
 * @param currentPage Zero-based index of the current page.
 * @param modifier Optional modifier.
 */
@Composable
fun OnboardingPageIndicator(
    totalPages: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.g_onb_step, currentPage + 1, totalPages)
    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalPages) { index ->
            Box(
                Modifier
                    .size(DOT_SIZE)
                    .background(
                        color = if (index == currentPage) Grille.colors.ink else Grille.colors.mesh,
                        shape = CircleShape
                    )
            )
        }
    }
}
