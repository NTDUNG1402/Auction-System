package app.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import app.model.AccountRole;
import app.model.Admin;
import app.model.Art;
import app.model.Auction;
import app.model.Bidder;
import app.model.Electronics;
import app.model.Item;
import app.model.Seller;
import app.model.User;
import app.model.Vehicle;

public class AppDatabase {
    private static final AppDatabase INSTANCE = new AppDatabase();
    private static final String DB_URL = "jdbc:sqlite:auction_app.db";

    private AppDatabase() {
        createTables();
        migrateVehicleColumns();
        migrateLegacyAccountRoles();

        ensureDefaultAccounts();
        if (getAuctions().isEmpty()) {
            seedInventory();
        }
    }

    public static AppDatabase getInstance() {
        return INSTANCE;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private void createTables() {
        String sqlAccounts = "CREATE TABLE IF NOT EXISTS Accounts (" +
                "username TEXT PRIMARY KEY, password TEXT, role TEXT);";

        String sqlItems = "CREATE TABLE IF NOT EXISTS Items (" +
                "id TEXT PRIMARY KEY, type TEXT, name TEXT, description TEXT, " +
                "startingPrice REAL, artist TEXT, creationYear INTEGER, warrantyMonths INTEGER, " +
                "brand TEXT, mileage INTEGER);";

        String sqlAuctions = "CREATE TABLE IF NOT EXISTS Auctions (" +
                "id TEXT PRIMARY KEY, item_id TEXT, startTime TEXT, stopTime TEXT, " +
                "currentHighestPrice REAL, status TEXT, FOREIGN KEY(item_id) REFERENCES Items(id));";

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(sqlAccounts);
            stmt.execute(sqlItems);
            stmt.execute(sqlAuctions);
        } catch (SQLException e) {
            System.err.println("Lỗi tạo bảng: " + e.getMessage());
        }
    }

