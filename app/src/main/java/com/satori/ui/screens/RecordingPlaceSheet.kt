package com.satori.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.satori.domain.model.RecordingPlace
import com.satori.domain.place.PlaceLabel
import com.satori.presentation.sessiondetails.PlaceError
import com.satori.ui.R
import com.satori.ui.design.ActionRow
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSheet
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.PillAction
import com.satori.ui.design.PillKind

/** The sheets of a recording's place: what it is, and the question before it is removed. */
internal enum class PlaceSheet { DETAILS, CONFIRM_REMOVE }

/**
 * The open [sheet] of the place [label], which reads [text] under the date; nothing while none is
 * open or the recording has no place.
 *
 * @param onSheet Opens another of the sheets, or with null closes them
 * @param onOpenInMaps Shows the place in a maps app
 * @param onRemove Removes the place, once the user has confirmed it
 */
@Composable
internal fun RecordingPlaceSheets(
    sheet: PlaceSheet?,
    label: PlaceLabel?,
    text: String?,
    onSheet: (PlaceSheet?) -> Unit,
    onOpenInMaps: () -> Unit,
    onRemove: () -> Unit
) {
    if (label == null || text == null) return
    when (sheet) {
        PlaceSheet.DETAILS -> PlaceDetailsSheet(
            label = label,
            // A place without a name reads as its coordinates, which the sheet shows below.
            title = if (label.place.name != null) text else stringResource(R.string.g_place_label),
            onOpenInMaps = onOpenInMaps,
            onRemove = { onSheet(PlaceSheet.CONFIRM_REMOVE) },
            onDismiss = { onSheet(null) }
        )
        PlaceSheet.CONFIRM_REMOVE -> ConfirmSheet(
            title = stringResource(R.string.g_place_remove_title),
            text = stringResource(R.string.g_place_remove_body),
            confirmLabel = stringResource(R.string.g_place_remove_confirm),
            onConfirm = {
                onSheet(null)
                onRemove()
            },
            onDismiss = { onSheet(null) }
        )
        null -> Unit
    }
}

/**
 * Where the recording was made: its coordinates and how accurate they are, the credit for the
 * place's name, and what can be done with it: show it in a maps app, which may use the network
 * (only the position and the name go to it), or remove it.
 */
@Composable
private fun PlaceDetailsSheet(
    label: PlaceLabel,
    title: String,
    onOpenInMaps: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val place = label.place
    GrilleSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = title,
                style = Grille.type.title,
                color = Grille.colors.ink,
                modifier = Modifier
                    .padding(horizontal = GrilleSpace.margin)
                    .semantics { heading() }
            )
            Spacer(Modifier.height(GrilleSpace.s))
            SettingsRow(title = stringResource(R.string.g_place_coordinates), value = PlaceText.coordinates(place))
            place.accuracyMeters?.let { accuracy ->
                SettingsDivider()
                SettingsRow(title = stringResource(R.string.g_place_accuracy), value = placeAccuracyText(accuracy))
            }
            if (place.name != null) {
                Spacer(Modifier.height(GrilleSpace.s))
                SettingsNote(stringResource(R.string.g_place_names_credit))
            }
        }
        Spacer(Modifier.height(GrilleSpace.l))
        ActionRow(
            primary = PillAction(stringResource(R.string.g_place_open_maps), onClick = onOpenInMaps),
            secondary = PillAction(stringResource(R.string.g_place_remove), onClick = onRemove),
            secondaryKind = PillKind.Outline,
            modifier = Modifier.padding(horizontal = GrilleSpace.margin)
        )
    }
}

/** Says what went wrong with the place and what to do, in the app's language. */
@Composable
internal fun placeErrorMessage(error: PlaceError): String = stringResource(
    when (error) {
        PlaceError.RemoveFailed -> R.string.g_place_error_remove
        PlaceError.NoMapsApp -> R.string.g_place_error_maps
    }
)

/**
 * Shows [place] in a maps app, with a pin named [name].
 *
 * @return false when no app on the phone shows places
 */
internal fun openInMaps(context: Context, place: RecordingPlace, name: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PlaceText.geoUri(place, name))))
    true
} catch (e: ActivityNotFoundException) {
    false
}
