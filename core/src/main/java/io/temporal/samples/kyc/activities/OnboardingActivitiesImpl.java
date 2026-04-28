package io.temporal.samples.kyc.activities;

import io.temporal.activity.Activity;
import io.temporal.samples.kyc.model.ActivateAccountInput;
import io.temporal.samples.kyc.model.ApplicationRequest;
import io.temporal.samples.kyc.model.ApplicationScenario;
import io.temporal.samples.kyc.model.KycCheckInput;
import io.temporal.samples.kyc.model.KycResult;
import io.temporal.samples.kyc.model.KycStatus;
import io.temporal.samples.kyc.model.SanctionsResult;
import io.temporal.samples.kyc.model.SanctionsScreeningInput;
import io.temporal.samples.kyc.model.SanctionsStatus;
import java.time.Instant;
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

  @Override
  public String storeDocuments(String idempotencyKey, ApplicationRequest request) {
    log.info(
        "[DOCUMENT STORE] Storing {} documents for customer {}",
        request.getDocumentIds().size(),
        request.getCustomerId());
    // IMPL-TO-DO: POST /api/documents with request body; return document bundle ID from response
    sleep(500);
    String docId = "DOC-" + request.getCustomerId().toUpperCase();
    log.info("[DOCUMENT STORE] Stored document bundle: {}", docId);
    return docId;
  }

  @Override
  public KycResult performKycCheck(KycCheckInput input) {
    String idempotencyKey = input.getIdempotencyKey();
    String customerId = input.getCustomerId();
    String documentId = input.getDocumentId();
    ApplicationScenario scenario = input.getScenario();
    log.info(
        "[KYC VENDOR] Starting KYC check for customer {} with document {}",
        customerId,
        documentId,
        idempotencyKey);
    // IMPL-TO-DO: Call KYC vendor SDK; implement frequent polling, call
    // Activity.getExecutionContext().heartbeat() every ~30s
    //       while polling for async result from vendor

    switch (scenario) {
      case HARD_FAIL:
        log.warn("[KYC VENDOR] Hard rejection for customer {}", customerId);
        sleep(800);
        return new KycResult(
            KycStatus.FAILED, "KYC-REJECT-" + customerId.toUpperCase(), Instant.now());

      case API_DOWNTIME:
        int attempt = Activity.getExecutionContext().getInfo().getAttempt();
        if (attempt <= 4) {
          log.warn(
              "[KYC VENDOR] Simulated downtime, attempt {}/5 for customer {}", attempt, customerId);
          sleep(500);
          throw new RuntimeException(
              "KYC vendor API unavailable (simulated downtime, attempt " + attempt + ")");
        }
        log.info(
            "[KYC VENDOR] KYC vendor recovered on attempt {} for customer {}", attempt, customerId);
        break;

      case NEEDS_REVIEW:
      case SANCTIONS_FLAGGED:
        log.info("[KYC VENDOR] Check complete: NEEDS_MANUAL_REVIEW for customer {}", customerId);
        sleep(800);
        return new KycResult(
            KycStatus.NEEDS_MANUAL_REVIEW, "KYC-REF-" + customerId.toUpperCase(), Instant.now());

      default:
        break;
    }

    sleep(800);
    String ref = "KYC-REF-" + customerId.toUpperCase();
    log.info("[KYC VENDOR] Check complete: PASSED for customer {} (ref: {})", customerId, ref);
    return new KycResult(KycStatus.PASSED, ref, Instant.now());
  }

  @Override
  public String submitToComplianceQueue(
      String idempotencyKey, String customerId, KycResult kycResult) {
    log.info(
        "[COMPLIANCE QUEUE] Creating review ticket for customer {} (KYC ref: {})",
        customerId,
        kycResult.getVendorReference());
    // IMPL-TO-DO: INSERT INTO compliance_tickets (customer_id, idempotencyKey, kyc_ref, status,
    // created_at) RETURNING id
    sleep(300);
    String ticketId = "TKT-" + customerId.toUpperCase();
    log.info("[COMPLIANCE QUEUE] Ticket created: {}", ticketId);
    return ticketId;
  }

  @Override
  public void escalateReview(String idempotencyKey, String customerId) {
    log.warn(
        "[COMPLIANCE ESCALATION] 30-day SLA breached for customer {}. Notifying compliance manager.",
        customerId);
    // IMPL-TO-DO: POST to PagerDuty / compliance manager webhook with workflow ID and customer
    // details
    sleep(200);
    log.warn("[COMPLIANCE ESCALATION] Escalation sent for customer {}", customerId);
  }

  @Override
  public String activateAccount(ActivateAccountInput input) {
    String idempotencyKey = input.getIdempotencyKey();
    String customerId = input.getCustomerId();
    String documentId = input.getDocumentId();
    log.info(
        "[ACCOUNT SERVICE] Activating account for customer {} (idempotencyKey: {}) with document {}",
        customerId,
        idempotencyKey,
        documentId);
    // IMPL-TO-DO: POST /api/accounts with Idempotency-Key header; return accountId from response
    //       The account service deduplicates on idempotencyKey, so retries are safe
    sleep(600);
    String accountId = "ACC-" + customerId.toUpperCase();
    log.info("[ACCOUNT SERVICE] Account activated: {}", accountId);
    return accountId;
  }

  @Override
  public void logAuditEvent(String eventType, String customerId, String details) {
    log.info("[AUDIT] {} | customer={} | {}", eventType, customerId, details);
    // IMPL-TO-DO: INSERT INTO audit_events (event_type, customer_id, details, occurred_at,
    // workflow_id)
    //       VALUES (?, ?, ?, NOW(), ?)
    //       ON CONFLICT (workflow_id, event_type) DO NOTHING   -- idempotent
    sleep(100);
  }

  @Override
  public void notifyCustomer(
      String idempotencyKey, String customerId, String status, String message) {
    log.info("[NOTIFICATION] customer={} status={} message='{}'", customerId, status, message);
    // IMPL-TO-DO: POST to email/SMS gateway with customer contact details from signup service
    //   optionally can use customerId+status as idempotency key to avoid duplicate notifications on
    // retries
    sleep(200);
  }

  @Override
  public SanctionsResult sanctionsScreening(SanctionsScreeningInput input) {
    String customerId = input.getCustomerId();
    ApplicationScenario scenario = input.getScenario();
    log.info("[SANCTIONS] Running sanctions screening for customer {}", customerId);
    // IMPL-TO-DO: Call OFAC / watchlist API; check result against sanctions lists
    sleep(400);

    if (scenario == ApplicationScenario.SANCTIONS_FLAGGED) {
      String ref = "SANC-FLAG-" + customerId.toUpperCase();
      log.warn("[SANCTIONS] Customer {} FLAGGED (ref: {})", customerId, ref);
      return new SanctionsResult(SanctionsStatus.FLAGGED, ref);
    }

    String ref = "SANC-CLR-" + customerId.toUpperCase();
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
