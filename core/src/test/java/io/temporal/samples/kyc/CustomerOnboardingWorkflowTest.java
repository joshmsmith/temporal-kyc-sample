package io.temporal.samples.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.ManagedChannel;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.operatorservice.v1.AddSearchAttributesRequest;
import io.temporal.api.operatorservice.v1.OperatorServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.samples.kyc.activities.OnboardingActivities;
import io.temporal.samples.kyc.model.*;
import io.temporal.samples.kyc.workflow.CustomerOnboardingWorkflow;
import io.temporal.samples.kyc.workflow.CustomerOnboardingWorkflowImpl;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
            .putSearchAttributes("KycStatus", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
            .putSearchAttributes("ReviewDeadline", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
            .build());

    activities = mock(OnboardingActivities.class, withSettings().withoutAnnotations());

    Worker worker = testEnv.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(CustomerOnboardingWorkflowImpl.class);
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

  /** Happy path: documents stored, KYC passes, account activated. */
  @Test
  void testHappyPath() {
    when(activities.storeDocuments(anyString(), any())).thenReturn("DOC-001");
    when(activities.performKycCheck(any()))
        .thenReturn(new KycResult(KycStatus.PASSED, "KYC-REF-001", Instant.now()));
    when(activities.activateAccount(any())).thenReturn("ACC-001");

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

  /**
   * Human-in-the-loop approval: KYC flags for manual review, compliance officer approves via
   * update, workflow activates the account.
   */
  @Test
  void testManualReviewApproved() throws Exception {
    when(activities.storeDocuments(anyString(), any())).thenReturn("DOC-001");
    when(activities.performKycCheck(any()))
        .thenReturn(new KycResult(KycStatus.NEEDS_MANUAL_REVIEW, "KYC-REF-002", Instant.now()));
    when(activities.submitToComplianceQueue(anyString(), anyString(), any())).thenReturn("TKT-001");
    when(activities.activateAccount(any())).thenReturn("ACC-002");

    ApplicationRequest request =
        new ApplicationRequest(
            "CUST-002",
            "Review User",
            "review@example.com",
            List.of("DOC-001"),
            Instant.now(),
            ApplicationScenario.NEEDS_REVIEW);

    // Start the workflow asynchronously so we can send the approval update.
    WorkflowClient.start(workflow::onboard, request);

    // Updates are validated synchronously, so wait until the workflow reaches
    // MANUAL_REVIEW_PENDING before sending — unlike signals, an update sent too
    // early would be rejected by the validator.
    long deadline = System.currentTimeMillis() + 5_000;
    while (!"MANUAL_REVIEW_PENDING".equals(workflow.getOnboardingState().getStep())) {
      assertTrue(
          System.currentTimeMillis() < deadline, "Timed out waiting for MANUAL_REVIEW_PENDING");
      Thread.sleep(50);
    }

    // Send the compliance officer approval update and verify the acknowledgement.
    ComplianceDecision ack = workflow.approveApplication("reviewer-001");
    assertTrue(ack.isApproved());
    assertEquals("reviewer-001", ack.getReviewerId());

    // Wait for the workflow to complete (time-skipping handles the 30-day await instantly).
    ApplicationStatus result =
        WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, ApplicationStatus.class);

    assertEquals(OnboardingOutcome.ACTIVATED, result.getOutcome());
    assertEquals("ACC-002", result.getAccountId());
  }

  /**
   * Human-in-the-loop rejection: KYC flags for manual review, compliance officer rejects via
   * update, workflow ends as REJECTED.
   */
  @Test
  void testManualReviewRejected() throws Exception {
    when(activities.storeDocuments(anyString(), any())).thenReturn("DOC-001");
    when(activities.performKycCheck(any()))
        .thenReturn(new KycResult(KycStatus.NEEDS_MANUAL_REVIEW, "KYC-REF-003", Instant.now()));
    when(activities.submitToComplianceQueue(anyString(), anyString(), any())).thenReturn("TKT-002");

    ApplicationRequest request =
        new ApplicationRequest(
            "CUST-003",
            "Reject User",
            "reject@example.com",
            List.of("DOC-001"),
            Instant.now(),
            ApplicationScenario.NEEDS_REVIEW);

    // Start the workflow asynchronously so we can send the rejection update.
    WorkflowClient.start(workflow::onboard, request);

    // Wait until the workflow is in MANUAL_REVIEW_PENDING before sending the update.
    long deadline = System.currentTimeMillis() + 5_000;
    while (!"MANUAL_REVIEW_PENDING".equals(workflow.getOnboardingState().getStep())) {
      assertTrue(
          System.currentTimeMillis() < deadline, "Timed out waiting for MANUAL_REVIEW_PENDING");
      Thread.sleep(50);
    }

    // Send the compliance officer rejection update and verify the acknowledgement.
    ComplianceDecision ack = workflow.rejectApplication("reviewer-002", "Incomplete documentation");
    assertFalse(ack.isApproved());
    assertEquals("reviewer-002", ack.getReviewerId());
    assertEquals("Incomplete documentation", ack.getReason());

    // Wait for the workflow to complete.
    ApplicationStatus result =
        WorkflowStub.fromTyped(workflow).getResult(10, TimeUnit.SECONDS, ApplicationStatus.class);

    assertEquals(OnboardingOutcome.REJECTED, result.getOutcome());
    assertTrue(result.getMessage().contains("Incomplete documentation"));

    // activateAccount should never have been called
    verify(activities, never()).activateAccount(any());
  }
}
