# Release procedure

When you cut the next release, three places need the version bump in lockstep:

- `<version>` in `plugin.xml`
- `<version>` in `pom.xml`
- a fresh `<change-notes>` entry above the previous one in `plugin.xml`

Tag the commit with `v0.x.y` to keep them aligned.
