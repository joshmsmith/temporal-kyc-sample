package io.temporal.samples.kyc;

import io.temporal.client.WorkflowClient;
import io.temporal.samples.kyc.model.ComplianceDecision;
import io.temporal.samples.kyc.workflow.CustomerOnboardingWorkflow;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a compliance review decision to a workflow that is waiting in MANUAL_REVIEW_PENDING.
 *
 * <p>Usage:
 *
 * <pre>
 * # Approve via Update (synchronous, validates workflow state):
 * ./gradlew -q execute -PmainClass=io.temporal.samples.kyc.KycApprover \
 *   -Parg=KYC-CUST-1234
 *
 * # Reject via Update:
 * DECISION=reject REASON="Incomplete documentation" \
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

    boolean approved = "approve".equalsIgnoreCase(decision);

    log.info(
        "Sending {} decision to workflow {} via update (reviewer={}, approved={})",
        decision,
        workflowId,
        reviewerId,
        approved);

    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());

    WorkflowClient client =
        WorkflowClient.newInstance(
            service,
            io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(namespace).build());

    CustomerOnboardingWorkflow workflow =
        client.newWorkflowStub(CustomerOnboardingWorkflow.class, workflowId);
    if (approved) {
      ComplianceDecision ack = workflow.approveApplication(reviewerId);
      log.info("Approve update acknowledged by workflow {}: {}", workflowId, ack);
    } else {
      ComplianceDecision ack = workflow.rejectApplication(reviewerId, reason);
      log.info("Reject update acknowledged by workflow {}: {}", workflowId, ack);
    }
  }
}
