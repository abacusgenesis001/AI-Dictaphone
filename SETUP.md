# AI Dictaphone V2 — deployment and testing

This version keeps the same overall architecture but fixes the repeated-transcript problem.

## V2 transcription fix

The Android app now:

- sends each audio chunk to speech-to-text without feeding the previous transcript back into the transcription model;
- stitches chunks locally;
- removes obvious multi-word overlap at chunk boundaries;
- broadcasts only the newly appended transcript text to the UI;
- starts each new recording as a fresh transcript session.

The Netlify transcription function no longer adds a previous-transcript prompt to the OpenAI transcription request.

The AI processing function also has a final safety rule to remove obvious accidental adjacent duplication while preserving intentional repetition.

## Repository structure

- `android/` — native Android application
- `netlify/functions/` — secure AI backend
- `.github/workflows/build-android.yml` — GitHub Actions Android build
- `netlify.toml` — Netlify configuration

## 1. GitHub

Push the project to your GitHub repository. The Android workflow is already in the required location:

`.github/workflows/build-android.yml`

A push to `main` or `master` triggers the Android build automatically.

## 2. Netlify

Connect the same GitHub repository to Netlify. Keep the repository root as the Netlify base directory. `netlify.toml` points Netlify at `web` for the site and `netlify/functions` for the serverless functions.

## 3. OpenAI secret

In Netlify → Project configuration → Environment variables, set:

`OPENAI_API_KEY` = your OpenAI API key

Never place the key in the Android application or GitHub source.

After changing the variable, redeploy the Netlify site.

## 4. Health check

Open:

`https://YOUR-SITE.netlify.app/.netlify/functions/health`

Expected result:

```json
{
  "ok": true,
  "service": "ai-dictaphone-backend",
  "openaiConfigured": true
}
```

## 5. Build the APK

In GitHub → Actions → `Build Android APK`, wait for a successful run. Download the artifact named `ai-dictaphone-debug-apk`.

The APK is `app-debug.apk`.

## 6. Configure the phone

Install the APK and enter only the base Netlify URL, for example:

`https://your-site.netlify.app`

Do not enter the `/health` path and do not enter the OpenAI API key.

## 7. V2 test order

1. Dictate in English for 1–2 minutes.
2. Dictate in German for 1–2 minutes.
3. Translate German → English.
4. Speak one short sentence repeatedly across a chunk boundary.
5. Do a 5-minute natural dictation.
6. Do the 10-minute reliability test.

For the chunk-boundary test, the important result is that the sentence appears once in the Raw Transcript and once (as cleaned text/translation as appropriate) in the AI output.

## 8. Updating later

For future changes, replace the relevant project files, then:

```bash
git add .
git commit -m "Describe the change"
git push
```

GitHub Actions rebuilds the APK, and Netlify redeploys the backend when the connected repository changes.
