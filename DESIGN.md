---
name: Apel (Android)
description: The category standard played straight. A flat charcoal room, one tonal lift, white as the only accent, and a customisable blob that tells the truth.
colors:
  ground: "#111113"
  surface: "#19191C"
  lift: "#232326"
  pressed: "#2E2E32"
  hairline: "#FFFFFF14"
  text: "#F5F5F7"
  muted: "#8E8E93"
  faint: "#5C5C62"
  accent-white: "#F5F5F7"
  on-accent: "#111113"
  live: "#30C77B"
  danger: "#FF5A4E"
  attention: "#F5A524"
  scrim: "#000000CC"
  channel-amber: "#F5B83D"
  channel-coral: "#F2604F"
  channel-blue: "#3D8BF2"
  channel-green: "#2FBF8F"
  channel-violet: "#A77BF0"
  channel-pink: "#F27BB0"
  channel-teal: "#2EB5C9"
  channel-cocoa: "#B08968"
typography:
  large-title:
    fontFamily: "Inter Display (Inter variable, opsz 28)"
    fontSize: "30sp"
    fontWeight: 600
    lineHeight: "36sp"
    letterSpacing: "-0.6sp"
  display:
    fontFamily: "Inter Display (Inter variable, opsz 28)"
    fontSize: "22sp"
    fontWeight: 600
    lineHeight: "28sp"
    letterSpacing: "-0.35sp"
  title:
    fontFamily: "Inter (Inter variable, opsz 14)"
    fontSize: "17sp"
    fontWeight: 600
    lineHeight: "22sp"
    letterSpacing: "-0.2sp"
  row:
    fontFamily: "Inter (Inter variable, opsz 14)"
    fontSize: "16sp"
    fontWeight: 500
    lineHeight: "21sp"
    letterSpacing: "-0.1sp"
  reading:
    fontFamily: "Inter (Inter variable, opsz 14)"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
  body:
    fontFamily: "Inter (Inter variable, opsz 14)"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "21sp"
  preview:
    fontFamily: "Inter (Inter variable, opsz 14)"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "19sp"
  label:
    fontFamily: "Inter (Inter variable, opsz 14)"
    fontSize: "13sp"
    fontWeight: 500
    lineHeight: "18sp"
  caption:
    fontFamily: "Inter (Inter variable, opsz 14)"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "16sp"
    letterSpacing: "0.1sp"
rounded:
  row: "14dp"
  menu: "16dp"
  tile: "18dp"
  bubble: "22dp"
  dialog: "24dp"
  sheet: "28dp"
  field: "28dp"
  pill: "9999dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "24dp"
components:
  button-primary:
    backgroundColor: "{colors.accent-white}"
    textColor: "{colors.on-accent}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 22dp"
    height: "48dp"
  button-secondary:
    backgroundColor: "{colors.lift}"
    textColor: "{colors.text}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 22dp"
    height: "48dp"
  button-secondary-pressed:
    backgroundColor: "{colors.pressed}"
  button-quiet:
    textColor: "{colors.text}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 16dp"
    height: "48dp"
  button-quiet-pressed:
    backgroundColor: "{colors.lift}"
  bar-button:
    textColor: "{colors.text}"
    rounded: "{rounded.pill}"
    size: "48dp"
  chip:
    backgroundColor: "{colors.lift}"
    textColor: "{colors.text}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 14dp"
    height: "36dp"
  chip-selected:
    backgroundColor: "{colors.accent-white}"
    textColor: "{colors.on-accent}"
  suggestion-tile:
    backgroundColor: "{colors.lift}"
    textColor: "{colors.text}"
    rounded: "{rounded.tile}"
    padding: "12dp 14dp"
    height: "64dp"
  suggestion-tile-pressed:
    backgroundColor: "{colors.pressed}"
  composer:
    backgroundColor: "{colors.lift}"
    textColor: "{colors.text}"
    typography: "{typography.reading}"
    rounded: "{rounded.field}"
    padding: "8dp"
    height: "56dp"
  send-disc:
    backgroundColor: "{colors.accent-white}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.pill}"
    size: "40dp"
  send-disc-idle:
    backgroundColor: "{colors.pressed}"
    textColor: "{colors.faint}"
  list-row:
    textColor: "{colors.text}"
    typography: "{typography.body}"
    rounded: "{rounded.row}"
    padding: "10dp 12dp"
    height: "48dp"
  list-row-selected:
    backgroundColor: "{colors.lift}"
  list-row-pressed:
    backgroundColor: "{colors.pressed}"
  search-pill:
    backgroundColor: "{colors.lift}"
    textColor: "{colors.text}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    height: "44dp"
  bubble-user:
    backgroundColor: "{colors.accent-white}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.bubble}"
    padding: "11dp 16dp"
    width: "320dp"
  bubble-agent:
    backgroundColor: "{colors.lift}"
    textColor: "{colors.text}"
    rounded: "{rounded.bubble}"
    padding: "12dp 16dp"
  sheet:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.sheet}"
  dialog:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.dialog}"
  context-menu:
    backgroundColor: "{colors.pressed}"
    textColor: "{colors.text}"
    rounded: "{rounded.menu}"
  drawer:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    width: "min(84%, 340dp)"
  top-bar:
    textColor: "{colors.text}"
    typography: "{typography.title}"
    height: "56dp"
