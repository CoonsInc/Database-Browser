package org.databasebrowser.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.databasebrowser.app.DemoData.Result;
import org.databasebrowser.app.DemoData.Table;

public final class MainWindow extends Application {
    private final TextArea sqlArea =
        new TextArea("SELECT id, name, email\nFROM users\nLIMIT 10;");
    private final TableView<ObservableList<String>> resultTable = new TableView<>();
    private final Label statusLabel = new Label("Ready");

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(menuBar(stage));
        root.setCenter(split());
        root.setBottom(new HBox(statusLabel));

        stage.setTitle("Database Browser (demo)");
        stage.setScene(new Scene(root, 1000, 650));
        stage.show();
    }

    private MenuBar menuBar(Stage stage) {
        MenuItem open = new MenuItem("Open database...");
        open.setOnAction(e -> openDatabase(stage));
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());

        Menu file = new Menu("File");
        file.getItems().addAll(open, new SeparatorMenuItem(), exit);

        MenuItem execute = new MenuItem("Execute");
        execute.setAccelerator(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN));
        execute.setOnAction(e -> execute());

        Menu query = new Menu("Query");
        query.getItems().add(execute);

        return new MenuBar(file, query);
    }

    private SplitPane split() {
        SplitPane split = new SplitPane(schemaTree(), sqlPane());
        split.setDividerPositions(0.28);
        return split;
    }

    private TreeView<String> schemaTree() {
        TreeItem<String> root = new TreeItem<>("demo.db");
        TreeItem<String> tables = new TreeItem<>("Tables");
        for (Table t : DemoData.tables()) {
            TreeItem<String> node = new TreeItem<>(t.name());
            t.columns()
                .forEach(c -> node.getChildren().add(new TreeItem<>(c.name() + " : " + c.type())));
            tables.getChildren().add(node);
        }
        TreeItem<String> views = new TreeItem<>("Views");
        DemoData.views().forEach(v -> views.getChildren().add(new TreeItem<>(v)));
        TreeItem<String> indexes = new TreeItem<>("Indexes");
        DemoData.indexes().forEach(i -> indexes.getChildren().add(new TreeItem<>(i)));
        root.getChildren().addAll(tables, views, indexes);
        root.setExpanded(true);
        tables.setExpanded(true);
        tables.getChildren().forEach(n -> n.setExpanded(true));
        return new TreeView<>(root);
    }

    private VBox sqlPane() {
        sqlArea.setPrefRowCount(6);
        Button executeButton = new Button("Execute");
        executeButton.setOnAction(e -> execute());

        Region spacer = new Region();
        HBox buttons = new HBox(spacer, executeButton);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttons.setPadding(new Insets(4, 0, 4, 0));

        resultTable.setPlaceholder(new Label("No results yet"));
        VBox.setVgrow(resultTable, Priority.ALWAYS);
        return new VBox(4, sqlArea, buttons, resultTable);
    }

    private void openDatabase(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open database");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("SQLite databases", "*.db", "*.sqlite", "*.sqlite3"),
            new FileChooser.ExtensionFilter("All files", "*.*"));
        var file = chooser.showOpenDialog(stage);
        if (file != null)
            statusLabel.setText("Selected: " + file.getAbsolutePath());
    }

    private void execute() {
        Result result = DemoData.demoResult();
        long start = System.nanoTime();
        resultTable.getColumns().setAll(result.columns().stream().map(name -> {
            int index = result.columns().indexOf(name);
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(name);
            column.setCellValueFactory(
                row -> new ReadOnlyStringWrapper(row.getValue().get(index)));
            return column;
        }).toList());
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        result.rows().forEach(row -> rows.add(FXCollections.observableArrayList(row)));
        resultTable.setItems(rows);
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        statusLabel.setText(result.rows().size() + " rows, " + String.format("%.1f", ms) + " ms");
    }
}
