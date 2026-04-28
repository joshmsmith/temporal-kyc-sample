package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Final return value of CustomerOnboardingWorkflow.onboard(). */
public class ApplicationStatus {

  private final OnboardingOutcome outcome;
  private final String accountId;
  private final String message;

  @JsonCreator
  public ApplicationStatus(
      @JsonProperty("outcome") OnboardingOutcome outcome,
      @JsonProperty("accountId") String accountId,
      @JsonProperty("message") String message) {
    this.outcome = outcome;
    this.accountId = accountId;
    this.message = message;
  }

  public static ApplicationStatus activated(String accountId) {
    return new ApplicationStatus(OnboardingOutcome.ACTIVATED, accountId, "Account activated");
  }

  public static ApplicationStatus rejected(String reason) {
    return new ApplicationStatus(OnboardingOutcome.REJECTED, null, reason);
  }

  public OnboardingOutcome getOutcome() {
    return outcome;
  }

  public String getAccountId() {
    return accountId;
  }

  public String getMessage() {
    return message;
  }

  @Override
  public String toString() {
    return "ApplicationStatus{outcome="
        + outcome
        + ", accountId='"
        + accountId
        + "', message='"
        + message
        + "'}";
  }
}
