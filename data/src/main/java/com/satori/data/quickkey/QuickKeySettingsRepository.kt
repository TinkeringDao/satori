package com.satori.data.quickkey

import com.satori.domain.model.RecordingLanguage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Stores [QuickKeySettings]. The quick key activity reads them on every press; Settings shows
 * them as "Record as <category>" (", in Malayalam" when it is) and changes them.
 *
 * Plain Kotlin on purpose, so it can move to :domain unchanged if the settings screen wants it there.
 */
interface QuickKeySettingsRepository {

    /** The current settings, then every change. The defaults while nothing is stored. */
    fun observe(): Flow<QuickKeySettings>

    /** The current settings, read once. */
    suspend fun get(): QuickKeySettings = observe().first()

    /**
     * Files quick recordings under this category from now on. Doesn't check that the category
     * exists; Settings offers only existing ones.
     *
     * @throws IllegalArgumentException if [categoryId] is blank
     */
    suspend fun setCategoryId(categoryId: String)

    /**
     * Sets what a press does while Satori records or gets a recording ready: [stop] true stops a
     * running recording and cancels one that is still getting ready; false only shows Satori.
     */
    suspend fun setStopOnSecondPress(stop: Boolean)

    /**
     * Records quick recordings in [language] from now on. Doesn't check that its model is on the
     * phone; Settings offers Malayalam only while it is, and the press checks again.
     */
    suspend fun setLanguage(language: RecordingLanguage)

    /**
     * The quick key activity forwarded a press at [atMs] (wall clock), proof that the key reaches
     * Satori ([QuickKeySettings.lastPressAtMs]). Does nothing by default, for the fakes of tests.
     */
    suspend fun recordPress(atMs: Long) {}

    /**
     * The owner says the key is set up ([QuickKeySettings.setUpByHand]). Does nothing by default,
     * for the fakes of tests.
     */
    suspend fun markSetUp() {}
}
