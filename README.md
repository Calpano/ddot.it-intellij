# ddot.it — IntelliJ IDEA support

Authoring support for **[ddot.it](https://ddot.it)**, a minimal text format for typed knowledge graphs. Each line is a triple — `subject ..predicate.. object` — written with double-dot separators.

## Facts
License: MIT

### Meta
```ddot.it
ddot.it/intellij ..has type.. IntelliJ plugin
..syntax.. ddot.it
..provides.. syntax highlighting
..provides.. smart autocompletion
..provides.. code folding
..provides.. structure view of all subjects

ddot.it .. url .. https://ddot.it
```


## What is ddot.it?

[ddot.it](https://ddot.it) is a line-oriented graph notation built around two separators:

- `..` — typed link: `subject ..predicate.. object`
- `....` — simple (untyped) link: `subject .... object`

Continuations and metadata extend a triple without repeating the subject:

```ddot
Project Eagle ..started in.. 2024
..doc site.. example.com/docbase/8dcjsid

John Doe ..leads.. Project Eagle ,, ..since.. 2025

Project Eagle .... Moonshot

Dirk Hagemann ..works at.. SAP ,,
..year.. 2010
..fictive.. yes
,,
```

Lines starting with `..` continue the previous subject. `,,` opens metadata; a closing `,,` ends a multi-line metadata block.

For the full format spec and the canonical event JSON shape, see the [ddot.it Developer Guide → Events](https://ddot.it/developer-guide.html#events).

## What this plugin does

- **Syntax highlighting** for `.ddot` files (configurable colors under *Settings → Editor → Color Scheme → ddot.it*).
- **Smart completions** that understand triple slots:
  - typing in the subject slot suggests known subjects;
  - typing in the predicate slot suggests known predicates;
  - typing in the object slot suggests known objects.
  - Multi-word entities (e.g. `John Doe`) complete as a single unit.
  - Suggestions are **prefix-matched** (case-insensitive), not fuzzy.
  - Picking a subject auto-inserts ` ..` and surfaces predicates; picking a predicate auto-inserts `.. ` and surfaces objects.
  - On a fresh line after a complete triple, suggestions auto-open with `..rel..`-form predicates.
  - When the cursor sits right after `..`, predicates show as `..rel..` items whose range absorbs the existing `..` (no doubled dots on accept).
- **Canonical formatter** (Code → Reformat Code, ⌘⌥L / Ctrl+Alt+L):
  - Typed link → `subject ..predicate.. object`
  - Simple link → `subject .... object`
  - Continuation → `..predicate.. object`
  - Metadata → ` ,, content` inline, ` ,,` at end of line
- **Structure view** — every distinct subject in the file is listed in the file structure popup (⌘F12 / Ctrl+F12).
- **Folding** — collapses a subject and its continuation lines.
- **Live templates** — type a prefix, press Tab:
  - `ddot-typed` — typed link
  - `ddot-link` — simple link
  - `ddot-property` — typed link with metadata
  - `ddot-continue` — continuation predicate
  - `ddot-meta` — metadata pair
- **Quick documentation** — Ctrl+Q on a triple shows the line shape (typed link / simple link / metadata).
- **Tools menu** (Tools → ddot.it):
  - **Validate Document** — flag incomplete triples;
  - **Format Document** — same as Reformat Code;
  - **Export as JSON** — emits one [triple event](https://ddot.it/developer-guide.html#events) per line (JSONL) with fields `from`, `type`, `to`, `meta`, `kind`, `source`, `location`.

## Building from source

```bash
mvn package
```

The plugin zip is at `target/ddot.it-intellij-<version>.zip`. Install it via *Settings → Plugins → ⚙ → Install Plugin from Disk…* and pick the zip.

Then open or create a `.ddot` file to try it.

## Status

The language is stable. This plugin is feature-complete for authoring.
