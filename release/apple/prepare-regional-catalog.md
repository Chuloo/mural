# How to prepare an Apple regional catalog

Prepare reviewed Apple checkout rows for both live and test purchases. This guide produces protected deployment candidates.

## Prerequisites

- Current App Store Connect price CSV and ZIP downloads for all three minute packs.
- A reviewed `apple-storefronts.json` country decision and the recorded ISO currency exponents.
- API dependencies installed and an isolated PostgreSQL test database.

## Prepare and verify

1. Save the original downloads as `small-prices`, `medium-prices` and `large-prices`, with `.csv` and `.zip` extensions, in a protected private directory.
2. Record one `export-manifest.json` entry per pack: `pack`, `sourceURL`, `capturedAt`, ZIP `sha256` and `csvSHA256`. Use each pack's exact App Store Connect purchase page URL. The importer normalizes capture timestamps to UTC.
3. Run the offline generator from the repository root:

   ```sh
   services/api/node_modules/.bin/tsx release/apple/generate-regional-apple-catalog.mts /absolute/private/export-directory reviewed-schedule-version
   ```

   With no arguments, it uses `release/private/apple-pricing-20261004` and `asc-20261004-v1`.
4. Check `catalog-summary.json`: the current export has 175 priced countries, 162 eligible countries, 40 eligible currencies and 486 rows per environment. Confirm the US prices remain $7/$13/$20 and credits remain 369/766/1161 cents. Resolve any import failure before continuing; the generator rejects changed source hashes, incomplete or duplicate countries, mismatched pack currencies and invalid decimals.
5. Merge the generated live and test Apple rows into a copy of the existing protected catalog, preserving every non-Apple row. Validate the complete candidate with both Apple verifiers and its approved hash. Retain the original catalog for rollback.
6. Run the API check, build and full isolated database suite. Follow the repository deployment checks, take the encrypted backup, and apply migration 033 before activating the new server/catalog pair. Configure the three Apple products for the reviewed countries with future countries disabled.

## Result

`apple-global-catalog-live.json` and `apple-global-catalog-test.json` contain environment-specific rows. `normalized-price-snapshot.json` retains every exported country, including exclusions; `catalog-summary.json` records output counts and hashes. All generated files are private candidates with mode `0600`. Store availability and server activation remain separate release steps.

For quote fields, amount limits and historical order compatibility, see [Apple regional pricing reference](regional-pricing.md).
