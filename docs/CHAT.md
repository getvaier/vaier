# Chat

Back to [README](../README.md).

A pane in the Explorer — ask about your fleet in plain sentences and the answer streams back, built only from Vaier's own facts. Paste your own Anthropic API key under **Settings** and **Chat** appears in the topbar's **Vaier** menu; without one, the pane explains itself instead. Reading never carries a key, a credential or a token, and nothing changes without your click.

---

## Marvin

It's Marvin who answers — the Paranoid Android, brought in at the operator's request — gloomy, weary and dryly sardonic about it, but never wrong: the complaint is a garnish on an accurate answer, never aimed at you and never a reason to refuse one.

## What it reads

Which machines are connected, who's waiting to join, published services and their liveness, backups and how the last run went, disk standings, containers wanting an update, and who's blocked at the edge. Each is one read of the fleet — a **Chat tool** — and every answer is built from those reads and nothing else.

## A read-only command on a machine

For the one thing none of those facts already answers — "are there OS updates available for Colina 27?", a log, a process list, a file's contents — Chat can run a single **read-only command** on a machine over SSH, as Vaier's own login user there and without sudo. Only commands that look (`ls`, `cat`, `df`, `apt list --upgradable`, `docker ps`, `journalctl`, `wg show`, and the like) are allowed; chaining and redirects are refused, and anything naming where a secret lives is refused too.

## Acting, with your click

Chat can also *act*, but never on its own say-so. It can propose letting a waiting phone in or refusing it, backing up a machine now, updating a container to a newer image, lifting a block, or trusting an address — each a verb the Explorer already has a button for, and none of them a restart, since Vaier has no restart button at all. Proposing one puts a one-sentence **Confirmation** card in the pane with a button that says exactly what it will do; nothing runs until you click it, and the card is gone in ten minutes either way.

## Files, as a bundle

Say "give me the pictures from last year today in a zip" and Chat finds them itself, then puts a download card in the pane: "pictures-2025-09-10.zip is ready: 34 files, 210 MB." Click it and the zip streams straight down; nothing is written anywhere first, and the link is good for an hour. For a large **bundle** (more than 50 files or 100 MB) Marvin asks first whether you want the card now or a link by email to fetch when it suits you — say yes and it mails you a link good for a day instead of an hour, since a zip that size can't be attached.

## Memory

Chat keeps a **memory** — short facts, for the whole fleet rather than one thread, whether you said them or it found them by looking. It never treats a fact as an instruction. Nothing it kept can sit there unseen: the pane's bar carries its own **Marvin** menu, and "What Marvin remembers (N)" opens a dialog listing every memory with a remove button.

## Spend

The same menu's "Spend this month, $x.xx" opens a dialog with the month's figure, how many answers it covers, and the token counts behind it — in, out, written to cache, read from cache. It is Vaier's own count at Anthropic's list price under your own key; the invoice is the one that's actually owed if they ever disagree.

## The conversation

The conversation is Vaier's to remember, not the browser's: it's kept per operator, so it's still there next time you sign in and a follow-up like "and Colina?" still knows what you meant. **Start over** in the pane forgets it for good. A long thread doesn't grow forever either: once it passes 40 turns, the older ones are shortened into a brief summary and the pane shows "Earlier, in brief: …" ahead of the last dozen turns kept in full.
