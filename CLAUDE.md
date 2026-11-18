# Release procedure

When you cut the next release, three places need the version bump in lockstep:

- `<version>` in `plugin.xml`
- `<version>` in `pom.xml`
- a fresh `<change-notes>` entry above the previous one in `plugin.xml`

Tag the commit with `v0.x.y` to keep them aligned.

Before tagging, push `../ddot.it`, `../ddot.it-syntax-tools`, `../ddot.it-java-core`, `../graphinout` and `../kbase`, and set the `ref:` of their
checkouts in both `.github/workflows/*.yml` to those repos' HEAD. CI bundles the Rouge lexer, tests
against the corpus, and installs the `ddotit-core`, graphinout and `com.calpano:kbase` SNAPSHOTs from exactly those commits, not from
your working copies. Locally, `mvn install` in `../ddot.it-java-core` and `../graphinout`, then in `../kbase`, after pulling them.
