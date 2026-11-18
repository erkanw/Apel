# Apel

Your own AI agent, on your phone. Apel runs entirely on Android: your chats, channels, files, skills, automations,
tools, databases and memory live in the app. You connect your own AI provider (OpenRouter by default, or any
OpenAI-compatible server) and pay it directly, only for what you use. There is no server, no account with the maker
and no telemetry.

> **Status: early development.** Chat, tools, files, skills, automations, databases, usage and the budget, the widgets,
> remote tool servers and an experimental browser all work on a test phone. Backups, an accessibility pass and the
> first release come next; nothing is released yet.

## What it will do

- **Chat** with streaming answers, the agent's steps (tool calls, reasoning) shown as they happen, attachments
  (pictures, PDFs, text files), dictation, edit and ask again (a fork of the chat), and Markdown.
- **Channels**: chats kept apart under their own name and colour. Two starters, Daily brief and Notes.
- **Tools**: web search, reading pages, files in its workspace, databases, memory, automations, skills,
  notifications, remote tool servers (MCP over HTTP, added with an address and an optional token), and an
  experimental hidden browser that can open, read, click and type on public pages (off until you turn it on). Each
  tool is Allowed, Ask first or Off; risky actions wait for your OK.
- **Files**: the agent's workspace, which you can browse, edit, import into and share from.
- **Skills**: instructions the agent follows, in the Agent Skills layout (`SKILL.md`); create, edit, import, export.
- **Automations**: scheduled jobs that run with the screen off and post their result to a channel.
- **Databases**: SQLite databases the agent can read or write under rules you set, with a table browser, a SQL
  console, CSV import and export, and backups.
- **Usage and budget**: what each model and each automation cost, and a monthly limit.
- **The mascot**: a Blob Atelier blob you can shape, colour and finish yourself, on Home and on a home-screen widget.

## Privacy

Everything stays on the phone. The only network calls go to the AI provider you chose, to web pages when the agent
searches or reads them, and to tool servers you add. Keys are kept in the Android Keystore and never leave the host
they belong to. By default, OpenRouter requests only go to providers that keep no data.

## Build and run

You need JDK 21 and the Android SDK (compileSdk 35). Then:

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is in `app/build/outputs/apk/debug/`. To see every screen without a provider, turn on demo mode in a
debug build and restart the app:

```
adb shell run-as io.github.erkanw.vault touch files/demo
```

Write `busy`, `error` or `empty` into that file for those states; delete it to turn demo mode off. The release build
has no demo data.

Debug builds also have three galleries for design review: `KitGalleryActivity` (components), `MascotGalleryActivity`
(the mascot's states) and `WidgetGalleryActivity` (the widgets).

## Layout

```
app/src/main/kotlin/io/github/erkanw/vault/
  MainActivity, AppGraph       the activity, and the one place long-lived parts are built
  ChatViewModel                chat, sessions, channels, composer, dictation
  PagesViewModel               Files, Skills, Automations, Tools
  SettingsViewModel            appearance, notifications, widget, models, usage
  agent/                       AgentBackend (what the screens ask of the agent) and TurnEvent (a turn, as it streams)
  chat/                        chat models and pure helpers (sessions, channels, attachments, markdown, commands)
  mascot/                      the blob: shapes, engine, painter, flat icons, states
  ui/                          the screens and the component kit
  widget/                      the mascot widget and the usage widget
  openrouter/                  the OpenRouter catalogue and usage
  store/                       the agent's profile
app/src/debug/                 demo mode (DemoBackend), galleries
app/src/release/               the empty twin of demo mode
```

The design system is in [DESIGN.md](DESIGN.md); the product brief is in [PRODUCT.md](PRODUCT.md).

## Licence

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). The mascot is a port of
[Blob Atelier](https://github.com/victoriawu-77/blob-atelier) by Victoria Wu (MIT); it and the other third-party
material (Inter, Lucide) are listed in [THIRD_PARTY.md](THIRD_PARTY.md), with their licences in `licenses/`.
