package app.tvessentials.installer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.tvessentials.core.install.PendingInstall
import app.tvessentials.core.install.PendingInstallStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** The only things this app remembers about the user: two switches. No accounts, no identifiers, no history. */
class Prefs(private val context: Context) {
    private val autoKey = booleanPreferencesKey("auto_check")
    private val stableKey = booleanPreferencesKey("prefer_stable")

    val autoCheck: Flow<Boolean> = context.settingsStore.data.map { it[autoKey] ?: true }
    val preferStable: Flow<Boolean> = context.settingsStore.data.map { it[stableKey] ?: true }

    suspend fun setAutoCheck(value: Boolean) { context.settingsStore.edit { it[autoKey] = value } }
    suspend fun setPreferStable(value: Boolean) { context.settingsStore.edit { it[stableKey] = value } }

    suspend fun current(): Pair<Boolean, Boolean> = autoCheck.first() to preferStable.first()
}

/** Synchronous store for the one in-flight "waiting for install permission" record. Expires on its own. */
class SharedPrefsPending(context: Context) : PendingInstallStore {
    private val sp = context.getSharedPreferences("pending_install", Context.MODE_PRIVATE)

    override fun get(): PendingInstall? {
        val id = sp.getString("app", null) ?: return null
        return PendingInstall(id, sp.getLong("at", 0L))
    }

    override fun set(appId: String, atMs: Long) { sp.edit().putString("app", appId).putLong("at", atMs).apply() }
    override fun clear() { sp.edit().clear().apply() }
}