    private void migrateLegacyAccountRoles() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE Accounts SET role = 'SELLER' WHERE role = 'DEV'");
            stmt.executeUpdate("UPDATE Accounts SET role = 'BIDDER' WHERE role = 'GUEST'");
            stmt.executeUpdate("UPDATE Accounts SET role = 'BIDDER' WHERE role IS NULL OR role = ''");
        } catch (SQLException e) {
            System.err.println("Lỗi migrate role tài khoản: " + e.getMessage());
        }
    }

    private void migrateVehicleColumns() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            if (!columnExists(conn, "Items", "brand")) {
                stmt.executeUpdate("ALTER TABLE Items ADD COLUMN brand TEXT");
            }
            if (!columnExists(conn, "Items", "mileage")) {
                stmt.executeUpdate("ALTER TABLE Items ADD COLUMN mileage INTEGER");
            }
        } catch (SQLException e) {
            System.err.println("Lỗi migrate cột Vehicle: " + e.getMessage());
        }
    }

    private boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }

    private void ensureDefaultAccounts() {
        ensureDefaultUser(new Admin("U_ADMIN", "admin", "admin123"));
        ensureDefaultUser(new Seller("U_SELLER", "seller", "seller123"));
        ensureDefaultUser(new Bidder("U_BIDDER", "bidder", "bidder123"));
    }

    private void ensureDefaultUser(User user) {
        if (!usernameExists(user.getUsername())) {
            addUser(user);
        }
    }

    private void seedInventory() {
        addAuction(new Auction("A01", new Art("I_A01", "Tranh phố cổ", "Tranh sơn dầu Hà Nội", 1200.0, "Nguyễn Xuân Phái", 1980), LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusHours(2), 1200.0, "RUNNING"));
        addAuction(new Auction("A02", new Art("I_A02", "Tượng Gỗ Lũa", "Tượng nghệ thuật điêu khắc", 500.0, "Nghệ nhân Việt", 2023), LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusHours(1), 500.0, "RUNNING"));
        addAuction(new Auction("E01", new Electronics("I_E01", "iPhone 15 Pro", "8/128", 1000.0, 12), LocalDateTime.now().minusMinutes(20), LocalDateTime.now().plusHours(3), 1000.0, "RUNNING"));
        addAuction(new Auction("E02", new Electronics("I_E02", "MacBook M4", "8/512", 2600.0, 24), LocalDateTime.now().minusMinutes(15), LocalDateTime.now().plusHours(3), 2600.0, "RUNNING"));
    }

    public synchronized boolean addUser(User user) {
        if (user == null || user.getUsername() == null) {
            return false;
        }
        if (!user.getRole().canSelfRegister() && user.getRole() != AccountRole.ADMIN) {
            return false;
        }

        String sql = "INSERT INTO Accounts (username, password, role) VALUES (?, ?, ?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, normalizeUsername(user.getUsername()));
            pstmt.setString(2, user.getPassword());
            pstmt.setString(3, user.getRole().name());
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized User authenticate(String username, String password) {
        String sql = "SELECT * FROM Accounts WHERE username = ? AND password = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, normalizeUsername(username));
            pstmt.setString(2, password);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return createUserFromResultSet(rs);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public synchronized boolean usernameExists(String username) {
        String sql = "SELECT 1 FROM Accounts WHERE username = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, normalizeUsername(username));
            return pstmt.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized List<User> getUsers() {
        List<User> users = new ArrayList<>();
        String sql = "SELECT * FROM Accounts";
        try (Connection conn = connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                users.add(createUserFromResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return users;
    }

    private User createUserFromResultSet(ResultSet rs) throws SQLException {
        String username = rs.getString("username");
        String password = rs.getString("password");
        AccountRole role = parseRole(rs.getString("role"));
        String id = "U_" + username;

        switch (role) {
            case ADMIN:
                return new Admin(id, username, password);
            case SELLER:
                return new Seller(id, username, password);
            case BIDDER:
            default:
                return new Bidder(id, username, password);
        }
    }

    private AccountRole parseRole(String role) {
        if ("DEV".equalsIgnoreCase(role)) {
            return AccountRole.SELLER;
        }
        if ("GUEST".equalsIgnoreCase(role)) {
            return AccountRole.BIDDER;
        }
        try {
            return AccountRole.valueOf(role);
        } catch (Exception e) {
            return AccountRole.BIDDER;
        }
    }

    public synchronized boolean addItem(Item item) {
        if (item == null || item.getId() == null) {
            return false;
        }
        String sql = "INSERT INTO Items (id, type, name, description, startingPrice, artist, creationYear, warrantyMonths, brand, mileage) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, item.getId());
            pstmt.setString(3, item.getName());
            pstmt.setString(4, item.getDescription());
            pstmt.setDouble(5, item.getStartingPrice());

            if (item instanceof Art) {
                Art art = (Art) item;
                pstmt.setString(2, "ART");
                pstmt.setString(6, art.getArtist());
                pstmt.setInt(7, art.getCreationYear());
                pstmt.setNull(8, Types.INTEGER);
                pstmt.setNull(9, Types.VARCHAR);
                pstmt.setNull(10, Types.INTEGER);
            } else if (item instanceof Electronics) {
                Electronics elec = (Electronics) item;
                pstmt.setString(2, "ELEC");
                pstmt.setNull(6, Types.VARCHAR);
                pstmt.setNull(7, Types.INTEGER);
                pstmt.setInt(8, elec.getWarrantyMonths());
                pstmt.setNull(9, Types.VARCHAR);
                pstmt.setNull(10, Types.INTEGER);
            } else if (item instanceof Vehicle) {
                Vehicle vehicle = (Vehicle) item;
                pstmt.setString(2, "VEHICLE");
                pstmt.setNull(6, Types.VARCHAR);
                pstmt.setNull(7, Types.INTEGER);
                pstmt.setNull(8, Types.INTEGER);
                pstmt.setString(9, vehicle.getBrand());
                pstmt.setInt(10, vehicle.getMileage());
            } else {
                return false;
            }
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized Item findItemById(String itemId) {
        String sql = "SELECT * FROM Items WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, itemId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return extractItemFromResultSet(rs);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public synchronized List<Item> getItems() {
        List<Item> items = new ArrayList<>();
        String sql = "SELECT * FROM Items";
        try (Connection conn = connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                items.add(extractItemFromResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return items;
    }

    private Item extractItemFromResultSet(ResultSet rs) throws SQLException {
        String type = rs.getString("type");
        String id = rs.getString("id");
        String name = rs.getString("name");
        String desc = rs.getString("description");
        double price = rs.getDouble("startingPrice");

        if ("ART".equals(type)) {
            return new Art(id, name, desc, price, rs.getString("artist"), rs.getInt("creationYear"));
        } else if ("ELEC".equals(type)) {
            return new Electronics(id, name, desc, price, rs.getInt("warrantyMonths"));
        } else if ("VEHICLE".equals(type)) {
            return new Vehicle(id, name, desc, price, rs.getString("brand"), rs.getInt("mileage"));
        }
        return null;
    }

    public synchronized boolean addAuction(Auction auction) {
        if (auction == null || auction.getId() == null) {
            return false;
        }

        if (findItemById(auction.getItem().getId()) == null) {
            addItem(auction.getItem());
        }

        String sql = "INSERT INTO Auctions (id, item_id, startTime, stopTime, currentHighestPrice, status) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, auction.getId());
            pstmt.setString(2, auction.getItem().getId());
            pstmt.setString(3, auction.getStartTime().toString());
            pstmt.setString(4, auction.getStopTime().toString());
            pstmt.setDouble(5, auction.getCurrentHighestPrice());
            pstmt.setString(6, auction.getStatus());
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized boolean stopAuction(String auctionId) {
        String sql = "UPDATE Auctions SET status = 'STOPPED' WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, auctionId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized boolean deleteAuction(String auctionId) {
        String sql = "DELETE FROM Auctions WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, auctionId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized Auction findAuctionById(String auctionId) {
        String sql = "SELECT * FROM Auctions WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, auctionId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                Item item = findItemById(rs.getString("item_id"));
                return new Auction(
                        rs.getString("id"),
                        item,
                        LocalDateTime.parse(rs.getString("startTime")),
                        LocalDateTime.parse(rs.getString("stopTime")),
                        rs.getDouble("currentHighestPrice"),
                        rs.getString("status")
                );
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public synchronized List<Auction> getAuctions() {
        List<Auction> auctions = new ArrayList<>();
        String sql = "SELECT * FROM Auctions";
        try (Connection conn = connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Item item = findItemById(rs.getString("item_id"));
                auctions.add(new Auction(
                        rs.getString("id"),
                        item,
                        LocalDateTime.parse(rs.getString("startTime")),
                        LocalDateTime.parse(rs.getString("stopTime")),
                        rs.getDouble("currentHighestPrice"),
                        rs.getString("status")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return auctions;
    }

    public synchronized Auction createAuction(String auctionId, Item item, LocalDateTime startTime,
                                              LocalDateTime stopTime, double currentHighestPrice, String status) {
        Auction auction = new Auction(auctionId, item, startTime, stopTime, currentHighestPrice, status);
        if (!addAuction(auction)) {
            return null;
        }
        return auction;
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
