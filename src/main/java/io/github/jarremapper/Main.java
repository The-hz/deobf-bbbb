package io.github.jarremapper;

import io.github.jarremapper.ui.AppController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * JavaFX entry point for the JarRemapper desktop tool.
 *
 * <p>Loads the main view from {@code /io/github/jarremapper/ui/AppView.fxml},
 * wires up {@link AppController}, and shows the primary stage.</p>
 */
public class Main extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                Objects.requireNonNull(getClass().getResource("/io/github/jarremapper/ui/AppView.fxml"),
                        "AppView.fxml missing on classpath"));
        Parent root = loader.load();
        AppController controller = loader.getController();
        controller.setStage(stage);

        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/io/github/jarremapper/ui/styles.css"),
                        "styles.css missing on classpath").toExternalForm());

        stage.setTitle("JarRemapper — JAR mapping transfer tool");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        // Optional app icon — silently ignore if not packaged.
        try (InputStream is = getClass().getResourceAsStream("/io/github/jarremapper/ui/icon.png")) {
            if (is != null) stage.getIcons().add(new Image(is));
        } catch (IOException ignored) {}

        stage.show();
        LOG.info("JarRemapper GUI ready.");
    }

    public static void main(String[] args) {
        // Force L&F on Linux for headless-friendly rendering when no DISPLAY is set.
        if (System.getenv("DISPLAY") == null && System.getProperty("os.name").toLowerCase().contains("linux")) {
            LOG.warn("DISPLAY is not set; JavaFX may fail to initialize a window.");
        }
        launch(args);
    }
}
