package io.temporal.samples.kyc.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.samples.kyc.activities.OnboardingActivities;
import io.temporal.samples.kyc.model.ActivateAccountInput;
import io.temporal.samples.kyc.model.ApplicationRequest;
import io.temporal.samples.kyc.model.ApplicationScenario;
import io.temporal.samples.kyc.model.ApplicationStatus;
import io.temporal.samples.kyc.model.ComplianceDecision;
import io.temporal.samples.kyc.model.KycCheckInput;
import io.temporal.samples.kyc.model.KycResult;
import io.temporal.samples.kyc.model.KycStatus;
import io.temporal.samples.kyc.model.OnboardingState;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;

public class CustomerOnboardingWorkflowImpl implements CustomerOnboardingWorkflow {

  private static final Logger log = Workflow.getLogger(CustomerOnboardingWorkflowImpl.class);

  // ── Search attribute keys (register in Temporal namespace before running) ──
  static final SearchAttributeKey<String> APPLICATION_STEP =
      SearchAttributeKey.forKeyword("ApplicationStep");
  static final SearchAttributeKey<String> CUSTOMER_ID_ATTR =
      SearchAttributeKey.forKeyword("CustomerId");
  static final SearchAttributeKey<String> KYC_STATUS_ATTR =
      SearchAttributeKey.forKeyword("KycStatus");
  static final SearchAttributeKey<String> REVIEW_DEADLINE =
      SearchAttributeKey.forKeyword("ReviewDeadline");

  // ── Activity stubs — three distinct retry / timeout policies ──────────────

