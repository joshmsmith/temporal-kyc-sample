package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result from the sanctions screening activity, introduced by a new compliance policy. Gated in the
 * workflow via Workflow.getVersion("sanctions-screening-v1").
 */
public class SanctionsResult {

  private final SanctionsStatus status;
  private final String screeningReference;

  @JsonCreator
  public SanctionsResult(
      @JsonProperty("status") SanctionsStatus status,
      @JsonProperty("screeningReference") String screeningReference) {
    this.status = status;
    this.screeningReference = screeningReference;
  }

  public SanctionsStatus getStatus() {
    return status;
  }

  public String getScreeningReference() {
    return screeningReference;
  }

  @Override
  public String toString() {
    return "SanctionsResult{status=" + status + ", ref='" + screeningReference + "'}";
  }
}
