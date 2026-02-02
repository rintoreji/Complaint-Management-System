import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.sql.*;
import java.util.*;
import java.util.List;

public class ComplaintManagementSystem {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(ComplaintSystemGUI::new);
    }
}

class InvalidStatusException extends Exception {
    public InvalidStatusException(String msg) { super(msg); }
}

class User {
    String userId, name, contact;

    User(String userId, String name, String contact) {
        this.userId = userId;
        this.name = name;
        this.contact = contact;
    }

    @Override
    public String toString() { return name + " (" + userId + ")"; }
}

class Admin extends User {
    Admin(String adminId, String name, String contact) { super(adminId, name, contact); }
}

class Complaint {
    String complaintId, userId, adminId, type, description, status;
    List<String> statusHistory;

    Complaint(String complaintId, String userId, String type, String description) {
        this.complaintId = complaintId;
        this.userId = userId;
        this.type = type;
        this.description = description;
        this.status = "OPEN";
        this.statusHistory = new ArrayList<>();
        this.statusHistory.add(this.status);
    }

    Complaint(String id, String uId, String aId, String type, String desc, String status, List<String> history) {
        this.complaintId = id;
        this.userId = uId;
        this.adminId = aId;
        this.type = type;
        this.description = desc;
        this.status = status;
        this.statusHistory = new ArrayList<>(history);
    }

    void updateStatus(String newStatus) throws InvalidStatusException {
        if (!("IN_PROGRESS".equals(newStatus) || "CLOSED".equals(newStatus))) {
            throw new InvalidStatusException("Invalid status! Use IN_PROGRESS or CLOSED.");
        }
        this.status = newStatus;
        this.statusHistory.add(newStatus);
    }
}

class DatabaseHelper {
    private static final String DB_NAME = "complaint_db";
    private static final String URL = "jdbc:mysql://localhost:3306/" + DB_NAME + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "complaint_user";
    private static final String DB_PASS = "password";

    public DatabaseHelper() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            setupDatabase();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "DB Error: " + e.getMessage());
        }
    }

    private void setupDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/?useSSL=false", DB_USER, DB_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS " + DB_NAME);
        }

        try (Connection conn = DriverManager.getConnection(URL, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (user_id VARCHAR(255) PRIMARY KEY, name VARCHAR(255), contact VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS admins (admin_id VARCHAR(255) PRIMARY KEY, name VARCHAR(255), contact VARCHAR(255))");
            stmt.execute("CREATE TABLE IF NOT EXISTS complaints (complaint_id VARCHAR(255) PRIMARY KEY, user_id VARCHAR(255), admin_id VARCHAR(255), " +
                         "type VARCHAR(100), description TEXT, status VARCHAR(50), history TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                         "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");
            
            stmt.execute("INSERT IGNORE INTO admins (admin_id, name, contact) VALUES ('A1', 'Default Admin', 'admin@system.com')");
        }
    }

    public void addUser(User u) throws SQLException {
        String sql = "INSERT INTO users(user_id, name, contact) VALUES(?,?,?)";
        try (Connection conn = DriverManager.getConnection(URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, u.userId); pstmt.setString(2, u.name); pstmt.setString(3, u.contact);
            pstmt.executeUpdate();
        }
    }

    public List<User> getAllUsers() throws SQLException {
        List<User> users = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(URL, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY name")) {
            while (rs.next()) users.add(new User(rs.getString("user_id"), rs.getString("name"), rs.getString("contact")));
        }
        return users;
    }

    public void addComplaint(Complaint c) throws SQLException {
        String sql = "INSERT INTO complaints(complaint_id, user_id, type, description, status, history) VALUES(?,?,?,?,?,?)";
        try (Connection conn = DriverManager.getConnection(URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, c.complaintId); pstmt.setString(2, c.userId);
            pstmt.setString(3, c.type); pstmt.setString(4, c.description);
            pstmt.setString(5, c.status); pstmt.setString(6, String.join(";", c.statusHistory));
            pstmt.executeUpdate();
        }
    }

    public Optional<Complaint> getComplaint(String id) throws SQLException {
        String sql = "SELECT * FROM complaints WHERE complaint_id = ?";
        try (Connection conn = DriverManager.getConnection(URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                List<String> history = new ArrayList<>(Arrays.asList(rs.getString("history").split(";")));
                return Optional.of(new Complaint(rs.getString("complaint_id"), rs.getString("user_id"), 
                        rs.getString("admin_id"), rs.getString("type"), rs.getString("description"), 
                        rs.getString("status"), history));
            }
        }
        return Optional.empty();
    }

    public void updateComplaint(Complaint c) throws SQLException {
        String sql = "UPDATE complaints SET status = ?, history = ?, admin_id = ? WHERE complaint_id = ?";
        try (Connection conn = DriverManager.getConnection(URL, DB_USER, DB_PASS);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, c.status); pstmt.setString(2, String.join(";", c.statusHistory));
            pstmt.setString(3, c.adminId); pstmt.setString(4, c.complaintId);
            pstmt.executeUpdate();
        }
    }

    public List<Vector<Object>> getComplaintViewData() throws SQLException {
        List<Vector<Object>> data = new ArrayList<>();
        String sql = "SELECT c.complaint_id, u.name, c.type, c.status, IFNULL(c.admin_id, 'Not Assigned') " +
                     "FROM complaints c JOIN users u ON c.user_id = u.user_id ORDER BY c.created_at DESC";
        try (Connection conn = DriverManager.getConnection(URL, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Vector<Object> row = new Vector<>();
                row.add(rs.getString(1)); row.add(rs.getString(2)); row.add(rs.getString(3));
                row.add(rs.getString(4)); row.add(rs.getString(5));
                data.add(row);
            }
        }
        return data;
    }
}

