package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Sent by a compliance officer to approve or reject an application that entered manual review. Can
 * be delivered either as a Signal (fire-and-forget) or as an Update (synchronous with validation).
 */
public class ComplianceDecision {

  private final boolean approved;
  private final String reviewerId;
  private final String reason;
  private final Instant decidedAt;

  @JsonCreator
  public ComplianceDecision(
      @JsonProperty("approved") boolean approved,
      @JsonProperty("reviewerId") String reviewerId,
      @JsonProperty("reason") String reason,
      @JsonProperty("decidedAt") Instant decidedAt) {
    this.approved = approved;
    this.reviewerId = reviewerId;
    this.reason = reason;
    this.decidedAt = decidedAt;
  }

  public boolean isApproved() {
    return approved;
  }

  public String getReviewerId() {
    return reviewerId;
  }

  public String getReason() {
    return reason;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  @Override
  public String toString() {
    return "ComplianceDecision{"
        + "approved="
        + approved
        + ", reviewerId='"
        + reviewerId
        + '\''
        + ", reason='"
        + reason
        + '\''
        + '}';
  }
}
