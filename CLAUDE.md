# CLAUDE.md — jadx-slides

jadx port of [ida-slides](https://github.com/hyuunnn/ida-slides): Marp/Slidev
decks rendered in an embedded Chromium (JCEF) inside a jadx-gui tab, with
`@` tokens that jump the decompiled code view. Full battle history (4 review
rounds, 5 native quit crashes) lives in `docs/review-log.ko.md` — read it
before "improving" anything listed under DO NOT TOUCH below.

## Build / install / verify

```sh
./gradlew shadowJar                 # the ONLY artifact; plain `jar` is disabled
jadx plugins --uninstall jadx-slides
jadx plugins --install-jar build/libs/jadx-slides-0.1.0.jar
```

No unit-test suite. Verification is headless smoke tests (see "Testing")
plus running jadx-gui. Target: brew jadx **1.5.5** (`compileOnly` pins).

## Architecture (1 minute)

- `SlidesPlugin` registers a menu action + `Ctrl+Shift+M`. State lives in
  `object Slides`, but **that object does NOT survive a project open**:
  jadx closes the plugin classloader and builds a fresh one each time
  (`JadxWrapper.open` → `close()` → `new JadxExternalPluginsLoader`,
  verified in 1.5.6 bytecode), so a new `Slides` comes with it. Only the
  CefApp and its cached natives are genuinely process-wide. Anything
  registered GLOBALLY (AWT listeners, key dispatchers) therefore has to be
  removed in `JadxPlugin.unload()` or it accumulates one set per project
  open — a docked deck keeps the stale set alive and still swallowing
  keys.
- `BridgeServer` (NanoHTTPD, 127.0.0.1:random) serves the rendered Marp
  html + assets, `/jump?t=<token>`, `/version` (poll-based live reload).
  CORS `*` only on `/jump` + `/version` (Slidev's vite origin needs it).
- `DeckPreprocess` rewrites `@` tokens at preprocess time into
  `<a onclick="fetch('http://127.0.0.1:PORT/jump?...')">` anchors, written
  to hidden siblings `.<name>.jadx-slides.md/.html` next to the deck.
- `Engines`: Marp = one-shot `marp --html` per save (WatchService+debounce);
  Slidev = dev server, port parsed from its OWN banner (ANSI-stripped).
- `JumpService` resolves FQN / smali / short names (orig + renamed alias).
  A NAMED MEMBER MUST EXIST — no falling back to the enclosing class, which
  would make a typo'd or stale `@Cls.member` look like a working jump
  via jadx-gui internals; resolution OFF the EDT, UI jump ON it.
- View: custom `SlidesNode(JNode)`/`SlidesContentPanel` opened through
  `TabsController.selectTab` (semi-sanctioned pattern; unknown node types
  are skipped by tab persistence — the tab just isn't restored). Opt-in
  **Dock** button reparents jadx's TabbedPane into a JSplitPane
  (`DockManager`); Tab reverses it.

## DO NOT TOUCH — each rule cost a native crash to learn

Quit flow of jadx: `windowClosing → cancelable save prompt → bg thread →
closeAll() (CLOSES THE PLUGIN CLASSLOADER) → dispose() → System.exit(0)`.
File→Exit menu skips windowClosing entirely; Cmd+Q is intercepted by CEF
and routed through `Slides.requestQuit`.

1. **Quit destroys NOTHING of CEF.** Detach the browser from the Swing
   hierarchy (`detachCefForQuit`) and leave browser/client/context alive
   until the process dies. Every macOS quit crash (TempWindowMac,
   util_mac::UpdateView, JCEFApplication event monitor, CefHandler
   setVisibility) was a live JCEF observer touching partially destroyed
   state. `disposeCef` exists ONLY for the mid-run createBrowser-failure
   path. `neutralizeCefShutdown()` marks CefApp TERMINATED so jcefmaven's
   shutdown hook no-ops.
2. **Detach at `windowClosing`** (before dispose). Cancel case: panel shows
   a hint; Reload reattaches the still-alive browser.
3. **No lazy class loads on the quit path.** jadx closes the plugin
   classloader mid-quit. Quit code uses named classes (`CloseTask`,
   `DetachRun`) + plain try/catch (no lambdas, no `runCatching` — its
   failure branch loads `ResultKt`). Preloads MUST be **field
   initializers** — the Kotlin compiler deletes a bare `X::class.java`
   statement as an unused pure expression (verify with
   `javap -c | grep <Class>` that the ldc survived). `Slides.init` warms
   ResultKt by actually throwing once.
4. **CefApp is built once and never disposed** (CEF cannot re-init in one
   JVM). Natives cache in `~/.cache/jadx-slides/jcef`.

## Non-obvious constraints & tradeoffs

- **Shadow relocation**: `kotlin` → `jadxslides.shadow.kotlin` (jadx's fat
  jar ships an older stdlib on the PARENT classloader — parent-first would
  break us), `fi.iki.elonen` likewise. **Never relocate `org.cef` /
  `me.friwi`** — JNI registers natives by class name.
- `compileOnly` jadx-core/jadx-gui do NOT bring slf4j/rsyntaxtextarea
  transitively — they're separate `compileOnly` entries.
- **jadx runs plugin MENU actions on its background executor, not the EDT**
  (verified against decompiled 1.5.5) — the key-binding path IS the EDT.
  Any action that touches Swing must hop to the EDT first (openAction does).
- jadx-gui internals are reachable because the plugin classloader's parent
  is the app classloader. Used: `MainWindow.getTabbedPane/getTabsController/
  getCacheObject`, `TabsController.codeJump`, `JNodeCache.makeFrom`,
  `AbstractCodeContentPanel.getCodeArea`. Compiled against 1.5.5 — a jadx
  bump needs these re-checked.
- **macOS needs** `JADX_GUI_OPTS="--add-opens=java.desktop/sun.awt=ALL-UNNAMED
  --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED
  --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"` for JCEF.
  `CefHolder.macOpensMissing()` checks first and falls back to the system
  browser (jump links work identically there — the bridge is the feature,
  the embedded view is presentation).
- **Rejected alternatives** (don't re-explore): JavaFX WebView (absent from
  jadx's JRE, old WebKit breaks Slidev), webview_java (Swing embedding
  crashes on macOS ARM — github.com/webview/webview_java/issues/37),
  injecting a pane into the per-class Code/Smali/Simple/Fallback bottom
  tabs (per-class panels × single-parent Swing × 1.5.6-only lazy split =
  strictly worse than DockManager), JxBrowser (commercial).
- **Keyboard**: macOS delivers keys to BOTH the native browser and the AWT
  focus owner. A global KeyEventDispatcher swallows nav keys while
  `browserHasKeyboard`. Set by: a real pointer-down inside the deck page
  (script injected on every load via CefLoadHandler pings the bridge's
  `/kbd`, which also parks the AWT focus), or WINDOW_ACTIVATED with the
  pointer over the deck. Cleared by: a FOCUS_GAINED whose
  `FocusEvent.getCause()` is NOT `ACTIVATION`, or an AWT MOUSE_PRESSED on a
  non-browser component (a click on the component that already holds the
  AWT focus fires no focus event at all). **App activation must never CLEAR
  the flag** — macOS restores the native first responder with the window,
  so the browser keeps reading keys; clearing made both sides move at once
  after Cmd+Tab. And the click that re-activates the app is swallowed by
  macOS: no `/kbd` ping follows it and the native first responder does not
  move, so activation-over-deck both sets the flag and calls
  `cefBrowser.setFocus(true)` (queued behind the activation's own focus
  restore) — without it the deck stayed dead until a second click. All
  log-verified with an instrumented build; `cause=ACTIVATION` vs
  `cause=MOUSE_EVENT` is what makes window restore distinguishable from a
  user click without any timing heuristic. **Never use CEF focus callbacks as this signal**:
  CefClient echoes `setFocus(true)` on its own and macOS re-fires
  `onGotFocus` for it in an endless loop (observed live: a continuous
  callback stream while the deck is focused) that outlasts any debounce and
  kept re-arming the flag after the user clicked back into the code area —
  Dock-only symptom, Tab mode hides the deck and masks it. Calling
  `setFocus` inside a callback additionally recursed to a
  StackOverflowError, and `onSetFocus` is never consulted for the echo, so
  cancelling there does nothing. Guards: keys are swallowed ONLY while the
  main frame is the focused window — jadx's search/usage/log windows are
  JFrames and every dialog gains focus with `cause=ACTIVATION`, which the
  clearer ignores, so without that test the guard ate arrows aimed at
  them and moved the slides instead (a modality check is not enough; it
  misses the JFrame ones). `deckPointerDown` applies the same test before
  parking the AWT focus. Open menus and a non-showing deck are never
  swallowed either, and after an `@` jump the code area legitimately owns
  the keys. Both listeners are held in fields and removed in `unload()`
  (see the classloader note above).
- **Slidev**: Node ≥17 binds "localhost" to ::1 only → probe BOTH stacks;
  trust the banner's port (vite silently auto-increments on conflict).
  Child processes are killed as a TREE (Windows `.cmd` shim would orphan
  node otherwise); `.cmd`/`.bat` must be spawned via `cmd.exe /c`.
- **Sessions**: reopening the SAME deck derives identical sibling paths —
  bridge handover is owner-identity-based (`publishDeck`/`clearDeck`) and
  file deletes check for a successor (`deleteSiblingsUnlessReused`).
  Publishing happens on the EDT under the `openGen` generation check;
  `pendingSession` covers quit-during-open.
- Harmless macOS terminal noise (`Exception in thread "AppKit Thread"`,
  signature -67030) is native-level and cannot be suppressed from the
  plugin — documented in the READMEs; don't chase it.

## Testing (headless, no GUI)

Scratch harness pattern used throughout (see git history / review log):
- ServiceLoader smoke: run a main with
  `-cp jadx-1.5.5-all.jar:build/libs/jadx-slides-0.1.0.jar`, iterate
  `ServiceLoader.load(JadxPlugin.class)` — plugin must appear.
- Preprocessor: call `DeckPreprocess.INSTANCE.rewrite(md, port)` from Java;
  regression cases: token in backticks/fences stays plain, `` `<style>` ``
  *mention* must NOT latch raw-HTML mode, double-backtick spans
  (``` ``@x`` ```) stay plain, a line-leading ``` inside `<script>` must
  NOT open a phantom fence (fence and raw-HTML state machines are
  mutually exclusive, raw HTML first), `assets/@logo.png` untouched,
  email `@` untouched, trailing-dot FQN not swallowed.
- Slidev E2E: call `Engines.INSTANCE.startSlidev(deck)` from Java (import
  `jadxslides.shadow.kotlin.Pair` — relocation!), curl the returned URL
  (Java HttpClient hangs on ::1 — use curl), then `stop()` and
  `pgrep slidev` to confirm the tree died.
- Quit-path changes: additionally `javap -c` the built classes to confirm
  preload ldc instructions survived (see DO NOT TOUCH #3).

## Scope policy

The user builds this for their own presentations and explicitly prefers
minimal scope: don't add features a human can do by eye (deck lint was
cut for this reason), don't apply cosmetic refactors proactively. Roadmap
items the user has kept: `@name[1:8]` embeds, hover preview of decompiled
code, "Copy @reference" context action. Windows/Linux are code-reviewed
but never run — test there before claiming support.
