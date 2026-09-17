# Use an OpenAI-compatible endpoint

Mural can send conversations to your own OpenAI-compatible server instead of OpenAI. Open **Settings → Advanced → Custom endpoint**, fill in the fields, turn on **Use this endpoint** and save. Turn it off to go back to your OpenAI key; both stay saved.

## What the server must offer

| Mural feature | Request | Needs |
| --- | --- | --- |
| Replies, meanings, word lookup, assessments | `POST {base}/chat/completions` or `POST {base}/responses` | Choose the matching **API style**. Assessments ask for strict JSON-schema output; a server that rejects it still converses, but records no learning evidence. |
| Voice: hearing you | `POST {base}/audio/transcriptions` | Multipart `file` (16 kHz mono WAV), `model`, `response_format=json`, and `language` set to the learning language (Norwegian is sent as `no`). Must return `{"text": "…"}`. |
| Voice: speaking | `POST {base}/audio/speech` | JSON `model`, `voice`, `input`, `response_format: "wav"`. Must return 16-bit PCM WAV. |

- **Base URL** must use `https://` and usually ends in a version path, such as `https://example.com/v1`. Plain `http://` is refused, so a server on your home network needs HTTPS through a reverse proxy or tunnel.
- **API key** is optional, but it can only be saved together with a base URL. When set, it is sent as `Authorization: Bearer …` to that server only, stored encrypted on the device and excluded from backups. Redirects are never followed.
- Conversations start with voice, so the transcription model, speech model and voice are all needed to start one. The chat model alone still covers meanings and word lookups in past conversations.
- **Skip model thinking** adds `chat_template_kwargs: {"enable_thinking": false}` to chat requests. Reasoning models such as Qwen on vLLM can otherwise take over 30 seconds per reply, and assessments can exceed the app's 60-second limit. Leave it off for servers that reject unknown fields, such as OpenAI.

## Check a server before using it

`scripts/check_custom_endpoint.py` runs four checks with the requests the app sends: a reply, an assessment, speech, and a transcription of that speech in the learning language. For comparison it also transcribes the same audio without a language, which doesn't affect the result. It asks for the API key without echoing it, or reads `MURAL_ENDPOINT_KEY`, and saves the generated speech so you can listen to it. It needs Python 3 and nothing else.

```sh
python scripts/check_custom_endpoint.py --base-url https://example.com/v1 \
  --chat-model MODEL --transcription-model MODEL --speech-model MODEL --voice VOICE --language es
```

## How voice differs from OpenAI voice

- It takes turns. Mural stops listening while it thinks and speaks, so you can't interrupt it. A pause of about 1.2 seconds ends your turn.
- Replies arrive a few seconds later than realtime voice, depending on your server.
- Speech recognition can quietly "fix" a learner's mistakes, so corrections and assessments see what the server heard.
- Transcription is told to expect the learning language, because auto-detection can turn accented speech into an English translation. A reply in another language may therefore transcribe poorly.
- The accent guidance can't shape a text-to-speech voice. If your server's voices are tied to one language, change **Voice name** when you switch learning language.
- Speech detection uses energy thresholds tuned for a quiet room. If Mural cuts you off or never hears you, adjust `SpeechDetector` in `apps/android/app/src/main/java/chat/mural/network/TurnTransport.kt` and `apps/ios/Core/SpeechTurns.swift`.

## Not available with a custom endpoint

- Web search, so **current topics** can't find sourced articles.
- The voice-cost estimate in Settings, which prices OpenAI voice only and is hidden while the endpoint is on. Your server's own dashboard or logs are authoritative.
