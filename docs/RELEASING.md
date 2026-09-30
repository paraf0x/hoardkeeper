# Releasing Hoardkeeper

A release is a tag `v<version>` (a stable release) or `v<version>-<name>.<n>` (a pre-release, e.g.
`v1.0.0-beta.1`). `<version>` must equal `mod_version` in `gradle.properties`.

1. Write `.github/release-notes/<tag>.md` — it starts with a `## TL;DR` block: one headline line,
   at most five bullets of about 80 characters, no version numbers (`scripts/check_tldr.py` checks
   it) — and `.github/release-notes/<tag>.title`, one line, the part after
   "Hoardkeeper <version> — ".
2. Push the tag. Where a tag cannot be pushed, write the tag name into `.github/publish` and push
   that instead; the workflow creates the tag.
3. `.github/workflows/release.yml` builds, creates the GitHub release (a pre-release for a suffixed
   tag) with the jar and the sources jar, and uploads the jar to Modrinth — `release` for a plain
   tag, `beta` for a suffixed one — with the notes as the changelog.

## Modrinth

The upload needs two settings in the repository (Settings → Secrets and variables → Actions):

| Kind | Name | Value |
|---|---|---|
| Secret | `MODRINTH_TOKEN` | A Modrinth personal access token with the *Create versions* scope |
| Variable (optional) | `MODRINTH_PROJECT` | The project's slug or id; defaults to `hoardkeeper` |

Without the secret the workflow still makes the GitHub release and skips Modrinth with a warning.
A failed upload fails the workflow and leaves the GitHub release standing; pushing the same tag
name into `.github/publish` again retries only what is missing (a version number Modrinth already
has is left alone).
