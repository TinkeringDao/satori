package com.satori.data.place

import android.content.Context
import android.telephony.TelephonyManager
import com.satori.domain.place.PhoneCountry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

/**
 * The phone's country: that of its SIM, which stays the same on a trip abroad and is read in
 * airplane mode too, else the country of the network it is on. Needs no permission.
 *
 * Without either it says none, so place names are stored without a country code. The language
 * setting's region is no stand-in: a phone in Germany set to US or Indian English would store
 * ", DE" after every place at home, for good.
 */
class AndroidPhoneCountry @Inject constructor(
    @ApplicationContext private val context: Context
) : PhoneCountry {

    override fun code(): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val candidates = sequence {
            // A phone without telephony has no TelephonyManager, or one that throws.
            yield(runCatching { telephony?.simCountryIso }.getOrNull())
            yield(runCatching { telephony?.networkCountryIso }.getOrNull())
        }
        return firstCountryCode(candidates)
    }
}

/**
 * The first of [reported] that is a country code, upper case ("DE"); null when none is. Android
 * reports them in lower case, and as an empty string when it doesn't know.
 */
internal fun firstCountryCode(reported: Sequence<String?>): String? =
    reported
        .mapNotNull { it?.trim()?.uppercase(Locale.ROOT) }
        .firstOrNull { code -> code.length == 2 && code.all { it in 'A'..'Z' } }
