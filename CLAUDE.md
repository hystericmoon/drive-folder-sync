# CLAUDE.md

Personal Android app (Kotlin, Jetpack Compose) that two-way syncs phone folders with Google Drive folders.
The owner is a product designer working **only from a phone**. They don't build locally or run tests themselves:
CI builds the APK, and the app updates itself from GitHub Releases. Read `README.md` (behaviour, code map) and
`SETUP.md` (Google Cloud, secrets, release flow) before changing anything.

## Status

- Installed and syncing on the owner's phone. The owner has said they will **not** run manual device tests.
- **Never verified on a real phone:** the v0.1.9 UI redesign (glass, animations, tabs), sync history,
  exclude rules, and the actual install step of self-update. Keep changes conservative.
- Tests are the safety net (see README → Tests). The sync engine is covered end to end by `SyncScenarioTest` (fakes in
  `test/…/sync/fakes/` and `sharedTest/`). Any change to `SyncEngine`, `Planner`, `RemoteTree`, `LocalFs` or `DriveApi`
  behaviour needs a scenario there. `DriveClientTest`/`UpdaterTest` cover the network code with MockWebServer.
- Emulator tests (`androidTest/`) can't run in this container (no KVM): compile them with
  `assembleDebugAndroidTest`, then let CI's `device-tests` job run them. It grants the test folder through the real
  folder picker with UiAutomator, so a DocumentsUI change in the emulator image can break it; check the job log first.
- CI: `build` (unit tests, signed APK) and `device-tests` (emulator + release-APK crash check) run in parallel; `release`
  publishes only if both pass. A failing test blocks the release and the auto-update.
- Google OAuth app is **In production** (published, unverified) since 2026-10-23. Home page and privacy
  policy are served from `docs/` via GitHub Pages (`https://hystericmoon.github.io/drive-folder-sync/`) and are
  linked on the consent screen, so keep those URLs working. Users see Google's "unverified app" warning once at sign-in.

## Workflow rules

- Work on the session's branch, push, wait for the **Build signed APK** workflow to go green, then
  fast-forward `main` (`git push origin HEAD:main`). The owner has approved this flow; `main` is the default branch.
- Every push builds and publishes a GitHub Release. Builds from `main` are full releases, and the app
  auto-updates to them. Builds from other branches are pre-releases, which the app ignores.
  So **a broken `main` build ships to the owner's phone**: run the checks below first.
- The signing key lives only in repo secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`). Never regenerate it:
  the SHA-1 is registered in Google Cloud, and a new key breaks sign-in and auto-update.
- Package name `io.github.hystericmoon.drivesync` is registered in Google Cloud; don't change it.
- The owner prefers concise explanations with UX reasoning, plus accessibility notes on any UI change.

## Local build (cloud container)

The Android SDK isn't preinstalled. Once per container:

```sh
mkdir -p /root/android-sdk/cmdline-tools && cd /root/android-sdk
curl -sSLo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q clt.zip -d cmdline-tools && mv cmdline-tools/cmdline-tools cmdline-tools/latest
yes | cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
echo "sdk.dir=/root/android-sdk" > /home/user/drive-folder-sync/local.properties
```

Robolectric downloads its Android jar at test time, which can fail behind the container proxy. If a test
fails in `MavenArtifactFetcher`, fetch the jar it names with curl from `https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/<version>/`
into the same path under `~/.m2/repository/`.

Checks before pushing (Maven Central may return 429; retry, and use `--max-workers=2`):

```sh
./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintRelease assembleDebugAndroidTest   # unit tests + lint
./gradlew --no-daemon --max-workers=2 testDebugUnitTest -Pscreenshots               # regenerates app/screenshots/*.png
```

Look at the screenshots after any UI change (light and dark) and commit the updated PNGs; the README shows them.
To check a signed release build locally, create a throwaway keystore with alias `drivesync`, then set
`SIGNING_KEYSTORE_PATH` and `SIGNING_STORE_PASSWORD`.

## Data-safety invariants (don't weaken)

- Deletes only ever go to the Drive trash or to `.sync-trash/` on the phone, which is purged after 30 days.
  Never delete permanently.
- The first sync of a pair is merge-only. Edits win over deletes.
- Conflicts keep both copies: the Drive version becomes `name (conflict YYYY-MM-DD).ext`.
- Mass-delete guard: abort if either side suddenly looks empty.
- The Changes API token advances only after a run with zero failures.
- Excluded paths are forgotten, never deleted.
- `sync/Planner.kt` is the pure decision table, and `PlannerTest` covers it. Change the tests together with the planner.
- Room schema changes need a migration plus an exported schema (`app/schemas/`), and a case in `MigrationTest`, which
  checks that the migration produces exactly the schema Room generates (schemas reach it as debug-only assets).

## Design system

`ui/glass/` holds the brand palettes (contrast tuned for glass), the `glass()` modifier, `AmbientBackground`,
`BottomScrim` and `GlassNavBar`.

- Text on glass gets its colour from `LocalContentColor`. `AmbientBackground` provides it because no
  `Surface` sits in between.
- Respect `LocalReducedMotion`: every animation must stop when Android's *Remove animations* is on.
- Touch targets are at least 48dp, and toggle rows use `Modifier.toggleable` with a role.
