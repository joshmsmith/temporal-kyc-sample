package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Input to the CustomerOnboardingWorkflow. Immutable after submission. */
public class ApplicationRequest {

  private final String customerId;
  private final String fullName;
  private final String email;
  private final List<String> documentIds;
  private final Instant submittedAt;
  private final ApplicationScenario scenario;

  @JsonCreator
  public ApplicationRequest(
      @JsonProperty("customerId") String customerId,
      @JsonProperty("fullName") String fullName,
      @JsonProperty("email") String email,
      @JsonProperty("documentIds") List<String> documentIds,
      @JsonProperty("submittedAt") Instant submittedAt,
      @JsonProperty("scenario") ApplicationScenario scenario) {
    this.customerId = customerId;
    this.fullName = fullName;
    this.email = email;
    this.documentIds = documentIds;
    this.submittedAt = submittedAt;
    this.scenario = scenario;
  }

  public String getCustomerId() {
    return customerId;
  }

  public String getFullName() {
    return fullName;
  }

  public String getEmail() {
    return email;
  }

  public List<String> getDocumentIds() {
    return documentIds;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public ApplicationScenario getScenario() {
    return scenario;
  }

  @Override
  public String toString() {
    return "ApplicationRequest{"
        + "customerId='"
        + customerId
        + '\''
        + ", fullName='"
        + fullName
        + '\''
        + ", scenario="
        + scenario
        + '}';
  }
}
