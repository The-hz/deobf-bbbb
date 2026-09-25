package io.github.jarremapper.ui;

import io.github.jarremapper.config.AppConfig;
import io.github.jarremapper.decompiler.DecompilerRegistry;
import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.MatchResult;
import io.github.jarremapper.pipeline.PipelineCallback;
import io.github.jarremapper.pipeline.RemapPipeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JavaFX controller for the main view. Implements {@link PipelineCallback} so
 * the {@link RemapPipeline} can call back into the GUI thread directly.
 *
 * <p>Pattern:
 * <ul>
 *   <li>{@link #onRun()} spawns a background {@link Task} that constructs a
 *       {@link RemapPipeline} with {@code this} as the callback.</li>
 *   <li>Updates from the pipeline thread are forwarded to the FX thread via
 *       {@link Platform#runLater}.</li>
 *   <li>The "ask for name" review dialog blocks the pipeline thread using a
 *       {@link CountDownLatch} until the user picks a name or clicks Skip.</li>
 * </ul>
 */
public class AppController implements PipelineCallback {

    private static final Logger LOG = LoggerFactory.getLogger(AppController.class);

    /* ---------- FXML-injected controls ---------- */

    @FXML private Slider thresholdSlider;
    @FXML private Label  thresholdLabel;
    @FXML private ComboBox<String> decompilerCombo;

    @FXML private Button runButton;
    @FXML private Button cancelButton;
    @FXML private Button openOutputButton;

    @FXML private VBox   targetJarZone;
    @FXML private VBox   mappingFileZone;
    @FXML private VBox   unmappedJarZone;

    @FXML private Label  targetJarLabel;
    @FXML private Label  mappingFileLabel;
    @FXML private Label  unmappedJarLabel;

    @FXML private ProgressBar progressBar;
    @FXML private Label  progressLabel;

    @FXML private TableView<MatchResult> resultsTable;
    @FXML private TableColumn<MatchResult, String>  colUnmapped;
    @FXML private TableColumn<MatchResult, String>  colTarget;
    @FXML private TableColumn<MatchResult, String>  colScore;
    @FXML private TableColumn<MatchResult, String>  colMethods;
    @FXML private TableColumn<MatchResult, String>  colFields;
    @FXML private TableColumn<MatchResult, String>  colStatus;

    @FXML private TextArea logArea;

    /* ---------- runtime state ---------- */

    private Stage stage;
    private Path targetJarPath;
    private Path mappingFilePath;
    private Path unmappedJarPath;

    private final ObservableList<MatchResult> results = FXCollections.observableArrayList();
    private final AtomicReference<Task<?>> runningTask = new AtomicReference<>();
    private volatile boolean cancelled = false;
    /** P1-9: when set by the "Skip All Remaining" button, all subsequent
     *  askForName() calls auto-Skip (return null) without showing a dialog. */
    private volatile boolean skipAllRemaining = false;
    private volatile Dialog<String> currentDialog;

    /* ---------- init ---------- */

    @FXML
    private void initialize() {
        // Hook the threshold slider to the label.
        thresholdSlider.valueProperty().addListener((obs, oldV, newV) -> {
            int pct = newV.intValue();
            thresholdLabel.setText(pct + "%");
        });

        // Populate decompiler combo with whatever is actually available.
        Map<String, Path> available = DecompilerRegistry.available();
        if (available.isEmpty()) {
            // Fallback to all three labels — they may resolve later.
            decompilerCombo.getItems().addAll("CFR", "Fernflower", "Vineflower");
        } else {
            // Show each entry, marking unavailable ones as "(missing)".
            if (available.containsKey("CFR"))         decompilerCombo.getItems().add("CFR");
            else                                       decompilerCombo.getItems().add("CFR (unavailable)");
            if (available.containsKey("Fernflower")) decompilerCombo.getItems().add("Fernflower");
            else                                       decompilerCombo.getItems().add("Fernflower (unavailable)");
            if (available.containsKey("Vineflower")) decompilerCombo.getItems().add("Vineflower");
            else                                       decompilerCombo.getItems().add("Vineflower (unavailable)");
        }
        decompilerCombo.getSelectionModel().select("CFR");

        // Wire the results table.
        resultsTable.setItems(results);

        colUnmapped.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().unmappedObfName()));
        colTarget   .setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().targetOrigName()));
        colScore    .setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().percent()));
        colMethods  .setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().matchedMethods() + " / " + p.getValue().totalMethods()));
        colFields   .setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().matchedFields() + " / " + p.getValue().totalFields()));
        colStatus   .setCellValueFactory(p -> {
            MatchResult r = p.getValue();
            String s = r.score() <= 0.0 ? "Unmatched" :
                       r.targetObfName().equals(r.targetOrigName()) ? "Identity" :
                       "Matched";
            return new ReadOnlyObjectWrapper<>(s);
        });
        // Colored status badges via CSS class.
        colStatus.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-matched", "status-unmatched", "status-identity");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    switch (item) {
                        case "Matched"   -> getStyleClass().add("status-matched");
                        case "Unmatched" -> getStyleClass().add("status-unmatched");
                        case "Identity"  -> getStyleClass().add("status-identity");
                    }
                }
            }
        });

        // Drag-and-drop setup for each drop zone.
        installDropZone(targetJarZone, ".jar", path -> {
            targetJarPath = path;
            targetJarLabel.setText(path.toString());
        });
        installDropZone(mappingFileZone, null, path -> {
            mappingFilePath = path;
            mappingFileLabel.setText(path + "  [" + guessFormat(path) + "]");
        });
        installDropZone(unmappedJarZone, ".jar", path -> {
            unmappedJarPath = path;
            unmappedJarLabel.setText(path.toString());
        });
    }

    /* ---------- drop-zone helpers ---------- */

    private void installDropZone(VBox zone, String requiredExt,
                                 java.util.function.Consumer<Path> onDrop) {
        zone.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
                zone.getStyleClass().add("drag-over");
            }
            e.consume();
        });
        zone.setOnDragExited(e -> zone.getStyleClass().remove("drag-over"));
        zone.setOnDragDropped((DragEvent e) -> {
            var db = e.getDragboard();
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                File f = db.getFiles().get(0);
                Path p = f.toPath().toAbsolutePath();
                if (requiredExt != null && !p.getFileName().toString().toLowerCase().endsWith(requiredExt)) {
                    log("Rejected (wrong extension): " + p);
                } else {
                    onDrop.accept(p);
                    log("Accepted: " + p);
                }
                e.setDropCompleted(true);
            } else {
                e.setDropCompleted(false);
            }
            e.consume();
        });
    }

    private String guessFormat(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".tiny"))  return "tiny";
        if (name.endsWith(".srg"))   return "srg";
        if (name.endsWith(".tsrg"))  return "tsrg";
        if (name.endsWith(".proguard") || name.endsWith(".txt")) return "mojang?";
        return "?";
    }

    /* ---------- click-to-browse ---------- */

    @FXML private void onClickTargetJar()    { browseFor(".jar", p -> {
        targetJarPath = p; targetJarLabel.setText(p.toString()); }); }
    @FXML private void onClickMappingFile()  { browseForAny(p -> {
        mappingFilePath = p; mappingFileLabel.setText(p + "  [" + guessFormat(p) + "]"); }); }
    @FXML private void onClickUnmappedJar()  { browseFor(".jar", p -> {
        unmappedJarPath = p; unmappedJarLabel.setText(p.toString()); }); }

    private void browseFor(String ext, java.util.function.Consumer<Path> onPicked) {
        FileChooser chooser = newFileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                ext.toUpperCase() + " file (*" + ext + ")", "*" + ext));
        File f = chooser.showOpenDialog(stage);
        if (f != null) onPicked.accept(f.toPath().toAbsolutePath());
    }

    private void browseForAny(java.util.function.Consumer<Path> onPicked) {
        FileChooser chooser = newFileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Mapping files", "*.tiny", "*.srg", "*.tsrg", "*.proguard", "*.txt"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All files", "*.*"));
        File f = chooser.showOpenDialog(stage);
        if (f != null) onPicked.accept(f.toPath().toAbsolutePath());
    }

    private FileChooser newFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setInitialDirectory(new File(System.getProperty("user.dir")));
        return chooser;
    }

    /* ---------- toolbar handlers ---------- */

    @FXML
    private void onRun() {
        if (targetJarPath == null || mappingFilePath == null || unmappedJarPath == null) {
            log("Cannot run: please set all three drop zones first.");
            return;
        }
        String decName = decompilerCombo.getValue();
        if (decName == null || decName.contains("(unavailable)")) {
            log("Decompiler not available, falling back to CFR.");
            decName = "CFR";
        }
        double threshold = thresholdSlider.getValue() / 100.0;
        Path outputDir = Path.of(System.getProperty("user.dir"), "output").toAbsolutePath();

        AppConfig config = AppConfig.builder()
                .targetJar(targetJarPath)
                .mappingFile(mappingFilePath)
                .unmappedJar(unmappedJarPath)
                .outputDir(outputDir)
                .similarityThreshold(threshold)
                .decompilerName(decName)
                .build();

        results.clear();
        logArea.clear();
        cancelled = false;
        skipAllRemaining = false;   // reset for the new run
        runButton.setDisable(true);
        cancelButton.setDisable(false);

        Task<io.github.jarremapper.model.MappingModel> task = new Task<>() {
            @Override
            protected io.github.jarremapper.model.MappingModel call() throws Exception {
                return new RemapPipeline(config, AppController.this).execute();
            }

            @Override
            protected void succeeded() {
                onPipelineFinished(false, null);
            }
            @Override
            protected void failed() {
                onPipelineFinished(true, getException());
            }
            @Override
            protected void cancelled() {
                onPipelineFinished(true, new InterruptedException("Cancelled"));
            }
        };
        runningTask.set(task);
        Thread t = new Thread(task, "jarremapper-pipeline");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onCancel() {
        cancelled = true;
        Dialog<String> d = currentDialog;
        if (d != null && d.isShowing()) {
            Platform.runLater(d::close);
        }
        Task<?> t = runningTask.get();
        if (t != null) {
            t.cancel(true);
        }
    }

    @FXML
    private void onOpenOutput() {
        Path outDir = Path.of(System.getProperty("user.dir"), "output").toAbsolutePath();
        try {
            Files.createDirectories(outDir);
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd;
            if (os.contains("win")) {
                cmd = new String[]{"explorer.exe", outDir.toString()};
            } else if (os.contains("mac")) {
                cmd = new String[]{"open", outDir.toString()};
            } else {
                cmd = new String[]{"xdg-open", outDir.toString()};
            }
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            log("Could not open output directory: " + e.getMessage());
        }
    }

    /* ---------- pipeline callback (called on the worker thread) ---------- */

    @Override
    public void onProgress(double fraction, String stage) {
        Platform.runLater(() -> {
            progressBar.setProgress(fraction);
            progressLabel.setText(String.format("%d%% — %s", (int) (fraction * 100), stage));
        });
    }

    @Override
    public void onLog(String message) {
        Platform.runLater(() -> log(message));
    }

    @Override
    public void onMatchResult(MatchResult result) {
        Platform.runLater(() -> results.add(result));
    }

    @Override
    public boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    @Override
    public String askForName(ClassInfo unmappedClass, String decompiledSource) {
        if (isCancelled()) return null;
        if (skipAllRemaining) return null;     // P1-9: batch "Skip All"

        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        Platform.runLater(() -> {
            try {
                result[0] = showNameDialog(unmappedClass, decompiledSource);
            } finally {
                latch.countDown();
            }
        });
        try {
            // P1-7: 5-minute timeout — if the user walks away without
            // picking a name, the pipeline auto-Skips instead of hanging
            // the worker thread (and the JVM shutdown) forever.
            if (!latch.await(5, java.util.concurrent.TimeUnit.MINUTES)) {
                log("askForName timed out after 5 min — auto-skipping " +
                        unmappedClass.internalName());
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }

    /** Show the review dialog. Must be called on the FX thread. */
    private String showNameDialog(ClassInfo info, String source) {
        Dialog<String> dialog = new Dialog<>();
        currentDialog = dialog;
        dialog.setTitle("Review unmapped class");
        dialog.setHeaderText("Class: " + info.internalName() +
                "  |  " + info.methodCount() + " methods, " + info.fieldCount() + " fields");

        VBox vbox = new VBox(8);
        vbox.setPadding(new Insets(12));
        Label sourceLabel = new Label("Decompiled source:");
        TextArea sourceArea = new TextArea(source == null || source.isBlank()
                ? "(decompilation produced no output)" : source);
        sourceArea.setEditable(false);
        sourceArea.setWrapText(true);
        sourceArea.setPrefSize(700, 460);
        sourceArea.setStyle("-fx-font-family: 'DejaVu Sans Mono', 'Menlo', monospace; -fx-font-size: 12px;");

        Label prompt = new Label("Choose a new fully-qualified class name " +
                "(e.g. com/example/Foo), or click Skip to create an identity mapping.");
        TextField nameField = new TextField();
        nameField.setPromptText("com/example/SomeName");
        nameField.setStyle("-fx-font-family: 'DejaVu Sans Mono', 'Menlo', monospace;");

        vbox.getChildren().addAll(sourceLabel, sourceArea, prompt, nameField);
        dialog.getDialogPane().setContent(vbox);

        ButtonType saveBtn       = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType skipBtn       = new ButtonType("Skip", ButtonBar.ButtonData.CANCEL_CLOSE);
        // P1-9: batch "Skip All Remaining" — closes the dialog and sets a
        // flag that makes subsequent askForName() calls auto-Skip.
        ButtonType skipAllBtn    = new ButtonType("Skip All Remaining", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, skipBtn, skipAllBtn);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtn) {
                String t = nameField.getText() == null ? "" : nameField.getText().trim();
                return t.isBlank() ? null : t;
            }
            if (btn == skipAllBtn) {
                skipAllRemaining = true;
                log("User picked 'Skip All Remaining' — all subsequent " +
                        "unmatched classes will be auto-identity-mapped.");
            }
            return null;
        });

        // Focus the text field when the dialog opens.
        Platform.runLater(nameField::requestFocus);

        String result = dialog.showAndWait().orElse(null);
        currentDialog = null;
        return result;
    }

    /* ---------- utility ---------- */

    private void log(String message) {
        String ts = java.time.LocalTime.now().toString();
        // Truncate nanoseconds for a cleaner timestamp.
        int dot = ts.indexOf('.');
        if (dot >= 0) ts = ts.substring(0, dot);
        logArea.appendText("[" + ts + "] " + message + System.lineSeparator());
    }

    private void onPipelineFinished(boolean failed, Throwable ex) {
        runButton.setDisable(false);
        cancelButton.setDisable(true);
        progressBar.setProgress(failed ? 0 : 1.0);
        if (failed) {
            String msg = ex == null ? "(unknown)" : ex.getMessage();
            progressLabel.setText("Failed: " + msg);
            LOG.error("Pipeline failed", ex);
            log("ERROR: " + msg);
        } else {
            progressLabel.setText("Done");
        }
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }
}
