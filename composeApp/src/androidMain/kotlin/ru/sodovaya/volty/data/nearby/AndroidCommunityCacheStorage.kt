package ru.sodovaya.volty.data.nearby

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [CommunityCacheStorage] in private SharedPreferences: server names and channel lists, no secrets. */
class AndroidCommunityCacheStorage(context: Context) : CommunityCacheStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) { prefs.getString(key, null) }
    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) { prefs.edit().putString(key, value).apply() }
    override suspend fun remove(key: String) = withContext(Dispatchers.IO) { prefs.edit().remove(key).apply() }

    private companion object {
        const val PREFS_NAME = "volty_community_cache"
    }
}
