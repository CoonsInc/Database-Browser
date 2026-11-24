package org.databasebrowser.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.databasebrowser.core.application.BrowserService;
import org.databasebrowser.core.domain.Connections.ExecutionMode;
import org.databasebrowser.core.domain.Connections.Session;
import org.databasebrowser.core.domain.Connections.Settings;
import org.databasebrowser.core.domain.Connections.TransactionPolicy;
import org.databasebrowser.core.domain.Errors.Code;
import org.databasebrowser.core.domain.Errors.CoreException;
import org.databasebrowser.core.domain.Exports.Target;
import org.databasebrowser.core.domain.Exports.TextOptions;
import org.databasebrowser.core.domain.Ids.AdapterId;
import org.databasebrowser.core.domain.Ids.SessionId;
import org.databasebrowser.core.domain.Schema.Index;
import org.databasebrowser.core.domain.Schema.Snapshot;
import org.databasebrowser.core.domain.Schema.Table;
import org.databasebrowser.core.domain.Schema.View;
import org.databasebrowser.core.domain.Sql;

public final class MainWindow extends Application {
    private static final int BATCH_SIZE = 50;

    private final BrowserService service =
        new BrowserService(List.of(new DemoAdapter()), MainWindow::writeText);
    private final TextArea sqlArea = new TextArea("SELECT id\nFROM existing;");
    private final TableView<ObservableList<String>> resultTable = new TableView<>();
    private final TreeView<String> schemaTree = new TreeView<>();
    private final Label statusLabel = new Label("Not connected");
    private SessionId session;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setTop(menuBar(stage));
        root.setCenter(split());
        root.setBottom(new HBox(statusLabel));

        connect();

        stage.setTitle("Database Browser (demo)");
        stage.setScene(new Scene(root, 1000, 650));
        stage.show();
    }

    @Override
    public void stop() {
        service.close();
    }

    private void connect() {
        try {
            Session s = service.connect(
                new Settings(DemoAdapter.ID, "demo.db", Map.of(), Map.of()));
            session = s.id();
            refreshSchema();
            statusLabel.setText(s.info().product() + " " + s.info().version() + " connected");
        } catch (CoreException e) {
            statusLabel.setText(e.code() + ": " + e.getMessage());
        }
    }

    private void refreshSchema() {
        Snapshot snapshot = service.readSchema(session);
        TreeItem<String> root = new TreeItem<>("demo.db");
        TreeItem<String> tables = new TreeItem<>("Tables");
        TreeItem<String> views = new TreeItem<>("Views");
        TreeItem<String> indexes = new TreeItem<>("Indexes");
        snapshot.objects().forEach(o -> {
            if (o instanceof Table t) {
                TreeItem<String> node = new TreeItem<>(t.name().name());
                t.columns().forEach(
                    c -> node.getChildren().add(new TreeItem<>(c.name() + " : " + c.type().name())));
                node.setExpanded(true);
                tables.getChildren().add(node);
            } else if (o instanceof View v) {
                views.getChildren().add(new TreeItem<>(v.name().name()));
            } else if (o instanceof Index i) {
                indexes.getChildren().add(new TreeItem<>(i.name().name()));
            }
        });
        root.getChildren().addAll(tables, views, indexes);
        root.setExpanded(true);
        tables.setExpanded(true);
        schemaTree.setRoot(root);
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
        SplitPane split = new SplitPane(schemaTree, sqlPane());
        split.setDividerPositions(0.28);
        return split;
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
            statusLabel.setText("Selected: " + file.getAbsolutePath() + " (demo adapter in use)");
    }

    private void execute() {
        if (session == null) {
            statusLabel.setText("Not connected");
            return;
        }
        try {
            Sql.Request request = new Sql.Request(service.newOperationId(), sqlArea.getText(),
                List.of(), new Sql.Options(ExecutionMode.SINGLE_STATEMENT,
                    TransactionPolicy.ATOMIC, BATCH_SIZE, Duration.ofSeconds(30)));
            Sql.Execution out = service.execute(session, request);
            showColumns(out.columns());

            ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
            addBatch(out.firstBatch(), rows);
            var resultId = out.result();
            boolean complete = out.firstBatch().complete();
            while (resultId.isPresent() && !complete) {
                Sql.Batch batch = service.fetchRows(session, resultId.get(), BATCH_SIZE);
                addBatch(batch, rows);
                complete = batch.complete();
            }
            if (resultId.isPresent())
                service.closeResult(session, resultId.get());
            resultTable.setItems(rows);

            long ms = out.statistics().elapsed().toMillis();
            statusLabel.setText(resultId.isPresent()
                ? rows.size() + " rows, " + ms + " ms"
                : "OK, affected: " + out.statistics().affectedRows().orElse(-1) + ", " + ms + " ms");
        } catch (CoreException e) {
            statusLabel.setText(e.code() + ": " + e.getMessage());
        }
    }

    private void showColumns(List<Sql.ResultColumn> columns) {
        resultTable.getColumns().setAll(columns.stream().map(column -> {
            int index = columns.indexOf(column);
            TableColumn<ObservableList<String>, String> shown =
                new TableColumn<>(column.label());
            shown.setCellValueFactory(
                row -> new ReadOnlyStringWrapper(row.getValue().get(index)));
            return shown;
        }).toList());
    }

    private static void addBatch(Sql.Batch batch, ObservableList<ObservableList<String>> rows) {
        batch.rows().forEach(
            row -> rows.add(FXCollections.observableArrayList(
                row.values().stream().map(MainWindow::format).toList())));
    }

    private static String format(Sql.Value value) {
        if (value instanceof Sql.NullValue)
            return "NULL";
        if (value instanceof Sql.TextValue v)
            return v.value();
        if (value instanceof Sql.NumberValue v)
            return v.value().toPlainString();
        if (value instanceof Sql.BooleanValue v)
            return Boolean.toString(v.value());
        if (value instanceof Sql.BinaryValue v)
            return "0x" + HexFormat.of().formatHex(v.value());
        if (value instanceof Sql.DateValue v)
            return v.value().toString();
        if (value instanceof Sql.TimeValue v)
            return v.value().toString();
        if (value instanceof Sql.TimestampValue v)
            return v.value().toString();
        if (value instanceof Sql.OffsetTimestampValue v)
            return v.value().toString();
        return "?";
    }

    private static void writeText(Target target, String text, TextOptions options) {
        try {
            Files.writeString(Path.of(target.value()), text, options.charset());
        } catch (IOException e) {
            throw new CoreException(Code.IO, "Cannot write " + target.value());
        }
    }
}
