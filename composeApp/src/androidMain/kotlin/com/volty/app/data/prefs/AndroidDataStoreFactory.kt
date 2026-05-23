package com.volty.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.appDataStore by preferencesDataStore(name = "volty_prefs")

actual class DataStoreFactory(private val context: Context) {
    actual fun create(): DataStore<Preferences> = context.appDataStore
}
