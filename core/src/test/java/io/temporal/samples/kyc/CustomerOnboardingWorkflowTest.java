package io.temporal.samples.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.samples.kyc.activities.OnboardingActivities;
import io.temporal.samples.kyc.model.*;
import io.temporal.samples.kyc.workflow.CustomerOnboardingWorkflow;
import io.temporal.samples.kyc.workflow.AuditingCustomerOnboardingWorkflowImpl;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CustomerOnboardingWorkflowTest {

  // Single mock registered with the worker at extension build time;
  // reset before each test so stubs don't bleed across tests.
  private static final OnboardingActivities activities =
      mock(OnboardingActivities.class, withSettings().withoutAnnotations());

  @RegisterExtension
  public static final TestWorkflowExtension testWorkflowExtension =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(AuditingCustomerOnboardingWorkflowImpl.class)
          .setActivityImplementations(activities)
          .build();

  @BeforeEach
  void resetMocks() {
    reset(activities);
  }

  private ApplicationRequest request(String customerId, ApplicationScenario scenario) {
    return new ApplicationRequest(
        customerId, "Test User", "test@example.com", List.of("DOC-001"), Instant.now(), scenario);
  }

  /** Happy path: KYC passes, sanctions clear, account activated. */
  @Test
  void testHappyPath(CustomerOnboardingWorkflow workflow) {
    when(activities.storeDocuments(any())).thenReturn("DOC-001");
    when(activities.performKycCheck(anyString(), anyString(), any()))
        .thenReturn(new KycResult(KycStatus.PASSED, "KYC-REF-001", Instant.now()));
    when(activities.sanctionsScreening(anyString(), any()))
        .thenReturn(new SanctionsResult(SanctionsStatus.CLEAR, "SANC-001"));
    when(activities.activateAccount(anyString(), anyString(), anyString())).thenReturn("ACC-001");

    ApplicationStatus result =
        workflow.onboard(request("CUST-001", ApplicationScenario.HAPPY_PATH));

    assertEquals(OnboardingOutcome.ACTIVATED, result.getOutcome());
    assertEquals("ACC-001", result.getAccountId());
  }

  /** KYC hard fail: workflow ends as REJECTED, account never activated. */
  @Test
  void testKycHardFail(CustomerOnboardingWorkflow workflow) {
    when(activities.storeDocuments(any())).thenReturn("DOC-001");
    when(activities.performKycCheck(anyString(), anyString(), any()))
        .thenReturn(new KycResult(KycStatus.FAILED, "KYC-REJECT-001", Instant.now()));

    ApplicationStatus result = workflow.onboard(request("CUST-002", ApplicationScenario.HARD_FAIL));

    assertEquals(OnboardingOutcome.REJECTED, result.getOutcome());
    verify(activities, never()).activateAccount(anyString(), anyString(), anyString());
  }

  /** Manual review approved via signal: workflow ends as ACTIVATED. */
  @Test
  void testManualReviewApproved(
      TestWorkflowEnvironment testEnv, CustomerOnboardingWorkflow workflow) throws Exception {
    when(activities.storeDocuments(any())).thenReturn("DOC-001");
    when(activities.performKycCheck(anyString(), anyString(), any()))
        .thenReturn(new KycResult(KycStatus.NEEDS_MANUAL_REVIEW, "KYC-REF-002", Instant.now()));
    when(activities.submitToComplianceQueue(anyString(), any())).thenReturn("TKT-001");
    when(activities.sanctionsScreening(anyString(), any()))
        .thenReturn(new SanctionsResult(SanctionsStatus.CLEAR, "SANC-001"));
    when(activities.activateAccount(anyString(), anyString(), anyString())).thenReturn("ACC-002");

    // Register the signal before starting so the simulated clock delivers it at
    // 1s — well before the 30-day review timeout fires under auto time skipping.
    testEnv.registerDelayedCallback(
        Duration.ofSeconds(1), () -> workflow.approveApplication("reviewer-001"));
    WorkflowClient.start(workflow::onboard, request("CUST-003", ApplicationScenario.NEEDS_REVIEW));

    ApplicationStatus result =
        WorkflowStub.fromTyped(workflow).getResult(30, TimeUnit.SECONDS, ApplicationStatus.class);

    assertEquals(OnboardingOutcome.ACTIVATED, result.getOutcome());
  }

  /** Sanctions flagged: workflow ends as REJECTED even though KYC passed. */
  @Test
  void testSanctionsFlagged(CustomerOnboardingWorkflow workflow) {
    when(activities.storeDocuments(any())).thenReturn("DOC-001");
    when(activities.performKycCheck(anyString(), anyString(), any()))
        .thenReturn(new KycResult(KycStatus.PASSED, "KYC-REF-003", Instant.now()));
    when(activities.sanctionsScreening(anyString(), any()))
        .thenReturn(new SanctionsResult(SanctionsStatus.FLAGGED, "SANC-FLAG-001"));

    ApplicationStatus result =
        workflow.onboard(request("CUST-004", ApplicationScenario.SANCTIONS_FLAGGED));

    assertEquals(OnboardingOutcome.REJECTED, result.getOutcome());
    verify(activities, never()).activateAccount(anyString(), anyString(), anyString());
  }
}
