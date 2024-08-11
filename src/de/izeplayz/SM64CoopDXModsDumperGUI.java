package de.izeplayz;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public class SM64CoopDXModsDumperGUI extends JFrame {

    private static final String BASE_URL = "https://mods.sm64coopdx.com/mods/";
    private static final String PAGE_URL = BASE_URL + "?page=";
    private static final Pattern LINK_PATTERN = Pattern.compile("href=\"(/mods/[^/]+/)\"");
    private static final Pattern DOWNLOAD_LINK_PATTERN = Pattern.compile("href=\"(/mods/[^/]+/download\\?file=[^\"]+)\"");
    private static final int MAX_PAGES_WITHOUT_NEW_URLS = 3;
    private static final int MAX_THREADS = 10;
    private static final List<String> VALID_EXTENSIONS = Arrays.asList(".lua", ".rar", ".7z", ".zip");
    private Path downloadPath;

    private JTextArea console;
    private JButton startButton;
    private JButton selectFolderButton;
    private JFileChooser folderChooser;

    public SM64CoopDXModsDumperGUI() {
        setTitle("SM64CoopDX Mods Dumper");
        setSize(1280, 720);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setupUI();
    }

    private void setupUI() {
        JPanel panel = new JPanel(new BorderLayout());

        console = new JTextArea();
        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(console);
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        selectFolderButton = new JButton("Select Output Folder");
        startButton = new JButton("Start Download");
        startButton.setEnabled(false);
        controlPanel.add(selectFolderButton);
        controlPanel.add(startButton);
        panel.add(controlPanel, BorderLayout.SOUTH);

        add(panel);

        folderChooser = new JFileChooser();
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        selectFolderButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int result = folderChooser.showOpenDialog(SM64CoopDXModsDumperGUI.this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    downloadPath = Paths.get(folderChooser.getSelectedFile().getAbsolutePath(), "mods-dump-" + System.currentTimeMillis());
                    startButton.setEnabled(true);
                }
            }
        });

        startButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startButton.setEnabled(false);
                selectFolderButton.setEnabled(false);
                new Thread(() -> {
                    dumpMods();
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(SM64CoopDXModsDumperGUI.this, "Download completed!", "Done", JOptionPane.INFORMATION_MESSAGE);
                        try {
                            Desktop.getDesktop().open(downloadPath.toFile());
                        } catch (IOException ioException) {
                            ioException.printStackTrace();
                        }
                        System.exit(0);
                    });
                }).start();
            }
        });
    }

    private void dumpMods() {
        Set<String> modUrls = new HashSet<>();
        int currentPage = 0;
        int pagesWithoutNewUrls = 0;

        while (pagesWithoutNewUrls < MAX_PAGES_WITHOUT_NEW_URLS) {
            String url = currentPage == 0 ? BASE_URL : PAGE_URL + currentPage;
            logToConsole("Scanning URL: " + url);

            Set<String> newUrls = scanPage(url);
            if (newUrls.isEmpty() || modUrls.containsAll(newUrls)) {
                pagesWithoutNewUrls++;
                logToConsole("No new URLs on page " + currentPage + ". Pages without new URLs: " + pagesWithoutNewUrls);
            } else {
                pagesWithoutNewUrls = 0;
                modUrls.addAll(newUrls);
                logToConsole("Found " + newUrls.size() + " new URLs on page " + currentPage);
            }

            logToConsole("Total URLs found: " + modUrls.size());
            currentPage++;
        }

        saveUrlsToFile(modUrls);
        downloadMods(modUrls);
    }

    private Set<String> scanPage(String urlString) {
        Set<String> urls = new HashSet<>();
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = LINK_PATTERN.matcher(line);
                    while (matcher.find()) {
                        String relativeUrl = matcher.group(1);
                        String fullUrl = "https://mods.sm64coopdx.com" + relativeUrl;
                        urls.add(fullUrl);
                    }
                }
                reader.close();
            } else {
                logToConsole("Failed to connect to URL: " + urlString);
            }

            connection.disconnect();
        } catch (IOException e) {
            logToConsole("Error scanning page: " + e.getMessage());
        }
        return urls;
    }

    private void saveUrlsToFile(Set<String> urls) {
        try {
            Files.createDirectories(downloadPath);

            String fileName = "mod-urls.txt";
            Path filePath = downloadPath.resolve(fileName);
            Files.write(filePath, urls.stream().sorted().collect(Collectors.toList()));
            logToConsole("URLs saved to file: " + filePath);
        } catch (IOException e) {
            logToConsole("Error saving URLs to file: " + e.getMessage());
        }
    }

    private void downloadMods(Set<String> urls) {
        Path modsFolderPath = downloadPath.resolve("mods");

        try {
            Files.createDirectories(modsFolderPath);
        } catch (IOException e) {
            logToConsole("Error creating mods directory: " + e.getMessage());
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);
        Set<String> downloadedFiles = Collections.synchronizedSet(new HashSet<>());

        List<Callable<Void>> tasks = new ArrayList<>();
        for (String url : urls) {
            tasks.add(() -> {
                processMod(url, modsFolderPath, downloadedFiles);
                return null;
            });
        }

        try {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            logToConsole("Error during download: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }

    private void processMod(String url, Path modsFolderPath, Set<String> downloadedFiles) {
        try {
            List<String> downloadUrls = getDownloadUrls(url);

            for (String downloadUrl : downloadUrls) {
                String fileName = getFileNameFromURL(downloadUrl);
                if (fileName == null) {
                    logToConsole("Failed to get file name from URL: " + downloadUrl);
                    continue;
                }

                Path filePath = modsFolderPath.resolve(fileName);

                if (downloadedFiles.contains(fileName)) {
                    logToConsole("Duplicate file: " + fileName + " from URL: " + downloadUrl + ". Skipping.");
                    return;
                }

                if (isValidExtension(fileName)) {
                    logToConsole("Downloading: " + fileName + " from URL: " + downloadUrl);
                    try (InputStream in = new URL(downloadUrl).openStream()) {
                        Files.copy(in, filePath, StandardCopyOption.REPLACE_EXISTING);
                        downloadedFiles.add(fileName);
                        logToConsole("Downloaded: " + fileName);
                    } catch (IOException e) {
                        logToConsole("Failed to download: " + fileName + " from URL: " + downloadUrl + " (" + e.getMessage() + ")");
                    }
                } else {
                    logToConsole("File " + fileName + " does not have a valid extension. Checking sub versions.");
                    List<String> subversionUrls = getDownloadUrls(url);
                    for (String subversionUrl : subversionUrls) {
                        String subversionFileName = getFileNameFromURL(subversionUrl);
                        if (subversionFileName == null) {
                            logToConsole("Failed to get file name from subversion URL: " + subversionUrl);
                            continue;
                        }

                        Path subversionFilePath = modsFolderPath.resolve(subversionFileName);

                        if (downloadedFiles.contains(subversionFileName)) {
                            logToConsole("Duplicate subversion file: " + subversionFileName + " from URL: " + subversionUrl + ". Skipping.");
                            continue;
                        }

                        logToConsole("Downloading subversion: " + subversionFileName + " from URL: " + subversionUrl);
                        try (InputStream in = new URL(subversionUrl).openStream()) {
                            Files.copy(in, subversionFilePath, StandardCopyOption.REPLACE_EXISTING);
                            downloadedFiles.add(subversionFileName);
                            logToConsole("Downloaded subversion: " + subversionFileName);
                        } catch (IOException e) {
                            logToConsole("Failed to download subversion: " + subversionFileName + " from URL: " + subversionUrl + " (" + e.getMessage() + ")");
                        }
                    }
                }
            }
        } catch (IOException e) {
            logToConsole("Error processing mod at URL: " + url + " (" + e.getMessage() + ")");
        }
    }

    private boolean isValidExtension(String fileName) {
        String lowerCaseFileName = fileName.toLowerCase();
        return VALID_EXTENSIONS.stream().anyMatch(lowerCaseFileName::endsWith);
    }

    private List<String> getDownloadUrls(String urlString) throws IOException {
        List<String> downloadUrls = new ArrayList<>();
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.connect();

        if (connection.getResponseCode() == 200) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            boolean hasMultipleVersions = false;
            while ((line = reader.readLine()) != null) {
                if (line.contains("<h1 class=\"p-title-value\">Choose file…</h1>")) {
                    hasMultipleVersions = true;
                    break;
                }
            }

            if (hasMultipleVersions) {
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = DOWNLOAD_LINK_PATTERN.matcher(line);
                    while (matcher.find()) {
                        String relativeUrl = matcher.group(1);
                        String fullUrl = "https://mods.sm64coopdx.com" + relativeUrl;
                        downloadUrls.add(fullUrl);
                    }
                }
            } else {
                downloadUrls.add(urlString + "download");
            }

            reader.close();
        } else {
            logToConsole("Failed to connect to URL: " + urlString);
        }

        connection.disconnect();
        return downloadUrls;
    }

    private String getFileNameFromURL(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() == 200) {
                String contentDisposition = connection.getHeaderField("Content-Disposition");
                if (contentDisposition != null && contentDisposition.contains("filename=")) {
                    return contentDisposition.split("filename=")[1].replace("\"", "").trim();
                } else {
                    return urlString.substring(urlString.lastIndexOf('/') + 1);
                }
            } else {
                logToConsole("Failed to connect to URL: " + urlString);
            }

            connection.disconnect();
        } catch (IOException e) {
            logToConsole("Error getting file name from URL: " + e.getMessage());
        }
        return null;
    }

    private void logToConsole(String message) {
        SwingUtilities.invokeLater(() -> {
            console.append(message + "\n");
            console.setCaretPosition(console.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SM64CoopDXModsDumperGUI dumper = new SM64CoopDXModsDumperGUI();
            dumper.setVisible(true);
        });
    }
}
