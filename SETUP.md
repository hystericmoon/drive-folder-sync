# Setup (phone only)

You need about 20 minutes, a GitHub account, and the Google account you'll sync with.
Every step works in a phone browser. Where a page is cramped or a tab is missing, open the
browser menu and turn on **Desktop site**.

Values you'll use:

| Thing | Value |
|---|---|
| Package name | `io.github.hystericmoon.drivesync` |
| Drive scope | `https://www.googleapis.com/auth/drive` |

---

## 1. Create the signing key (once)

The APK must always be signed with the same key. Google ties your OAuth client to that key's SHA-1.

1. In this repo, go to **Actions** → **Generate signing keystore** → **Run workflow** → **Run workflow**.
2. Open the finished run. Its **Summary** shows `Signing SHA-1: AA:BB:…`. Copy it into a note; you'll need it in step 5.
3. At the bottom of the summary, download the **signing-secrets** artifact. It expires after 1 day.
4. Open the zip in your Files app. It holds `KEYSTORE_BASE64.txt` and `KEYSTORE_PASSWORD.txt`.

## 2. Store the key as repo secrets

**Settings** → **Secrets and variables** → **Actions** → **Secrets** tab → **New repository secret**:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | The full contents of `KEYSTORE_BASE64.txt`, one long line. Open it in a text viewer and use *Select all* → *Copy*. |
| `KEYSTORE_PASSWORD` | The contents of `KEYSTORE_PASSWORD.txt` |

Then delete the zip from your phone. If you ever lose these secrets you need a new key, and with it a new SHA-1 (step 5) and a fresh app install.

> Running the keystore workflow again does nothing while `KEYSTORE_BASE64` exists. This stops you replacing the key by accident.

## 3. Google Cloud project + Drive API

1. Open <https://console.cloud.google.com> and sign in with the Google account you'll sync.
2. Use the project picker at the top → **New project**. Name it something like `drive-folder-sync` → **Create**, then select it.
3. Go to **☰** → **APIs & Services** → **Library**. Search **Google Drive API** → **Enable**.

## 4. OAuth consent screen

Go to **☰** → **Google Auth Platform**. On older consoles: **APIs & Services → OAuth consent screen**.

1. **Get started** / **Branding**: app name `Drive Folder Sync`, plus your email as the support email and the developer contact → **Save**.
2. **Audience**: user type **External**. Under **Test users**, tap **Add users**, add your own Google address, then **Save**.
3. **Data access** (optional): **Add or remove scopes** → paste `https://www.googleapis.com/auth/drive` under *Manually add scopes* → **Add to table** → **Update** → **Save**.

This leaves the app in **Testing**, where Google expires its Drive access every 7 days. The app then notifies you and needs one tap on **Grant access**. Step 9 removes that limit.

## 5. Android OAuth client (uses the SHA-1)

**Google Auth Platform** → **Clients** → **Create client**:

- Application type: **Android**
- Package name: `io.github.hystericmoon.drivesync`
- SHA-1 certificate fingerprint: the value from step 1

→ **Create**. Nothing to copy: Google matches this client by package name + SHA-1 at runtime.

## 6. Web OAuth client (for "Sign in with Google")

Credential Manager's Google sign-in needs a **Web** client ID to issue the sign-in token, even in an Android app.

1. **Clients** → **Create client** → Application type **Web application** → name `drive-folder-sync-web` → **Create**. You don't need redirect URIs.
2. Copy the **Client ID**. It ends in `.apps.googleusercontent.com`.
3. In GitHub: **Settings** → **Secrets and variables** → **Actions** → **Variables** tab → **New repository variable**:
   `GOOGLE_WEB_CLIENT_ID` = that client ID. It's not a secret, so a variable is fine.

## 7. Build and install

1. **Actions** → **Build signed APK** → **Run workflow**. Every push also triggers a build.
2. When it's green, open **Releases** (repo home → *Releases*) and download `drive-folder-sync-0.1.N.apk`.
   Builds from branches other than the default branch are marked *Pre-release*.
3. Open the APK. Allow your browser to *install unknown apps* if asked. If Play Protect warns about an unknown developer, choose **Install anyway**.
4. Later builds install over the old one and keep your folder pairs, because the signing key never changes.

## 8. First run

1. **Sign in with Google** → choose your account.
2. **Grant access**. Google shows *"Google hasn't verified this app"* → **Advanced** → **Go to Drive Folder Sync (unsafe)** → **Continue**. This is expected for a personal, unverified app.
3. **Add folder pair** → pick a phone folder → browse to (or create) a Drive folder → **Sync with "…"**.
4. For reliable background syncing: long-press the app icon → **App info** → **Battery** → **Unrestricted**.

## 9. Leave Testing mode (optional, ends the weekly re-grant)

Google only lets you publish once the Branding page has a home page and privacy policy on a domain you list. This repo ships both in `docs/`, served free by GitHub Pages.

1. GitHub: **Settings** → **Pages**. Under *Build and deployment*: Source **Deploy from a branch**, Branch **main**, folder **/docs** → **Save**.
   After a minute, <https://hystericmoon.github.io/drive-folder-sync/> should load.
2. Google Cloud → **Google Auth Platform** → **Branding**:
   - Application home page: `https://hystericmoon.github.io/drive-folder-sync/`
   - Application privacy policy link: `https://hystericmoon.github.io/drive-folder-sync/privacy.html`
   - Authorized domains → **Add domain**: `hystericmoon.github.io`
   - Don't upload a logo, because that requires verification. Tap **Save**.
3. **Audience** → **Publish app** → **Confirm**. The status should read **In production**.
4. In the app, sign out and sign in again so Google issues a non-expiring grant.

## 10. Updates

The app checks this repo's **Releases** twice a day on Wi-Fi and installs newer builds from `main`. Pre-releases from other branches are ignored. You can also tap **Settings → Check for updates**, or turn off **Auto-update** there.

- The first time, Android asks you to allow *Drive Folder Sync* to install apps. Allow it.
- The first self-update shows Android's install prompt. After that, Android 12+ usually installs updates silently. When it can't, you get an "Update ready" notification.
- The app refuses any APK that isn't signed with the same key, so only builds from your repo's workflow get installed.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Build fails with "Signing secrets missing" | Do steps 1–2. |
| App says "Not configured" | `GOOGLE_WEB_CLIENT_ID` wasn't set when that APK was built. Set it (step 6) and rebuild. |
| Sign-in error mentioning *developer console*, or code **10** | The Android client's package name or SHA-1 doesn't match. Compare the SHA-1 in the build's run summary with step 5. |
| "No credentials available" | The web client ID is wrong, or no Google account is on the phone. |
| Pair shows "Lost access to the phone folder" | Android dropped the folder permission (e.g. SD card removed). Remove the pair and add it again. Files aren't touched. |
| "Drive sync is paused" notification | Google access expired (Testing mode, step 4). Open the app → **Grant access**, or do step 9. |
| "Update failed" notification | Usually means the APK wasn't signed with your key, or storage is full. Install manually from Releases. |
| "…looks empty but N files were synced before" | Safety stop: the app won't mirror a whole folder vanishing. If you really emptied it, remove and re-add the pair. |
