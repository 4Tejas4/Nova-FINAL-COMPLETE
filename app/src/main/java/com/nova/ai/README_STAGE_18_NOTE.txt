Stage 18: Background wake-word reliability

- Keeps Nova as a foreground microphone service while enabled.
- Adds debouncing so one wake phrase cannot trigger multiple command sessions.
- Adds exponential recovery backoff for Vosk timeouts/errors.
- Cancels pending recovery callbacks during shutdown.
- Uses START_STICKY lifecycle behavior already present so Android can recreate the service if reclaimed.
- Prefers the phrase “hey nova”, while allowing “nova” as a practical fallback when Vosk drops “hey”.
- Does not attempt to bypass Android background-execution restrictions or battery-optimization policies.
