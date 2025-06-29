package pl.umcs.oop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.*;

public class ServerApp extends Application {

    private static int kernelSize = 3;
    private static final Object lock = new Object();
    private static final int PORT = 5000;
    private static volatile boolean serverRunning = false;
    private static Label statusLabel;

    public static void main(String[] args) {
        // Uruchom serwer w osobnym wątku
        Thread serverThread = new Thread(ServerApp::startServer);
        serverThread.setDaemon(true);
        serverThread.start();

        launch(args);
    }

    @Override
    public void start(Stage stage) {
        Slider slider = new Slider(1, 15, 3);
        slider.setBlockIncrement(2);
        slider.setMajorTickUnit(2);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setShowTickLabels(true);
        slider.setShowTickMarks(true);

        Label label = new Label("Promień: 3");
        statusLabel = new Label("Status serwera: Uruchamianie...");

        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int value = newVal.intValue();
            if (value % 2 == 0) value++;
            label.setText("Promień: " + value);
            synchronized (lock) {
                kernelSize = value;
            }
        });

        VBox root = new VBox(10, slider, label, statusLabel);
        root.setPadding(new Insets(20));

        stage.setScene(new Scene(root, 400, 150));
        stage.setTitle("Ustawienia filtra - Server");
        stage.setOnCloseRequest(e -> {
            Platform.exit();
            System.exit(0);
        });
        stage.show();
    }

    private static void updateStatus(String status) {
        if (statusLabel != null) {
            Platform.runLater(() -> statusLabel.setText("Status serwera: " + status));
        }
    }

    private static void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            try {
                createDB();
                serverRunning = true;
                updateStatus("Uruchomiony na porcie " + PORT);
                System.out.println("Serwer uruchomiony na porcie " + PORT);
            } catch (Exception e) {
                System.err.println("Błąd inicjalizacji bazy danych: " + e.getMessage());
                updateStatus("Błąd inicjalizacji");
                return;
            }

            while (true) {
                System.out.println("Czekam na klienta...");
                updateStatus("Oczekiwanie na klienta (port " + PORT + ")");

                try (Socket socket = serverSocket.accept()) {
                    System.out.println("Połączono z klientem: " + socket.getInetAddress());
                    updateStatus("Obsługa klienta: " + socket.getInetAddress());

                    File receivedFile = receiveImage(socket);
                    if (receivedFile == null) {
                        System.err.println("Błąd podczas odbierania pliku");
                        continue;
                    }

                    int currentKernel;
                    synchronized (lock) {
                        currentKernel = kernelSize;
                    }

                    System.out.println("Przetwarzam obraz z promieniem: " + currentKernel);
                    updateStatus("Przetwarzanie obrazu...");

                    long startTime = System.currentTimeMillis();
                    File processed = applyBoxBlur(receivedFile, currentKernel);
                    long delay = System.currentTimeMillis() - startTime;

                    System.out.println("Przetwarzanie zakończone w " + delay + "ms");

                    saveToDB(processed.getPath(), currentKernel, delay);
                    sendImage(socket, processed);

                    System.out.println("Obraz wysłany do klienta");
                    updateStatus("Gotowy (port " + PORT + ")");

                } catch (IOException e) {
                    System.err.println("Błąd podczas obsługi klienta: " + e.getMessage());
                    updateStatus("Błąd obsługi klienta");
                }
            }
        } catch (IOException e) {
            System.err.println("Błąd serwera: " + e.getMessage());
            serverRunning = false;
            updateStatus("Błąd serwera");
        }
    }

    private static File receiveImage(Socket socket) {
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            long size = input.readLong();

            if (size <= 0 || size > 100 * 1024 * 1024) { // Max 100MB
                System.err.println("Nieprawidłowy rozmiar pliku: " + size);
                return null;
            }

            File dir = new File("images");
            if (!dir.exists()) dir.mkdirs();

            String filename = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".png";
            File outFile = new File(dir, filename);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[8192];
                int read;
                long received = 0;

                System.out.println("Odbieram plik o rozmiarze: " + size + " bajtów");

                while (received < size && (read = input.read(buffer)) != -1) {
                    int toWrite = (int) Math.min(read, size - received);
                    fos.write(buffer, 0, toWrite);
                    received += toWrite;
                }

                System.out.println("Odebrano plik: " + outFile.getName() + " (" + received + " bajtów)");
            }

            return outFile;
        } catch (IOException e) {
            System.err.println("Błąd podczas odbierania obrazu: " + e.getMessage());
            return null;
        }
    }

    private static void sendImage(Socket socket, File file) throws IOException {
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            output.writeLong(file.length());

            System.out.println("Wysyłam plik: " + file.getName() + " (" + file.length() + " bajtów)");

            int count;
            while ((count = fis.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        }
    }

    private static File applyBoxBlur(File inputFile, int kernelSize) throws IOException {
        BufferedImage image = ImageIO.read(inputFile);
        if (image == null) {
            throw new IOException("Nie można odczytać obrazu: " + inputFile.getName());
        }

        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());

        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(cores);
        CountDownLatch latch = new CountDownLatch(cores);

        int height = image.getHeight();
        int rowsPerThread = height / cores;

        for (int i = 0; i < cores; i++) {
            int startY = i * rowsPerThread;
            int endY = (i == cores - 1) ? height : startY + rowsPerThread;

            executor.submit(() -> {
                try {
                    for (int y = startY; y < endY; y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            result.setRGB(x, y, averageColor(image, x, y, kernelSize));
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Przetwarzanie przerwane", e);
        } finally {
            executor.shutdown();
        }

        File out = new File("images/blurred_" + inputFile.getName());
        ImageIO.write(result, "png", out);
        return out;
    }

    private static int averageColor(BufferedImage img, int x, int y, int size) {
        int half = size / 2;
        long r = 0, g = 0, b = 0;
        int count = 0;

        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                int nx = Math.min(Math.max(x + dx, 0), img.getWidth() - 1);
                int ny = Math.min(Math.max(y + dy, 0), img.getHeight() - 1);
                int rgb = img.getRGB(nx, ny);

                r += (rgb >> 16) & 0xFF;
                g += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
                count++;
            }
        }

        int rr = (int) (r / count);
        int gg = (int) (g / count);
        int bb = (int) (b / count);

        return (0xFF << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private static void createDB() {
        // Upewnij się, że katalog images istnieje
        File dir = new File("images");
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                System.out.println("Utworzono katalog: " + dir.getAbsolutePath());
            } else {
                System.err.println("Nie można utworzyć katalogu: " + dir.getAbsolutePath());
            }
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:images/index.db")) {
            String sql = "CREATE TABLE IF NOT EXISTS transformations (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT, size INTEGER, delay INTEGER)";
            conn.createStatement().execute(sql);
            System.out.println("Baza danych zainicjalizowana: " + new File("images/index.db").getAbsolutePath());
        } catch (SQLException e) {
            System.err.println("Błąd bazy danych: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static void saveToDB(String path, int size, long delay) {
        String sql = "INSERT INTO transformations (path, size, delay) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:images/index.db");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, path);
            stmt.setInt(2, size);
            stmt.setLong(3, delay);
            stmt.executeUpdate();
            System.out.println("Zapisano do bazy: " + path + ", promień: " + size + ", czas: " + delay + "ms");
        } catch (SQLException e) {
            System.err.println("Błąd zapisu do bazy: " + e.getMessage());
        }
    }
}