# AI providers (AstOS 3.3)

Open **AI providers** from the dashboard or Settings. Add a provider, enter its API key, fetch the models available to that account, choose a text-generation model, then save. **Test provider** makes a billable request. ChatGPT/Claude consumer subscriptions are not API credits.

| Preset | API base | Request format |
| --- | --- | --- |
| Groq | https://api.groq.com/openai/v1 | OpenAI-compatible chat |
| OpenAI | https://api.openai.com/v1 | Chat completions |
| Anthropic / Claude | https://api.anthropic.com/v1 | Native Messages |
| Google Gemini | https://generativelanguage.googleapis.com/v1beta | Native generateContent |
| OpenRouter | https://openrouter.ai/api/v1 | OpenAI-compatible chat |
| Mistral AI | https://api.mistral.ai/v1 | OpenAI-compatible chat |
| Custom | User supplied | Choose OpenAI-compatible, Anthropic or Gemini |

Model names are account-dependent: model discovery is preferable to copying a stale name. Some listed models are for embeddings/audio rather than chat; select a text-capable model. Groq's previous Llama defaults may not be available to every account; existing legacy Groq keys migrate to `openai/gpt-oss-20b`. Fetch models if the account rejects it. HTTP 404 now explains model/endpoint selection instead of a generic Groq error.

Providers are tried in displayed order. **Fallback is off by default.** Enabling it explicitly permits the same prompt to be sent to subsequent enabled providers and may incur additional costs. Connection failures, authentication/quota failures, missing endpoints/models, timeouts and server errors may fall back. Invalid requests, refusals, empty/safety-blocked responses do not automatically fall back. Disabled providers are skipped. Move providers up/down to set priority.

Keys stay in the shell app's private preferences; they are not exported through companion setup snapshots. This is not hardware-backed key encryption; trusted Java plugins running in the app share its privileges. Install only plugins you trust. HTTPS is required except loopback HTTP for local custom services. Redirects are not followed with credentials.

Use **Type a prompt** when speech is unsuitable. Offline Vosk displays live partial text and the final transcript. Optional Groq Whisper transcribes after recording (not live interim captions); it uses a configured Groq key and is independent of the conversational provider. No claim is made that microphone or accent accuracy is physically validated. Existing PC playback commands are locally routed; arbitrary AI replies do not execute code/tools.

## Documentation consulted

Context7 was used to retrieve official Android/Spicetify documentation and Anthropic request guidance. Official references were also checked for the current provider endpoints:

- Android navigation/system UI: https://developer.android.com/reference/android/view/View#SYSTEM_UI_FLAG_FULLSCREEN
- Android discoverability: https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices
- Spicetify Player events/progress/playback: https://spicetify.app/docs/development/api-wrapper/methods/player/
- Spicetify documentation source: https://github.com/spicetify/docs/blob/main/docs/development/api-wrapper/methods/player.md
- Anthropic Messages: https://platform.claude.com/docs/en/api/messages/create
- Google native generateContent: https://ai.google.dev/api/generate-content
- OpenAI chat API: https://platform.openai.com/docs/api-reference/chat/create
- Groq account models: https://console.groq.com/docs/models
- OpenRouter: https://openrouter.ai/docs/quickstart
- Mistral: https://docs.mistral.ai/api

Provider contract tests use local mock HTTP servers, not live paid APIs or fabricated successful credentials.
