package io.temporal.samples.kyc.model;

/** Result status from the sanctions screening check (new policy gate). */
public enum SanctionsStatus {
  CLEAR,
  FLAGGED
}
