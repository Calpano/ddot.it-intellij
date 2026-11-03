# Release procedure

When you cut the next release, three places need the version bump in lockstep:

- `<version>` in `plugin.xml`
- `<version>` in `pom.xml`
- a fresh `<change-notes>` entry above the previous one in `plugin.xml`

Tag the commit with `v0.x.y` to keep them aligned.

Before tagging, push `../ddot.it` and `../ddot.it-syntax-tools`, and set the `ref:` of their
checkouts in both `.github/workflows/*.yml` to those repos' HEAD. CI bundles the Rouge lexer and
tests against the corpus from exactly those commits, not from your working copies.
