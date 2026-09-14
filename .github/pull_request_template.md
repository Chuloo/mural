## Summary

<!-- What changed and why. -->

## Checklist

- [ ] Describes the user-visible change, or states there is none.
- [ ] Says how it was verified (device/simulator, or why that wasn't possible).
- [ ] A change under `apps/ios/Core/` has the matching change under `apps/android/app/src/main/java/chat/mural/core/`, verified with `python3 scripts/check_cross_platform.py`.
- [ ] Fixtures under `shared/fixtures/cross-platform/` are updated if shared behavior changed.
- [ ] Notes any remaining limitations.