class ComplaintSystemGUI extends JFrame {
    private final DatabaseHelper dbHelper = new DatabaseHelper();
    private final Admin systemAdmin = new Admin("A1", "Default Admin", "admin@system.com");
    
    private JTable complaintTable, userTable;
    private DefaultTableModel complaintModel, userModel;
    private JComboBox<User> userComboBox;

    public ComplaintSystemGUI() {
        setTitle("Complaint Management System - Dashboard");
        setSize(400, 450);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel menuPanel = new JPanel(new GridLayout(4, 1, 15, 15));
        menuPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JButton btnUsers = createStyledButton("Manage Users");
        JButton btnFile = createStyledButton("File New Complaint");
        JButton btnView = createStyledButton("View All Complaints");
        JButton btnExit = createStyledButton("Exit");

        btnUsers.addActionListener(e -> showManageUsersDialog());
        btnFile.addActionListener(e -> showFileComplaintDialog());
        btnView.addActionListener(e -> showComplaintsDialog());
        btnExit.addActionListener(e -> System.exit(0));

        menuPanel.add(btnUsers); menuPanel.add(btnFile); menuPanel.add(btnView); menuPanel.add(btnExit);
        add(menuPanel, BorderLayout.CENTER);
        
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private String generateID(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private JButton createStyledButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("SansSerif", Font.BOLD, 14));
        b.setBackground(new Color(230, 240, 250));
        return b;
    }

