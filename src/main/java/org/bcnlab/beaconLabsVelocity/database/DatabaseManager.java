package org.bcnlab.beaconLabsVelocity.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bcnlab.beaconLabsVelocity.BeaconLabsVelocity;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseManager {

    private final BeaconLabsVelocity plugin;
    private final Logger logger;
    private HikariDataSource dataSource;
    private boolean enabled = false;

    public DatabaseManager(BeaconLabsVelocity plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void connect() {
        ConfigurationNode dbConfig = plugin.getConfig().node("database");
        this.enabled = dbConfig.node("enabled").getBoolean(false);

        if (!enabled) {
            logger.info("Database connection is disabled in the configuration.");
            return;
        }

        logger.info("Attempting to connect to the database...");

        try {
            // Explicitly load the relocated driver class BEFORE initializing HikariCP
            // Shaded driver class (originally org.mariadb.jdbc.Driver)
            Class.forName("org.bcnlab.beaconLabsVelocity.lib.mariadb.Driver");
            logger.debug("Successfully loaded relocated MariaDB driver class.");
        } catch (ClassNotFoundException e) {
            logger.error("Failed to load the relocated MariaDB driver class! Make sure it's shaded correctly.", e);
            this.enabled = false;
            return; // Can't proceed without the driver
        }

        HikariConfig config = new HikariConfig();
        String host = dbConfig.node("host").getString("localhost");
        int port = dbConfig.node("port").getInt(3306);
        String database = dbConfig.node("database").getString("velocity_db");
        String username = dbConfig.node("username").getString("velocity_user");
        String password = dbConfig.node("password").getString("");

        config.setJdbcUrl(String.format("jdbc:mariadb://%s:%d/%s?useSSL=false&autoReconnect=true", host, port, database));
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(dbConfig.node("pool-size").getInt(10));
        config.setConnectionTimeout(dbConfig.node("connection-timeout").getLong(30000));
        config.setIdleTimeout(dbConfig.node("idle-timeout").getLong(600000));
        config.setMaxLifetime(dbConfig.node("max-lifetime").getLong(1800000));

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        try {
            this.dataSource = new HikariDataSource(config);
            logger.info("Successfully connected to the database ({}:{}/{})", host, port, database);
        } catch (Exception e) {
            logger.error("Could not establish database connection!", e);
            this.dataSource = null;
            this.enabled = false; // Mark as disabled if connection failed
        }
        // Initialize punishments table
        if (isConnected()) {
            String createTable = "CREATE TABLE IF NOT EXISTS punishments (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "player_name VARCHAR(16) NOT NULL, " +
                    "issuer_uuid VARCHAR(36), " +
                    "issuer_name VARCHAR(16), " +
                    "type VARCHAR(10) NOT NULL, " +
                    "reason VARCHAR(255), " +
                    "duration BIGINT, " +
                    "start_time BIGINT NOT NULL, " +
                    "end_time BIGINT, " +
                    "active BOOLEAN NOT NULL" +
                ")";
            try (var conn = getConnection(); var stmt = conn.createStatement()) {
                stmt.execute(createTable);
            } catch (Exception ex) {
                logger.error("Failed to initialize punishments table", ex);
            }
            // Legal acceptance table (for legal feature)
            String createLegalTable = "CREATE TABLE IF NOT EXISTS legal_acceptance (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY, " +
                    "accepted_at BIGINT NOT NULL" +
                    ")";
            try (var conn = getConnection(); var stmt = conn.createStatement()) {
                stmt.execute(createLegalTable);
            } catch (Exception ex) {
                logger.error("Failed to initialize legal_acceptance table", ex);
            }
            
            // AntiBot IP cache table
            String createAntiBotTable = "CREATE TABLE IF NOT EXISTS antibot_ip_cache (" +
                    "ip_address VARCHAR(45) PRIMARY KEY, " +
                    "confidence_score INT NOT NULL, " +
                    "is_whitelisted BOOLEAN NOT NULL DEFAULT FALSE, " +
                    "is_blacklisted BOOLEAN NOT NULL DEFAULT FALSE, " +
                    "data_json TEXT, " +
                    "last_checked BIGINT NOT NULL" +
                    ")";
            try (var conn = getConnection(); var stmt = conn.createStatement()) {
                stmt.execute(createAntiBotTable);
            } catch (Exception ex) {
                logger.error("Failed to initialize antibot_ip_cache table", ex);
            }
            
            // AntiBot API Usage table
            String createApiUsageTable = "CREATE TABLE IF NOT EXISTS antibot_api_usage (" +
                    "usage_date DATE PRIMARY KEY, " +
                    "request_count INT DEFAULT 0" +
                    ")";
            try (var conn = getConnection(); var stmt = conn.createStatement()) {
                stmt.execute(createApiUsageTable);
            } catch (Exception ex) {
                logger.error("Failed to initialize antibot_api_usage table", ex);
            }
            
            // Player sessions table (for tracking playtime history)
            String createPlayerSessionsTable = "CREATE TABLE IF NOT EXISTS player_sessions (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "start_time BIGINT NOT NULL, " +
                    "end_time BIGINT NOT NULL, " +
                    "duration BIGINT NOT NULL, " +
                    "INDEX (start_time)" +
                    ")";
            try (var conn = getConnection(); var stmt = conn.createStatement()) {
                stmt.execute(createPlayerSessionsTable);
            } catch (Exception ex) {
                logger.error("Failed to initialize player_sessions table", ex);
            }
            
            // Screening passes table
            String createScreeningPassesTable = "CREATE TABLE IF NOT EXISTS screening_passes (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "ip_address VARCHAR(45) NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "INDEX (player_uuid, ip_address)" +
                    ")";
            try (var conn = getConnection(); var stmt = conn.createStatement()) {
                stmt.execute(createScreeningPassesTable);
            } catch (Exception ex) {
                logger.error("Failed to initialize screening_passes table", ex);
            }
            
            // Force screen table
            String createForceScreenTable = "CREATE TABLE IF NOT EXISTS force_screen (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY" +
                    ")";
            try (var conn = getConnection(); var stmt = conn.createStatement()) {
                stmt.execute(createForceScreenTable);
            } catch (Exception ex) {
                logger.error("Failed to initialize force_screen table", ex);
            }
            
            // Login history table
            String createLoginHistoryTable = "CREATE TABLE IF NOT EXISTS login_history (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "INDEX (timestamp)" +
                    ")";
            try (var conn = getConnection(); var stmt = conn.createStatement()) {
                stmt.execute(createLoginHistoryTable);
            } catch (Exception ex) {
                logger.error("Failed to initialize login_history table", ex);
            }
        }
    }

    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Closing database connection pool...");
            dataSource.close();
            logger.info("Database connection pool closed.");
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || !enabled) {
            throw new SQLException("Database connection is not available or not enabled.");
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            logger.error("Failed to retrieve connection from pool", e);
            throw e;
        }
    }

    public boolean isConnected() {
        return enabled && dataSource != null && !dataSource.isClosed();
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }
}
