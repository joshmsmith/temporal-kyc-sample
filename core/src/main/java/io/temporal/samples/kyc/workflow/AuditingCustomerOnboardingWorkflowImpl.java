package io.temporal.samples.kyc.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.failure.ApplicationFailure;
import io.temporal.samples.kyc.activities.OnboardingActivities;
import io.temporal.samples.kyc.model.ApplicationRequest;
import io.temporal.samples.kyc.model.ApplicationScenario;
import io.temporal.samples.kyc.model.ApplicationStatus;
import io.temporal.samples.kyc.model.ComplianceDecision;
import io.temporal.samples.kyc.model.KycResult;
import io.temporal.samples.kyc.model.KycStatus;
import io.temporal.samples.kyc.model.OnboardingState;
import io.temporal.samples.kyc.model.SanctionsResult;
import io.temporal.samples.kyc.model.SanctionsStatus;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;

public class AuditingCustomerOnboardingWorkflowImpl implements CustomerOnboardingWorkflow {

  private static final Logger log =
      Workflow.getLogger(AuditingCustomerOnboardingWorkflowImpl.class);

  // ── Search attribute keys (register in Temporal namespace before running) ──
  static final SearchAttributeKey<String> APPLICATION_STEP =
      SearchAttributeKey.forKeyword("ApplicationStep");
  static final SearchAttributeKey<String> CUSTOMER_ID_ATTR =
      SearchAttributeKey.forKeyword("CustomerId");
  static final SearchAttributeKey<String> KYC_STATUS_ATTR =
      SearchAttributeKey.forKeyword("KycStatus");
  static final SearchAttributeKey<String> REVIEW_DEADLINE =
      SearchAttributeKey.forKeyword("ReviewDeadline");
  // Manually managed — Java SDK does not auto-upsert TemporalChangeVersion
  static final SearchAttributeKey<List<String>> TEMPORAL_CHANGE_VERSION =
      SearchAttributeKey.forKeywordList("TemporalChangeVersion");

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