---

# Design System: Apel (Android)

> **Scope status.** Apel grew out of an earlier private agent client for Android: the screens below keep its look unchanged; the data now comes from an on-device agent (`AgentBackend`). Home, the side drawer, Chat (messages, working line, composer, steps, model sheet), the session information page, the media viewer and the global foundation (palette, type, motion, icons, component kit in `ui/Kit.kt`) are reviewed; the secondary pages (Files, Skills, Automations, Tools, Settings, Models, the usage page) are on the page kit in `ui/PageKit.kt`. The mascot is a customisable Blob Atelier blob, and the Look page is its in-app studio. New screens (onboarding, Look, Databases, Agent, budget, approval card, Trash) are added to this file as they are built. **Dark is the primary world; light is its inverse**, with the same roles and components (see Light theme).

## Overview

**Creative North Star: "The Category Standard, Played Straight"**

Apel looks and behaves like the Grok app, held to Grok Bot and Perplexity polish. Home is one hero, one question, one input: the blob near the middle of the screen, "What should Apel do?" (the agent's name, whatever the user called it) under it, a 2x2 grid of suggestion tiles, and the Ask pill at the foot. Chats and channels live in a side drawer that the screen slides away to reveal. Nothing on the first screen is a launcher.

The material is flat charcoal. There is one ground (#111113), one tonal step for chrome that floats (drawer, sheets, dialogs at #19191C), and one lift for everything you touch or read in a block (tiles, the input, the agent's answers, search at #232326). A pressed block steps up one more tone (#2E2E32). There are no borders and no shadows on interface surfaces; separation is by tone alone. White is the only accent and it means "yours" or "go": the send disc, your own message, a selected chip. Green, red and amber only ever report a state.

Character comes from the mascot, not from chrome. The agent is a Blob Atelier blob (Victoria Wu, MIT, ported to Compose) that the user shapes in the Look page, alive in eight moods; each channel is a small flat blob of the same shape in its own colour. Everything else stays quiet: sentence-case labels, one-line status notes, springs that start on finger-down.

**Key Characteristics:**
- Flat charcoal ground, one tonal lift, no borders, no shadows on UI.
- White as the single accent; state colours used only for state.
- Inter variable, bundled, with a display optical size for 20sp and up.
- Springs everywhere; press feedback is a sink, never a ripple.
- The user's blob for the agent, flat coloured blobs of the same shape for channels.
- Lucide-derived line icons at one stroke weight.

## Colors

A near-monochrome charcoal scale with a single white accent and three signal colours that are never decorative.

### Primary
- **Paper White** (accent-white): the one accent. The send disc when there is something to send, your own message bubble, a selected chip, the primary button, the unread dot. Text on it is the ground colour (on-accent).

### Neutral
- **Charcoal Ground** (ground): every screen's background, and the colour of the unread dot's ring so it reads clear of the icon behind it.
- **Raised Charcoal** (surface): the drawer, bottom sheets, dialogs. One step above the ground; Material's `surfaceContainer*Low/High` map here.
- **Lift Grey** (lift): what you touch or read in a block: suggestion tiles, the Ask field, the search pill, the agent's answer bubble, a selected drawer row. Also the press fill of bar buttons and quiet buttons.
- **Pressed Grey** (pressed): a pressed tile, row, chip or secondary button; the idle send disc; and the container of the chat long-press menu, which sits one step above everything in the drawer.
- **Hairline** (hairline, 8% white): only where two surfaces of the same tone meet, e.g. above the drawer's Settings footer. Never a card border.
- **Near White** (text): all primary text and icons.
- **Quiet Grey** (muted): previews, times, section labels, placeholders, status lines, secondary icons (4.5:1 on the lift).
- **Faint Grey** (faint): disabled text, the idle send arrow, the sheet grab handle, the "unknown" presence dot.
- **Scrim** (scrim, 80% black): Material's dialog and sheet scrim. The drawer uses its own 50% black scrim scaled by drawer progress.

### Signal (state only)
- **Live Green** (live): connected, working, done. Title dot, working dots (breathing), the blob's done tick.
- **Alarm Red** (danger): destructive actions (delete in dialogs and menus), the blob's default error badge. Material `error` maps here.
- **Needs-You Amber** (attention): not connected, a request waiting. The title dot, the status line under the hero, the blob's error badge on Home, the retry line in the drawer.

### Channel colours
Eight fixed hues for the flat channel clouds, assigned by index and never reshuffled (a channel keeps its colour): amber, coral, blue, green, violet, pink, teal, cocoa (`ChannelColors`). They appear only as the cloud itself, never as text, fills or backgrounds.

### Light theme
The same roles inverted, as Grok's own light look (`ui/Theme.kt`): ground #FFFFFF, surface #F7F7F8 (drawer, sheets), lift #F0F0F2 (tiles, groups, bubbles, the composer), pressed #E5E5E8, text and accent #111113 (your bubbles, the send disc, chosen chips and switches are ink with white), muted #68686D, faint #AEAEB2. Fields stay inset wells, here white set into the grey lift.
- **Signal colours are a step deeper** so they hold as small text on the lift as well as on white (4.5:1 or better): live #1E9E5E, danger #C9342A, attention #9A5700. Muted is 4.9:1 on the lift.
- **Dim layers are lighter:** the drawer dims the pushed screen with 14 % black (dark: 50 %), sheets with 16 % (dark: 32 %); grey over white reads as dirt.
- **A disabled primary button** is the pressed grey with faint text instead of the faded ink pill.
- **The blob on a light page** keeps its body; its rim becomes a faint ink line (#2A2140 at 11 %) so its dome doesn't melt into the white (and on a dark page a near-black body, ink, gets a light rim, white at 12 %, for the same reason), and his ground shadow is the original's soft ink (#2A2140 at 22 %) instead of black.
- **System, Light, Dark** (Settings, General). The window colour and the launch screen follow the phone's own setting (`values` / `values-night`, `drawable-nodpi` / `drawable-night-nodpi`); on Android 12+ the app tells the system its own choice (`applyNightMode`), so with System, Light or Dark it opens on the tone it shows. Android 10 and 11 follow the phone. The widgets stay dark.

### Named Rules
**The One Accent Rule.** White is the only accent, and it means "yours" or "go". No second brand colour, no tinted buttons.

**The Signal Rule.** Green, red and amber report state and nothing else. Amber means "this needs you" (unreachable, waiting), red means destructive or failed.

**The Tone-Not-Line Rule.** Surfaces are separated by tonal step (ground, surface, lift, pressed), never by a border or shadow. The hairline exists only where two surfaces of the same tone meet.

## Typography

**Display Font:** Inter Display: the bundled Inter variable font (`res/font/inter_variable.ttf`, SIL OFL 1.1) at optical size 28.
**Body Font:** Inter: the same file at optical size 14.

**Character:** One family doing all the work, regular and semibold with medium for rows and labels, tracking tightening as size grows. Everything is in sp so it follows the phone's font size.

### Hierarchy
- **Large Title** (600, 30sp/36sp, -0.6sp, display cut): reserved; pages use the page header's centred Title instead (see Navigation).
- **Display** (600, 22sp/28sp, -0.35sp, display cut): Home's question under the blob. Material `headlineSmall`.
- **Title** (600, 17sp/22sp, -0.2sp): top-bar titles, sheet and dialog titles. Material `titleMedium`; `titleLarge` is the same at 20sp/26sp in the display cut.
- **Row** (500, 16sp/21sp, -0.1sp): a list row's or tile's main line (rows and tiles set it at the Body size, 15sp, with Row's weight).
- **Reading** (400, 16sp/24sp): chat messages and the Ask field. Material `bodyLarge`.
- **Body** (400, 15sp/21sp): inputs, dialog text, general body. Material `bodyMedium`.
- **Preview** (400, 14sp/19sp): second lines, empty-state notes.
- **Label** (500, 13sp/18sp): section headings, chips, status lines; buttons use Label's weight at 15sp. Second lines of rows and tiles use it at 400.
- **Caption** (400, 12sp/16sp, +0.1sp): times, counts, fine print, the one-line status note.

### Named Rules
**The Optical Size Rule.** Text 20sp and up uses the display cut (opsz 28); everything smaller uses the text cut (opsz 14). Never mix in a system face.

**The Sentence Case Rule.** Section labels are sentence case, muted, Label style. Never uppercase, never tracked-out.

## Layout

Single column, phone first (no tablet or foldable layout exists). Edge-to-edge with status, navigation and IME insets padded.

- **Top bar:** 56dp, transparent, a 48dp round button at each side (4dp outer inset) and a centred title with 56dp side clearance. Where content scrolls under it, a 24dp fade from the ground (TopScrim) replaces a hard line.
- **Home:** the hero column fills the remaining height and centres the blob (210dp drawing box, shrinking to 150dp on a spring when the keyboard is up) with the Display question under it and an optional status line 8dp below. The suggestion tiles sit above the Ask field in a 2-column grid, 16dp side margins, 8dp gaps; they collapse away while typing. The Ask field has 16dp sides, 8dp above, 12dp below.
- **Drawer:** width min(84% of the screen, 340dp). Search pill (44dp) at the top, 12dp sides; list padded 8dp; rows 12dp inside. Settings sits on its own footer, under a hairline inset 20dp, with a More button (Models, usage) beside it; the list fades into the surface over the last 56dp above the footer.
- **Rhythm:** 4 / 8 / 12 / 16 / 24dp. 8dp between sibling blocks, 12dp inside rows and around drawer groups, 16dp screen margins, 24dp for hero and sheet title sides.
- **Targets:** every touch target is at least 48dp; a 36dp chip sits inside a 48dp box.

## Elevation & Depth

Flat. Interface surfaces carry no shadow and no tonal elevation (Material `tonalElevation` and `shadowElevation` are set to 0 on sheets, dialogs and menus). Depth is told by tone: ground #111113, surface for floating chrome, lift for touchable blocks, pressed for the press and the menu that sits above the drawer. The drawer adds depth by motion instead: the screen slides right and a black scrim (up to 50%) dims it in proportion to drawer progress.

The one shadow in the app belongs to the mascot: the blob draws its own soft contact shadow (black at 55%) as part of its material. It is not a UI elevation token.

### Named Rules
**The Flat Chrome Rule.** No interface surface casts a shadow. If something needs to sit above its neighbour, give it the next tone up.

## Shapes

Generous, continuous rounding; nothing square. The radius grows with the size of the block:
- Rows and the drawer's retry line (14dp), the long-press menu (16dp), suggestion tiles (18dp), chat bubbles (22dp), dialogs (24dp), sheets' top corners and the Ask field (28dp).
- Buttons, chips, the search pill, the send disc, bar buttons, the title press area and every dot are full pills or circles.
- No borders on any shape. Icons are 1.7-unit round-capped, round-joined strokes on a 24-unit grid (Lucide shapes, ISC, written out in `ui/VaultIcons.kt`), shown at 18 to 22dp.

## Components

Every component follows one rule from `ui/Kit.kt`: a block you can touch or read sits on the lift with no border, sinks a little on finger-down, has generous corners, and casts no shadow.

### Press behaviour (all touchables)
`Modifier.pressable`: the response starts on finger-down. The element scales to its pressed scale on a spring (damping 0.65, stiffness 900) and its fill crossfades to the pressed tone in 80ms. No ripple; the sink is the feedback. Scales: bar buttons 0.9, send disc 0.88, chips 0.95, title 0.96, buttons and tiles 0.97, rows 0.985. Sending fires a light haptic tap; so does opening the long-press menu.

### Buttons
- **Shape:** full pill, at least 48dp high.
- **Primary:** the white pill (accent on, on-accent text), 22dp sides, Label weight at 15sp. One per screen at most. Pressed: accent at 85%. Disabled: 40% opacity.
- **Secondary:** on the lift, text in near white (or a passed state colour), pressed to the pressed tone.
- **Quiet:** text only, 16dp sides, lift on press. Used for dialog actions; the confirm turns red when destructive, the dismiss is muted.
- **Bar button:** 48dp circle with a 22dp icon (21dp for New chat), lift on press.

### Chips
- **Style:** 36dp pill inside a 48dp target, 14dp sides, Label text, optional leading item 7dp before the text.
- **State:** unselected on the lift (pressed tone on press); selected is the white accent with ground-coloured text. Colours move on the quick spring.
- **Inside a group on the lift (`onLift`):** unselected rests one tone up, on the pressed tone (faint on press), so it still reads as a control. Chip rows wrap onto a second line at large font sizes instead of cutting a choice off.

### Cards / Containers
- **Suggestion tile:** at least 64dp, 18dp corners, lift, 14dp by 12dp padding; optional leading (a 22dp flat channel cloud, or a 20dp muted icon) 10dp before a title (15sp, 500) and one muted line (13sp, 400). At font scale above 1.2, both lines may wrap to two rather than truncate. Tiles arrive with a staggered 8dp rise.
- **Sheet (VaultSheet):** surface, 28dp top corners, a 36 by 4dp faint grab handle, optional Title 24dp in, always fully expanded.
- **Dialog (VaultDialog):** surface, 24dp corners, Title plus muted Body, quiet actions.
- **Long-press menu:** pressed tone, 16dp corners, no shadow, anchored under the row so it never covers its title; items are Body text with an 18dp muted (or red) icon.

### Inputs / Fields
- **Ask field (Composer):** a 56dp pill (28dp radius) on the lift, growing up to 6 lines. Left: a 40dp + button (muted icon). Right: a 40dp send disc, white with a ground-coloured arrow when there is something to send, the pressed tone with a faint arrow (scaled to 0.92) when not, and a stop square while the agent is busy. Placeholder "Ask the agent" in muted Reading; cursor in near white.
- **Search pill:** 44dp pill on the lift, 18dp muted search icon, Body text, a clear button once there is a query.
- **Field (VaultField):** an inset well: the ground tone (#111113) set into the lift, 48dp, 14dp corners, Body text, faint placeholder, caret in the text colour, no outline and no focus ring. Its label sits above it (Label, muted) and its help text below (Caption, muted), both on the group's text edge; both turn amber when the value can't be used. Dialogs use the same field (`DialogField`) on their surface.

### Navigation
- **Top bar (VaultTopBar):** menu left (with a 12dp unread dot ringed in the ground colour), the agent's name centred with its 7dp presence dot (green ready, breathing green working, amber problem, faint unknown; tapping it retries when there is a problem), New chat right.
- **Push drawer (PushDrawer):** the screen slides right to reveal the drawer on the surface tone. While a finger is down it tracks 1:1, with a rubber band past fully open (constant 0.15). On release it projects where the flick was heading (deceleration 0.998) and settles there on the fling spring, carrying the finger's velocity; it can be caught mid-way. Tap-to-open and tap-to-close use the settle spring with no bounce. The scrim takes a tap to close; system Back closes it; the system back-gesture edges and scrollable children keep their own drags.
- **Page header (PageHeader):** the 56dp top bar with back on the left (Files: up a folder first), the page's Title centred with an optional muted Caption line under it, an optional action on the right (Files: save the folder as a zip), and optional chips under it (the session page: Session / Model / Files).
- **Drawer rows (VaultRow):** at least 48dp, 14dp corners, transparent at rest, lift when selected, pressed tone on press; a 28dp leading slot (26dp flat channel cloud or 20dp icon), title at 15sp/500 (600 when emphasised), an optional muted second line, a trailing time, count, pin or StateDot (8dp: white unread, breathing green working).

### Page kit (`ui/PageKit.kt`)
The secondary pages are lists of groups:
- **Group label (GroupLabel):** sentence-case Label in muted above a group, 8dp in; an optional state at its end (GroupState: a 7dp dot, green when on or faint when not, and a Caption word).
- **Group (VaultGroup):** rows on the lift, 18dp corners, 4dp top and bottom padding, no outline. Rows inside are separated by an inset hairline (GroupDivider, 16dp in): two parts of one surface meeting.
- **Rows:** at least 56dp; title in Body at 500, an optional muted Label second line. GroupSwitchRow toggles on the whole row; GroupActionRow runs an action, with an optional chevron when it opens a page. Both press to the pressed tone with no scale.
- **Switch (VaultSwitch):** white track with a ground-coloured thumb when on; the pressed tone with a muted thumb when off; no outline. In a list the switches stand in one column (a row without a menu keeps the menu's 48dp slot).
- **Cards:** a scheduled job is its own group (name, schedule, the channel it runs in as a small cloud and "In …", switch; next and last run, amber when the last run failed; a two-line preview of its request); Run now stays on the card, Edit and Delete live in its ⋯ menu.
- **Edit sheet (automations):** the schedule is only checked and sent once one of its fields is changed (a rename never rewrites it); a channel made from the sheet is only kept when the job is saved into it; a paused job is asked to be made paused where it moves. A bottom sheet of inset-well fields (name, what the agent should do), chips for Every / Cron / Once with their fields and the schedule read back in words (amber when it can't be one), chips with clouds for the channel it runs in plus New channel, and the primary button ("Save and open the channel" when moving it).
- **New automation / New skill:** a + in the page header opens the same sheet, empty (New automation) or a sheet of three wells (name with its folder name read back, one line on what it does, instructions); a caption says the agent does the saving, and the primary button opens the chat with the request ("Open a new chat", or "Open “Channel”").
- **Tools page:** two groups, Built in and Connected tools. A built-in tool is an action row (title, its level as the muted second line: Allowed, Ask first, Off, or "Not available" when the provider can't run it, as web search on your own server) with a chevron; it opens a sheet with one line on what the tool does, its level chips (three; web search has only Allowed and Off, since OpenRouter runs it and the app can’t ask first), and a Caption saying what the chosen level means ("Apel asks you each time, in the chat."). A server row keeps its switch and a chevron to its tools (a sheet of switch rows under All on / All off quiet buttons). A server that can't be reached says so in amber. The header counts the tools that aren't Off.
- **List states (PageBody):** loading is the quiet typing dots; a failed load is the reason in Preview with a Try again button; empty is one muted sentence. A failed refresh over a list still shown is one amber, tappable line above it.
- **Approval card (`ui/ApprovalCard.kt`):** what a tool waits on before it runs (PLAN §7.5), the earlier request card made generic: "Wants your OK" in Body 16 semibold with a breathing amber dot, the summary in one Body line, the details (the SQL, the file path, the schedule) in 13sp monospace in a ground-tone well, then Allow and Deny (in red) stacked on the pressed tone with a hairline between them, and "Always allow this tool" as a quiet checkbox row (20dp mark, 6dp corners, chosen by tone: white with a ground-coloured check when on). Allow from a notification always asks for the fingerprint. It sits above the composer while a tool in this chat waits, 16dp in, at most a third of the screen tall (what doesn’t fit scrolls inside it). "Always allow this tool" shows only when the tool’s level is what asked (not when replacing a file you made, or remembering something after reading the web); if the app isn't on screen an "Approvals" notification ("Apel wants your OK") opens it. Unanswered after 30 minutes, the answer is no.

### Status line
One quiet centred line between messages or under the hero ("Apel is working", "Created routine · Morning brief"): Caption in muted, optional leading item 6dp before it. Amber and tappable to retry when the chats can't be loaded.

### Chat bubbles
- **Yours (UserBubble):** the inverse pill, white with ground text, 22dp corners, 16 by 11dp padding, at most 320dp wide, at the end of the line.
- **The agent's (VaultBubble):** on the lift, 22dp corners, 16 by 12dp padding (13dp in Chat), as wide as the answer needs up to the full column. Text in Reading (16/24).
- **Failed reply:** the same shape on the error container, its copy naming the problem and the way out.
- **Inside an answer:** code blocks and tables sit on the surface tone (#19191C) with 12dp corners and no outline. Table columns take their widest cell (at least 64dp), one line per cell; anything wider than the answer scrolls sideways and its right edge fades into the surface while there is more.
- **Under a message:** the time and quiet 40dp icon tools (copy, which turns into a green check for 1.5 s; edit; ask again), shown for the latest answer and for any message tapped. No timestamp under every bubble; day captions ("Today") are centred Caption.

### Working line (Chat)
While the agent works with no words yet: a 48dp-box blob (lite finish) playing the chat's state (thinking, working, talking, done, error), the status in muted Label ("Thinking…", or the agent's own step text), the tool count only when it adds something ("· 1 tool"), and a chevron. Tapping opens the steps list under it and the list glides to its end so the steps show whole. The blob appears once per screen: here, or large in an empty chat.
- **Steps from real tool calls:** one thinking step first (updated in place as reasoning streams), then each tool as `name argument` in monospace with its short result on a second line (`→ Wrote notes/x.md`, `→ You said no`, `→ 5 sources: …` for web search). What the agent says on its way to a tool ("I’ll look that up") becomes a note step, so the answer bubble holds only its final reply. Stopping keeps the steps, with "Stopped." as the reply.

### Chat composer
Home's Ask field grown up: the same 56dp pill on the lift, an attachment tray above the text when files are chosen, + opening a sheet (Photo, File, Commands, Model with the current model), and the 40dp disc: white send when there is something to send, white stop while the agent answers, the microphone on the pressed tone when the box is empty.

### List edges
Scrolling content fades into the ground (18dp under the top bar, 22dp above the composer, 56dp above the drawer's footer) instead of being cut by a line.

### The mascot (signature)
A Blob Atelier blob, drawn natively on a Canvas from Blob Atelier's shapes, finishes and spring model (`mascot/`: `BlobShapes`, `BlobEngine`, `BlobPainter`, `Blob`, `BlobFlat`), and customisable by the user (handbook §4).
- **The look:** one `BlobLook(shape, color, finish, eyes, blush)` with Blob Atelier's ids. Shapes: Dumpling (`mochi`), Bean, Pebble, Squircle, Pill, Drop, Kitty, Cloud. Finishes: Matte, Soft, Jelly, Glass, Clay, Velvet. Colours: milk, peach, butter, matcha, mint, sky, lilac, bubblegum, tangerine, cocoa, ink. Eyes: Dot or Capsule. Blush on or off.
- **Default:** Dumpling, milk (#F2ECE4), matte, dot eyes, blush on. Every new install shows it; the app icon, launch screen, notification icon and store art are made from it; "Reset to default" restores it.
- **Moods:** idle, listening, thinking, talking, working, happy, done, error, and sleeping (eyes shut, a slower breath, no blinks) at night when sleep is on. A look change squashes it once and morphs the shape over the settle spring (every shape shares one 72-point path).
- **Life:** breathes, blinks, looks around, follows a finger, squishes when poked, can be told where to look (it looks down at the Ask field while you type).
- **Sizes:** the box is the drawing area. On Home it is 210dp, 150dp with the keyboard up; 168dp on the Look page; 48dp in the working line. Thumbnails are stills. Never on every list row.
- **Reduced motion:** when the system animator scale is 0, the blob holds still in its mood's pose.
- **Finishes, natively:** gradients over the body's bounds as SVG's objectBoundingBox, blurred layers as Gaussian-profile radial gradients (discs, ellipses) or BlurMaskFilter (stroked edges). The layers that never move against the body (glass frost and edge, jelly glow and edges, clay light and shade, velvet sheen) and the grain are baked into one bitmap per look, size and shape. The grain is light-grey noise (about sRGB 187, as the original's linear-light turbulence) in soft light inside that isolated layer, so over bare body it lightens: this is what makes clay and velvet read pale on ink. Velvet's edge fuzz (a displacement filter) is not reproduced.
- **Eyes:** dot 21 by 21 or capsule 15 by 30 units, with a glint; white on dark bodies (luminance under 0.18: ink and cocoa) except on glass, dark (#1E1C24) otherwise. Blush ellipses by hue (#FF7EA6 on dark bodies, #FF5E86 on pink-red hues, #FF7A93 otherwise).
- **The saved look** is one Studio-link hash in SharedPreferences (`LookStore`), given to every Blob by `LocalBlobLook`.

### Home-screen widgets
RemoteViews can't use Compose, so the tokens are repeated in `res/values/widget.xml` and the layouts use its styles.
- **Card:** the surface tone (#19191C), no outline; the corner is the launcher's own from Android 12 (`system_app_widget_background_radius`), 24dp before.
- **Text:** Inter. The label in Title (15sp, 600), the start of the last answer in Body (13sp, muted), the source and time in Caption (12sp, 500; muted and faint). The source word (the agent's name) takes the state's tone: green while the agent works, amber when something waits on you, red on an error, grey otherwise. Rows on the tall widget are led by an 8dp drawn dot in the same tones (never a "●" character: launcher fonts draw it small and low); they are empty until Phase 7. Lines with nothing to say are hidden, not left empty, so the rest stays centred.
- **Message bar:** a 40dp pill on the lift with "Message Apel…" (the agent's name) in muted, like the app's composer; tapping it (or the card) opens the chat with the keyboard up.
- **Request buttons:** 40dp pills in place of the bar when a tool waits for your OK: Allow is the white primary with ground-coloured text, Deny is the lift with its word in red (wired in Phase 3).
- **The blob:** 84dp on the 2x2 widget, 76dp beside the text on the wide and tall ones. A flipbook of 72 frames at 90ms the launcher loops by itself, drawn offscreen by the app's own engine and painter. The engine's loop mode makes everything the blob does by itself repeat exactly every 6.48s (blinks and glances on a schedule, rhythms rounded to whole beats), so the last frame leads into the first. Frames are cropped to the body (250 of the painter's 320 units) and sized to stay under the launcher's bitmap ceiling; the listening halo is drawn at 0.8 so it fits the crop.
- **Sizes:** small below 200dp wide; wide from 200dp; tall (with the session list) from 250dp high, read as the portrait height the launcher reports (`widget/WidgetSize.kt`).
- **Picker:** both widgets have a preview layout (Android 12+) with sample content, so the picker shows the real card.

### Look page (`ui/LookScreen.kt`)
Settings → Agent → Look (the row's second line names shape, colour and finish), and "Change look" on onboarding's last step, which opens the page over the welcome. Header "Look" with the agent's name as caption and a ⋯ menu holding Reset to default. Under it the live 168dp blob, a scrolling row of mood chips to preview each mood ("Preview: Thinking"), and a secondary button Randomize with the Lucide dices icon (the Studio's taste rules; the blob is poked as it changes). Then groups: **Shape** (a 4 by 2 grid of tiles) and **Finish** (3 by 2), each tile on the lift with a still thumbnail of the current look in that option and a Caption label; the chosen tile sits on the pressed tone with its label in text colour and an 18dp accent check disc (white on dark, ink on light) in its top-right corner. **Colour**: the eleven colours as 36dp discs in 48dp targets on a lift group, each with the hairline, or a faint rim when the colour is within a step of the lift's tone (ink on dark, milk on light), so no disc vanishes; the chosen one is ringed 2dp in the text colour with a 3dp gap, the one ring in the app. **Face**: Eyes as two chips and Blush as a switch row. Every change is saved at once, the blob squashes and morphs, and the widget is redrawn in the background. Finish tiles show a larger thumbnail (64dp body) than shape tiles (44dp) so the material reads. Tiles, swatches and the Eyes chips read "Shape: Bean, selected"; the widget is redrawn once the choices settle (400ms).

### App icon, notification icon, launch screen
- **Always the default look** (Dumpling, milk, matte, dot eyes, blush), whatever the user picks.
- **Launcher icon:** adaptive. The foreground is the blob drawn by the app's painter (idle, still, eyes straight ahead) at every density by the debug `IconExport`, its body 50dp of the 108dp canvas; the background is the charcoal with a soft radial lift (#2A2A2E to #111113) behind it. The monochrome layer for themed icons is its silhouette with the eyes cut out, written by the same `IconExport` from the outline.
- **Notification icon:** the same silhouette across 22 of 24 units, its dot eyes enlarged 1.4x, white; the system tints it.
- **Launch screen (Android 12+):** The blob alone on the ground tone, from a 960px export, then the app fades in on the same #111113. The window colour is #111113 in both system themes until the light theme exists.

### Channel blob (BlobFlat)
A channel's avatar: the user's chosen mascot shape as a flat silhouette in the channel's colour (its own, or one of the eight you pick in its dialog), filling its box edge to edge so it aligns with text and icons, with still eyes enlarged 1.35x so they read at avatar size. Eyes are dark (#1E1C24) on light hues and white on dark ones. Sizes in use: 14dp (the "In …" line of an automation), 16dp (channel chips), 22dp (tiles), 24dp (colour swatches), 26dp (drawer rows), 28dp (channel dialog).
- **Colour swatches:** the eight colours as 24dp clouds in 48dp targets, four to a row; the chosen one sits on a pressed-tone disc (chosen by tone, never by outline). A channel you made has Delete channel instead of Reset.
- **New channel:** a muted + at the end of the drawer's "Channels" heading (a 48dp target lined up with the rows' status dots), not a row of its own; its dialog is the channel dialog with Create, starting on the first colour no channel wears.

### File viewer (`ui/FileViewer.kt`)
Full screen on the ground, with the page header's layout: Close on the left, the file's name and kind ("Markdown · 405 B") in the middle, the download on the right where the Files page keeps its own. The download crossfades to a green check for a moment once saved, and a note on the pressed tone opens under the header, moving the text down rather than covering it ("Saved in Downloads/Apel", Open). Markdown in Reading size, prose in Reading, data (CSV, JSON, calendars…) and code in 13sp monospace that scrolls sideways, labelled Data or Code; selectable. RTF and other markup formats are saved and opened elsewhere. The picture viewer carries the same download on a dark disc opposite Close.

### Rise (OpenRouter page)
The numbers arrive in order: each card has its own clock, started at once for the cards on screen and as you scroll for the rest, and each card, figure and row rises 16dp (10–12dp for rows) and fades in over 520ms on the decelerate curve, top to bottom and left to right; the thirty bars grow from the baseline 16ms apart; a model's share bar slides out after its row. Only the draw phase reads the clock.

### Motion tokens
- **settle** (damping 1, response 0.35s, stiffness 322): the default; panels, sheets, tap-opened drawer, the blob resize.
- **fling** (damping 0.85, response 0.3s, stiffness 438): after a drag release; the only spring allowed to overshoot.
- **quick** (damping 1, response 0.25s, stiffness 632): chips, dots, the send disc.
- **Entrances:** list rows and tiles fade and slide in over 460ms on the decelerate curve, staggered 40ms per item up to 8 items, once per key.

## Do's and Don'ts

### Do:
- **Do** put every touchable or readable block on the lift (#232326) with no border, and press it to #2E2E32.
- **Do** put floating chrome (drawer, sheets, dialogs) on the surface tone #19191C, and anything that must sit above the drawer (the long-press menu) on #2E2E32.
- **Do** keep white for "yours" and "go": the send disc, your bubble, a selected chip, the one primary button.
- **Do** use the settle spring for anything a tap moved and the fling spring only for what a finger threw, carrying its release velocity.
- **Do** start press feedback on finger-down with `pressable`: a sink and a tone change, no ripple.
- **Do** draw icons from `VaultIcons` (1.7 stroke, 24 grid) and tint them text or muted.
- **Do** use the display cut of Inter for 20sp and up, and keep every size in sp.
- **Do** let status speak in one quiet Caption line, amber when it needs you.

### Don't:
- **Don't** add borders, outlines or shadows to interface surfaces; take the next tone up instead.
- **Don't** introduce a second accent or use green, red or amber as decoration.
- **Don't** use channel colours for anything but the flat cloud.
- **Don't** set section labels in uppercase or tracked-out caps.
- **Don't** use Material ripples, tonal elevation or a system font.
- **Don't** put the blob on every list row; the blob is the hero, flat clouds are the channels.
