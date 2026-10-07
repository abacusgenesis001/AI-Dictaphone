# AI Dictaphone V2

A native Android long-form dictation app with a secure AI processing layer.

## What V2 does

- Continuous microphone recording using Android `AudioRecord`.
- Records in small WAV segments so long sessions can continue without one giant upload.
- Sends each audio segment to a server-side OpenAI transcription endpoint.
- Does **not** feed the previous transcript back into the transcription model.
- Stitches segment results locally and removes obvious multi-word overlap at chunk boundaries.
- Sends only the newly appended transcript text to the Android UI.
- Starts each new recording as a fresh transcript session.
- After recording stops, sends the complete raw transcript to an AI processing endpoint for:
  - clean dictation
  - translation
  - notes + summary
- Keeps the OpenAI API key on the server. The Android app only knows the backend URL.
- Uses a foreground microphone service so recording can continue when the app is not the visible screen.

## Why V2 changes the transcription pipeline

The earlier build supplied the previous transcript as a transcription prompt for every new audio chunk. That context could be copied into later transcription results, which then appeared as repeated text.

V2 removes that prompt and relies on local transcript stitching instead. A second AI cleanup pass can remove an obvious accidental adjacent duplication while preserving intentional repetition.

## Architecture

Android app -> Netlify Functions -> OpenAI

The Android application never contains `OPENAI_API_KEY`.

## Deploy the backend

1. Create or update the GitHub repository with the whole project.
2. In Netlify, import the GitHub repository.
3. Keep the repository root as the Netlify base directory.
4. Netlify uses `netlify.toml` to deploy `web/` and `netlify/functions/`.
5. In Netlify, open Project configuration -> Environment variables.
6. Add `OPENAI_API_KEY` with your OpenAI API key and make sure it is available to Functions.
7. Redeploy after setting or changing the variable.
8. Visit `https://YOUR-NETLIFY-SITE/.netlify/functions/health`. It should report `ok: true` and `openaiConfigured: true`.

## Configure the Android app

Install the APK. On first launch, enter only the Netlify site URL, for example:

`https://your-ai-dictaphone.netlify.app`

Do NOT paste the OpenAI API key into the Android app.

The app has three modes:

- Dictate: clean long-form transcript.
- Translate: clean transcript + translation.
- Notes: clean transcript + summary + notes.

## Build the APK with GitHub Actions

The workflow is at:

`.github/workflows/build-android.yml`

After pushing to `main`, open GitHub -> Actions -> Build Android APK. Download the artifact named `ai-dictaphone-debug-apk`.

## V2 test plan

1. English dictation for 1-2 minutes.
2. German dictation for 1-2 minutes.
3. German -> English translation.
4. A phrase that crosses a chunk boundary; verify it appears only once.
5. Natural 5-minute dictation.
6. 10-minute reliability test.

## Future direction

After V2 proves stable, the speech layer can be moved toward a lower-latency realtime transcription connection. That should be treated as a separate build rather than mixed into the duplicate-transcript fix.
