package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input to the activateAccount activity. Adding new fields here is backwards-compatible. */
public class ActivateAccountInput {

  private final String idempotencyKey;
  private final String customerId;
  private final String documentId;

  @JsonCreator
  public ActivateAccountInput(
      @JsonProperty("idempotencyKey") String idempotencyKey,
      @JsonProperty("customerId") String customerId,
      @JsonProperty("documentId") String documentId) {
    this.idempotencyKey = idempotencyKey;
    this.customerId = customerId;
    this.documentId = documentId;
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
}
