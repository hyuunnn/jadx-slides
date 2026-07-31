# jadx-slides

Real **Marp** or **Slidev** slide decks inside a **jadx-gui tab** — the jadx
port of [ida-slides](https://github.com/hyuunnn/ida-slides).

Write `@com.example.app.MainActivity` or `@Lcom/foo/Bar;->run()V` anywhere in
the deck and it becomes a clickable link; clicking it jumps the jadx code view
to that class, member, or line. The deck renders in an embedded Chromium
(JCEF) browser inside a regular jadx tab, opened through the normal tab
flow — no changes to jadx's own layout.

Prefer slides and code visible at the same time? Two options:

- **Dock** (toolbar button): splits the main window — code tabs on the
  left, slides pinned on the right, exactly the ida-slides layout. Jumps
  move only the code side. Press **Tab** to go back to a regular tab.
  (This reparents jadx's tab area into a split pane at runtime — contained
  and fully reversible, but it does go beyond the official plugin API,
  which is why it's opt-in rather than the default.)
- **Browser**: the deck opens in the system browser and the `@` jump links
  keep working there, so a browser window next to jadx works the same way.

## Usage

1. `Ctrl+Shift+M` (or Plugins → jadx-slides → Open Slides…)
2. Pick your Markdown deck (`.md`; marp-exported `.html` also loads)

The engine is picked per deck:

- **Marp** (default): the `marp` CLI renders to HTML on every save and the
  view reloads in place, keeping the current slide.
- **Slidev**: chosen when the front matter has Slidev-specific keys
  (`transition:`, `mdc:`, `drawings:` …). A local `slidev` dev server is
  started; Vite HMR applies saves instantly.

Force an engine with `jadx-slides-engine: marp` (or `slidev`) in the front
matter. Navigate with each tool's usual keys (←/→, `f` fullscreen, Slidev's
`o` overview, …).

## Requirements

- Marp: `npm i -g @marp-team/marp-cli`
- Slidev: `npm i -g @slidev/cli @slidev/theme-default`
- CLIs are found via PATH, nvm, Homebrew, npm/pnpm/yarn global bins.
- jadx-gui 1.5.5 (the plugin compiles against its internals).

**macOS**: the embedded browser (JCEF) needs JVM flags jadx doesn't pass by
default. Launch jadx with:

```sh
JADX_GUI_OPTS="--add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED" jadx-gui
```

Without them (or if JCEF fails for any reason) the deck opens in the system
browser instead — jump links still work there. On first use JCEF downloads
~100MB of natives into `~/.cache/jadx-slides/jcef` (one time).

## `@` reference syntax

| Syntax | In a slide it becomes… |
|--------|------------------------|
| `@com.example.app.MainActivity` | a link that opens the class (original or renamed name) |
| `@com.example.app.MainActivity:42` | …positioned at line 42 of the decompiled code |
| `@com.example.app.MainActivity.onCreate` | a link to that method/field |
| `@Lcom/example/app/Crypto;->encrypt(Ljava/lang/String;)V` | smali descriptors work too |
| `@MainActivity` | bare short class names resolve as well |

Renamed items match on both the original and the alias name, so decks keep
working as you rename during analysis. Tokens inside fenced code blocks,
inline backticks, and the front matter are left alone so decks can document
the syntax itself. Unknown names are logged to the jadx log when clicked.

## Writing decks

Each engine's standard conventions apply (front matter, `---` separators,
themes, layouts). See `examples/sample-marp.md` and `examples/sample-slidev.md`.

Before a deck reaches the engine, jadx-slides preprocesses it into a hidden
`.<name>.jadx-slides.md` sibling, rewriting `@` tokens into anchors that hit
a local bridge server (`127.0.0.1`, random port); Marp additionally renders a
`.<name>.jadx-slides.html`. Both sit next to your `.md` so relative image
paths keep working, and both are removed when the deck is closed.

## Build & install

```sh
gradle shadowJar
jadx plugins --install-jar build/libs/jadx-slides-0.1.0.jar
```

## Implementation notes

- The slides tab is a custom `JNode`/`ContentPanel` opened via jadx's own
  `TabsController` — the additive pattern jadx half-sanctions (it even
  exposes `registerTabStatePersistAdapter` for custom tabs). Unknown node
  types are skipped when jadx persists open tabs, so the slides tab simply
  isn't restored on project reopen.
- Rendering is a local NanoHTTPD bridge + a JCEF browser in the tab. The
  bridge also serves `/jump`, so the same deck works identically in the
  embedded view and in an external browser (CORS is open for the Slidev
  dev-server origin).
- CefApp is created once and never disposed (CEF cannot re-init in one JVM);
  jadx re-instantiates plugins per project open, so all long-lived state
  lives in a process-wide singleton.
- kotlin-stdlib and NanoHTTPD are shadow-relocated: jadx's fat jar ships its
  own (older) kotlin-stdlib on the parent classloader.

## Roadmap

- `@name[1:8]` embeds: paste decompiled lines into the deck as code blocks
- Hover preview of decompiled code on `@` links
- "Copy @reference" context-menu action in the code view
- Deck lint (report unresolvable tokens on save)
