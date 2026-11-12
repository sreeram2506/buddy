# Buddy: code review and architecture

Measured on the code as of this review: about 5,700 lines of Kotlin in 43 files, 41 unit tests, lint clean (0 errors).

## 1. Verdict

The behaviour is good and the safety design (approval before anything is sent or paid) is the strongest part.
The structure is the weak part. Most code was written to make a feature work quickly, so it relies on **timing and wording** instead of
**checking facts**. Three of the bugs you hit were the same mistake: Buddy said "done" without looking at whether it was done
(the call that never started, the message that was never sent, who-said-what reversed).

| Area | Grade | Why |
|---|---|---|
| Safety / approvals (`SendGuard`, `Turns`) | B+ | Clear rules, tested. Approval still depends on matching words like "yes" in a transcript. |
| Voice (wake word, live session) | B | Measured and tuned with real data. Large functions; state spread over flags. |
| Messaging / app control | C → B | Was "tap, sleep, assume". New `msg/` package verifies from the screen (see 3). |
| Data and settings | C | 45 places open the same SharedPreferences with string keys; JSON files edited by hand. |
| Structure | C | `PhoneActions.kt` is 978 lines and 69 functions; 28 global `object` singletons; no dependency injection. |
| Tests | B- | Pure logic is tested (41 tests). Android-dependent code has none. |

## 2. What is already good (keep it)
- Approval design: nothing is sent, called or paid without a fresh "yes", enforced in code, not just in the AI prompt.
- Offline wake word with a sound gate (about 1% CPU while listening).
- Honest error handling in the AI layer (fallbacks, retries, plain-language errors).
- Pure-logic parts are separate and tested (`WakePhrases`, `SendGuard`, `MemoryStore.isSensitive`).

## 3. False assumptions found

| # | Assumption in the code | What goes wrong | Status |
|---|---|---|---|
| 1 | "If I tapped Send, it was sent." `confirmWhatsapp` returned "Sent" right after `clickLabeled("Send")`. | Message not sent but Buddy says it was. | **Fixed**: result is judged from the screen (`SendVerifier`). |
| 2 | "A call started if I launched ACTION_CALL." Android ignores that from the background. | "Calling…" with no call. | **Fixed**: `TelecomManager.placeCall` + check the call state. |
| 3 | "The Send button is called Send." Label match, English only. | Fails on Hindi/Telugu phones or after a WhatsApp update. | **Fixed**: pressed by resource id `com.whatsapp:id/send`, with multi-language label fallback. |
| 4 | "WhatsApp tells me who sent a message." It does not. | Buddy read your messages as theirs. | **Fixed**: side read from bubble position (`ChatParser.side`), tested on real layout numbers. |
| 5 | "Sleep 600 ms and the screen will be ready." 34 `Thread.sleep` calls, 14 in `PhoneActions`. | Too slow on a fast phone, too fast on a slow one. | **Partly fixed**: messaging uses `Waiter.until` (returns as soon as true). Rest still sleeps. |
| 6 | "A 10-digit number is an Indian number." `normalizeNumber`. | Wrong for other countries, numbers with a leading 0, or +91 already present. | **Fixed**: libphonenumber (`PhoneNumbers`). |
| 7 | "The contact name I heard is spelled like the contact." `LIKE %name%`. | "Seva" finds nobody when the contact is "Siva". | **Fixed**: sound-alike match (`NameMatcher`), asks when several match. |
| 8 | "A yes in the transcript means the user approved." `Turns.affirmative()` regex. | The transcript can be wrong, and a stray "yes" from the room counts. | **Open**: see 4.5. |
| 9 | "Only one thing runs at a time." Global singletons with `@Volatile` flags. | Races between wake word, live session, call handling. | **Open**: see 4.4. |
| 10 | "The preference key is spelled right." 45 `getSharedPreferences("jarvis")` with string keys. | A typo silently reads the default. | **Open**: see 4.3. |
| 11 | "Writing the JSON file is safe." Away state and logs are read-modify-write. | A crash mid-write loses data. | **Open**: see 4.3. |
| 12 | "A failure can be ignored." 14 empty `catch (e: Exception) { }`. | Problems are invisible. | **Open**: log or report them. |
| 13 | Tool results are English strings that the model interprets. | The model can ignore or misread them. | **Improved** for sending (`SendOutcome.forModel`); other tools still free text. |

## 4. Better architecture

### 4.1 Layers
```
Voice (wake word, live audio)  ->  Brain (model + tool choice)
                                        |
                              Tool registry (one place per tool)
                                        |
                    Actions: each returns an Outcome (Verified / Unverified / Failed)
                          |                    |                    |
                 AppAdapters (WhatsApp,   Telephony (calls,   Data (settings, memory,
                  Messages, Spotify...)    SMS, Telecom)       activity log)
                          |
              Screen model (ScreenNode -> ChatScreen)   <- pure Kotlin, unit-tested
                          |
                 AccessibilityService (the only code that touches Android nodes)
```
Rule: anything that decides something (who sent it, whether it was delivered, which contact) is pure Kotlin on plain data, so it is
tested on a laptop. Android classes appear only at the edges.

