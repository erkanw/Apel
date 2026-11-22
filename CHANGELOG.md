# Changelog

Each release has a section here; its first lines are the GitHub release notes, and
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` holds the short version F-Droid shows.

## 0.3.0 (versionCode 5)

Agents that work together, automations that keep running after you close the app, and fewer approvals to tap.

- **Keep running in the background** (Automations): Apel stays running, like a VPN app, so automations start on time
  even after you close it or clear your recent apps. A quiet notification shows the next job and can turn it off; Android
  lists Apel under its active apps, where you can stop it too. Off until you turn it on; offered when you save your first
  automation.
- **Agents message each other:** an agent can ask your other agents what they know (from their memory, files or
  databases) and use the answer. The question and the whole answer show in the chat’s steps.
- **Subagents:** for a big task, such as reading many pages to compare prices, an agent can hand the work to a helper
  with a fresh chat, so its own chat stays short. It picks the model from your models, or uses its own. Several can run
  at once. Subagents don’t see the agent’s memory or About you, and can’t message agents or start subagents themselves.
- Each agent’s page has switches for messaging other agents, starting subagents and choosing their model. Every message
  and subagent asks you first.
- **Allow and don’t ask again** on an approval: requests like it (the same site, folder, table, tool or agent) then run
  without asking. Remembered approvals are listed on the Tools page, where you can forget them.
- **Approve what you’ve allowed before** (experimental, on the Tools page): any request like one you allowed once runs
  without asking; only new kinds of requests still wait for you. Automations, skills and memory notes always ask.

## 0.2.2 (versionCode 4)

The rest of the security fixes from Han Wong’s private report
([GHSA-jr23-87jf-9x8j](https://github.com/erkanw/apel/security/advisories/GHSA-jr23-87jf-9x8j)). Please update.

- **Approvals:** a request shows everything it will run, however long, and says when it is long. Writing or editing a
  file shows the text.
- **Web:** after reading a page, the hidden browser or a tool server’s answer, opening an address that wasn’t in your
  message or in what was read asks first, so a page can’t send your information somewhere else.
- **Sign-in:** OpenRouter’s sign-in only accepts its own return address, so another app can’t get in the way.
- **Backups:** a restore checks the backup’s database the way the app opens it, puts everything back if it fails, and
  “Always allow” from a backup comes back as “Ask first” for tools that ask by default.
- **Notifications:** each approval request has its own.
- **Chats:** an answer finishes when you swipe the app away.
- **Automations:** one waiting for your OK in a background job ends properly before Android stops it.
- **Tool servers:** every tool asks first until you allow it, and levels stay with the server they were set for.
- **Agents** read only their own memory.
- **Privacy:** Android’s own backup and phone-to-phone transfer copy nothing; the app is hidden in recent apps.
- **Hidden browser:** it can’t reach this phone or the home network through a name or a WebSocket either.
- CSV import keeps zeros in front; a price that isn’t listed shows as unknown, not free; the budget ignores costs that
  aren’t real amounts; a saved server token isn’t lost on a passing error.

## 0.2.1 (versionCode 3)

Security fixes, found and reported privately by Han Wong
([GHSA-hxqm-jrhr-f2fw](https://github.com/erkanw/apel/security/advisories/GHSA-hxqm-jrhr-f2fw)). Please update.

- **Files:** the agent can no longer write into memory/ or skills/ by spelling the path differently (such as
  `./skills/…`); those folders are changed only by their own tools.
- **Approvals:** Allow (and Always allow) on a request that came by notification now asks for your fingerprint, face
  or screen lock first. Allow in a chat you’re watching is still one tap.
- **Databases:** a database name or archived file name the app wouldn’t make, for example from a crafted backup, never
  becomes a file path.
- **Editing files:** editing a file bigger than 200 KB no longer cuts it short; files over 1 MB can’t be edited in place.

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
