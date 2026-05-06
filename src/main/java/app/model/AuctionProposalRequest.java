package app.model;

import java.time.LocalDateTime;

public class AuctionProposalRequest {
    private final String bidderId;
    private final String productDescription;
    private final LocalDateTime requestedStartTime;
    private final long requestedDurationMinutes;

    public AuctionProposalRequest(String bidderId, String productDescription,
                                  LocalDateTime requestedStartTime, long requestedDurationMinutes) {
        this.bidderId = bidderId;
        this.productDescription = productDescription;
        this.requestedStartTime = requestedStartTime;
        this.requestedDurationMinutes = requestedDurationMinutes;
    }

    public String getBidderId() {
        return bidderId;
    }

    public String getProductDescription() {
        return productDescription;
    }

    public LocalDateTime getRequestedStartTime() {
        return requestedStartTime;
    }

    public long getRequestedDurationMinutes() {
        return requestedDurationMinutes;
    }
}
