# Mural Subscription Bridge

This optional adapter lets the Mural iPhone client use the signed-in ChatGPT account available to a locally running Codex CLI. It provides account status, voice discovery, WebRTC signalling, short classroom updates, and structured teaching responses. Audio remains on the WebRTC path; the bridge handles signalling and the explicitly supported classroom events.

The bridge is a community adapter for a local installation. It is not iPhone OAuth, does not expose an OpenAI API key, and is not an official OpenAI subscription API or compatibility guarantee. It relies on the private/local Codex app-server and realtime protocol; that protocol may change, and a compatible Codex CLI version must be tested before deployment.

## Local setup

Use a supported Codex CLI that is already signed in with ChatGPT. From this directory:

```sh
npm run setup
CODEX_BIN=/absolute/path/to/codex npm start
```

`npm run setup` creates a random pairing token and never prints it. The token is stored in `.local/bridge-token`, which must remain private and have mode `0600`. The service refuses symlinks, unexpected ownership, and loose permissions. Keep `.local/` out of Git.

The bridge binds to loopback by default. To connect an iPhone, place it behind an HTTPS endpoint that you control and that forwards only to the loopback listener, for example `https://bridge.example.test/` to `http://127.0.0.1:8787`. Use a private network or access gateway with its own authentication and firewall policy. Do not expose the listener directly to the public internet. The iPhone stores the pairing token in its own device-only Keychain; it does not receive the Codex or ChatGPT sign-in credentials.

The endpoint, certificate, process manager, and firewall are deployment-specific and are deliberately not included here. Do not commit real hostnames, ports, personal paths, launch-agent files, pairing tokens, OAuth values, or machine-specific installation notes. Use environment variables or an ignored local configuration file for local values.

## Boundaries

Each request or live session gets a dedicated Codex child process and ephemeral workspace. Shell access, plugins, hooks, app connectors, memory, browser/computer control, agents, and other high-risk capabilities are disabled. The child uses the named read-only classroom profile, with network access disabled; built-in provider web search is enabled only for the explicitly supported current-facts response route. Unexpected external-tool startup or server requests fail closed.

Only the active realtime thread can request the empty-argument `mural_support` classroom tool. The iPhone returns the result for that one pending request. The bridge limits live sessions and text/account requests, expires idle sessions, bounds event history and request sizes, and does not persist classroom transcripts, pairing codes, or account credentials. Provider retention rules still apply.

The adapter validates completed responses and Mural's bounded JSON schema vocabulary. It does not turn model-written URLs into citations; sources must come from provider search metadata. Actual WebRTC audio, subscription entitlements, latency, and protocol compatibility must be verified on the target device and are not proven by unit tests alone.

## Verification and rollback

Run:

```sh
npm test
```

These tests cover request validation, token handling, process isolation, session limits, event lifecycle, and deterministic RPC contracts. They do not prove a signed iPhone build, real audio, or the absence of vulnerabilities in the installed Codex CLI and its dependencies.

Stop a foreground instance with `SIGINT` or `SIGTERM`; it closes child processes and live sessions. A deployment should provide its own service-manager stop and rollback procedure. To revoke access, stop the service, rotate the pairing token intentionally, and reset only the Mural connection that used it. Do not treat app removal as a credential-reset shortcut when local learning records matter.

## Voice and account behavior

The subscription route follows the existing reader's Codex V3 realtime transport and loads the currently available V1-family voices dynamically. Unsupported voice choices are rejected instead of silently substituted. The app's managed-account configuration remains disabled by default; local signing and service values belong in ignored local configuration files.
