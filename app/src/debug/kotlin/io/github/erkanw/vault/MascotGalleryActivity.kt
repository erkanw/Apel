package io.github.erkanw.vault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erkanw.vault.mascot.Blob
import io.github.erkanw.vault.mascot.BlobFlat
import io.github.erkanw.vault.mascot.BlobMood

/**
 * Debug only: Blob's eight states side by side, or one large (extra "mood", e.g. --es mood working), for comparing
 * against the Blob Atelier original. Tap a cell to make it the large one.
 */
class MascotGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("export", false)) IconExport.run(this)
        val asked = intent.getStringExtra("mood")?.let { n -> BlobMood.entries.firstOrNull { it.name.equals(n, true) } }
        val bg = intent.getStringExtra("bg")?.let { Color(android.graphics.Color.parseColor("#$it")) } ?: Color(0xFF111113)
        val light = bg.luminance() > 0.5f
        setContent { io.github.erkanw.vault.ui.VaultTheme(if (light) io.github.erkanw.vault.ui.ThemeMode.Light else io.github.erkanw.vault.ui.ThemeMode.Dark) {
            var big by remember { mutableStateOf(asked) }
            val label = TextStyle(color = Color(0xFF8E8E93), fontSize = 13.sp)
            Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding()) {
                val one = big
                if (one != null) {
                    Column(Modifier.fillMaxSize().clickable { big = null }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Blob(one, size = 320.dp)
                        BasicText(one.name, style = label)
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                        BlobMood.entries.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                pair.forEach { mood ->
                                    Column(Modifier.clickable { big = mood }, horizontalAlignment = Alignment.CenterHorizontally) {
                                        Blob(mood, size = 150.dp, interactive = false)
                                        BasicText(mood.name, style = label)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            listOf(0xFFF2C230, 0xFF3D7BE0, 0xFF33B39A, 0xFFE8605A, 0xFFA877E0).forEach { BlobFlat(Color(it), size = 36.dp) }
                            Blob(BlobMood.Idle, size = 56.dp, interactive = false)
                        }
                    }
                }
            }
        } }
    }
}
