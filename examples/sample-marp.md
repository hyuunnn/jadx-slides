---
marp: true
theme: default
paginate: true
---

# jadx-slides

Marp slides inside a jadx-gui tab.

---

## Why

- No more Alt-Tab between jadx and Keynote during a live demo
- Slides open in a jadx tab — or press **Dock** to pin them beside the code
- Edit the deck in your editor; jadx-slides re-renders and reloads on save

---

## How to use

1. Author Markdown with the usual Marp directives
2. In jadx: `Ctrl+Shift+M` → pick this `.md`
3. It renders with the real marp CLI in an embedded Chromium view;
   just save in your editor and the slide reloads in place

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

## Bespoke.js shortcuts

| Key            | Action            |
|----------------|-------------------|
| `→` / PgDown   | Next slide        |
| `←` / PgUp     | Previous slide    |
| `Home` / `End` | First / last      |
| `f`            | Fullscreen toggle |

---

## Thanks

That's it. Have fun reversing.
