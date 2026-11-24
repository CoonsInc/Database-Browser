package org.databasebrowser.app;

import java.util.List;

final class DemoData {
    private DemoData() {}

    record Column(String name, String type) {}

    record Table(String name, List<Column> columns) {}

    record Result(List<String> columns, List<List<String>> rows) {}

    static List<Table> tables() {
        return List.of(
            new Table("users",
                List.of(new Column("id", "INTEGER"), new Column("name", "TEXT"),
                    new Column("email", "TEXT"))),
            new Table("orders",
                List.of(new Column("id", "INTEGER"), new Column("user_id", "INTEGER"),
                    new Column("total", "REAL"), new Column("created_at", "TEXT"))));
    }

    static List<String> views() {
        return List.of("v_active_users");
    }

    static List<String> indexes() {
        return List.of("ix_users_email", "ix_orders_user_id");
    }

    static Result demoResult() {
        return new Result(List.of("id", "name", "email"),
            List.of(List.of("1", "Alice", "alice@example.com"), List.of("2", "Bob", "bob@example.com"),
                List.of("3", "Carol", "carol@example.com")));
    }
}
