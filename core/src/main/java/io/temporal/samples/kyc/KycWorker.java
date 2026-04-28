package io.temporal.samples.kyc;

import io.temporal.client.WorkflowClient;
import io.temporal.samples.kyc.activities.OnboardingActivitiesImpl;
import io.temporal.samples.kyc.workflow.CustomerOnboardingWorkflowImpl;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long-running worker process. Polls the KYC task queue and executes workflow and activity tasks.
 *
 * <p>Configuration via environment variables:
 *
 * <ul>
 *   <li>TEMPORAL_ADDRESS — gRPC endpoint, default localhost:7233
 *   <li>TEMPORAL_NAMESPACE — default: default
 *   <li>TEMPORAL_TASK_QUEUE — default: kyc-onboarding
 * </ul>
 *
 * <p>Run with: {@code ./gradlew -q execute -PmainClass=io.temporal.samples.kyc.KycWorker}
 */
public class KycWorker {

  private static final Logger log = LoggerFactory.getLogger(KycWorker.class);

  public static void main(String[] args) {
    String address = System.getenv().getOrDefault("TEMPORAL_ADDRESS", "localhost:7233");
    String namespace = System.getenv().getOrDefault("TEMPORAL_NAMESPACE", "default");
    String taskQueue = System.getenv().getOrDefault("TEMPORAL_TASK_QUEUE", KycConstants.TASK_QUEUE);

    log.info("Starting KYC worker on {}  namespace={}  queue={}", address, namespace, taskQueue);

    WorkflowServiceStubs service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());

    WorkflowClient client =
        WorkflowClient.newInstance(
            service,
            io.temporal.client.WorkflowClientOptions.newBuilder().setNamespace(namespace).build());

    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskQueue);

    worker.registerWorkflowImplementationTypes(CustomerOnboardingWorkflowImpl.class);
    worker.registerActivitiesImplementations(new OnboardingActivitiesImpl());

    factory.start();
    log.info("KYC worker started. Polling for tasks on queue '{}'...", taskQueue);
  }
}
