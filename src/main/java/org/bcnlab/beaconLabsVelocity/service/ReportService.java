package org.bcnlab.beaconLabsVelocity.service;

import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.bcnlab.beaconLabsVelocity.database.DatabaseManager;
import org.slf4j.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing player reports
 */
public class ReportService {
    private final BeaconLabsVelocity plugin;
    private final DatabaseManager databaseManager;
    private final Logger logger;

    private static final String SQL_CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS player_reports (" +
            "id INT AUTO_INCREMENT PRIMARY KEY, " +
            "reported_uuid VARCHAR(36) NOT NULL, " +
            "reported_name VARCHAR(16) NOT NULL, " +
            "reporter_uuid VARCHAR(36) NOT NULL, " +
            "reporter_name VARCHAR(16) NOT NULL, " +
            "reason VARCHAR(255) NOT NULL, " +
            "server_name VARCHAR(50) NOT NULL, " + 
            "report_time BIGINT NOT NULL, " +
            "status ENUM('OPEN', 'IN_PROGRESS', 'RESOLVED', 'REJECTED') DEFAULT 'OPEN', " +
            "handled_by VARCHAR(36), " +
            "handled_by_name VARCHAR(16), " +
            "resolution_note VARCHAR(255), " +
            "resolution_time BIGINT, " +
            "INDEX idx_reported_name (reported_name), " +
            "INDEX idx_reporter_name (reporter_name), " +
            "INDEX idx_status (status), " +
            "INDEX idx_report_time (report_time)" +
            ")";

    private static final String SQL_ADD_REPORT =
            "INSERT INTO player_reports (reported_uuid, reported_name, reporter_uuid, reporter_name, reason, server_name, report_time) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_GET_REPORT =
            "SELECT * FROM player_reports WHERE id = ?";

    private static final String SQL_GET_REPORTS =
            "SELECT * FROM player_reports WHERE status = ? ORDER BY report_time DESC LIMIT ? OFFSET ?";

    private static final String SQL_GET_ALL_REPORTS =
            "SELECT * FROM player_reports ORDER BY report_time DESC LIMIT ? OFFSET ?";

    private static final String SQL_GET_PLAYER_REPORTS =
            "SELECT * FROM player_reports WHERE reported_name LIKE ? ORDER BY report_time DESC";
            
    private static final String SQL_UPDATE_REPORT_STATUS =
            "UPDATE player_reports SET status = ?, handled_by = ?, handled_by_name = ?, resolution_note = ?, resolution_time = ? " +
            "WHERE id = ?";

    public ReportService(BeaconLabsVelocity plugin, DatabaseManager databaseManager, Logger logger) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.logger = logger;
        
        // Initialize database table
        initReportsTable();
    }

