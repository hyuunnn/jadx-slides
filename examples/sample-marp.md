---
marp: true
theme: gaia
paginate: true
---

# My APK analysis

A Marp deck presented inside **jadx-gui**.

Write `@` references anywhere — they become clickable links that jump
the decompiled code view on the left.

---

## Entry point

The app boots in @com.example.app.MainActivity — check
@com.example.app.MainActivity.onCreate:12 for the interesting part.

Smali descriptors work too: @Lcom/example/app/Crypto;->encrypt(Ljava/lang/String;)V

---

## Notes

- Bare short names resolve as well: @MainActivity
- Renamed classes match on both the original and the new name
- Tokens in `inline code` and fenced blocks are left alone:

```text
@this.stays.plain
```
