# Build the APK without Android Studio

You only need a free GitHub account and a web browser.

## Upload the project

1. Extract `Javad_File_Processor_Android.zip` on your Windows computer.
2. Sign in at **github.com**.
3. Click the **+** in the upper-right corner and choose **New repository**.
4. Name it `Javad-File-Processor-Android`.
5. Choose **Private**, leave the initialization boxes unchecked, and click **Create repository**.
6. On the new repository page, click **uploading an existing file**.
7. Open the extracted `android_javad_processor` folder in File Explorer.
8. Drag **all contents inside that folder** onto the GitHub upload page. Include the `.github` folder. Do not upload the outer folder or ZIP as one file.
9. Wait for the files to finish uploading, then click **Commit changes**.

## Download the APK

1. Open the repository's **Actions** tab.
2. Click **Build Android APK** on the left.
3. If asked, click **I understand my workflows, go ahead and enable them**.
4. Open the newest workflow run and wait for the green checkmark.
5. At the bottom of the run page, under **Artifacts**, click **Javad-File-Processor-APK**.
6. Extract the downloaded artifact ZIP. It contains `app-debug.apk`.
7. Move `app-debug.apk` to the Tab Active3 and tap it to install.

On the tablet, Android may ask you to allow installations from My Files or Chrome. Enable that permission for the installation, then turn it back off afterward if desired.