  /**
   * Audit log: best-effort, never blocks workflow progress. Max 3 attempts with short timeout.
   *
   * <p>If the audit log is unavailable we still proceed; the event can be reconstructed from
   * workflow history if needed.
   */
  private final OnboardingActivities auditActivities =
      Workflow.newActivityStub(
          OnboardingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumAttempts(3)
                      .setInitialInterval(Duration.ofSeconds(1))
                      .build())
              .build());

  // ── Mutable workflow state (survives replay because it is derived from deterministic code) ─

  private String step = "SUBMITTED";
  private String kycStatusStr = "PENDING";
  private int progressPct = 0;
  private Instant reviewDeadline = null;

  // Set by signal or update when the workflow is in MANUAL_REVIEW_PENDING
  private ComplianceDecision reviewDecision = null;

  // Cached for query handler — set at the start of onboard()
  private String customerId = "";

  // ── Workflow entry point ────────────────────────────────────────────────────

  @Override
  public ApplicationStatus onboard(ApplicationRequest request) {
    customerId = request.getCustomerId();
    ApplicationScenario scenario = request.getScenario();

    log.info("Onboarding started for customer {}, scenario={}", customerId, scenario);
    updateStep("SUBMITTED", 5);

    // ── Step 1: Store documents ─────────────────────────────────────────────
    String documentId = onboardingActivities.storeDocuments(request);
    auditActivities.logAuditEvent("DOCUMENTS_STORED", customerId, "documentId=" + documentId);
    updateStep("KYC_CHECKING", 20);

    // ── Step 2: KYC vendor check ────────────────────────────────────────────
    KycResult kycResult = kycActivities.performKycCheck(customerId, documentId, scenario);
    kycStatusStr = kycResult.getStatus().name();
    Workflow.upsertTypedSearchAttributes(KYC_STATUS_ATTR.valueSet(kycStatusStr));
    auditActivities.logAuditEvent(
        "KYC_CHECKED",
        customerId,
        "status=" + kycResult.getStatus() + " ref=" + kycResult.getVendorReference());
    log.info("KYC result for customer {}: {}", customerId, kycResult.getStatus());

    // ── Step 3: Hard KYC rejection ──────────────────────────────────────────
    if (kycResult.getStatus() == KycStatus.FAILED) {
      auditActivities.logAuditEvent(
          "KYC_REJECTED", customerId, "ref=" + kycResult.getVendorReference());
      onboardingActivities.notifyCustomer(customerId, "REJECTED", "KYC check did not pass.");
      updateStep("REJECTED", 100);
      ApplicationStatus status = ApplicationStatus.rejected("KYC check failed");
      return status;
    }

    // ── Step 4: Manual compliance review (when KYC flags for review) ────────
    if (kycResult.getStatus() == KycStatus.NEEDS_MANUAL_REVIEW) {
      String ticketId = onboardingActivities.submitToComplianceQueue(customerId, kycResult);
      auditActivities.logAuditEvent("REVIEW_SUBMITTED", customerId, "ticketId=" + ticketId);

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
        // SLA breach — escalate and fail the workflow so it surfaces in the UI
        onboardingActivities.escalateReview(customerId);
        auditActivities.logAuditEvent("REVIEW_ESCALATED", customerId, "SLA breached after 30 days");
        updateStep("REVIEW_TIMEOUT", 100);
        throw ApplicationFailure.newNonRetryableFailure(
            "Compliance review SLA exceeded 30 days for customer " + customerId, "ReviewTimeout");
      }

      auditActivities.logAuditEvent(
          "REVIEW_COMPLETED",
          customerId,
          "approved="
              + reviewDecision.isApproved()
              + " reviewer="
              + reviewDecision.getReviewerId()
              + " reason="
              + reviewDecision.getReason());
      log.info(
          "Review decision received for customer {}: approved={}",
          customerId,
          reviewDecision.isApproved());

      if (!reviewDecision.isApproved()) {
        onboardingActivities.notifyCustomer(
            customerId, "REJECTED", "Compliance review rejected: " + reviewDecision.getReason());
        updateStep("REJECTED", 100);
        ApplicationStatus status =
            ApplicationStatus.rejected("Compliance review rejected: " + reviewDecision.getReason());
        return status;
      }
    }

    // ── Step 5: Sanctions screening — NEW POLICY (patching API) ────────────
    //
    // Workflow.getVersion() branches based on whether this execution started before or after
    // the policy change:
    //   - DEFAULT_VERSION (-1): old in-flight workflows that have no marker in their history
    //     → skip sanctions screening (old policy)
    //   - version 1: new workflows and old workflows that have not yet reached this point
    //     → run sanctions screening (new policy)
    //
    // To deploy this policy change:
    //   1. Deploy this code (Phase 1 — "patch in"). Both paths exist.
    //   2. After all pre-patch workflows complete, bump minSupported to 1 and remove old branch.
    //   3. After all Phase-2 workflows complete, remove the getVersion call entirely.
    int sanctionsVersion =
        Workflow.getVersion(
            "sanctions-screening-v1",
            Workflow.DEFAULT_VERSION, // minSupported: keep old path for in-flight workflows
            1 // maxSupported: current version
            );

    // Record which version of the policy this workflow runs under, for operator filtering:
    //   temporal workflow list --query 'TemporalChangeVersion = "sanctions-screening-v1-1"'
    Workflow.upsertTypedSearchAttributes(
        TEMPORAL_CHANGE_VERSION.valueSet(List.of("sanctions-screening-v1-" + sanctionsVersion)));

    if (sanctionsVersion >= 1) {
      updateStep("SANCTIONS_SCREENING", 70);
      SanctionsResult sanctions = onboardingActivities.sanctionsScreening(customerId, scenario);
      auditActivities.logAuditEvent(
          "SANCTIONS_CHECKED",
          customerId,
          "status=" + sanctions.getStatus() + " ref=" + sanctions.getScreeningReference());

      if (sanctions.getStatus() == SanctionsStatus.FLAGGED) {
        log.warn("Customer {} flagged by sanctions screening", customerId);
        onboardingActivities.notifyCustomer(
            customerId, "REJECTED", "Application could not be processed at this time.");
        updateStep("REJECTED", 100);
        ApplicationStatus status = ApplicationStatus.rejected("Sanctions screening flagged");
        return status;
      }
      log.info("Sanctions screening CLEAR for customer {}", customerId);
    } else {
      log.info("Skipping sanctions screening for customer {} (pre-policy workflow)", customerId);
    }

    // ── Step 6: Activate account ────────────────────────────────────────────
    //
    // Workflow.randomUUID() is deterministic across replays — it always produces the same UUID
    // for a given point in history, so retrying activateAccount is safe.
    String idempotencyKey = Workflow.randomUUID().toString();
    updateStep("ACTIVATING", 85);

    String accountId = onboardingActivities.activateAccount(idempotencyKey, customerId, documentId);
    auditActivities.logAuditEvent("ACCOUNT_ACTIVATED", customerId, "accountId=" + accountId);
    onboardingActivities.notifyCustomer(
        customerId, "ACTIVATED", "Your account is now active. Account ID: " + accountId);
    updateStep("COMPLETED", 100);

    log.info("Onboarding complete for customer {}. Account: {}", customerId, accountId);
    ApplicationStatus status = ApplicationStatus.activated(accountId);
    return status;
  }

  // ── Signal handlers ──────────────────────────────────────────────────────────

  @Override
  public void approveApplication(String reviewerId) {
    log.info("Approve signal received for customer {} from reviewer {}", customerId, reviewerId);
    // if (!"MANUAL_REVIEW_PENDING".equals(step)) {
    //   log.warn(
    //       "approveApplication signal ignored — workflow is in step '{}', not
    // MANUAL_REVIEW_PENDING",
    //       step);
    //   return;
    // }
    reviewDecision = new ComplianceDecision(true, reviewerId, "Approved via signal", Instant.now());
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
      return;
    }
    reviewDecision = new ComplianceDecision(false, reviewerId, reason, Instant.now());
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
