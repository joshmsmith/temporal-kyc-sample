package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input to the performKycCheck activity. Adding new fields here is backwards-compatible. */
public class KycCheckInput {

  private final String idempotencyKey;
  private final String customerId;
  private final String documentId;
  private final ApplicationScenario scenario;

  @JsonCreator
  public KycCheckInput(
      @JsonProperty("idempotencyKey") String idempotencyKey,
      @JsonProperty("customerId") String customerId,
      @JsonProperty("documentId") String documentId,
      @JsonProperty("scenario") ApplicationScenario scenario) {
    this.idempotencyKey = idempotencyKey;
    this.customerId = customerId;
    this.documentId = documentId;
    this.scenario = scenario;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getCustomerId() {
    return customerId;
  }

  public String getDocumentId() {
    return documentId;
  }

  public ApplicationScenario getScenario() {
    return scenario;
  }
}
