# 03: Curated model catalogue + in-app download

**What to build:** The on-device model picker grows from manual file import into a curated catalogue a learner can download from directly: Gemma 4 E4B as the default recommendation, Gemma 4 E2B and Qwen2.5-1.5B alongside, each showing its size, downloading in-app with progress, and deletable to reclaim storage. URL import and local-file import remain. Demoable: on a fresh install, Advanced > download E4B, watch progress, and the provider becomes ready.

**Blocked by:** 02 (On-Device Model provider + offline typed conversations)

**Status:** done

- [ ] The curated list shows Gemma 4 E4B (default), Gemma 4 E2B and Qwen2.5-1.5B with sizes and a short description each
- [ ] Models download in-app with progress, can be cancelled, and interrupted downloads recover or restart cleanly
- [ ] Downloaded models can be deleted from the same screen, reclaiming storage
- [ ] A direct model URL can be pasted and downloaded through the same flow; local-file import still works
- [ ] Exactly one global active model serves all languages and Helpers; switching is one action
- [ ] Provider readiness reflects download state; a learner mid-download is told why the provider cannot start yet
- [ ] The learner is warned before multi-gigabyte downloads and before downloading with little free storage
- [ ] Catalogue state and download planning are unit tested at the Model Runtime/policy seams

## Comments

- 2026-11-27: Claimed by foreman. Worktree `../mural-worktrees/03-model-catalogue-download`, branch `ticket/03-model-catalogue-download`, base `feature/on-device-model` @ 7fb0398.
- 2026-11-27: Done. Ticket branch `d3fa765`, merged into `feature/on-device-model` as `73f29a1`. Curated model catalogue (Gemma 4 E4B default, Gemma 4 E2B, Qwen2.5-1.5B), OkHttp streaming downloader with `.part` + `Range` resume/cancel, model deletion, HTTPS URL import, storage/memory download planner, and Advanced UI + JVM tests.
