# Google AI Studio provider

Mural can use a personal Google AI Studio API key directly from the iPhone or Android app. Select **Google AI Studio** in **Settings → Advanced → Personal key provider**, then set **Conversation access** to **My API key** and save a key created at [Google AI Studio](https://aistudio.google.com/app/apikey). The key is stored in the platform’s protected credential store and is never included in learning backups or source files.

The provider uses two Gemini models because Google exposes text generation and low-latency voice through different model contracts:

- `gemma-4-31b-it` handles translations, teaching, assessments, lookups and optional grounded search.
- `gemini-3.8-live` handles the Live API voice session over a direct WebSocket, with 16 kHz PCM input, 24 kHz PCM output and input/output transcription.

The requested “Flash live” combination is represented by Gemma for the text teacher and Gemini Live for the voice transport; `gemma-4-31b-it` itself is not a Live API model. Google documents Gemma’s hosted Gemini API support, including grounded search, in its [Gemma API guide](https://ai.google.dev/gemma/docs/core/gemma_on_gemini_api); see the [Live API WebSocket guide](https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket) for voice.

OpenAI remains the default personal-key provider and its WebRTC/server-session path is unchanged. Google AI Studio is a direct client path, so no Mural API or database migration is required for that provider; Live voice still requires the platform microphone permission. Hosted-account conversations continue to use OpenAI regardless of the personal-key selection. The companion Thai locale needs the updated server language admission before a release enables Thai with Mural minutes; this PR does not deploy production.

Google AI Studio keys are personal billing credentials. Use a restricted key where possible, keep it out of logs and screenshots, and rotate it in AI Studio if the device is lost. Google recommends ephemeral tokens for production client applications; this repository’s personal-build flow follows the requested API-key model instead.
