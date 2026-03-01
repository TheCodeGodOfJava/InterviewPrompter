# 🚀 Getting Started with the Project

> This project is designed and tested **exclusively on Windows 11**.  
> Compatibility with **macOS or Linux is not guaranteed**.

---

## ✅ Prerequisites & Installation
Take notice that at the time of developing this app, Spring AI was INCOMPATIBLE with SpringBoot 4.0 or higher


### 1️⃣ Install FFmpeg (required for audio processing)

Download the **full build** (❌ *not* the essentials version):

- 🔗 Recommended source: https://www.gyan.dev/ffmpeg/builds/
- Choose one of:
    - `ffmpeg-git-full.7z`
    - `ffmpeg-release-full.7z`

#### Installation steps

#### a) Extract the archive to a permanent location, for example: C:\ffmpeg

#### b) Add FFmpeg to the system `PATH`:

- Press **Win + S**
- Search for **Edit the system environment variables**
- Click **Environment Variables**
- Under **System variables**, select **Path** → **Edit**
- Click **New** and add:

  ```
  C:\ffmpeg\bin
  ```

- Click **OK** on all dialogs

#### c) Verify installation
Open a **new** PowerShell or Command Prompt and run:

```powershell
ffmpeg -version
  ```
✅ You should see FFmpeg version information.

### 2️⃣ Configure IDE – Force UTF-8 Encoding
To prevent broken characters or corrupted console output, configure your IDE to use UTF-8.

Open Run / Debug Configurations (IntelliJ IDEA / VS Code / Eclipse). Enable VM options:
- -Dconsole.encoding=UTF-8
- -Dfile.encoding=UTF-8
- -Dsun.stdout.encoding=UTF-8
- -Dsun.stderr.encoding=UTF-8

Save the configuration. Restart the IDE if required

### 3️⃣ Enable “Stereo Mix” (critical for system audio capture)

Windows hides this device by default.

Steps
- Right-click the speaker icon in the taskbar
- Select Sound settings
- Scroll down → click More sound settings
- Open the Recording tab
- Right-click inside the list and enable:
- ✅ Show Disabled Devices
- ✅ Show Disconnected Devices
- Find Stereo Mix
- Right-click → Enable
- Right-click again → Set as Default Device

If Stereo Mix does not appear: Update your Realtek audio driver.

### 3️⃣ Add to "resources" secrets.yml to store your remote API keys