# 08: Untimed Conversations toggle

**What to build:** One toggle in Settings > Advanced — untimed on-device conversations, default off — that lets a learner keep an on-device session going without a length limit. When on, an on-device session ends only through the natural limits the app already applies (going quiet, ending manually); timed behavior and every other provider are unchanged. Demoable: toggle on, hold a session longer than the conversation-length preference, and nothing cuts it off.

**Blocked by:** 07 (Local voice Talk)

**Status:** ready-for-agent

- [ ] The toggle lives in Settings > Advanced next to the on-device model picker, defaults to off, and persists
- [ ] With it on, on-device sessions never end by time: only the existing idle end or manual end apply, and no limit reason or report is triggered by duration
- [ ] With it off, timed on-device behavior is unchanged, including the standard end flow
- [ ] The toggle affects only On-Device Model sessions; cloud providers are untouched
- [ ] The preference and its policy are unit tested alongside the existing provider policy tests
