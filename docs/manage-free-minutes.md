# How to manage free minutes

Run these commands from Mural's server directory on a trusted operator machine, using its private `DATABASE_URL`. Apply database migrations first. The commands do not need an OpenAI key. Never put a production database URL in a command, screenshot, issue or checked-in file.

Use an operator database role permitted to maintain minute policy and grants. The app and website cannot call these operations. Until deployment roles and funded voice are finished, use the isolated test environment only. See [conversation minutes](conversation-minutes.md) for the behavior and current activation limits.

## Change the allowance for new accounts

Configure funding separately from the number of minutes. Both limits must permit a grant. Read the current dollar policy with:

```sh
npm run minutes-admin -- funding
```

Use its version in a private request file. Amounts below are **US cents**. This staging example reserves ten cents per granted minute, with five dollars per day and twenty dollars in total:

```json
{
  "actor": "mural-operator",
  "reason": "Staging funding limits",
  "policy": {
    "version": 1,
    "currency": "USD",
    "reserveCostPerMinuteMinor": 10,
    "dailyBudgetMinor": 500,
    "lifetimeBudgetMinor": 2000
  }
}
```

```sh
npm run minutes-admin -- set-funding < /private/path/funding.json
```

This reserve must cover voice, teaching helpers and connection overhead. It is an internal funding allowance, not the consumer price or a measured provider charge. Changing its rate affects new grants only. Previously reserved costs remain counted after a learner uses their time, signs in or deletes their account. Lowering a budget below the amount already reserved pauses new grants without changing issued minutes.

The shipped funding budgets are zero. Do not enable public trials until real provider cutoffs and helper limits are verified. A dollar allocation budget does not prevent all existing users from spending previously granted time on the same day.

Read the current policy:

```sh
npm run minutes-admin -- policy
```

Copy its `version` into a private JSON file. Set the new-user allowance and the maximum welcome minutes allocated per UTC day and over the campaign lifetime. The following is an example for staging, not the approved production budget:

```json
{
  "actor": "mural-operator",
  "reason": "Staging trial policy",
  "policy": {
    "version": 1,
    "welcomeEnabled": true,
    "welcomeMinutes": 10,
    "dailyWelcomeBudgetMinutes": 100,
    "lifetimeWelcomeBudgetMinutes": 1000
  }
}
```

Apply the file:

```sh
npm run minutes-admin -- set-policy < /private/path/policy.json
```

If another operator changed the policy, this fails with `policy_changed_review_again`. Read it again and review the new version. To stop offering time to new accounts, set `welcomeEnabled` to `false`. Existing offers and balances remain intact.

Do not lower `welcomeMinutes` to reclaim existing minutes. Daily and lifetime budgets govern new welcome allocations; they do not directly stop funded sessions or remove issued time.

## Issue minutes to selected users

Prepare a private request file. Generate a new UUID for this campaign and keep it for retries. Use existing account IDs; do not include email addresses or sensitive details in the reason.

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "actor": "mural-operator",
  "reason": "Early tester thank you",
  "audience": ["22222222-2222-4222-8222-222222222222"],
  "minutesPerUser": 30,
  "maxTotalMinutes": 30
}
```

Replace those example IDs, then prepare the campaign:

```sh
npm run minutes-admin -- prepare-grant < /private/path/grant.json
```

Preparation records the intended recipients but grants no time. Review the returned recipient count and total minutes. To issue time to every current account, use `"audience": "all-current-users"` and a deliberate `maxTotalMinutes` cap. This includes registered accounts, not waitlist email addresses or future signups.

Apply the reviewed campaign by submitting its returned `campaignID` and `confirmation`:

```json
{
  "campaignID": "11111111-1111-4111-8111-111111111111",
  "confirmation": "copy-the-returned-confirmation-digest-here"
}
```

```sh
npm run minutes-admin -- apply-grant < /private/path/apply.json
```

The result reports granted, skipped and pending recipients. If the process is interrupted, rerun the same apply request. Do not generate a new campaign ID to retry a grant. A new ID represents a separate gift.

Keep the campaign ID with the operational record. The database retains the operator label, reason, recipients and immutable minute entries. Use these records for reconciliation instead of changing balances directly.
