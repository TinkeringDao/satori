package com.satori.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.satori.ui.R

/**
 * The "satori" wordmark in Doto with the red dot after it, the app's sign. The dot sits on the
 * baseline like a full stop and scales with the type; it is decoration, so TalkBack reads only
 * the name.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val style = Grille.type.wordmark
    val dot = with(LocalDensity.current) { (style.fontSize * 0.2f).toDp() }
    Row(modifier) {
        Text(
            text = stringResource(R.string.app_name).lowercase(),
            style = style,
            color = Grille.colors.ink,
            modifier = Modifier.alignBy(FirstBaseline)
        )
        Spacer(Modifier.width(dot * 0.6f))
        Box(
            Modifier
                .alignBy { it.measuredHeight }
                .size(dot.coerceAtLeast(6.dp))
                .background(Grille.colors.rec, CircleShape)
        )
    }
}
