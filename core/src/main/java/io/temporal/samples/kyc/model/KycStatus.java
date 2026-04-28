package io.temporal.samples.kyc.model;

/** Result status returned by the KYC vendor. */
public enum KycStatus {
  PASSED,
  FAILED,
  NEEDS_MANUAL_REVIEW
}
