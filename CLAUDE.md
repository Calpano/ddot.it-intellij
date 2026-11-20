# Release procedure

When you cut the next release, three places need the version bump in lockstep:

- `<version>` in `plugin.xml`
- `<version>` in `pom.xml`
- a fresh `<change-notes>` entry above the previous one in `plugin.xml`

Tag the commit with `v0.x.y` to keep them aligned.

Before tagging, push `../ddot.it` and set the `ref:` of its checkout in both `.github/workflows/*.yml` to its
HEAD. CI tests against the corpus from exactly that commit, not from your working copy. The Rouge lexer comes
from the rouge-ddot gem: to update it, set `rouge-ddot.version` and `rouge-ddot.sha256` in `pom.xml`. `ddotit-core`, graphinout and `com.calpano:kbase`
are releases from Maven Central; keep `ddotit-core` and graphinout at the versions the kbase release builds on.
