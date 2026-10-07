# AI Dictaphone — exact setup and deployment steps

This project is split into two parts:

- `android/` — the native Android application.
- `netlify/functions/` — the secure AI backend.

GitHub stores the source code. GitHub Actions builds the Android APK. Netlify hosts the backend functions that hold and use the OpenAI API key.

## 1. Create an OpenAI API key

1. Sign in to the OpenAI API platform.
2. Create a new API key for this project.
3. Keep the key private.
4. Do not paste it into the Android app, GitHub source files, or `README.md`.

The backend reads the key as the `OPENAI_API_KEY` environment variable.

## 2. Create a GitHub repository

You can use the GitHub website, GitHub Desktop, or Terminal. VS Code is not required.

Terminal example on macOS:

```bash
cd /path/to/AI-Dictaphone
git init
git add .
git commit -m "Initial AI Dictaphone build"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/ai-dictaphone.git
git push -u origin main
```

## 3. Deploy the backend to Netlify

1. Sign in to Netlify.
2. Choose **Add new project** / **Import an existing project**.
3. Connect GitHub.
4. Select your `ai-dictaphone` repository.
5. Leave the repository root as the project base directory.
6. The included `netlify.toml` already tells Netlify to use:
   - static publish directory: `web`
   - functions directory: `netlify/functions`
7. Deploy the site.

Netlify will create a URL similar to:

`https://your-site-name.netlify.app`

## 4. Put the OpenAI key into Netlify — this is the only place the app needs a secret

In Netlify:

**Project configuration → Environment variables**

Add:

- Key: `OPENAI_API_KEY`
- Value: your OpenAI API key
- Scope: Functions/runtime (or the default scope that includes Functions)

Save it.

Then redeploy the site. Netlify applies environment-variable changes to new deploys, so a new deployment is required after adding or changing the key.

## 5. Test the backend before touching the Android app

Open this in a browser:

`https://YOUR-SITE.netlify.app/.netlify/functions/health`

You should see JSON similar to:

```json
{
  "ok": true,
  "service": "ai-dictaphone-backend",
  "openaiConfigured": true
}
```

If `openaiConfigured` is `false`, the environment variable is missing or the site needs to be redeployed.

## 6. Build the Android APK with GitHub Actions

The workflow is already included here:

`android/.github/workflows/build-apk.yml`

After the first push to `main`:

1. Open GitHub.
2. Open your repository.
3. Open **Actions**.
4. Open **Build Android APK**.
5. Wait for the job to finish successfully.
6. Open the completed run.
7. Under **Artifacts**, download `ai-dictaphone-debug-apk`.
8. Unzip it to get `app-debug.apk`.

## 7. Install the APK on your Android phone

Transfer `app-debug.apk` to the phone.

Open it and install it. Android may ask you to allow installation from that source because this APK is not coming from Google Play.

## 8. Configure the app

Open **AI Dictaphone**.

The first launch asks for the AI backend URL.

Enter only:

`https://YOUR-SITE.netlify.app`

Never enter the OpenAI API key here.

Tap **Test** or **Test server**.

You want the message:

**Server connected and OpenAI is configured.**

## 9. First test

Use:

- Mode: `Dictate`
- Source: `English`

Tap **Record** and speak for 1–2 minutes.

Tap **Stop**.

The app will:

1. finish any audio segment still being uploaded;
2. build the raw transcript;
3. send the transcript to the AI processing function;
4. return a cleaned transcript.

## 10. Test German

Select:

- Mode: `Dictate`
- Source: `German`

Speak normally for a few minutes.

Then test:

- Mode: `Translate`
- Source: `German`
- Output: `English`

The app will show the translated result after the recording is finished.

## 11. Test Notes mode

Select:

- Mode: `Notes`
- Source: whichever language you want
- Output: the language you want for the notes

Speak for several minutes and stop.

The result panel will contain:

- cleaned transcript
- summary
- notes
- warnings when the AI is uncertain

## 12. How the long recording works

The app does not try to upload one huge recording.

It records short WAV segments locally, uploads them one at a time, and feeds recent transcript context into the next transcription request.

That means a long session can continue without a single giant request. The first version uses roughly 12-second audio segments.

## 13. What is protected

The Android APK contains no OpenAI secret.

The app only knows the Netlify backend URL.

The OpenAI key exists only as `OPENAI_API_KEY` on the server.

## 14. Updating the application later

When I give you a new version:

1. Replace the files in the project folder.
2. Run:

```bash
git add .
git commit -m "Describe the change"
git push
```

3. GitHub Actions will build a new APK.
4. Netlify will redeploy the backend automatically if the GitHub repository is connected to the Netlify project.

## 15. Important V1 reality

This build intentionally uses chunked transcription because it is straightforward to deploy and is resilient for long recordings. It is not a claim that it reproduces ChatGPT's private dictation pipeline exactly.

A later build can move the speech layer to a direct Realtime transcription connection so the experience can become closer to true low-latency, continuously updating dictation.
