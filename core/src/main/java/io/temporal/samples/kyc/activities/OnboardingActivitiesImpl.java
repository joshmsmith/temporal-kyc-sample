package io.temporal.samples.kyc.activities;

import io.temporal.activity.Activity;
import io.temporal.samples.kyc.model.ApplicationRequest;
import io.temporal.samples.kyc.model.ApplicationScenario;
import io.temporal.samples.kyc.model.KycResult;
import io.temporal.samples.kyc.model.KycStatus;
import io.temporal.samples.kyc.model.SanctionsResult;
import io.temporal.samples.kyc.model.SanctionsStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub implementation of OnboardingActivities. Each method simulates the corresponding external
 * system call with log output and a short sleep. In production:
 *
 * <ul>
 *   <li>storeDocuments → POST to document service API
 *   <li>performKycCheck → call KYC vendor SDK / REST API with heartbeating
 *   <li>submitToComplianceQueue → insert into Postgres compliance_tickets table
 *   <li>escalateReview → call PagerDuty / compliance manager webhook
 *   <li>activateAccount → POST to account service with idempotency header
 *   <li>logAuditEvent → INSERT INTO audit_events (Postgres) with ON CONFLICT DO NOTHING
 *   <li>notifyCustomer → send via email/SMS gateway
 *   <li>sanctionsScreening → call OFAC / sanctions screening vendor API
 * </ul>
 */
public class OnboardingActivitiesImpl implements OnboardingActivities {

  private static final Logger log = LoggerFactory.getLogger(OnboardingActivitiesImpl.class);

  // Shared counter used by API_DOWNTIME scenario to fail the first 4 attempts.
  private final AtomicInteger kycAttemptCounter = new AtomicInteger(0);

  /**
   * KycHardFailException marks the KYC check as non-retryable. Temporal will not retry activities
   * that throw this exception type.
   */
  public static class KycHardFailException extends RuntimeException {
    public KycHardFailException(String message) {
      super(message);
    }
  }

  @Override
  public String storeDocuments(ApplicationRequest request) {
    log.info(
        "[DOCUMENT STORE] Storing {} documents for customer {}",
        request.getDocumentIds().size(),
        request.getCustomerId());
    // TODO: POST /api/documents with request body; return document bundle ID from response
    sleep(500);
    String docId = "DOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    log.info("[DOCUMENT STORE] Stored document bundle: {}", docId);
    return docId;
  }

  @Override
  public KycResult performKycCheck(
      String customerId, String documentId, ApplicationScenario scenario) {
    log.info(
        "[KYC VENDOR] Starting KYC check for customer {} with document {}", customerId, documentId);
    // TODO: Call KYC vendor SDK; implement frequent polling, call
    // Activity.getExecutionContext().heartbeat() every ~30s
    //       while polling for async result from vendor

    switch (scenario) {
      case HARD_FAIL:
        log.warn("[KYC VENDOR] Hard rejection for customer {}", customerId);
        sleep(800);
        return new KycResult(
            KycStatus.FAILED,
            "KYC-REJECT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            Instant.now());

      case API_DOWNTIME:
        int attempt = kycAttemptCounter.incrementAndGet();
        if (attempt <= 4) {
          log.warn(
              "[KYC VENDOR] Simulated downtime, attempt {}/5 for customer {}", attempt, customerId);
          sleep(500);
          throw new RuntimeException(
              "KYC vendor API unavailable (simulated downtime, attempt " + attempt + ")");
        }
        log.info(
            "[KYC VENDOR] KYC vendor recovered on attempt {} for customer {}", attempt, customerId);
        kycAttemptCounter.set(0);
        break;

      case NEEDS_REVIEW:
      case SANCTIONS_FLAGGED:
        log.info("[KYC VENDOR] Check complete: NEEDS_MANUAL_REVIEW for customer {}", customerId);
        sleep(800);
        return new KycResult(
            KycStatus.NEEDS_MANUAL_REVIEW,
            "KYC-REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            Instant.now());

      default:
        break;
    }

    sleep(800);
    String ref = "KYC-REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    log.info("[KYC VENDOR] Check complete: PASSED for customer {} (ref: {})", customerId, ref);
    return new KycResult(KycStatus.PASSED, ref, Instant.now());
  }

  @Override
  public String submitToComplianceQueue(String customerId, KycResult kycResult) {
    log.info(
        "[COMPLIANCE QUEUE] Creating review ticket for customer {} (KYC ref: {})",
        customerId,
        kycResult.getVendorReference());
    // TODO: INSERT INTO compliance_tickets (customer_id, kyc_ref, status, created_at) RETURNING id
    sleep(300);
    String ticketId = "TKT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    log.info("[COMPLIANCE QUEUE] Ticket created: {}", ticketId);
    return ticketId;
  }

  @Override
  public void escalateReview(String customerId) {
    log.warn(
        "[COMPLIANCE ESCALATION] 30-day SLA breached for customer {}. Notifying compliance manager.",
        customerId);
    // TODO: POST to PagerDuty / compliance manager webhook with workflow ID and customer details
    sleep(200);
    log.warn("[COMPLIANCE ESCALATION] Escalation sent for customer {}", customerId);
  }

  @Override
  public String activateAccount(String idempotencyKey, String customerId, String documentId) {
    log.info(
        "[ACCOUNT SERVICE] Activating account for customer {} (idempotencyKey: {})",
        customerId,
        idempotencyKey);
    // TODO: POST /api/accounts with Idempotency-Key header; return accountId from response
    //       The account service deduplicates on idempotencyKey, so retries are safe
    sleep(600);
    String accountId =
        "ACC-"
            + customerId.toUpperCase()
            + "-"
            + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    log.info("[ACCOUNT SERVICE] Account activated: {}", accountId);
    return accountId;
  }

  @Override
  public void logAuditEvent(String eventType, String customerId, String details) {
    log.info("[AUDIT] {} | customer={} | {}", eventType, customerId, details);
    // TODO: INSERT INTO audit_events (event_type, customer_id, details, occurred_at, workflow_id)
    //       VALUES (?, ?, ?, NOW(), ?)
    //       ON CONFLICT (workflow_id, event_type) DO NOTHING   -- idempotent
    sleep(100);
  }

  @Override
  public void notifyCustomer(String customerId, String status, String message) {
    log.info("[NOTIFICATION] customer={} status={} message='{}'", customerId, status, message);
    // TODO: POST to email/SMS gateway with customer contact details from signup service
    sleep(200);
  }

  @Override
  public SanctionsResult sanctionsScreening(String customerId, ApplicationScenario scenario) {
    log.info("[SANCTIONS] Running sanctions screening for customer {}", customerId);
    // TODO: Call OFAC / watchlist API; check result against sanctions lists
    sleep(400);

    if (scenario == ApplicationScenario.SANCTIONS_FLAGGED) {
      String ref = "SANC-FLAG-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
      log.warn("[SANCTIONS] Customer {} FLAGGED (ref: {})", customerId, ref);
      return new SanctionsResult(SanctionsStatus.FLAGGED, ref);
    }

    String ref = "SANC-CLR-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    log.info("[SANCTIONS] Customer {} CLEAR (ref: {})", customerId, ref);
    return new SanctionsResult(SanctionsStatus.CLEAR, ref);
  }

  private void sleep(long millis) {
    // Use Activity.getExecutionContext().heartbeat() for long operations instead of sleep
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Activity.wrap(e);
    }
  }
}
