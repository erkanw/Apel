# Changelog

Each release has a section here; its first lines are the GitHub release notes, and
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` holds the short version F-Droid shows.

## 0.2.0 (versionCode 2)

Agents: more than one agent, each made for a job.

- **Agents** in the drawer: make one from a template (Study companion, Researcher, Training coach, Code reviewer) or
  blank. Each has its own name, look, model, system prompt and memory; About you is shared.
- **Everything is on by default**; on an agent's page you can switch off tools and skills and give it less access to a
  database than the database allows.
- **System prompt** for every agent, explained in the app, with an example to start from and a default.
- Home's chips choose the agent for new chats; a channel and an automation choose theirs. Usage shows spend by agent.
- **A chat keeps its agent**; each answer names the agent that wrote it, and the session page changes the agent or
  only the model.
- **Agent settings** in the drawer holds Automations, Skills and Tools. A short **walkthrough** follows the welcome
  (again from About).
- **About you** is a form (only your name is needed), also asked in the welcome.
- Your own server's models (Groq and others) are listed from the server, without prices.
- Failed answers from free models say why free models fail more often. The OpenRouter sign-in works on Samsung phones.

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