    /**
     * Initialize the reports database table
     */
    private void initReportsTable() {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Report system will be disabled.");
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_CREATE_TABLE)) {
                stmt.execute();
                logger.info("Player reports table initialized.");
            } catch (SQLException e) {
                logger.error("Failed to create player reports table", e);
            }
        });
    }

    /**
     * Represents a player report status
     */
    public enum ReportStatus {
        OPEN("Open"),
        IN_PROGRESS("In Progress"),
        RESOLVED("Resolved"),
        REJECTED("Rejected");
        
        private final String displayName;
        
        ReportStatus(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Class representing a player report
     */
    public static class Report {
        private int id;
        private String reportedUuid;
        private String reportedName;
        private String reporterUuid;
        private String reporterName;
        private String reason;
        private String serverName;
        private long reportTime;
        private ReportStatus status;
        private String handledBy;
        private String handledByName;
        private String resolutionNote;
        private Long resolutionTime;

        public Report(int id, String reportedUuid, String reportedName, String reporterUuid, String reporterName,
                     String reason, String serverName, long reportTime, ReportStatus status,
                     String handledBy, String handledByName, String resolutionNote, Long resolutionTime) {
            this.id = id;
            this.reportedUuid = reportedUuid;
            this.reportedName = reportedName;
            this.reporterUuid = reporterUuid;
            this.reporterName = reporterName;
            this.reason = reason;
            this.serverName = serverName;
            this.reportTime = reportTime;
            this.status = status;
            this.handledBy = handledBy;
            this.handledByName = handledByName;
            this.resolutionNote = resolutionNote;
            this.resolutionTime = resolutionTime;
        }

        // Getters
        public int getId() { return id; }
        public String getReportedUuid() { return reportedUuid; }
        public String getReportedName() { return reportedName; }
        public String getReporterUuid() { return reporterUuid; }
        public String getReporterName() { return reporterName; }
        public String getReason() { return reason; }
        public String getServerName() { return serverName; }
        public long getReportTime() { return reportTime; }
        public ReportStatus getStatus() { return status; }
        public String getHandledBy() { return handledBy; }
        public String getHandledByName() { return handledByName; }
        public String getResolutionNote() { return resolutionNote; }
        public Long getResolutionTime() { return resolutionTime; }
        
        // Factory method to create from ResultSet
        public static Report fromResultSet(ResultSet rs) throws SQLException {
            return new Report(
                rs.getInt("id"),
                rs.getString("reported_uuid"),
                rs.getString("reported_name"),
                rs.getString("reporter_uuid"),
                rs.getString("reporter_name"),
                rs.getString("reason"),
                rs.getString("server_name"),
                rs.getLong("report_time"),
                ReportStatus.valueOf(rs.getString("status")),
                rs.getString("handled_by"),
                rs.getString("handled_by_name"),
                rs.getString("resolution_note"),
                rs.getObject("resolution_time") != null ? rs.getLong("resolution_time") : null
            );
        }
    }

    /**
     * Create a new player report
     * 
     * @param reportedUuid UUID of the reported player
     * @param reportedName Username of the reported player
     * @param reporterUuid UUID of the reporting player
     * @param reporterName Username of the reporting player
     * @param reason Reason for the report
     * @param serverName Server where the incident occurred
     * @return CompletableFuture that completes with the report ID if successful, or -1 if failed
     */
    public CompletableFuture<Integer> createReport(String reportedUuid, String reportedName, 
                                               String reporterUuid, String reporterName, 
                                               String reason, String serverName) {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot create report.");
            return CompletableFuture.completedFuture(-1);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_ADD_REPORT, Statement.RETURN_GENERATED_KEYS)) {
                
                long currentTime = Instant.now().getEpochSecond();
                
                stmt.setString(1, reportedUuid);
                stmt.setString(2, reportedName);
                stmt.setString(3, reporterUuid);
                stmt.setString(4, reporterName);
                stmt.setString(5, reason);
                stmt.setString(6, serverName);
                stmt.setLong(7, currentTime);
                
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                        if (generatedKeys.next()) {
                            int reportId = generatedKeys.getInt(1);
                            logger.info("Player {} reported player {} (Report ID: {})", reporterName, reportedName, reportId);
                            return reportId;
                        }
                    }
                }
                
                logger.warn("Failed to create player report");
                return -1;
            } catch (SQLException e) {
                logger.error("Database error creating player report: {}", e.getMessage(), e);
                return -1;
            }
        });
    }

    /**
     * Get a specific report by ID
     * 
     * @param reportId The report ID
     * @return CompletableFuture that completes with the Report if found, or null if not found
     */
    public CompletableFuture<Report> getReport(int reportId) {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot get report.");
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_GET_REPORT)) {
                
                stmt.setInt(1, reportId);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Report.fromResultSet(rs);
                    }
                }
                
                return null;
            } catch (SQLException e) {
                logger.error("Database error getting report: {}", e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Get reports with a specific status
     * 
     * @param status The report status to filter by, or null for all reports
     * @param limit Maximum number of reports to retrieve
     * @param offset Pagination offset
     * @return CompletableFuture that completes with a list of Reports
     */
    public CompletableFuture<List<Report>> getReports(ReportStatus status, int limit, int offset) {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot get reports.");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        return CompletableFuture.supplyAsync(() -> {
            List<Report> reports = new ArrayList<>();
            
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = status != null 
                     ? conn.prepareStatement(SQL_GET_REPORTS)
                     : conn.prepareStatement(SQL_GET_ALL_REPORTS)) {
                
                if (status != null) {
                    stmt.setString(1, status.name());
                    stmt.setInt(2, limit);
                    stmt.setInt(3, offset);
                } else {
                    stmt.setInt(1, limit);
                    stmt.setInt(2, offset);
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        reports.add(Report.fromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Database error getting reports: {}", e.getMessage(), e);
            }
            
            return reports;
        });
    }

    /**
     * Get reports for a specific player
     * 
     * @param playerName The player's name (can be partial for LIKE search)
     * @return CompletableFuture that completes with a list of Reports
     */
    public CompletableFuture<List<Report>> getPlayerReports(String playerName) {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot get player reports.");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        return CompletableFuture.supplyAsync(() -> {
            List<Report> reports = new ArrayList<>();
            
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_GET_PLAYER_REPORTS)) {
                
                stmt.setString(1, "%" + playerName + "%");
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        reports.add(Report.fromResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                logger.error("Database error getting player reports: {}", e.getMessage(), e);
            }
            
            return reports;
        });
    }

    /**
     * Update a report's status
     * 
     * @param reportId The report ID
     * @param newStatus The new status
     * @param handledByUuid UUID of the staff member handling the report
     * @param handledByName Username of the staff member handling the report
     * @param resolutionNote Note about the resolution
     * @return CompletableFuture that completes with true if successful, false otherwise
     */
    public CompletableFuture<Boolean> updateReportStatus(int reportId, ReportStatus newStatus, 
                                                    String handledByUuid, String handledByName, 
                                                    String resolutionNote) {
        if (!databaseManager.isConnected()) {
            logger.warn("Database is not connected. Cannot update report status.");
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SQL_UPDATE_REPORT_STATUS)) {
                
                Long resolutionTime = null;
                if (newStatus == ReportStatus.RESOLVED || newStatus == ReportStatus.REJECTED) {
                    resolutionTime = Instant.now().getEpochSecond();
                }
                
                stmt.setString(1, newStatus.name());
                stmt.setString(2, handledByUuid);
                stmt.setString(3, handledByName);
                stmt.setString(4, resolutionNote);
                
                if (resolutionTime != null) {
                    stmt.setLong(5, resolutionTime);
                } else {
                    stmt.setNull(5, Types.BIGINT);
                }
                
                stmt.setInt(6, reportId);
                
                int affectedRows = stmt.executeUpdate();
                
                if (affectedRows > 0) {
                    logger.info("Report {} status updated to {} by {}", reportId, newStatus.name(), handledByName);
                    return true;
                } else {
                    logger.warn("Failed to update report {} status", reportId);
                    return false;
                }
            } catch (SQLException e) {
                logger.error("Database error updating report status: {}", e.getMessage(), e);
                return false;
            }
        });
    }
}
