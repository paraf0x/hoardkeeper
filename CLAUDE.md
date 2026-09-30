# Hoardkeeper — for agents

**What it is:** a Fabric client mod (Minecraft 26.3, Java 25) that scans the containers around the
player without a screen, keeps a local map of what is where, and answers from it: search, tooltip
counts, the crouch peek card, the singleplayer deposit. Published on Modrinth as `hoardkeeper`. The
README is the user-facing description; this file is for agents.

**Build and test:** JDK 25 only. `./gradlew test` (JUnit 5) · `./gradlew build` for
`build/libs/hoardkeeper-<v>.jar` (never starts a client) · client gametests:
`./gradlew runClientGameTest -Pscenario=all` (CI runs them under xvfb on every push).

**Rules that bite:** never `git stash`. Never pipe a test run through `tail`; redirect to a file. A
test you have not watched fail is decoration. No Minecraft type leaves `capture/`, the mixins and
the client-facing services — the `model/` DTOs and the pure rule classes are plain Java so they can
be unit-tested without a client. **No network code, no URL, no token** in this repository: CI
checks the jar for it. Anything that talks to a server of its own is an add-on
(`dev.hoardkeeper.api.Addon`, entrypoint `hoardkeeper:addon`).

**Where it may act:** the scanner opens containers only in singleplayer or on servers in
`scanServers` (`/hoard allow`); it observes everywhere. The deposit is singleplayer-only. Keep it
that way — it is what makes the mod acceptable on servers that forbid automation.

**Where things are:** `src/main/java/dev/hoardkeeper/` — `scan/` (the walk: candidates, sessions,
rate limits, `ServerList`, `PhantomMenu`), `capture/` (a container's contents → `model/ScannedItem`),
`measured/` (the local map and its projection), `index/`, `search/`, `tooltip/`, `peek/`,
`observe/` (containers opened by hand), `deposit/`, `chat/`, `command/`, `api/` (the add-on seam),
`LegacyMigration` (storage-scanner → Hoardkeeper, once) · `src/test/java` mirrors it ·
`src/gametest` is the client gametest harness · `docs/RELEASING.md` (how a version ships).
