package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input to the sanctionsScreening activity. Adding new fields here is backwards-compatible. */
public class SanctionsScreeningInput {

  private final String idempotencyKey;
  private final String customerId;
  private final ApplicationScenario scenario;

  @JsonCreator
  public SanctionsScreeningInput(
      @JsonProperty("idempotencyKey") String idempotencyKey,
      @JsonProperty("customerId") String customerId,
      @JsonProperty("scenario") ApplicationScenario scenario) {
    this.idempotencyKey = idempotencyKey;
    this.customerId = customerId;
    this.scenario = scenario;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getCustomerId() {
    return customerId;
  }

  public ApplicationScenario getScenario() {
    return scenario;
  }
}