### 4.2 Sending a WhatsApp message (implemented in `msg/`)
Ladder, most reliable first:
1. **Notification reply** (`RemoteInput`) when a recent notification from that person exists: no screen needed, no UI, supported by Android.
2. **Deep link + accessibility**: `wa.me/<number>?text=` opens the chat with the text typed, then:
   `Waiter` until the chat is open and the text is in the typing box, press Send by id, `Waiter` until the message appears,
   then `SendVerifier.judge(before, after, text)` returns one of **Delivered / Pending / NoTick / NotInChat / CouldNotPress**.
3. **Hand over**: tell the user to tap Send; never claim it was sent.

The model is told only the verified outcome (`SendOutcome.forModel`). There is no unofficial WhatsApp API for personal accounts
(WhatsApp Business Cloud API is for businesses, with a separate number and templates), so the screen is the only way to confirm.

### 4.3 App handling: one adapter per app
```kotlin
interface AppAdapter { val pkg: String
  fun parse(nodes: List<ScreenNode>, ...): ChatScreen      // who said what, ticks
  fun sendButton(): Matcher                                // resource id first, labels second
  fun openChat(contact: Contact, text: String): Intent }  // deep link
```
Adapters: `WhatsAppAdapter` (done as `ChatParser`), then Google Messages, Telegram, Instagram. Adding an app means one small file and one
fixture-based test, not edits across `PhoneActions`. When an app updates and breaks, the failing test shows exactly what changed.

### 4.4 State and concurrency
- Replace raw `Thread {}` (39 uses) and `sleep` with **Kotlin coroutines**: cancellable, structured, no leaked threads.
- Replace the flags in `Session`/`WakeWordService.companion` with one `AssistantState` (sealed: Idle, WakeListening, Live, OnCall, Away…)
  held in a `StateFlow`. The wake listener, UI orb and call handler all read the same state, so "Buddy is on a call but also listening" cannot happen.

### 4.5 Approvals
Keep `SendGuard`, but give each pending action an id and a **tap-to-confirm** path (a notification action or button), so approval does not
depend only on a speech transcript. Voice "yes" stays, with the same freshness rules.

### 4.6 Data
- **Typed settings** class (`Settings.wake`, `Settings.awayDelay`…) over one DataStore: no string keys scattered through the code.
- **Room (SQLite) with FTS5** for Memory, activity log and Away log: atomic writes, indexed search over 100k messages in milliseconds,
  and real stemming/ranking instead of loading a file into memory.
- **kotlinx.serialization** instead of hand-built `org.json` (17 files): compile-time checked.

### 4.7 Tool registry
Today every tool is declared in one list and executed in one 200-line `when` (`decl` + `execute`). Make each tool a small class
`Tool(name, description, params, run(): Outcome)` registered in a list: declarations are generated, tools are individually testable, and the
model's tool list is built from the registry.

## 5. Open-source packages

| Package | Use | Decision |
|---|---|---|
| **libphonenumber** | Number parsing | **Added.** Adds about 7 MB to the APK (metadata for every country). |
| **kotlinx-coroutines** | Threads, waiting, cancellation | Adopt next (small, standard). |
| **Room + FTS5** | Memory/log storage and search | Adopt next. |
| **kotlinx-serialization** | JSON | Adopt gradually. |
| **Jetpack DataStore** | Typed settings | Adopt with typed settings. |
| **Koin** (or Hilt) | Replace global singletons | Later; do after coroutines. |
| **Timber** | Logging with levels | Small win; replace `Log.d("Jarvis"…)`. |
| **MockK + Robolectric + Turbine** | Test Android-dependent code and flows | Adopt with coroutines. |
| **Vosk** (already), **OkHttp** (already) | Offline speech, networking | Keep. |
| Not recommended | Any "WhatsApp automation" library for personal accounts | These use unofficial APIs, risk getting the number banned, and break often. |

## 6. Roadmap
1. (done) Verified sending, call placing, side detection, name and number matching: `msg/`, 41 tests.
2. Coroutines + remove fixed sleeps (about 1 day). Biggest speed and reliability win.
3. Split `PhoneActions` into a tool registry with one file per area (about 1 day, mechanical).
4. Room/FTS for Memory and logs; typed Settings (about 1 day).
5. `AssistantState` for wake/live/call (about half a day, then delete the flag soup).
6. Adapters for Google Messages and Telegram (about half a day each, with fixtures).
7. Instrumented tests on a phone for the accessibility paths (about 1 day).

## 7. Not yet verified on the phone
The new send path, side tags and name matching are covered by unit tests built from a real WhatsApp screen layout, but they have not been
run on the phone since this change. Next phone session: send a message to yourself, ask Buddy to read a chat, and say a misspelled contact name.
