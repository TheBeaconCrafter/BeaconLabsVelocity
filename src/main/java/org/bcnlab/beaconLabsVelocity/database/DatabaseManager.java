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
