# Assets and sources

This page records the provenance that a public contribution can make visible. It does not include private deployment data or machine-specific signing settings.

## Ogden Basic

The 850-word curriculum, meanings, definitions, examples, related-word lists, IPA, and 1,700 bundled US/UK word recordings are adapted from the fixed [longlong-skyligo/Ogden revision `9d924597f047342b7cd78ba7ac928d940d3190ed`](https://github.com/longlong-skyligo/Ogden/tree/9d924597f047342b7cd78ba7ac928d940d3190ed). The source repository identifies itself as a secondary creation based on [ogden.munch.love](https://ogden.munch.love/) and supplies an MIT licence. The copied licence and attribution are preserved in [Ogden-NOTICE.txt](../apps/ios/Core/Resources/Ogden-NOTICE.txt).

The app keeps stable word IDs and saved-progress compatibility. A small set of editorial changes is documented in the notice file. Semantic screening used AI assistance; it is not a substitute for a human dictionary editor. IPA and individual recordings are not claimed to have independent expert review, and the source does not identify the recording speaker or provider. Related words are not automatically synonyms. Maintainers should recheck the pinned revision and its licence before merging or redistributing the data.

## Generated visual concepts

The 22 added orb concepts were created through an authenticated OpenAI image-generation workflow: ten approved concepts from an earlier visual board and twelve additional concepts. The generation tool did not expose a verifiable model version, so no specific image-model version is claimed. The PNGs retain their embedded C2PA provenance metadata. Their native animation is implemented separately in Swift and Metal.

For OpenAI-generated output, the current [OpenAI Terms of Use](https://openai.com/policies/row-terms-of-use/) state that, as between the user and OpenAI and to the extent permitted by law, the user retains rights in input and owns output. The same terms also say that outputs may not be unique and that the user is responsible for having the rights needed for submitted input. Those terms do not establish exclusivity, third-party clearance, or a licence for unrelated source material. Contributors must review the applicable account terms and local law before publishing visual assets under this project's MIT licence.

The contributor supplies these generated assets under Mural's MIT licence to the extent of the contributor's rights. This is not a claim of exclusive copyright or a guarantee of third-party clearance. No user photos, personal screenshots, account-bearing generation logs, or private subscription-bridge records are included.

## Existing third-party material

The repository's root `LICENSE` remains the governing licence for Mural code. Existing notices in `apps/ios/App/ThirdPartyNotices.txt` must remain with the project, including the Google Sign-In branding notice and the Google WebRTC BSD notice. These notices do not grant permission to reuse Google trademarks or artwork as Mural branding.

## Public-release checklist

- Verify every added file has a source, licence, or an explicit statement that it is original project code.
- Exclude `Config/Local.xcconfig`, personal signing identifiers, private hostnames and ports, bridge pairing data, tokens, local paths, verification screenshots/logs, and internal plans.
- Keep the Ogden notice and pinned revision visible when the curriculum or recordings are shipped.
- Run the project tests and cross-platform checks required by `CONTRIBUTING.md`; report device, audio, subtitle, import, and motion checks separately.
