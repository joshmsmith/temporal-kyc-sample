package io.temporal.samples.kyc;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.samples.kyc.model.ApplicationStatus;
import io.temporal.samples.kyc.model.ComplianceDecision;
import io.temporal.samples.kyc.workflow.CustomerOnboardingWorkflow;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a compliance review decision to a workflow that is waiting in MANUAL_REVIEW_PENDING.
 *
 * <p>Usage:
 *
 * <pre>
 * # Approve via Signal (fire-and-forget):
 * ./gradlew -q execute -PmainClass=io.temporal.samples.kyc.KycApprover \
 *   -Parg=KYC-CUST-1234
 *
 * # Reject via Signal:
 * DECISION=reject REASON="Incomplete documentation" \
 *   ./gradlew -q execute -PmainClass=io.temporal.samples.kyc.KycApprover \
 *   -Parg=KYC-CUST-1234
 *
 * # Approve via Update (synchronous, returns current status):
 * MECHANISM=update \
 *   ./gradlew -q execute -PmainClass=io.temporal.samples.kyc.KycApprover \
 *   -Parg=KYC-CUST-1234
 * </pre>
 *
 * <p>Environment variables:
 *
 * <ul>
 *   <li>DECISION — "approve" (default) or "reject"
 *   <li>REASON — rejection reason (only used when DECISION=reject)
 *   <li>REVIEWER_ID — reviewer identifier (default: "compliance-officer-1")
 *   <li>MECHANISM — "signal" (default) or "update"
 * </ul>
 */
public class KycApprover {

  private static final Logger log = LoggerFactory.getLogger(KycApprover.class);

  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("Usage: KycApprover <workflowId>");
      System.err.println("  e.g. KycApprover KYC-CUST-1234");
      System.exit(1);
    }

    String workflowId = args[0];
    String address = System.getenv().getOrDefault("TEMPORAL_ADDRESS", "localhost:7233");
    String namespace = System.getenv().getOrDefault("TEMPORAL_NAMESPACE", "default");
    String decision = System.getenv().getOrDefault("DECISION", "approve");
    String reason = System.getenv().getOrDefault("REASON", "Approved by compliance officer");
    String reviewerId = System.getenv().getOrDefault("REVIEWER_ID", "compliance-officer-1");
    String mechanism = System.getenv().getOrDefault("MECHANISM", "signal");

    boolean approved = "approve".equalsIgnoreCase(decision);

    log.info(
        "Sending {} decision to workflow {} via {} (reviewer={}, approved={})",
        decision,
        workflowId,
        mechanism,
        reviewerId,
        approved);

    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());

    WorkflowClient client =
        WorkflowClient.newInstance(
            service,
            io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(namespace).build());

    if ("update".equalsIgnoreCase(mechanism)) {
      // Update: synchronous, validator runs first, returns current ApplicationStatus
      CustomerOnboardingWorkflow workflow =
          client.newWorkflowStub(CustomerOnboardingWorkflow.class, workflowId);
      ComplianceDecision complianceDecision =
          new ComplianceDecision(approved, reviewerId, reason, Instant.now());
      ApplicationStatus status = workflow.submitComplianceDecision(complianceDecision);
      log.info("Update accepted. Workflow status: {}", status);

    } else {
      // Signal: fire-and-forget
      WorkflowStub untyped = client.newUntypedWorkflowStub(workflowId);
      if (approved) {
        untyped.signal("approveApplication", reviewerId);
        log.info("Approve signal sent to workflow {}", workflowId);
      } else {
        untyped.signal("rejectApplication", reviewerId, reason);
        log.info("Reject signal sent to workflow {} with reason: {}", workflowId, reason);
      }
    }
  }
}
