package app.controller;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.Gson;

import app.database.AppDatabase;
import app.database.Session;
import app.model.AccountRole;
import app.model.Admin;
import app.model.Art;
import app.model.Auction;
import app.model.AuctionProposalRequest;
import app.model.BidTransaction;
import app.model.Bidder;
import app.model.Electronics;
import app.model.Item;
import app.model.Message;
import app.model.Seller;
import app.model.User;
import app.model.Vehicle;
import app.network.ClientConnection;
import app.network.NetworkConfig;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class MainController {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML
    private FlowPane itemContainer;

    @FXML
    private Button loginButton;

    @FXML
    private Button signUpButton;

    @FXML
    private Button requestProductButton;

    @FXML
    private Button proposeAuctionButton;

    @FXML
    private Button createAuctionButton;

    private final AppDatabase database = AppDatabase.getInstance();

    private final Gson gson = new com.google.gson.GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new com.google.gson.TypeAdapter<LocalDateTime>() {
                @Override
                public void write(com.google.gson.stream.JsonWriter out, LocalDateTime value) throws java.io.IOException {
                    out.value(value != null ? value.toString() : null);
                }

                @Override
                public LocalDateTime read(com.google.gson.stream.JsonReader in) throws java.io.IOException {
                    return LocalDateTime.parse(in.nextString());
                }
            }).create();

    @FXML
    public void initialize() {
        ClientConnection.getInstance().connect(NetworkConfig.HOST, NetworkConfig.PORT);
        applySessionState();
        loadAuctionItems();
    }

    @FXML
    public void login() {
        openScene("/app/Login.fxml");
    }

    @FXML
    public void signUp() {
        openScene("/app/Signup.fxml");
    }

    @FXML
    public void handleRequestProduct() {
        User user = Session.getCurrentUser();
        if (!(user instanceof Bidder)) {
            showAlert(Alert.AlertType.WARNING, "Không đủ quyền", "Chỉ tài khoản Bidder được gửi yêu cầu sản phẩm mới.");
            return;
        }

        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Yêu cầu sản phẩm");
        dialog.setHeaderText("Gửi yêu cầu sản phẩm mới cho Seller");
        dialog.setContentText("Mô tả sản phẩm:");

        dialog.showAndWait().ifPresent(description -> {
            if (description.trim().isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Thiếu thông tin", "Vui lòng nhập mô tả sản phẩm.");
                return;
            }

            Message message = ((Bidder) user).createProductRequest(description.trim());
            sendMessageToServer(message);
            showAlert(Alert.AlertType.INFORMATION, "Đã gửi", "Yêu cầu sản phẩm đã được gửi cho Seller.");
        });
    }

    @FXML
    public void handleProposeAuction() {
        User user = Session.getCurrentUser();
        if (!(user instanceof Bidder)) {
            showAlert(Alert.AlertType.WARNING, "Không đủ quyền", "Chỉ tài khoản Bidder được đề xuất sản phẩm muốn đấu giá.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Đề xuất đấu giá");
        dialog.setHeaderText("Đưa ra sản phẩm muốn đấu giá cho Seller");

        TextField descriptionField = new TextField();
        descriptionField.setPromptText("Tên/mô tả sản phẩm");

        TextField startTimeField = new TextField(LocalDateTime.now().plusMinutes(10).format(DATE_TIME_FORMATTER));
        startTimeField.setPromptText("yyyy-MM-dd HH:mm");

        TextField durationField = new TextField("60");
        durationField.setPromptText("Số phút");

        GridPane grid = createFormGrid();
        grid.addRow(0, new Label("Sản phẩm:"), descriptionField);
        grid.addRow(1, new Label("Bắt đầu mong muốn:"), startTimeField);
        grid.addRow(2, new Label("Thời lượng mong muốn (phút):"), durationField);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(createSubmitButton("Gửi đề xuất"), ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
            return;
        }

        try {
            String description = requireText(descriptionField, "Vui lòng nhập sản phẩm muốn đấu giá.");
            LocalDateTime requestedStartTime = parseStartTime(startTimeField.getText());
            long requestedDurationMinutes = parsePositiveLong(durationField.getText(), "Thời lượng phải là số phút lớn hơn 0.");

            AuctionProposalRequest proposal = ((Bidder) user).createAuctionProposal(
                    description,
                    requestedStartTime,
                    requestedDurationMinutes
            );

            sendMessageToServer(new Message("AUCTION_PROPOSAL", gson.toJson(proposal)));
            showAlert(Alert.AlertType.INFORMATION, "Đã gửi", "Đề xuất đấu giá kèm thời gian mong muốn đã được gửi cho Seller.");
        } catch (IllegalArgumentException e) {
            showAlert(Alert.AlertType.WARNING, "Dữ liệu chưa hợp lệ", e.getMessage());
        }
    }

    @FXML
    public void handleCreateAuction() {
        User user = Session.getCurrentUser();
        if (!(user instanceof Seller)) {
            showAlert(Alert.AlertType.WARNING, "Không đủ quyền", "Chỉ tài khoản Seller được tạo sản phẩm và phiên đấu giá mới.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Tạo phiên đấu giá");
        dialog.setHeaderText("Seller tạo sản phẩm mới và thời gian phiên đấu giá");

        ComboBox<String> typeComboBox = new ComboBox<>();
        typeComboBox.getItems().addAll("ART", "ELECTRONICS", "VEHICLE");
        typeComboBox.setValue("ART");
        typeComboBox.setMaxWidth(Double.MAX_VALUE);

        TextField nameField = new TextField();
        TextField descriptionField = new TextField();
        TextField startingPriceField = new TextField();
        TextField startTimeField = new TextField(LocalDateTime.now().plusMinutes(10).format(DATE_TIME_FORMATTER));
        TextField durationField = new TextField("60");
        TextField detailOneField = new TextField();
        TextField detailTwoField = new TextField();

        nameField.setPromptText("Tên sản phẩm");
        descriptionField.setPromptText("Mô tả");
        startingPriceField.setPromptText("Giá khởi điểm");
        startTimeField.setPromptText("yyyy-MM-dd HH:mm");
        durationField.setPromptText("Số phút");
        detailOneField.setPromptText("Art: tác giả | Electronics: bảo hành | Vehicle: hãng");
        detailTwoField.setPromptText("Art: năm sáng tác | Vehicle: số km");

        GridPane grid = createFormGrid();
        grid.addRow(0, new Label("Loại sản phẩm:"), typeComboBox);
        grid.addRow(1, new Label("Tên:"), nameField);
        grid.addRow(2, new Label("Mô tả:"), descriptionField);
        grid.addRow(3, new Label("Giá khởi điểm:"), startingPriceField);
        grid.addRow(4, new Label("Bắt đầu:"), startTimeField);
        grid.addRow(5, new Label("Thời lượng (phút):"), durationField);
        grid.addRow(6, new Label("Thông tin 1:"), detailOneField);
        grid.addRow(7, new Label("Thông tin 2:"), detailTwoField);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(createSubmitButton("Tạo phiên"), ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
            return;
        }

        try {
            Item item = createItemFromForm(
                    typeComboBox.getValue(),
                    requireText(nameField, "Vui lòng nhập tên sản phẩm."),
                    requireText(descriptionField, "Vui lòng nhập mô tả sản phẩm."),
                    parsePositiveDouble(startingPriceField.getText(), "Giá khởi điểm phải lớn hơn 0."),
                    detailOneField.getText().trim(),
                    detailTwoField.getText().trim()
            );

            LocalDateTime startTime = parseStartTime(startTimeField.getText());
            long durationMinutes = parsePositiveLong(durationField.getText(), "Thời lượng phải là số phút lớn hơn 0.");
            LocalDateTime stopTime = startTime.plusMinutes(durationMinutes);

            Auction auction = new Auction(
                    "A_" + UUID.randomUUID(),
                    item,
                    startTime,
                    stopTime,
                    item.getStartingPrice(),
                    "RUNNING"
            );

            if (database.addAuction(auction)) {
                ((Seller) user).addItem(item);
                loadAuctionItems();
                showAlert(Alert.AlertType.INFORMATION, "Thành công", "Đã tạo sản phẩm và phiên đấu giá mới.");
            } else {
                showAlert(Alert.AlertType.ERROR, "Lỗi", "Không thể lưu phiên đấu giá mới.");
            }
        } catch (IllegalArgumentException e) {
            showAlert(Alert.AlertType.WARNING, "Dữ liệu chưa hợp lệ", e.getMessage());
        }
    }

    private void applySessionState() {
        User user = Session.getCurrentUser();
        boolean loggedIn = user != null;
        boolean isBidder = loggedIn && user.getRole() == AccountRole.BIDDER;
        boolean isSeller = loggedIn && user.getRole() == AccountRole.SELLER;

        loginButton.setVisible(!loggedIn);
        loginButton.setManaged(!loggedIn);
        signUpButton.setVisible(!loggedIn);
        signUpButton.setManaged(!loggedIn);

        requestProductButton.setVisible(isBidder);
        requestProductButton.setManaged(isBidder);
        proposeAuctionButton.setVisible(isBidder);
        proposeAuctionButton.setManaged(isBidder);

        createAuctionButton.setVisible(isSeller);
        createAuctionButton.setManaged(isSeller);
    }

    private void loadAuctionItems() {
        itemContainer.getChildren().clear();

        for (Auction auction : database.getAuctions()) {
            createAuctionCard(auction);
        }
    }

    private void createAuctionCard(Auction auction) {
        Item item = auction.getItem();
        User user = Session.getCurrentUser();
        boolean isBidder = user != null && user.getRole() == AccountRole.BIDDER;
        boolean isAdmin = user != null && user.getRole() == AccountRole.ADMIN;

        VBox card = new VBox(12);
        card.getStyleClass().add("item-card");
        card.setPrefWidth(250);

        Label nameLabel = new Label(item.getName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Label priceLabel = new Label("Giá hiện tại: " + auction.getCurrentHighestPrice() + " USD");
        priceLabel.setStyle("-fx-text-fill: #E67E22; -fx-font-weight: bold;");

        Label statusLabel = new Label("Trạng thái: " + auction.getStatus());
        Label startLabel = new Label("Bắt đầu: " + auction.getStartTime().format(DATE_TIME_FORMATTER));
        Label durationLabel = new Label("Thời lượng: " + java.time.Duration.between(auction.getStartTime(), auction.getStopTime()).toMinutes() + " phút");

        Label detailLabel = new Label();
        detailLabel.setStyle("-fx-text-fill: #7F8C8D; -fx-font-style: italic;");

        if (item instanceof Art) {
            detailLabel.setText("Nghệ sĩ: " + ((Art) item).getArtist());
        } else if (item instanceof Electronics) {
            detailLabel.setText("Bảo hành: " + ((Electronics) item).getWarrantyMonths() + " tháng");
        } else if (item instanceof Vehicle) {
            Vehicle vehicle = (Vehicle) item;
            detailLabel.setText("Hãng: " + vehicle.getBrand() + " - " + vehicle.getMileage() + " km");
        }

        Button bidBtn = new Button(isBidder ? "Đặt giá" : "Chỉ Bidder được đặt giá");
        bidBtn.getStyleClass().add("btn-primary");
        bidBtn.setMaxWidth(Double.MAX_VALUE);
        bidBtn.setDisable(!isBidder);
        bidBtn.setOnAction(e -> handleBidAction(auction, priceLabel));

        card.getChildren().addAll(nameLabel, priceLabel, statusLabel, startLabel, durationLabel, detailLabel, bidBtn);

        if (isAdmin) {
            Button stopBtn = new Button("Ngưng phiên");
            stopBtn.setMaxWidth(Double.MAX_VALUE);
            stopBtn.setOnAction(e -> handleStopAuction(auction));

            Button deleteBtn = new Button("Xóa phiên");
            deleteBtn.setMaxWidth(Double.MAX_VALUE);
            deleteBtn.setOnAction(e -> handleDeleteAuction(auction));

            card.getChildren().addAll(stopBtn, deleteBtn);
        }

        itemContainer.getChildren().add(card);
    }

    private void handleBidAction(Auction auction, Label priceLabel) {
        User user = Session.getCurrentUser();
        if (!(user instanceof Bidder)) {
            showAlert(Alert.AlertType.WARNING, "Không đủ quyền", "Chỉ tài khoản Bidder được đặt giá.");
            return;
        }

        Item item = auction.getItem();
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Đấu giá");
        dialog.setHeaderText("Sản phẩm: " + item.getName());
        dialog.setContentText("Nhập giá đấu (USD):");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(bidAmountStr -> {
            try {
                double bidAmount = Double.parseDouble(bidAmountStr);
                if (bidAmount <= auction.getCurrentHighestPrice()) {
                    showAlert(Alert.AlertType.ERROR, "Lỗi", "Giá đặt phải cao hơn hiện tại!");
                    return;
                }

                BidTransaction transaction = new BidTransaction(
                        UUID.randomUUID().toString(),
                        auction.getId(),
                        user.getId(),
                        bidAmount,
                        LocalDateTime.now()
                );

                boolean success = auction.placeBid(transaction);

                if (success) {
                    priceLabel.setText("Giá hiện tại: " + auction.getCurrentHighestPrice() + " USD");
                    sendBidToServer(transaction);
                    showAlert(Alert.AlertType.INFORMATION, "Thành công", "Đã đặt giá: " + bidAmount + " USD");
                } else {
                    showAlert(Alert.AlertType.ERROR, "Lỗi", "Không thể đặt giá cho phiên này!");
                }
            } catch (NumberFormatException e) {
                showAlert(Alert.AlertType.WARNING, "Chú ý", "Vui lòng nhập số tiền hợp lệ.");
            }
        });
    }

    private void handleStopAuction(Auction auction) {
        if (!isAdmin()) {
            showAlert(Alert.AlertType.WARNING, "Không đủ quyền", "Chỉ Admin được ngưng phiên đấu giá.");
            return;
        }

        if (database.stopAuction(auction.getId())) {
            loadAuctionItems();
            showAlert(Alert.AlertType.INFORMATION, "Đã ngưng", "Phiên đấu giá đã được ngưng.");
        } else {
            showAlert(Alert.AlertType.ERROR, "Lỗi", "Không thể ngưng phiên đấu giá.");
        }
    }

    private void handleDeleteAuction(Auction auction) {
        if (!isAdmin()) {
            showAlert(Alert.AlertType.WARNING, "Không đủ quyền", "Chỉ Admin được xóa phiên đấu giá.");
            return;
        }

        if (database.deleteAuction(auction.getId())) {
            loadAuctionItems();
            showAlert(Alert.AlertType.INFORMATION, "Đã xóa", "Phiên đấu giá đã được xóa.");
        } else {
            showAlert(Alert.AlertType.ERROR, "Lỗi", "Không thể xóa phiên đấu giá.");
        }
    }

    private boolean isAdmin() {
        User user = Session.getCurrentUser();
        return user instanceof Admin;
    }

    private Item createItemFromForm(String type, String name, String description, double startingPrice,
                                    String detailOne, String detailTwo) {
        String itemId = "I_" + UUID.randomUUID();
        switch (type) {
            case "ART":
                if (detailOne.isEmpty()) {
                    throw new IllegalArgumentException("Vui lòng nhập tác giả cho tác phẩm nghệ thuật.");
                }
                return new Art(itemId, name, description, startingPrice, detailOne, parseInteger(detailTwo, "Năm sáng tác phải là số."));
            case "ELECTRONICS":
                return new Electronics(itemId, name, description, startingPrice, parseInteger(detailOne, "Bảo hành phải là số tháng."));
            case "VEHICLE":
                if (detailOne.isEmpty()) {
                    throw new IllegalArgumentException("Vui lòng nhập hãng xe.");
                }
                return new Vehicle(itemId, name, description, startingPrice, detailOne, parseInteger(detailTwo, "Số km phải là số."));
            default:
                throw new IllegalArgumentException("Loại sản phẩm không hợp lệ.");
        }
    }

    private GridPane createFormGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        return grid;
    }

    private ButtonType createSubmitButton(String text) {
        return new ButtonType(text, ButtonBar.ButtonData.OK_DONE);
    }

    private String requireText(TextField field, String errorMessage) {
        String value = field.getText() == null ? "" : field.getText().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return value;
    }

    private LocalDateTime parseStartTime(String value) {
        try {
            return LocalDateTime.parse(value.trim(), DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Thời gian bắt đầu phải theo định dạng yyyy-MM-dd HH:mm.");
        }
    }

    private double parsePositiveDouble(String value, String errorMessage) {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private long parsePositiveLong(String value, String errorMessage) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private int parseInteger(String value, String errorMessage) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private void sendBidToServer(BidTransaction transaction) {
        String jsonData = gson.toJson(transaction);
        sendMessageToServer(new Message("BID", jsonData));
    }

    private void sendMessageToServer(Message message) {
        ClientConnection.getInstance().sendMessage(message);
    }

    @FXML
    void handleLogout(ActionEvent event) {
        Session.clear();
        openScene("/app/Main.fxml");
    }

    private void openScene(String resource) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(resource));
            Scene scene = new Scene(loader.load());

            Stage stage = (Stage) itemContainer.getScene().getWindow();
            stage.setScene(scene);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
