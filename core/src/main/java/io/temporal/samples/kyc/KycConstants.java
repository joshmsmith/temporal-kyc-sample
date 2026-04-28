package io.temporal.samples.kyc;

/** Shared constants used by worker, starter, and approver. */
public final class KycConstants {

  public static final String TASK_QUEUE = "kyc-onboarding";

  /** WorkflowId prefix. Full ID = KYC_WORKFLOW_ID_PREFIX + customerId */
  public static final String KYC_WORKFLOW_ID_PREFIX = "KYC-";

  private KycConstants() {}
}
