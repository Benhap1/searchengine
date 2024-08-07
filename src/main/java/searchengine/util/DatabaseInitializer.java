package searchengine.util;

import org.springframework.stereotype.Service;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Service
public class DatabaseInitializer {

    private final DataSource dataSource;
    private final SpringLiquibase springLiquibase;

    public DatabaseInitializer(DataSource dataSource, SpringLiquibase springLiquibase) {
        this.dataSource = dataSource;
        this.springLiquibase = springLiquibase;
    }

    public void initializeDatabase() {
        try {
            recreateDatabase();
            runLiquibaseMigrations();
        } catch (Exception e) {
            throw new RuntimeException("Error initializing database", e);
        }
    }

    private void recreateDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            dropAndCreateDatabase(connection);
        } catch (SQLException e) {
            throw new RuntimeException("Error dropping and creating database", e);
        }
    }

    private void dropAndCreateDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS searchengine");
            statement.executeUpdate("CREATE DATABASE searchengine");
            statement.executeUpdate("USE searchengine");
        }
    }

    private void runLiquibaseMigrations() throws LiquibaseException {
        springLiquibase.setChangeLog("classpath:/db/changelog/changelog.xml");
        springLiquibase.afterPropertiesSet();
    }
}
