package com.satori.data.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Debug builds only: reads the stored test server ([ModelSource.install]) as the app's process
 * starts, before anything downloads, so the release build needs no call for it anywhere. It
 * provides nothing; it is not exported.
 */
class ModelSourceInstaller : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let(ModelSource::install)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
