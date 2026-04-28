package io.temporal.samples.kyc.workflow;

import io.temporal.samples.kyc.model.ApplicationRequest;
import io.temporal.samples.kyc.model.ApplicationStatus;
import io.temporal.samples.kyc.model.ComplianceDecision;
import io.temporal.samples.kyc.model.OnboardingState;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Single workflow covering the full customer onboarding / KYC lifecycle:
 *
 * <p>Application Submitted → Document Storage → KYC Vendor Check → (optional) Manual Compliance
 * Review → Sanctions Screening (new policy) → Account Activation
 *
 * <h3>Human-in-the-loop</h3>
 *
 * Updates are provided for a compliance officer to deliver a review decision: {@link
 * #approveApplication} / {@link #rejectApplication} — synchronous Updates that validate the
 * workflow is in the correct review state before accepting the decision.
 *
 * <h3>Operator visibility</h3>
 *
 * Call {@link #getOnboardingState()} at any time to see the current step, KYC status, review
 * deadline, and progress percentage. Search attributes are upserted at each transition.
 */
@WorkflowInterface
public interface CustomerOnboardingWorkflow {

  /**
   * Main workflow entry point. WorkflowId should be set to {@code "KYC-<customerId>"} by the caller
   * to ensure at most one active onboarding per customer.
   */
  @WorkflowMethod
  ApplicationStatus onboard(ApplicationRequest request);

  // ── Human-in-the-loop: Update (validated, synchronous) ─────────────────────

  /**
   * Update: Compliance officer approves the application while it is waiting in manual review.
   *
   * @return the recorded {@link ComplianceDecision} as confirmation
   */
  @UpdateMethod
  ComplianceDecision approveApplication(String reviewerId);

  /**
   * Update: Compliance officer rejects the application while it is waiting in manual review.
   *
   * @return the recorded {@link ComplianceDecision} as confirmation
   */
  @UpdateMethod
  ComplianceDecision rejectApplication(String reviewerId, String reason);

  // ── Operator visibility: Query ───────────────────────────────────────────────

  /**
   * Query: Return a read-only snapshot of current workflow state. Safe to call at any point,
   * including on completed workflows.
   */
  @QueryMethod
  OnboardingState getOnboardingState();
}
