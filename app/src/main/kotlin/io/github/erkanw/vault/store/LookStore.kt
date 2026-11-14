package io.github.erkanw.vault.store

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.mascot.BlobLook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The mascot's look (handbook §4.3), kept as a Studio link's hash so it reads the same anywhere. Hero, chat, channels,
 * widget and onboarding all read it from here.
 */
object LookStore {
    private const val PREFS = "agent"
    private const val KEY = "look"

    private val _look = MutableStateFlow<BlobLook?>(null)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun look(context: Context): BlobLook = _look.value ?: BlobLook.parse(prefs(context).getString(KEY, null)).also { _look.value = it }

    fun flow(context: Context): StateFlow<BlobLook?> {
        look(context)
        return _look.asStateFlow()
    }

    fun set(context: Context, look: BlobLook) {
        prefs(context).edit { if (look == BlobLook.Default) remove(KEY) else putString(KEY, look.toQuery()) }
        _look.value = look
    }
}