  /** Default: short-lived steps (document storage, queue submission, notification). */
  private final OnboardingActivities onboardingActivities =
      Workflow.newActivityStub(
          OnboardingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofSeconds(30))
                      .setMaximumAttempts(5)
                      .build())
              .build());

  /**
   * KYC vendor: potentially slow, heartbeats required, hard-fail is non-retryable.
   *
   * <p>scheduleToClose caps total time including all retries at 1 hour, preventing a stuck KYC
   * check from blocking the workflow indefinitely.
   */
  private final OnboardingActivities kycActivities =
      Workflow.newActivityStub(
          OnboardingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(10))
              .setScheduleToCloseTimeout(Duration.ofHours(1))
              .setHeartbeatTimeout(Duration.ofSeconds(45))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(2))
                      .setBackoffCoefficient(2.0)
                      .setMaximumInterval(Duration.ofMinutes(2))
                      .setMaximumAttempts(10)
                      .build())
              .build());

  // ── Mutable workflow state (survives replay because it is derived from deterministic code) ─

  private String step = "SUBMITTED";
  private String kycStatusStr = "PENDING";
  private int progressPct = 0;
  private Instant reviewDeadline = null;

  // Set by signal when the workflow is in MANUAL_REVIEW_PENDING
  private ComplianceDecision reviewDecision = null;

  // Cached for query handler — set at the start of onboard()
  private String customerId = "";

  // ── Workflow entry point ────────────────────────────────────────────────────

  @Override
  public ApplicationStatus onboard(ApplicationRequest request) {
    customerId = request.getCustomerId();
    ApplicationScenario scenario = request.getScenario();

    log.info("Onboarding started for customer {}, scenario={}", customerId, scenario);
    // Workflow.randomUUID() is deterministic across replays — always the same value for a given
    // point in history, making every activity call safely retryable with this key.
    String idempotencyKey = customerId + "-" + Workflow.randomUUID().toString();
    updateStep("SUBMITTED", 5);

    // ── Step 1: Store documents ─────────────────────────────────────────────
    String documentId = onboardingActivities.storeDocuments(idempotencyKey, request);
    updateStep("KYC_CHECKING", 20);

    // ── Step 2: KYC vendor check ────────────────────────────────────────────
    KycResult kycResult =
        kycActivities.performKycCheck(
            new KycCheckInput(idempotencyKey, customerId, documentId, scenario));
    kycStatusStr = kycResult.getStatus().name();
    Workflow.upsertTypedSearchAttributes(KYC_STATUS_ATTR.valueSet(kycStatusStr));
    log.info("KYC result for customer {}: {}", customerId, kycResult.getStatus());

    // ── Step 3: Hard KYC rejection ──────────────────────────────────────────
    if (kycResult.getStatus() == KycStatus.FAILED) {
      onboardingActivities.notifyCustomer(
          idempotencyKey, customerId, "REJECTED", "KYC check did not pass.");
      updateStep("REJECTED", 100);
      ApplicationStatus status = ApplicationStatus.rejected("KYC check failed");
      return status;
    }

    // ── Step 4: Manual compliance review (when KYC flags for review) ────────
    if (kycResult.getStatus() == KycStatus.NEEDS_MANUAL_REVIEW) {
      onboardingActivities.submitToComplianceQueue(idempotencyKey, customerId, kycResult);

      reviewDeadline =
          Instant.ofEpochMilli(Workflow.currentTimeMillis())
              .plusSeconds(Duration.ofDays(30).getSeconds());
      Workflow.upsertTypedSearchAttributes(REVIEW_DEADLINE.valueSet(reviewDeadline.toString()));
      updateStep("MANUAL_REVIEW_PENDING", 50);

      log.info(
          "Waiting for compliance review decision for customer {}. Deadline: {}",
          customerId,
          reviewDeadline);

      // Block until a decision arrives or the 30-day SLA expires
      // to identify in the temporal UI
      boolean decisionReceived = Workflow.await(Duration.ofDays(30), () -> reviewDecision != null);

      if (!decisionReceived) {
        // SLA breach — escalate and wait an additional 7 days before auto-rejecting
        onboardingActivities.escalateReview(idempotencyKey, customerId);
        updateStep("REVIEW_TIMEOUT", 55);
        log.info(
            "Compliance review SLA exceeded for customer {}. Waiting 7 more days after escalation.",
            customerId);

        boolean finalDecisionReceived =
            Workflow.await(Duration.ofDays(7), () -> reviewDecision != null);

        if (!finalDecisionReceived) {
          // Still no decision after grace period — auto-reject without failing the workflow
          onboardingActivities.notifyCustomer(
              idempotencyKey,
              customerId,
              "REJECTED",
              "Compliance review not completed within SLA.");
          updateStep("REJECTED", 100);
          return ApplicationStatus.rejected("Compliance review SLA exceeded");
        }
      }

      log.info(
          "Review decision received for customer {}: approved={}",
          customerId,
          reviewDecision.isApproved());

      if (!reviewDecision.isApproved()) {
        onboardingActivities.notifyCustomer(
            idempotencyKey,
            customerId,
            "REJECTED",
            "Compliance review rejected: " + reviewDecision.getReason());
        updateStep("REJECTED", 100);
        ApplicationStatus status =
            ApplicationStatus.rejected("Compliance review rejected: " + reviewDecision.getReason());
        return status;
      }
    }

    // ── Step 5: Activate account ────────────────────────────────────────────
    updateStep("ACTIVATING", 85);

    String accountId =
        onboardingActivities.activateAccount(
            new ActivateAccountInput(idempotencyKey, customerId, documentId));
    onboardingActivities.notifyCustomer(
        idempotencyKey,
        customerId,
        "ACTIVATED",
        "Your account is now active. Account ID: " + accountId);
    updateStep("COMPLETED", 100);

    log.info("Onboarding complete for customer {}. Account: {}", customerId, accountId);
    ApplicationStatus status = ApplicationStatus.activated(accountId);
    return status;
  }

  // ── Signal handlers ──────────────────────────────────────────────────────────

  @Override
  public void approveApplication(String reviewerId) {
    log.info("Approve signal received for customer {} from reviewer {}", customerId, reviewerId);
    if (!"MANUAL_REVIEW_PENDING".equals(step)) {
      log.warn(
          "approveApplication signal ignored — workflow is in step '{}', not MANUAL_REVIEW_PENDING",
          step);
    }
    reviewDecision =
        new ComplianceDecision(
            true,
            reviewerId,
            "Approved via signal",
            Instant.ofEpochMilli(Workflow.currentTimeMillis()));
  }

  @Override
  public void rejectApplication(String reviewerId, String reason) {
    log.info(
        "Reject signal received for customer {} from reviewer {}: {}",
        customerId,
        reviewerId,
        reason);
    if (!"MANUAL_REVIEW_PENDING".equals(step)) {
      log.warn(
          "rejectApplication signal ignored — workflow is in step '{}', not MANUAL_REVIEW_PENDING",
          step);
    }
    reviewDecision =
        new ComplianceDecision(
            false, reviewerId, reason, Instant.ofEpochMilli(Workflow.currentTimeMillis()));
  }

  // ── Query handler ─────────────────────────────────────────────────────────────

  @Override
  public OnboardingState getOnboardingState() {
    return new OnboardingState(step, kycStatusStr, reviewDeadline, customerId, progressPct);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private void updateStep(String newStep, int pct) {
    step = newStep;
    progressPct = pct;
    Workflow.upsertTypedSearchAttributes(APPLICATION_STEP.valueSet(newStep));
    log.info("Step → {} ({}%)", newStep, pct);
  }
}
