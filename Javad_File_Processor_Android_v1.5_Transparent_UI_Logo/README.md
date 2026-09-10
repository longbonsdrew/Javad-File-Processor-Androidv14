# Javad File Processor — Android

## Version 1.5 transparent UI logo

The approved red FORSITE and JAVAD composition now appears without a separate
green background in the upper-left of the existing dark-green app banner. The
launcher icon retains its green background.

## Version 1.4 launcher-icon placement

The JAVAD logo on the launcher icon now follows the supplied red-box placement
guide, with the logo larger and positioned farther left and higher.

## Version 1.3 final branding

The banner now uses one transparent red FORSITE and JAVAD lockup in the
upper-left, replacing the two previous banner images. The launcher icon keeps
the JAVAD logo in the lower-right with slightly larger lettering and improved
edge spacing.

## Version 1.2 branded app icon

The installed app now uses the dark-green icon with the centered red FORSITE
mark and the small green-and-white JAVAD wordmark in the lower-right corner.

## Version 1.1 download repair

JAVAD supplies TXT results to Android as temporary `blob:` addresses. Version
1.1 reads those reports inside the signed-in JAVAD page and writes the TXT
content directly to the selected results folder. Normal HTTP/HTTPS downloads
remain supported, PDFs are ignored, and duplicate download events are blocked.

This is the tablet edition of the Northwest Management | FORSITE Javad File Processor. It is configured for the Samsung Galaxy Tab Active3 (SM-T570) running Android 13 and also supports Android 10 or newer.

## Field workflow

1. Install the APK and open **Javad File Processor**.
2. Tap **Choose observation folder** and select the folder containing the `.jps` files.
3. Tap **Choose results folder** and select the folder where TXT reports should be saved.
4. Tap **Sign in / open JAVAD**, sign in, and confirm that Upload Data is visible.
5. Tap **Return to Processor**.
6. Tap **Start Processing**. Keep the app open until both orange progress bars reach 100%.
7. Review any failed filenames shown under the status. The same list is saved as `failed_files_latest.txt` in the results folder.

The app keeps the screen awake, submits one file at a time, resets the JAVAD Upload Data page between files, checks up to 10 Browse Reports pages, ignores PDF downloads, and saves only TXT results.

## Build an installable APK

The easiest method does not require Android Studio. Follow `BUILD_APK_WITH_GITHUB.md` to build and download the APK using a free GitHub account.

### Android Studio alternative

1. Install the current Android Studio on a Windows computer.
2. Open the `android_javad_processor` folder as a project.
3. Allow Android Studio to install Android SDK 35 and finish Gradle synchronization.
4. Connect the Tab Active3 by USB with USB debugging enabled.
5. Use **Build > Generate App Bundles or APKs > Generate APKs**.
6. Copy `app/build/outputs/apk/debug/app-debug.apk` to the tablet and open it to install.

Android may ask whether to allow installation from the file-management app. Approve that prompt only for this APK.

## Important first-release note

JAVAD controls its website and can change the Upload Data or Browse Reports page without notice. Test this first release with two or three copied plots before running a large field folder. If a page element has changed, the on-screen log and failed-file list identify where the workflow stopped.
