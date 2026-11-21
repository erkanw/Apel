# Third-party material in Apel

| What | Where it is used | Licence | Text |
|---|---|---|---|
| Inter, by The Inter Project Authors (rsms/inter), variable font | The app's typeface, `app/src/main/res/font/inter_variable.ttf` | SIL Open Font License 1.1 | [licenses/Inter-OFL.txt](licenses/Inter-OFL.txt) |
| Blob Atelier by Victoria Wu (github.com/victoriawu-77/blob-atelier) | The mascot: its shapes, finishes, states and spring model, ported to Kotlin in `mascot/` | MIT | [licenses/BlobAtelier-MIT.txt](licenses/BlobAtelier-MIT.txt) |
| Lucide icons (lucide.dev), some derived from Feather by Cole Bemis | Icon shapes written out in `ui/VaultIcons.kt` | ISC (Feather-derived icons: MIT) | [licenses/Lucide-ISC.txt](licenses/Lucide-ISC.txt) |
| AndroidX and Jetpack Compose (Room, WorkManager, Browser, WebKit, Core, Activity, Lifecycle), by The Android Open Source Project | The app's screens, storage and scheduling | Apache License 2.0 | [LICENSE](LICENSE) |
| Kotlin standard library and kotlinx.coroutines, by JetBrains | The language runtime | Apache License 2.0 | [LICENSE](LICENSE) |
| OkHttp and Okio, by Square | Every network connection | Apache License 2.0 | [LICENSE](LICENSE) |

The app shows each of these, with its full licence text, in Settings → About → Licences. The texts are copied into the APK from `LICENSE`, `NOTICE` and `licenses/` at build time.
