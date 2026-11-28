# 17: Parallel model downloads and independent deletes

**What to build:** The on-device model manager handles several downloads at once: starting a download never blocks starting another, each model row shows its own progress and its own cancel, and deleting a stored model works immediately even while downloads are in flight — never queued behind one. Today the manager owns a single download slot: a second download can't start, cancelling stops whatever is downloading, and deletes wait. Demoable: start Gemma 4 E2B and Qwen2.5 downloads together, watch both progress bars move independently, delete an old model mid-flight, cancel one download and see the other continue.

**Blocked by:** 03 (Curated model catalogue + in-app download — done)

**Status:** ready-for-agent

- [ ] Starting a second (and third…) download while one is in flight works; each row in Settings > Advanced shows its own progress and its own cancel
- [ ] Cancelling one download leaves every other download untouched; the cancelled model's partial file still resumes on the next attempt
- [ ] Deleting a downloaded model works while any download is in flight and takes effect immediately; deleting several models does not serialize behind downloads or each other
- [ ] The pre-download storage check accounts for that download plus all currently in-flight downloads together, rejecting with the existing out-of-storage message when the combination would not fit
- [ ] A model that finishes downloading while another is still running becomes selectable and provider-ready without waiting for the other
- [ ] Downloading the same model twice concurrently is still prevented
- [ ] A mid-download relaunch recovers each in-flight download independently, per the existing partial-file resume
- [ ] Unit tests cover per-model download state, per-model cancellation, delete-during-download, and the combined storage planning; the existing download-planner tests extend to the multi-flight case
