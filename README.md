# Waypoint (Android)

The app is `app/src/main/assets/index.html`. The Kotlin code wraps it and gives it:
the Waypoint natural voice (Piper "Lessac", fully offline), the phone's other voice
engines, voice capture, keep-screen-on, music ducking while it talks, and a back
button that won't drop you out mid-drive.

The big voice files (about 110 MB) are NOT in this folder. GitHub downloads them
during the build, so the repo stays small enough to upload from a browser.

## Build on GitHub
1. Create a new repository on github.com.
2. "Add file" > "Upload files", drag in everything from this folder, commit.
3. Check the repo has `.github/workflows/build-apk.yml`. If the upload skipped it,
   use "Add file" > "Create new file", type that exact path as the name, paste the
   contents of build-apk.yml from this zip, and commit.
4. Open the Actions tab > "Build APK". It takes about 5 minutes.
5. Open the finished run and download "Waypoint-debug", then unzip it to get the APK.

## Build in Android Studio
Run `bash fetch-voice.sh` once in this folder, then open it and Build > Build APK(s).
