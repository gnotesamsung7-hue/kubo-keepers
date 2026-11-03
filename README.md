# Kubo Keepers

3D co-op tower defense. Walk your character around, build towers on empty spots,
collect coins and gems, and keep enemies from reaching the hut. Two versions:
**Gubat** (forest guardians and sproutlings) and **Sahod edition** (Kuya Jun and
Ate Joy protecting the ipon from bayarin, utang and lumilipad na ipis).

## Get the APK (no PC tools needed)

1. Create a new GitHub repo named `kubo-keepers`.
2. Upload everything inside this zip with **Add file > Upload files**, then commit.
3. The `.github` folder usually gets skipped by the browser. If the **Actions** tab
   shows no workflow, click **Actions > set up a workflow yourself**, name it
   `main.yml`, paste the contents of `workflow-main.yml`, and commit.
4. Check that `app/kubokeepers.keystore` was uploaded (next to `app/build.gradle`).
   The build stops without it.
5. Wait for the green check in **Actions** (about 3 to 5 minutes), then download
   the APK from **Releases > Latest APK** (`releases/tag/apk-latest`) on your phone.

Every build is signed with the same key, so new APKs install over old ones and
keep your progress.

## Updating the game later

The game lives in `docs/index.html`. Replace that file and commit; the workflow
copies `docs/` into the app automatically.

## Co-op over Wi-Fi in the APK

Both phones on the same Wi-Fi, or one phone turns on its hotspot and the other
connects to it. Host phone: **Co-op Wi-Fi > Host on this Wi-Fi**. Other phone:
**Co-op Wi-Fi > Join on this Wi-Fi**, then tap the game in the list (or type the
address shown on the host). No internet needed.

## Play in a browser (optional)

In repo **Settings > Pages**, choose branch `main` and folder `/docs`. The browser
version uses QR codes to connect two phones instead.
