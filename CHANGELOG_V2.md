# AI Dictaphone V2 change log

## Fixed repeated speech in Raw Transcript

The transcription request no longer receives previous transcript context. This removes the main source of model-generated context repetition.

## Added boundary deduplication

When successive chunks are stitched together, the Android service checks whether the beginning of the new transcription exactly matches the end of the existing transcript. Obvious multi-word overlap is removed before storage and display.

## Fixed repeated UI updates

The service broadcasts only the newly appended transcript delta instead of always broadcasting the full transcription result.

## Fresh transcript per recording

Starting a new recording clears the displayed transcript and the saved `last_transcript` value so an old session cannot be accidentally appended to a new session.

## AI cleanup safety rule

The final AI processing layer can remove obvious accidental adjacent duplication while preserving repetition that carries meaning or emphasis.

## Version

Android versionCode: 2
Android versionName: 1.1
