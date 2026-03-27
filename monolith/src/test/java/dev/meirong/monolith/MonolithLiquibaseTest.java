package dev.meirong.monolith;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class MonolithLiquibaseTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shopdb")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void allTablesExist() throws Exception {
        Set<String> expected = Set.of("users", "products", "orders", "order_items", "payments");
        Set<String> actual = new HashSet<>();

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", null, new String[]{"TABLE"})) {
                while (rs.next()) {
                    actual.add(rs.getString("TABLE_NAME"));
                }
            }
        }

        assertTrue(actual.containsAll(expected),
                "Missing tables: " + expected.stream().filter(t -> !actual.contains(t)).toList());
    }

    @Test
    void foreignKeyConstraintsExist() throws Exception {
        Map<String, List<String>> expectedFKs = Map.of(
                "orders", List.of("fk_orders_user_id"),
                "order_items", List.of("fk_order_items_order_id", "fk_order_items_product_id"),
                "payments", List.of("fk_payments_order_id")
        );

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (var entry : expectedFKs.entrySet()) {
                String table = entry.getKey();
                List<String> expectedConstraints = entry.getValue();
                Set<String> actualConstraints = new HashSet<>();

                try (ResultSet rs = meta.getImportedKeys(null, "public", table)) {
                    while (rs.next()) {
                        actualConstraints.add(rs.getString("FK_NAME"));
                    }
                }

                for (String fk : expectedConstraints) {
                    assertTrue(actualConstraints.contains(fk),
                            "Table '" + table + "' missing FK constraint: " + fk);
                }
            }
        }
    }

    @Test
    void databaseChangeLogTableExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", "databasechangelog", new String[]{"TABLE"})) {
                assertTrue(rs.next(), "DATABASECHANGELOG table should exist in public schema");
            }
        }
    }
}
