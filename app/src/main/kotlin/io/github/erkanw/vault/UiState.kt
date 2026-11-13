package io.github.erkanw.vault

import android.graphics.Bitmap
import android.net.Uri

// The state types the ViewModels share with the screens.

/** A file chosen for the next message: first being prepared (a picture shrunk, a document read), then ready to send. */
data class PendingAttachment(
    val id: Long,
    val name: String,
    val image: Boolean,
    val ready: Boolean,
    val bytes: Long,
    val dataUrl: String? = null,
    val thumbnail: Bitmap? = null,
    /** A copy kept on the phone, so it can be opened later. */
    val localPath: String? = null,
)

/** The microphone button's state: nothing, dictating (with how long and how loud), or waiting for the last words. */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Recording(val elapsedMs: Long, val level: Float) : VoiceState
    data object Transcribing : VoiceState
}

/** A list page's content: what it shows, whether it is loading, and what went wrong. */
data class PageState<T>(val items: List<T> = emptyList(), val loading: Boolean = false, val error: String? = null)

/** The OpenRouter catalogue for the "Add model" page, when it was read, and whether it is loading or failed. */
data class CatalogState(
    val models: List<io.github.erkanw.vault.openrouter.CatalogModel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val at: Long = 0L,
)

/** A change to the models: what is being done now, or why it failed. */
data class ModelsStatus(val working: String? = null, val error: String? = null)

/** A file saved to Downloads, and what it is, so the page can offer to open it. */
data class SavedFile(val name: String, val uri: Uri, val mime: String)

/** The Files page: the folder shown, whether it is loading, a download running, and the last one that finished. */
data class FilesState(
    val path: String = "",
    val entries: List<io.github.erkanw.vault.chat.FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** The name of what is being downloaded now. */
    val busy: String? = null,
    /** The name of the file being fetched to look at (not to keep). */
    val viewing: String? = null,
    val saved: SavedFile? = null,
)

/** A workspace file opened to look at: its text, or (for a picture) its copy in the app's cache. */
data class ViewedFile(val name: String, val path: String, val text: String? = null, val picture: String? = null)

/** Where saved files go on the phone: Downloads/Apel. */
const val DOWNLOADS_FOLDER = "Download/Apel"
