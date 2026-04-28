package io.temporal.samples.kyc;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.samples.kyc.model.ApplicationRequest;
import io.temporal.samples.kyc.model.ApplicationScenario;
import io.temporal.samples.kyc.model.ApplicationStatus;
import io.temporal.samples.kyc.workflow.CustomerOnboardingWorkflow;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a single CustomerOnboardingWorkflow execution for demonstration purposes.
 *
 * <p>Usage: {@code ./gradlew -q execute -PmainClass=io.temporal.samples.kyc.KycStarter}
 *
 * <p>To choose a scenario, set the SCENARIO environment variable to one of: HAPPY_PATH,
 * NEEDS_REVIEW, HARD_FAIL, API_DOWNTIME (default: HAPPY_PATH)
 *
 * <p>The workflowId is set to {@code KYC-<customerId>}, so re-running with the same customerId
 * while a workflow is already running will attach to the existing execution rather than starting a
 * new one (idempotent submission).
 */
public class KycStarter {

  private static final Logger log = LoggerFactory.getLogger(KycStarter.class);

  public static void main(String[] args) {
    String address = System.getenv().getOrDefault("TEMPORAL_ADDRESS", "localhost:7233");
    String namespace = System.getenv().getOrDefault("TEMPORAL_NAMESPACE", "default");
    String taskQueue = System.getenv().getOrDefault("TEMPORAL_TASK_QUEUE", KycConstants.TASK_QUEUE);
    String scenarioEnv = System.getenv().getOrDefault("SCENARIO", "HAPPY_PATH");

    ApplicationScenario scenario;
    try {
      scenario = ApplicationScenario.valueOf(scenarioEnv);
    } catch (IllegalArgumentException e) {
      log.error(
          "Unknown scenario '{}'. Valid values: {}",
          scenarioEnv,
          List.of(ApplicationScenario.values()));
      System.exit(1);
      return;
    }

    String customerId = "CUST-" + String.format("%04d", (int) (Math.random() * 9999));
    String workflowId = KycConstants.KYC_WORKFLOW_ID_PREFIX + customerId;

    ApplicationRequest request =
        new ApplicationRequest(
            customerId,
            "Jane Smith",
            "jane.smith@example.com",
            List.of("PASSPORT-001", "PROOF-OF-ADDRESS-002"),
            Instant.now(),
            scenario);

    log.info("Starting onboarding workflow: workflowId={} scenario={}", workflowId, scenario);

    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());

    WorkflowClient client =
        WorkflowClient.newInstance(
            service,
            io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(namespace).build());

    WorkflowOptions options =
        WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(taskQueue).build();

    CustomerOnboardingWorkflow workflow =
        client.newWorkflowStub(CustomerOnboardingWorkflow.class, options);

    log.info("Workflow submitted. WorkflowId: {}", workflowId);
    log.info("Track progress in the Temporal UI or with:");
    log.info("  temporal workflow describe --workflow-id {}", workflowId);

    // Blocking call — waits for the workflow to complete
    ApplicationStatus result = workflow.onboard(request);
    log.info("Workflow completed: {}", result);
  }
}
