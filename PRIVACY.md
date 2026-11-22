# Privacy statement

This is the statement Apel shows in Settings → About → Privacy statement.

Apel has no server and no account with its maker. Nothing about you or how you use the app is sent to its maker: no
analytics, no crash reports, no telemetry.

## What stays on this phone

Your chats, channels, files, skills, memory, automations, databases, usage log and settings are kept in the app’s
private storage. Keys and tokens are encrypted with the phone’s keystore; they are never shown, logged or put in a
backup. Android’s cloud backup is turned off for Apel.

## What leaves it, and where to

- The AI provider you chose (OpenRouter, or your own server) receives what each turn needs: your message, attached
  files, the agent’s instructions and memory, the chat so far and what its tools found. The provider’s own privacy
  policy applies. On OpenRouter, “Private providers only” (on unless you turn it off) sends requests only to
  providers that keep no data.
- Web search runs through OpenRouter. When the agent reads a web page or uses the browser, that site sees a visit
  from this phone.
- A tool server you add receives the calls the agent makes to it, with the token you gave it.
- Dictation uses your phone’s speech recogniser, which may send your voice to its own service.

## Backups

A backup is a zip you make yourself, saved in Downloads/Apel. It holds everything above except keys and tokens, and
it isn’t encrypted: keep it somewhere safe.

## Permissions

The internet and its state (to wait for a connection); notifications (replies, approvals, automations, the budget);
exact alarms, starting after a restart, keeping the phone awake and running in the background while Apel works
(automations on time), and all the time if you turn on Keep running in the background; an exemption from battery
optimisation, only if you allow it when asked; vibration, for haptic feedback; the microphone, only while you dictate.

## Questions

Apel is open source: you can read what it does. Ask or report a problem at
[github.com/erkanw/apel-agent](https://github.com/erkanw/apel-agent).
