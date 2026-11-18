package org.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class Main {
    private static final String DB_URL = "jdbc:sqlite:test.db";

    public static void main(String[] args) {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement()) {

            System.out.println("Connected to " + DB_URL);

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS users ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "name TEXT NOT NULL)");

            statement.executeUpdate("INSERT INTO users (name) VALUES ('Alice')");
            statement.executeUpdate("INSERT INTO users (name) VALUES ('Bob')");

            try (ResultSet resultSet = statement.executeQuery("SELECT id, name FROM users")) {
                while (resultSet.next()) {
                    System.out.println(resultSet.getInt("id") + ": " + resultSet.getString("name"));
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
