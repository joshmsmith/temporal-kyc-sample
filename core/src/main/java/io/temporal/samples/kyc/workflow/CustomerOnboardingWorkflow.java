package io.temporal.samples.kyc.workflow;

import io.temporal.samples.kyc.model.ApplicationRequest;
import io.temporal.samples.kyc.model.ApplicationStatus;
import io.temporal.samples.kyc.model.ComplianceDecision;
import io.temporal.samples.kyc.model.OnboardingState;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
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
 * Two mechanisms are provided for a compliance officer to deliver a review decision:
 *
 * <ul>
 *   <li>{@link #approveApplication} / {@link #rejectApplication} — fire-and-forget Signals,
 *       suitable for CLI tools and async integrations
 *   <li>{@link #submitComplianceDecision} — synchronous Update, suitable for systems that need
 *       validation confirmation (rejects duplicate decisions and wrong-state decisions before they
 *       are persisted in history)
 * </ul>
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

  // ── Human-in-the-loop: Signal (fire-and-forget) ────────────────────────────

  /**
   * Signal: Compliance officer approves the application while it is waiting in manual review.
   * Ignored if the workflow is not in the MANUAL_REVIEW_PENDING step.
   */
  @SignalMethod
  void approveApplication(String reviewerId);

  /**
   * Signal: Compliance officer rejects the application while it is waiting in manual review.
   * Ignored if the workflow is not in the MANUAL_REVIEW_PENDING step.
   */
  @SignalMethod
  void rejectApplication(String reviewerId, String reason);

  // ── Human-in-the-loop: Update (synchronous with validation) ─────────────────

  /**
   * Update: Deliver a compliance review decision synchronously. Returns the current
   * ApplicationStatus. The validator rejects the call if:
   *
   * <ul>
   *   <li>The workflow is not in the MANUAL_REVIEW_PENDING step, or
   *   <li>A decision has already been recorded.
   * </ul>
   */
  @UpdateMethod
  ApplicationStatus submitComplianceDecision(ComplianceDecision decision);

  /** Validator for submitComplianceDecision — must not modify state or block. */
  @UpdateValidatorMethod(updateName = "submitComplianceDecision")
  void validateComplianceDecision(ComplianceDecision decision);

  // ── Operator visibility: Query ───────────────────────────────────────────────

  /**
   * Query: Return a read-only snapshot of current workflow state. Safe to call at any point,
   * including on completed workflows.
   */
  @QueryMethod
  OnboardingState getOnboardingState();
}
