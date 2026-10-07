# AI Dictaphone

A native Android long-form dictation app with an AI processing layer.

## What this version does

- Continuous microphone recording using Android `AudioRecord`.
- Records in small WAV segments so long sessions can continue without a giant audio upload.
- Sends each segment to a server-side OpenAI transcription endpoint.
- Uses previous transcript context to help the next segment stay coherent.
- Keeps the raw transcript editable on the phone.
- After recording stops, sends the transcript to an AI processing endpoint for:
  - clean dictation
  - translation
  - notes + summary
- Keeps the OpenAI API key on the server. The Android app only knows the backend URL.
- Supports a foreground microphone service so recording can continue when the app is not the visible screen.

## Architecture

Android app -> Netlify Functions -> OpenAI

The Android application never contains `OPENAI_API_KEY`.

## Deploy the backend

1. Create a GitHub repository and upload the whole project.
2. In Netlify, import the GitHub repository.
3. Keep the repository root as the Netlify base directory.
4. Netlify will use `netlify.toml` and deploy `web/` plus `netlify/functions/`.
5. In Netlify, open Project configuration -> Environment variables.
6. Add `OPENAI_API_KEY` with your OpenAI API key and make sure it is available to Functions.
7. Redeploy after setting the variable.
8. Visit `https://YOUR-NETLIFY-SITE/.netlify/functions/health`. It should report `ok: true` and `openaiConfigured: true`.

## Configure the Android app

Install the APK. On first launch, tap Server settings and enter only the Netlify site URL, for example:

`https://your-ai-dictaphone.netlify.app`

Do NOT paste the OpenAI API key into the Android app.

The app has three modes:

- Dictate: clean long-form transcript.
- Translate: clean transcript + translation.
- Notes: clean transcript + summary + notes.

## Build the APK with GitHub Actions

The workflow is already included at:

`android/.github/workflows/build-apk.yml`

After pushing to `main`, open GitHub -> Actions -> Build Android APK. Download the artifact named `ai-dictaphone-debug-apk`.

## Important V1 limitation

This is a long-form, chunked transcription architecture rather than a copy of any private ChatGPT speech pipeline. It is designed so the app can keep recording for long sessions without a single giant request. The next engineering phase can replace chunked transcription with a direct Realtime transcription connection if desired.
