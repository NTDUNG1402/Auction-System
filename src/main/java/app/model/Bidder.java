package app.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Bidder extends User {
    private final List<BidTransaction> bidHistory;

    public Bidder(String id, String username, String password) {
        super(id, username, password);
        this.bidHistory = new ArrayList<>();
    }

    public boolean placeBid(Auction auction, double amount) {
        if (auction == null) {
            System.out.println("Không tìm thấy phiên đấu giá.");
            return false;
        }
        if (amount <= 0) {
            System.out.println("Số tiền đấu giá phải lớn hơn 0.");
            return false;
        }

        BidTransaction bid = new BidTransaction(
                UUID.randomUUID().toString(),
                auction.getId(),
                getId(),
                amount,
                LocalDateTime.now()
        );

        boolean success = auction.placeBid(bid);
        if (success) {
            this.bidHistory.add(bid);
            System.out.println(username + " đã đặt giá: " + amount);
        }
        return success;
    }

    public Message createProductRequest(String productDescription) {
        return new Message("PRODUCT_REQUEST", productDescription);
    }

    public AuctionProposalRequest createAuctionProposal(String productDescription,
                                                        LocalDateTime requestedStartTime,
                                                        long requestedDurationMinutes) {
        return new AuctionProposalRequest(getId(), productDescription, requestedStartTime, requestedDurationMinutes);
    }

    public List<BidTransaction> getBidHistory() {
        return bidHistory;
    }

    @Override
    public AccountRole getRole() {
        return AccountRole.BIDDER;
    }
}
