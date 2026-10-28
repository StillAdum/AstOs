# Playback + AI Tools preview mod 0.1

**Test first; not merged into the ROM source.** This preview materializes a patch in a temporary build directory. The production `astshell/`, `astlink/`, `companion/` and `spicetify/` source files on main remain unchanged. Only main is used; no extra branch.

This is a native app-update mod, not a JavaScript-only dashboard card. The `.astpl` carries an updated AstShell APK because native provider tool loops, durable alarm receivers and structured AstLink results cannot be safely retrofitted with a passive voice-observer script. It does NOT flash a ROM, touch system partitions, reset setup, or enable navigation buttons. Existing app data and provider keys are retained. A separate rollback `.astpl` restores the baseline app code.

## Install all three matching preview parts

1. Quit the old Companion **from its tray menu**. Launch the preview Windows Companion from this prerelease (portable or installer). It uses the existing pairing data. Do not run two companions.
2. Replace the old Spicetify `astos.js` with this prerelease's `astos.js` in the Extensions directory (`spicetify path` shows configuration paths). Keep only ONE AstOS bridge extension. Run `spicetify config extensions astos.js` if not already enabled, then `spicetify apply`. Restart Spotify. Reuse the existing bridge token, or pair through Spotify's **AstOS: pair PC companion** menu.
3. Connect the preview Companion to the tablet over Wi-Fi. In **Mods**, import `AstOS-Playback-Tools-preview.astpl`, enable/trust it, then tap its dashboard card to install the app update. Confirm the system installer if asked, and reopen Home. Native installer UI may be shown during this experimental update only.
4. AstShell reports version `3.4-tools-preview.1`; Companion reports `1.4.1-preview.1`. Choose a tool-capable model in AI providers. Normal song commands and the small keyboard prompt remain usable. Reminders, alarms, timers and natural-language AI requests use native tools.

The installer uses a precompiled DEX helper (not runtime JVM bytecode generation, which is incompatible with Dalvik). The mod checks the currently installed AstShell signing certificate before submitting an update. This ROM's standard public cm-14.1 development platform certificate is used; its key and certificate downloads are hash-pinned. If your installed ROM uses another certificate, it stops safely. Do NOT uninstall AstShell or remove its data to bypass a signature mismatch. Report the mismatch instead.

No USB is required for this route once the existing Wi-Fi pairing works. Keep the tablet/PC on a trusted LAN. Importing a large native-update mod can take longer than importing a small JS plugin.

## What changed

- The 4-second client timeout versus 25-second server long-poll mismatch is removed. Event requests have a 30-second deadline; the preview server sends a one-second heartbeat so background command/state work is network-driven rather than dependent solely on throttled UI timers.
- Ordinary playback never automatically pauses because the tablet link hiccups. Disconnect-pausing is **off by default**. The Spotify menu has an explicit opt-in switch; only opt-in + a sticky command may pause after sustained disconnect. Manual pauses are not automatically undone.
- Native progress samples are timestamped, interpolated on a monotonic clock, clamped to song duration, and reset on track/seek/pause changes. Missing updates are marked estimated (`~` on tablet time), not passed off as fresh telemetry. Song-change/pause events still publish immediately. Truly quitting Spotify's process cannot provide live playback or telemetry; keep it running in the background, not necessarily with its window visible.
- Spotify search returns up to five real title/artist/URI results. Track links and exact URIs can play without a search call. `Player.playUri` has a documented native `Platform.PlayerAPI.play` fallback. API/search errors are returned instead of false success. Spotify account/region/ad restrictions are not bypassed.
- Structured results now travel back through extension → companion → tablet → AI. Duplicate command IDs are not replayed and expired queued commands are rejected.

## AI tools

Native tool calling is implemented for OpenAI-compatible providers (including Groq, OpenRouter and Mistral), Claude's tool-use protocol, and Gemini function calls. The selected model must support tools. No model-generated JSON in ordinary text is executed.

- `search_songs`, `play_song`, `control_playback`, `get_playback` — connected PC Spotify.
- `add_reminder`, `set_alarm`, `set_timer` — persistent one-shot tablet schedules.
- `list_schedules`, `cancel_schedule`, `dismiss_alerts`.

Try “search Spotify for songs by Daft Punk”, “play [song] on my PC”, “pause the music”, “set a timer for 90 seconds”, or “remind me tomorrow at 9 AM to drink water”. Ambiguous times should prompt a question. Absolute times require an explicit timezone offset. Scheduled items survive app recreation/reboot and fire with an AstOS-styled dismiss screen and alarm/notification audio; they cannot ring while powered off. Schedules are capped at 64 pending items; timers at seven days. Only one alert sound plays at once; overlapping alerts replace the visible alert. Dismiss through the screen or ask Ast to dismiss alerts.

Tools are allowlisted and validate arguments. A bounded native loop returns real tool results to the model, preserves Gemini signatures, deduplicates repeated call IDs, and never changes providers after an action has been attempted (avoids repeated playback/duplicate alarms). Music and scheduling metadata are sent to the AI provider you selected to complete the requested tool loop. No AI shell execution was added.

## Five acceptance checks before ROM promotion

1. Search by title+artist and play a returned URI. Also test a Spotify track link. Confirm the correct song starts, not only a success message.
2. Leave Spotify minimized/backgrounded for 15 minutes. Confirm the tablet timeline advances, track changes refresh and pause/seek resynchronize. A fully quit Spotify process is a different state.
3. Briefly interrupt tablet Wi-Fi and restore it. With disconnect-pausing OFF, music must not pause. Confirm manual pause still works.
4. Through mic and keyboard, test search/play/next/previous/pause/resume/volume; inspect a real provider failure. No false claim of success.
5. Set a short timer, reminder and alarm; test dismissal, cancellation and reboot restoration. Confirm there are still no system navigation buttons.

Automated regression tests and clean release compilation are gates, **not proof of Windows Spotify/tablet hardware success**. This environment has neither the user's signed-in Spotify session nor their tablet. Report which acceptance check fails, the displayed error, app versions and provider/model (never the API key). Once the user confirms this mod works, apply these exact patches to the production ROM source and build the ROM. Do not promote automatically.

## Roll back without wiping settings

Import/enable `AstOS-Playback-Tools-rollback.astpl`, tap its dashboard card, and confirm the update. Both APKs deliberately retain baseline versionCode 8 so rollback is a same-version-code replacement, not a forced downgrade. Disable/remove both installer mods afterward. Restore the previous Companion and the production `spicetify/astos.js` from main; run `spicetify apply` and restart Spotify. Cancel pending preview schedules before rollback (the baseline does not contain the preview receivers).

## Primary references

- Spicetify Player and native play: https://spicetify.app/docs/development/api-wrapper/methods/player and https://spicetify.app/docs/development/api-wrapper/methods/platform (checked through Context7).
- Authenticated Cosmos requests: https://spicetify.app/docs/development/api-wrapper/methods/cosmos-async
- OpenAI Chat Completions tool/result correlation: https://platform.openai.com/docs/guides/function-calling
- Claude tool-use schema: https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use
- Gemini function tools: https://github.com/google-gemini/api-examples (checked through Context7).
- API25 PackageInstaller/AlarmManager: https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/core/java/android/content/pm/PackageInstaller.java and https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/core/java/android/app/AlarmManager.java
