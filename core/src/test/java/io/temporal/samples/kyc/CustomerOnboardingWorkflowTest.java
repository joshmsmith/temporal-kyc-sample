package io.temporal.samples.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.ManagedChannel;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.operatorservice.v1.AddSearchAttributesRequest;
import io.temporal.api.operatorservice.v1.OperatorServiceGrpc;
import io.temporal.client.WorkflowOptions;
import io.temporal.samples.kyc.activities.OnboardingActivities;
import io.temporal.samples.kyc.model.*;
import io.temporal.samples.kyc.workflow.AuditingCustomerOnboardingWorkflowImpl;
import io.temporal.samples.kyc.workflow.CustomerOnboardingWorkflow;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerOnboardingWorkflowTest {

  private static final String TASK_QUEUE = "kyc-test";
  private TestWorkflowEnvironment testEnv;
  private OnboardingActivities activities;
  private CustomerOnboardingWorkflow workflow;

  @BeforeEach
  void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();

    // Register custom search attributes upserted by AuditingCustomerOnboardingWorkflowImpl.
    // The in-memory test server validates attribute names, so they must be pre-registered.
    ManagedChannel channel = testEnv.getWorkflowServiceStubs().getRawChannel();
    OperatorServiceGrpc.OperatorServiceBlockingStub operatorStub =
        OperatorServiceGrpc.newBlockingStub(channel);
    operatorStub.addSearchAttributes(
        AddSearchAttributesRequest.newBuilder()
            .setNamespace(testEnv.getNamespace())
            .putSearchAttributes("ApplicationStep", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
            .putSearchAttributes("CustomerId", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
            .putSearchAttributes("KycStatus", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
            .putSearchAttributes("ReviewDeadline", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
            .build());

    activities = mock(OnboardingActivities.class, withSettings().withoutAnnotations());

    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(AuditingCustomerOnboardingWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    testEnv.start();

    workflow =
        testEnv
            .getWorkflowClient()
            .newWorkflowStub(
                CustomerOnboardingWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
  }

  @AfterEach
  void tearDown() {
    testEnv.close();
  }

  /** Happy path: documents stored, KYC passes, sanctions clear, account activated. */
  @Test
  void testHappyPath() {
    when(activities.storeDocuments(any())).thenReturn("DOC-001");
    when(activities.performKycCheck(anyString(), anyString(), any()))
        .thenReturn(new KycResult(KycStatus.PASSED, "KYC-REF-001", Instant.now()));
    when(activities.sanctionsScreening(anyString(), any()))
        .thenReturn(new SanctionsResult(SanctionsStatus.CLEAR, "SANC-001"));
    when(activities.activateAccount(anyString(), anyString(), anyString())).thenReturn("ACC-001");

    ApplicationRequest request =
        new ApplicationRequest(
            "CUST-001",
            "Test User",
            "test@example.com",
            List.of("DOC-001"),
            Instant.now(),
            ApplicationScenario.HAPPY_PATH);

    ApplicationStatus result = workflow.onboard(request);

    assertEquals(OnboardingOutcome.ACTIVATED, result.getOutcome());
    assertEquals("ACC-001", result.getAccountId());
  }
}
