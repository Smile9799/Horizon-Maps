package com.headunit.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.headunit.launcher.L
import com.headunit.launcher.PreferenceKeys
import com.headunit.launcher.Prefs
import com.headunit.launcher.Region
import java.util.Locale

/* The South African settings (a self-build addition: see Region.kt). */

/**
 * The pump price in rand a litre ("21.50"), the way it's written on the forecourt here. Kept the way the rest of the app expects
 * it: cents a litre.
 */
@Composable
internal fun RandFuelPrice(prefs: Prefs) {
    var text by remember {
        mutableStateOf(prefs.s(PreferenceKeys.FUELPPL, "").toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: "")
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SItem(L.t("Fuel price (rand per litre)"), L.t("The price changes on the first Wednesday of each month"))
        SField(text, { s ->
            text = s.filter { it.isDigit() || it == '.' || it == ',' }.take(6)
            val rand = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
            prefs.put(PreferenceKeys.FUELPPL, rand?.let { String.format(Locale.US, "%.1f", it * 100.0) } ?: "")
        }, placeholder = L.t("e.g. 21.50"), number = true, width = 460.dp, fill = true)
    }
}

/** Robots and circles, or traffic lights and roundabouts: shown only in South Africa. */
@Composable
internal fun ZaWordingToggle(prefs: Prefs) {
    if (!Region.za || !L.en) return
    SToggle(prefs, "South African wording", Region.WORDS_KEY, true,
        "Robots, circles, freeways and trucks, on screen and in the spoken directions. Off: traffic lights, roundabouts, motorways and lorries")
}
