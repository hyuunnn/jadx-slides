---
theme: default
transition: slide-left
title: jadx-slides Slidev sample
---

# jadx-slides × Slidev

Slidev decks inside a jadx-gui tab.

---

## Why

- No more Alt-Tab between jadx and Keynote during a live demo
- Slides open in a jadx tab — or press **Dock** to pin them beside the code
- Edit the deck in your editor; Vite HMR applies it on save — no reload

---

## How to use

1. Author Markdown with the usual Slidev syntax
2. In jadx: `Ctrl+Shift+M` → pick this `.md`
3. This deck has Slidev front-matter keys (`transition:` …), so jadx-slides
   starts a local `slidev` dev server and renders it in the tab
4. Force an engine with `jadx-slides-engine: slidev` (or `marp`) in front matter

---

## Clickable jadx references

Write `@` followed by a class, member, or smali descriptor — it becomes
a link that jumps the decompiled code view:

- Class by FQN: @com.example.app.MainActivity
- A member: @com.example.app.MainActivity.onCreate
- Land on a line: @com.example.app.MainActivity:42
- Smali descriptor: @Lcom/example/app/Crypto;->encrypt(Ljava/lang/String;)V
- Bare short name: @MainActivity

Renamed classes match on both the original and the new name.
Unknown names still look like links — clicking one just reports to
the jadx log: @no.such.Name

---

## Documenting the syntax

Tokens inside `inline code` and fenced blocks stay plain, so decks can
show the grammar itself:

```text
@com.example.app.MainActivity:42   ← stays plain here
```

(Embedded decompiled code — `@name[1:8]` — is on the roadmap.)

---

## Slidev shortcuts

| Key            | Action            |
|----------------|-------------------|
| `→` / `Space`  | Next slide        |
| `←`            | Previous slide    |
| `o`            | Slide overview    |
| `f`            | Fullscreen toggle |
| `d`            | Dark mode toggle  |

---

## Thanks

That's it. Have fun reversing.
