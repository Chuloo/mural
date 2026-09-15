-- Tax-inclusive provider totals have a separate representation bound from catalog prices.
-- Match Number.MAX_SAFE_INTEGER so every SDK amount can be checked and converted to BigInt exactly.
ALTER TABLE minute_stripe_paid_totals
  DROP CONSTRAINT minute_stripe_paid_totals_gross_minor_check,
  ADD CONSTRAINT minute_stripe_paid_totals_gross_minor_check
    CHECK (gross_minor BETWEEN 1 AND 9007199254740991) NOT VALID;
