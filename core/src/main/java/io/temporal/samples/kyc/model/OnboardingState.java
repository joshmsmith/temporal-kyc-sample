package io.temporal.samples.kyc.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Read-only snapshot of workflow state returned by the getOnboardingState query. Safe to call at
 * any point during execution; callers use it to build dashboards and health checks.
 */
public class OnboardingState {

  private final String step;
  private final String kycStatus;
  private final Instant reviewDeadline;
  private final String customerId;
  private final int progressPct;

  @JsonCreator
  public OnboardingState(
      @JsonProperty("step") String step,
      @JsonProperty("kycStatus") String kycStatus,
      @JsonProperty("reviewDeadline") Instant reviewDeadline,
      @JsonProperty("customerId") String customerId,
      @JsonProperty("progressPct") int progressPct) {
    this.step = step;
    this.kycStatus = kycStatus;
    this.reviewDeadline = reviewDeadline;
    this.customerId = customerId;
    this.progressPct = progressPct;
  }

  public String getStep() {
    return step;
  }

  public String getKycStatus() {
    return kycStatus;
  }

  public Instant getReviewDeadline() {
    return reviewDeadline;
  }

  public String getCustomerId() {
    return customerId;
  }

  public int getProgressPct() {
    return progressPct;
  }

  @Override
  public String toString() {
    return "OnboardingState{"
        + "step='"
        + step
        + '\''
        + ", kycStatus='"
        + kycStatus
        + '\''
        + ", progressPct="
        + progressPct
        + '}';
  }
}