    private void showManageUsersDialog() {
        JDialog dialog = new JDialog(this, "Manage Users", true);
        dialog.setLayout(new BorderLayout(10, 10));

        userModel = new DefaultTableModel(new String[]{"User ID", "Name", "Contact"}, 0);
        userTable = new JTable(userModel);
        refreshUserTable();

        JPanel addPanel = new JPanel(new GridLayout(3, 2, 5, 5));
        addPanel.setBorder(BorderFactory.createTitledBorder("Register New User"));
        JTextField nameF = new JTextField(), contactF = new JTextField();
        
        addPanel.add(new JLabel("Name:")); addPanel.add(nameF);
        addPanel.add(new JLabel("Contact:")); addPanel.add(contactF);
        
        JButton btnAdd = new JButton("Generate ID & Add");
        btnAdd.addActionListener(e -> {
            try {
                if(nameF.getText().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(dialog, "Name cannot be empty!");
                    return;
                }
                String uniqueUserID = generateID("USR");
                dbHelper.addUser(new User(uniqueUserID, nameF.getText(), contactF.getText()));
                refreshUserTable();
                nameF.setText(""); contactF.setText("");
                JOptionPane.showMessageDialog(dialog, "User Created with ID: " + uniqueUserID);
            } catch (SQLException ex) { JOptionPane.showMessageDialog(dialog, "Error: " + ex.getMessage()); }
        });
        addPanel.add(new JLabel()); addPanel.add(btnAdd);

        dialog.add(new JScrollPane(userTable), BorderLayout.CENTER);
        dialog.add(addPanel, BorderLayout.SOUTH);
        dialog.setSize(500, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showFileComplaintDialog() {
        JDialog dialog = new JDialog(this, "File New Complaint", true);
        dialog.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5); gbc.fill = GridBagConstraints.HORIZONTAL;

        userComboBox = new JComboBox<>();
        refreshUserComboBox();

        JTextField typeF = new JTextField(15);
        JTextArea descA = new JTextArea(4, 15);
        
        gbc.gridx = 0; gbc.gridy = 0; dialog.add(new JLabel("Select User:"), gbc);
        gbc.gridx = 1; dialog.add(userComboBox, gbc);
        gbc.gridx = 0; gbc.gridy = 1; dialog.add(new JLabel("Type:"), gbc);
        gbc.gridx = 1; dialog.add(typeF, gbc);
        gbc.gridx = 0; gbc.gridy = 2; dialog.add(new JLabel("Description:"), gbc);
        gbc.gridx = 1; dialog.add(new JScrollPane(descA), gbc);

        JButton btnSubmit = new JButton("Auto-Generate ID & Submit");
        btnSubmit.addActionListener(e -> {
            try {
                User u = (User) userComboBox.getSelectedItem();
                if (u != null) {
                    String uniqueCompID = generateID("CMP");
                    dbHelper.addComplaint(new Complaint(uniqueCompID, u.userId, typeF.getText(), descA.getText()));
                    JOptionPane.showMessageDialog(dialog, "Complaint Filed! ID: " + uniqueCompID);
                    dialog.dispose();
                }
            } catch (SQLException ex) { JOptionPane.showMessageDialog(dialog, "Error: " + ex.getMessage()); }
        });
        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        dialog.add(btnSubmit, gbc);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showComplaintsDialog() {
        JDialog dialog = new JDialog(this, "All Complaints", true);
        dialog.setLayout(new BorderLayout());

        complaintModel = new DefaultTableModel(new String[]{"ID", "User", "Type", "Status", "Admin"}, 0);
        complaintTable = new JTable(complaintModel);
        refreshComplaintTable();

        JPanel btnPanel = new JPanel();
        JButton btnStatus = new JButton("Update Status");
        JButton btnAssign = new JButton("Assign (Admin)");

        btnStatus.addActionListener(e -> {
            int row = complaintTable.getSelectedRow();
            if (row != -1) {
                String id = (String) complaintModel.getValueAt(row, 0);
                try {
                    Complaint c = dbHelper.getComplaint(id).get();
                    String[] options = {"IN_PROGRESS", "CLOSED"};
                    String status = (String) JOptionPane.showInputDialog(null, "Status:", "Update", JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                    if (status != null) {
                        c.updateStatus(status);
                        dbHelper.updateComplaint(c);
                        refreshComplaintTable();
                    }
                } catch (Exception ex) { JOptionPane.showMessageDialog(dialog, "Error: " + ex.getMessage()); }
            }
        });

        btnAssign.addActionListener(e -> {
            int row = complaintTable.getSelectedRow();
            if (row != -1) {
                try {
                    String id = (String) complaintModel.getValueAt(row, 0);
                    Complaint c = dbHelper.getComplaint(id).get();
                    c.adminId = systemAdmin.userId;
                    dbHelper.updateComplaint(c);
                    refreshComplaintTable();
                } catch (SQLException ex) { JOptionPane.showMessageDialog(dialog, ex.getMessage()); }
            }
        });

        btnPanel.add(btnStatus); btnPanel.add(btnAssign);
        dialog.add(new JScrollPane(complaintTable), BorderLayout.CENTER);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setSize(700, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void refreshUserTable() {
        try {
            userModel.setRowCount(0);
            for (User u : dbHelper.getAllUsers()) userModel.addRow(new Object[]{u.userId, u.name, u.contact});
        } catch (SQLException ignored) {}
    }

    private void refreshUserComboBox() {
        try {
            userComboBox.removeAllItems();
            for (User u : dbHelper.getAllUsers()) userComboBox.addItem(u);
        } catch (SQLException ignored) {}
    }

    private void refreshComplaintTable() {
        try {
            complaintModel.setRowCount(0);
            for (Vector<Object> row : dbHelper.getComplaintViewData()) complaintModel.addRow(row);
        } catch (SQLException ignored) {}
    }
}