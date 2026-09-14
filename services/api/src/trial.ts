import { ServiceError } from './errors.js';
import { transaction, type Database } from './db.js';
import { TRIAL_MS } from './pricing.js';

// Implemented adapters must validate Apple's certificate chain, nonce, app identity,
// signature counter and DeviceCheck promotion state. No client ID is accepted as proof.
export interface TrialAttestor {
  verify(proof: unknown): Promise<{ deviceReference: string; attestationKeyID: string; previouslyClaimed: boolean }>;
}
export class UnconfiguredAttestor implements TrialAttestor {
  async verify(_proof: unknown): Promise<never> { throw new ServiceError('trial_attestation_unavailable', 503); }
}
export async function trialEligibility(db: Database, proof: unknown, attestor: TrialAttestor) {
  const verified = await attestor.verify(proof);
  if (verified.previouslyClaimed) throw new ServiceError('trial_already_claimed', 403);
  return transaction(db, async sql => {
    await sql.query(`INSERT INTO trial_devices(device_reference,attestation_key_id) VALUES($1,$2)
      ON CONFLICT(device_reference) DO NOTHING`, [verified.deviceReference, verified.attestationKeyID]);
    const device = (await sql.query('SELECT * FROM trial_devices WHERE device_reference=$1 FOR UPDATE', [verified.deviceReference])).rows[0];
    if (device.blocked || device.attestation_key_id !== verified.attestationKeyID) throw new ServiceError('trial_unavailable', 403);
    return { remainingMilliseconds: Math.max(0, TRIAL_MS - Number(device.used_ms) - Number(device.reserved_ms)) };
  });
}
