# Changelog

Each release has a section here; its first lines are the GitHub release notes, and
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` holds the short version F-Droid shows.

## 0.1.0 (versionCode 1)

The first release: an AI agent that runs entirely on your Android phone, with your own AI provider.

- **Chat** with streaming answers, the agent's steps as they happen, attachments (pictures, PDFs, text files),
  dictation, editing and asking again, Markdown. **Channels** keep chats apart; Daily brief and Notes to start.
- **Your provider**: sign in to OpenRouter (private providers only, by default) or connect any OpenAI-compatible
  server. Models with their price per message; free models labelled; fallbacks.
- **Tools**, each Allowed, Ask first or Off: web search and reading pages, files in its workspace, memory, skills,
  automations, databases, notifications, the time; remote tool servers (MCP over HTTP); an experimental hidden
  browser (off until you turn it on). Risky actions wait for your OK on an approval card.
- **Files, skills and rules**: the agent's workspace with editing, Trash, import and sharing; skills in the Agent
  Skills layout; instructions, "about you" and memory, with nightly tidying.
- **Automations**: scheduled jobs that run with the screen off and the app closed, and post to a channel.
- **Databases**: SQLite databases the agent reads or writes under rules you set, with a table browser, a SQL
  console, CSV import and export, and backups.
- **Usage and budget**: what each model and job cost, a monthly budget that warns, pauses jobs or asks first.
- **The mascot**: a Blob Atelier blob you shape, colour and finish, on Home and on two home-screen widgets.
- **Backup**: everything except keys and tokens in one zip in Downloads/Apel, and restore on a new phone.
- **About**: the privacy statement, every licence in full, diagnostics to copy for a bug report.

No server, no account with the maker, no telemetry.
