package io.temporal.samples.kyc.model;

/**
 * Demo scenario to simulate different KYC outcomes without a real vendor.
 *
 * <p>HAPPY_PATH - KYC passes immediately, account activated. NEEDS_REVIEW - KYC vendor flags for
 * manual compliance review; a human must signal/update the workflow. HARD_FAIL - KYC vendor returns
 * a hard rejection; workflow ends as REJECTED. API_DOWNTIME - KYC vendor times out several times
 * before succeeding, demonstrating retries. SANCTIONS_FLAGGED - New policy path (sanctions
 * screening); KYC passes but sanctions check flags the applicant.
 */
public enum ApplicationScenario {
  HAPPY_PATH,
  NEEDS_REVIEW,
  HARD_FAIL,
  API_DOWNTIME,
  SANCTIONS_FLAGGED
}
