package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Result from the KYC vendor check activity. */
public class KycResult {

  private final KycStatus status;
  private final String vendorReference;
  private final Instant checkTimestamp;

  @JsonCreator
  public KycResult(
      @JsonProperty("status") KycStatus status,
      @JsonProperty("vendorReference") String vendorReference,
      @JsonProperty("checkTimestamp") Instant checkTimestamp) {
    this.status = status;
    this.vendorReference = vendorReference;
    this.checkTimestamp = checkTimestamp;
  }

  public KycStatus getStatus() {
    return status;
  }

  public String getVendorReference() {
    return vendorReference;
  }

  public Instant getCheckTimestamp() {
    return checkTimestamp;
  }

  @Override
  public String toString() {
    return "KycResult{" + "status=" + status + ", vendorReference='" + vendorReference + '\'' + '}';
  }
}
