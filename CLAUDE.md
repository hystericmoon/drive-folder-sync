# CLAUDE.md

Personal Android app (Kotlin, Jetpack Compose) that two-way syncs phone folders with Google Drive folders.
The owner is a product designer working **only from a phone**. They don't build locally or run tests themselves:
CI builds the APK, and the app updates itself from GitHub Releases. Read `README.md` (behaviour, code map) and
`SETUP.md` (Google Cloud, secrets, release flow) before changing anything.

## Status

- Installed and syncing on the owner's phone. The owner has said they will **not** run manual device tests.
- **Never verified on a device:** the v0.1.9 UI redesign (glass, animations, tabs), sync history,
  notifications, exclude rules, and the self-update flow. Verify with the unit and screenshot tests below,
  and keep changes conservative.
- Google OAuth app is in **Testing** mode: Drive access expires every 7 days, and the app shows a
  "Drive sync is paused" notification. Publishing (SETUP.md step 9) needs GitHub Pages turned on for `/docs`.
  The owner hasn't done that yet; Google kept the Publish button disabled until home page and privacy links exist.

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

Checks before pushing (Maven Central may return 429; retry, and use `--max-workers=2`):

```sh
./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintRelease   # unit tests + lint
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
- Room schema changes need a migration plus an exported schema (`app/schemas/`). Check that the migration
  produces exactly the schema Room generates.

## Design system

`ui/glass/` holds the brand palettes (contrast tuned for glass), the `glass()` modifier, `AmbientBackground`,
`BottomScrim` and `GlassNavBar`.

- Text on glass gets its colour from `LocalContentColor`. `AmbientBackground` provides it because no
  `Surface` sits in between.
- Respect `LocalReducedMotion`: every animation must stop when Android's *Remove animations* is on.
- Touch targets are at least 48dp, and toggle rows use `Modifier.toggleable` with a role.
