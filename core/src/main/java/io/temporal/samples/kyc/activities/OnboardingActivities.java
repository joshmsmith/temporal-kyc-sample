package io.temporal.samples.kyc.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.samples.kyc.model.ActivateAccountInput;
import io.temporal.samples.kyc.model.ApplicationRequest;
import io.temporal.samples.kyc.model.KycCheckInput;
import io.temporal.samples.kyc.model.KycResult;

/**
 * All activities executed as part of the customer onboarding workflow.
 *
 * <p>Each method maps to one integration point. Implementations interact with real systems
 * (document store, KYC vendor, compliance queue, account service, Postgres audit log). The stub
 * implementation simulates these with log statements and controlled delays.
 */
@ActivityInterface
public interface OnboardingActivities {

  /**
   * Store uploaded customer documents in the document store. Returns the document bundle ID used
   * for downstream checks. Retryable — idempotent if called more than once with the same request.
   */
  @ActivityMethod
  String storeDocuments(String idempotencyKey, ApplicationRequest request);

  /**
   * Submit the document bundle to the KYC vendor and wait for a result. Long-running: up to 10
   * minutes per attempt, heartbeating every 30 seconds. Non-retryable on KycHardFailException.
   */
  @ActivityMethod
  KycResult performKycCheck(KycCheckInput input);

  /**
   * Create a ticket in the compliance review queue for manual human review. Returns the ticket ID
   * for audit trail purposes.
   */
  @ActivityMethod
  String submitToComplianceQueue(String idempotencyKey, String customerId, KycResult kycResult);

  /**
   * Escalate a stalled review to the compliance manager when the 30-day SLA is breached. Called
   * before the workflow fails with ReviewTimeout.
   */
  @ActivityMethod
  void escalateReview(String idempotencyKey, String customerId);

  /**
   * Activate the customer account in the account service. The idempotency key ensures this is safe
   * to retry: if called twice with the same key the second call returns the existing account ID.
   */
  @ActivityMethod
  String activateAccount(ActivateAccountInput input);

  /**
   * Append an immutable event to the audit log in Postgres. Best-effort (max 3 attempts), but never
   * blocks workflow progress on persistent failure.
   */
  @ActivityMethod
  void logAuditEvent(String eventType, String customerId, String details);

  /**
   * Notify the customer of an onboarding status change via email/SMS. Fire-and-forget from the
   * workflow's perspective — transient failures are retried by the activity retry policy.
   */
  @ActivityMethod
  void notifyCustomer(String idempotencyKey, String customerId, String status, String message);
}
