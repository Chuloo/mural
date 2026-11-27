# Mural

Mural is a language app an adult eventually deletes: they practise a new language through
conversation, and their learning records stay on their own device.

## Language

**Conversation Provider**:
The explicitly chosen source of AI for a conversation — **Personal Key**, **Mural Minutes**, or
**On-Device Model**. Never silently substituted: an unavailable provider never authorizes use of
another.
_Avoid_: mode, backend, engine (when a provider is meant)

**Personal Key**:
A **Conversation Provider** where the learner's own OpenAI API key pays for cloud AI.

**Mural Minutes**:
A **Conversation Provider** where a prepaid hosted balance pays for cloud AI.

**On-Device Model**:
A **Conversation Provider** that runs the whole conversation on the learner's phone. While active,
the session is strictly offline: no network call is made.
_Avoid_: offline mode, local mode, fallback

**Model Runtime**:
The engine on the phone that executes an **On-Device Model** (today: LiteRT-LM or AICore). A model
belongs to exactly one runtime.
_Avoid_: provider (when the engine is meant)

**Helper**:
A single-turn background request that supports a conversation — meaning subtitle, assessment,
lookup, delegation, typed reply, topic or help — distinct from the live conversation itself.
_Avoid_: tool, feature call

**Language Module**:
A learning language together with its variety, guidance, themes and greeting — e.g. Portuguese
(Brazil). Two varieties of one language are two **Language Modules**.
_Avoid_: locale, language pack

**Typed-only Module**:
A **Language Module** with no voice on any provider: both sides of the conversation are typed
(e.g. Roman Konkani, Jamaican Patois).
_Avoid_: silent module, text mode

**Custom Service**:
A future **Conversation Provider** pointing at a learner-chosen cloud endpoint. Online by nature;
not part of **On-Device Model**.

**Untimed Conversation**:
An **On-Device Model** session with no conversation-length limit; it ends only by idle or manual
end. Timed on-device sessions use the same conversation length as every other provider.

**Session Provenance**:
Which **Conversation Provider** produced a session. Recorded with the session and preserved in
backups; a session's provenance is never rewritten.
