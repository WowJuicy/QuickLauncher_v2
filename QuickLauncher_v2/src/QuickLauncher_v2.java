import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;

public class QuickLauncher_v2 {
    private static final ExecutorService executor = new ForkJoinPool(
        Math.max(2, Runtime.getRuntime().availableProcessors()),
        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        null, false
    );
    private static volatile boolean searchCancelled = false; // Cancellation flag
    private static JFrame frame;
    private static JTextField gameNameField;
    private static JComboBox<String> exeComboBox;
    private static JLabel statusLabel;
    private static JButton searchButton;
    private static JButton confirmButton;
    private static JButton cancelButton;
    private static java.util.List<String> searchResults;
    private static String currentGameName;
    private static int fileCount = 0;
    private static long lastUpdateTime = 0;
    private static final Map<String, String> KEYWORD_MAP = Collections.synchronizedMap(new HashMap<>());
    private static final Pattern WIKI_PATTERN = Pattern.compile("wiki|fandom", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("https?://(?:[\\w-]+\\.)*([\\w-]+)\\.(?:wiki|fandom)(?:\\.\\w+)?(?:/\\{\\}|/)?");
    private static final Queue<String> searchQueue = new LinkedList<>();

    public static void main(String[] args) {
        try {
            loadKeywords(QuickLauncher_v2_Config.KEYWORDS_FILE);
            if (KEYWORD_MAP.isEmpty()) {
                System.err.println("Warning: keywords.txt is empty or could not be loaded.");
                SwingUtilities.invokeLater(() -> setTruncatedStatus("Warning: keywords.txt is empty or could not be loaded."));
            } else {
                System.out.println("Loaded " + KEYWORD_MAP.size() + " keywords from keywords.txt");
            }
        } catch (Exception e) {
            System.err.println("Error loading keywords.txt: " + e.getMessage());
            SwingUtilities.invokeLater(() -> setTruncatedStatus("Error loading keywords.txt: " + e.getMessage()));
        }
        SwingUtilities.invokeLater(QuickLauncher_v2::createAndShowGUI);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate in time.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Shutdown interrupted: " + e.getMessage());
            }
        }));
    }

    public static void createAndShowGUI() {
        frame = new JFrame("Quick Launcher v2");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1000, 150));
        frame.setResizable(false);

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 5, 2, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridy = 0;
        gbc.weighty = 0;

        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        mainPanel.add(new JLabel("Game Name or Command:"), gbc);

        gameNameField = new JTextField();
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gameNameField.setPreferredSize(new Dimension(400, 25));
        mainPanel.add(gameNameField, gbc);

        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;

        JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 10, 0));
        searchButton = new JButton("Search");
        cancelButton = new JButton("Cancel");
        confirmButton = new JButton("Launch");
        confirmButton.setEnabled(false);
        cancelButton.setEnabled(false);

        buttonPanel.add(searchButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(confirmButton);
        mainPanel.add(buttonPanel, gbc);

        gbc.gridy = 2;
        gbc.anchor = GridBagConstraints.WEST;
        statusLabel = new JLabel();
        statusLabel.setPreferredSize(new Dimension(400, 25));
        statusLabel.setMaximumSize(new Dimension(400, 25));
        setTruncatedStatus("Enter game name or command to search/open.");
        mainPanel.add(statusLabel, gbc);

        gbc.gridy = 3;
        exeComboBox = new JComboBox<>();
        exeComboBox.setVisible(false);
        mainPanel.add(exeComboBox, gbc);

        frame.add(mainPanel);
        gbc.gridy = 4;
        gbc.weighty = 1.0;
        mainPanel.add(Box.createVerticalGlue(), gbc);

        searchButton.addActionListener(e -> handleSearchAction());
        gameNameField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    handleSearchAction();
                }
            }
        });
        confirmButton.addActionListener(e -> {
            if (searchResults == null || searchResults.isEmpty()) {
                setTruncatedStatus("No paths to launch.");
                processNextSearch();
                return;
            }
            String path = searchResults.size() == 1 ? searchResults.get(0) : (String) exeComboBox.getSelectedItem();
            if (path == null) {
                setTruncatedStatus("Select a path from the dropdown.");
                return;
            }
            String processName = new File(path).getName();
            if (isProcessRunning(processName)) {
                setTruncatedStatus("Game is already running: " + processName);
                confirmButton.setEnabled(false);
                cancelButton.setEnabled(false);
                exeComboBox.setVisible(false);
                searchResults = null;
                gameNameField.setText("");
                processNextSearch();
                return;
            }
            try {
                Desktop.getDesktop().open(new File(path));
                setTruncatedStatus("Launched: " + path);
                saveKeyword(currentGameName, path);
            } catch (IOException ex) {
                setTruncatedStatus("Error launching: " + path);
                ex.printStackTrace();
            }
            confirmButton.setEnabled(false);
            cancelButton.setEnabled(false);
            exeComboBox.setVisible(false);
            searchResults = null;
            gameNameField.setText("");
            processNextSearch();
        });
        cancelButton.addActionListener(e -> {
            searchCancelled = true; // Signal cancellation
            searchQueue.clear(); // Clear pending searches
            cancelButton.setEnabled(false);
            searchButton.setEnabled(true);
            setTruncatedStatus("Search cancelled.");
        });

        frame.pack();
        frame.setVisible(true);
    }

    private static void setTruncatedStatus(String text) {
        if (text != null && text.length() > 200) {
            text = text.substring(0, 197) + "...";
        }
        statusLabel.setText(text);
    }

    private static void handleSearchAction() {
        String input = gameNameField.getText();
        if (input == null || input.trim().isEmpty()) {
            setTruncatedStatus("Enter a game name or command.");
            return;
        }
        gameNameField.setText(""); // Clear the text field immediately
        input = input.trim();
        String[] subCommands = input.contains("  ") ? input.split("\\s{2,}") : new String[]{input};

        searchQueue.clear();
        java.util.List<String> immediateLaunches = new ArrayList<>();

        for (String subCommand : subCommands) {
            String[] parts = subCommand.trim().split("\\s+", 2);
            String keyword = parts[0].toLowerCase();
            String argument = parts.length > 1 ? parts[1].trim() : "";
            String target = KEYWORD_MAP.get(keyword);

            if (target != null) {
                immediateLaunches.add(subCommand);
            } else {
                searchQueue.add(subCommand);
            }
        }

        for (String subCommand : immediateLaunches) {
            String[] parts = subCommand.trim().split("\\s+", 2);
            String keyword = parts[0].toLowerCase();
            String argument = parts.length > 1 ? parts[1].trim() : "";
            String target = KEYWORD_MAP.get(keyword);
            processCommand(keyword, argument, target);
        }

        processNextSearch();
    }

    private static void processNextSearch() {
        if (searchQueue.isEmpty()) {
            searchButton.setEnabled(true);
            setTruncatedStatus("All inputs processed.");
            return;
        }

        String subCommand = searchQueue.poll();
        String[] parts = subCommand.trim().split("\\s+", 2);
        String keyword = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1].trim() : "";
        currentGameName = subCommand.replaceAll("[<>:\"/\\\\|?*]", "");
        if (currentGameName.isEmpty()) {
            setTruncatedStatus("Invalid game name: " + subCommand);
            processNextSearch();
            return;
        }
        currentGameName = currentGameName.toLowerCase();
        String normalizedGameName = currentGameName.replaceAll("\\s+", "");
        String cachedPath = KEYWORD_MAP.get(normalizedGameName);
        if (cachedPath != null && Files.exists(Paths.get(cachedPath))) {
            String processName = new File(cachedPath).getName();
            if (isProcessRunning(processName)) {
                setTruncatedStatus("Game is already running: " + processName);
                gameNameField.setText("");
                processNextSearch();
                return;
            }
            try {
                Desktop.getDesktop().open(new File(cachedPath));
                setTruncatedStatus("Launched cached path: " + cachedPath);
                gameNameField.setText("");
            } catch (IOException e) {
                setTruncatedStatus("Error launching cached path: " + cachedPath);
                e.printStackTrace();
            }
            processNextSearch();
            return;
        }
        startSearch(currentGameName, normalizedGameName);
    }

    private static void processCommand(String keyword, String argument, String targetTemplate) {
        try {
            boolean isWiki = WIKI_PATTERN.matcher(targetTemplate).find();
            String target;

            if (targetTemplate.contains("{}")) {
                String urlArgument = isWiki
                        ? capitalizeUnderscoreSeparatedWords(argument.replace(" ", "_"))
                        : argument;

                if (urlArgument.isEmpty()) {
                    try {
                        URI uri = new URI(targetTemplate.replace("{}", ""));
                        String baseUrl = uri.getScheme() + "://" + uri.getHost();
                        if (uri.getPath().contains("/search") || uri.getQuery() != null) {
                            target = baseUrl;
                        } else {
                            target = targetTemplate.replace("{}", "").replaceAll("/+$", "");
                        }
                    } catch (Exception e) {
                        target = targetTemplate.replace("{}", "").replaceAll("/+$", "");
                    }
                } else {
                    target = targetTemplate.replace("{}", encodeURIComponent(urlArgument));
                }
            } else {
                target = targetTemplate;
            }

            if (isWiki && !argument.isEmpty() && !isPageAvailable(target)) {
                String wikiName = extractWikiName(targetTemplate);
                target = "https://www.google.com/search?q=" + encodeURIComponent(wikiName + " wiki " + argument);
            }

            openTarget(target);
            setTruncatedStatus("Opened: " + target);
            saveKeyword(keyword + (argument.isEmpty() ? "" : " " + argument), target);
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> setTruncatedStatus("Error processing command: " + e.getMessage()));
        }
    }

    private static String extractWikiName(String url) {
        Matcher matcher = DOMAIN_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "wiki";
    }

    private static String capitalizeUnderscoreSeparatedWords(String input) {
        if (input.isEmpty()) return input;
        StringBuilder result = new StringBuilder();
        String[] words = input.split("_");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1));
            }
            if (i < words.length - 1) result.append("_");
        }
        return result.toString();
    }

    private static boolean isPageAvailable(String urlString) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static String encodeURIComponent(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static void openTarget(String target) {
        try {
            if (target.startsWith("https://") || target.startsWith("http://")) {
                Desktop.getDesktop().browse(new URI(target));
            } else {
                File file = new File(target);
                Desktop.getDesktop().open(file);
            }
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> setTruncatedStatus("Error opening target: " + target));
            e.printStackTrace();
        }
    }

    private static boolean isProcessRunning(String processName) {
        try {
            Process process = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + processName).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains(processName.toLowerCase())) {
                        return true;
                    }
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.err.println("Error checking process: " + processName);
            e.printStackTrace();
        }
        return false;
    }

    private static void startSearch(String originalGameName, String normalizedGameName) {
        searchButton.setEnabled(false);
        confirmButton.setEnabled(false);
        cancelButton.setEnabled(true);
        exeComboBox.setVisible(false);
        searchCancelled = false; // Reset cancellation flag
        setTruncatedStatus("Searching for " + currentGameName + " on all drives...");
        fileCount = 0;
        lastUpdateTime = 0;

        executor.submit(() -> {
            searchResults = findGameExecutables(originalGameName, normalizedGameName);
            SwingUtilities.invokeLater(() -> updateSearchResults());
        });
    }

    private static void updateSearchResults() {
        searchButton.setEnabled(true);
        cancelButton.setEnabled(false);
        if (searchCancelled) {
            setTruncatedStatus("Search cancelled.");
            searchResults = null;
            exeComboBox.setVisible(false);
            confirmButton.setEnabled(false);
            processNextSearch();
            return;
        }
        if (searchResults.isEmpty()) {
            setTruncatedStatus("No executables found for " + currentGameName);
            processNextSearch();
        } else if (searchResults.size() == 1) {
            setTruncatedStatus("Path: " + searchResults.get(0));
            confirmButton.setEnabled(true);
            gameNameField.setText("");
        } else {
            setTruncatedStatus("Multiple executables found for " + currentGameName + ". Select one to launch:");
            exeComboBox.removeAllItems();
            searchResults.forEach(exeComboBox::addItem);
            exeComboBox.setVisible(true);
            confirmButton.setEnabled(true);
            gameNameField.setText("");
        }
    }

    private static void loadKeywords(String fileName) {
        File file = new File(fileName);
        if (!file.exists()) {
            System.err.println("keywords.txt not found at: " + file.getAbsolutePath());
            SwingUtilities.invokeLater(() -> setTruncatedStatus("keywords.txt not found at: " + file.getAbsolutePath()));
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.contains("=")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String[] keys = parts[0].split(",");
                    String target = parts[1].trim();
                    for (String key : keys) {
                        String trimmedKey = key.trim().toLowerCase();
                        if (!trimmedKey.isEmpty()) {
                            KEYWORD_MAP.put(trimmedKey, target);
                        }
                    }
                }
            }
            System.out.println("Keywords loaded: " + KEYWORD_MAP.keySet());
        } catch (IOException e) {
            System.err.println("Error reading keywords.txt: " + e.getMessage());
            SwingUtilities.invokeLater(() -> setTruncatedStatus("Error reading keywords.txt: " + e.getMessage()));
        }
    }

    private static void saveKeyword(String gameName, String path) {
        if (gameName == null || gameName.trim().isEmpty() || path == null || path.trim().isEmpty()) {
            return;
        }
        // Skip saving if gameName contains a space (indicating an argument)
        if (gameName.contains(" ")) {
            return;
        }
        String absolutePath = path.startsWith("http://") || path.startsWith("https://") ? 
                            path : new File(path).getAbsolutePath();
        KEYWORD_MAP.put(gameName.toLowerCase(), absolutePath);

        File keywordsFile = new File(QuickLauncher_v2_Config.KEYWORDS_FILE);
        try (BufferedWriter writer = Files.newBufferedWriter(keywordsFile.toPath(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            // Group keywords by target
            Map<String, Set<String>> targetToKeys = new HashMap<>();
            for (Map.Entry<String, String> entry : KEYWORD_MAP.entrySet()) {
                targetToKeys.computeIfAbsent(entry.getValue(), k -> new TreeSet<>()).add(entry.getKey());
            }
            // Write each target with its comma-separated keywords
            for (Map.Entry<String, Set<String>> entry : targetToKeys.entrySet()) {
                writer.write(String.join(",", entry.getValue()) + "=" + entry.getKey());
                writer.newLine();
            }
        } catch (IOException ex) {
            SwingUtilities.invokeLater(() -> setTruncatedStatus("Error saving to keywords.txt"));
            ex.printStackTrace();
        }
    }
    private static java.util.List<String> findGameExecutables(String originalGameName, String normalizedGameName) {
        java.util.Set<String> results = Collections.synchronizedSet(new HashSet<>());

        String cachedPath = KEYWORD_MAP.get(normalizedGameName);
        if (cachedPath != null) {
            try {
                if (Files.exists(Paths.get(cachedPath))) {
                    results.add(cachedPath);
                    SwingUtilities.invokeLater(() -> setTruncatedStatus("Found in keywords: " + cachedPath));
                } else {
                    SwingUtilities.invokeLater(() -> setTruncatedStatus("Cleaning invalid keyword entry, searching..."));
                    KEYWORD_MAP.remove(normalizedGameName);
                    saveKeyword("", ""); // Update keywords.txt to remove invalid entry
                }
            } catch (InvalidPathException e) {
                KEYWORD_MAP.remove(normalizedGameName);
                saveKeyword("", ""); // Update keywords.txt to remove invalid entry
                System.err.println("Invalid path in keywords: " + cachedPath);
            }
        }

        java.util.List<File> directoriesToSearch = new ArrayList<>(Arrays.asList(File.listRoots()));
        java.util.List<Future<Integer>> futures = new ArrayList<>();
        for (File dir : directoriesToSearch) {
            if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) {
                SwingUtilities.invokeLater(() -> setTruncatedStatus("Cannot access drive: " + dir.getAbsolutePath()));
                System.err.println("Cannot access drive: " + dir.getAbsolutePath());
                continue;
            }
            futures.add(executor.submit(() -> {
                searchDrive(dir, originalGameName, normalizedGameName, results);
                return 0;
            }));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        return new ArrayList<>(results);
    }

    private static void searchDrive(File dir, String originalGameName, String normalizedGameName, java.util.Set<String> results) {
        for (String launcherDir : QuickLauncher_v2_Config.LAUNCHER_DIRS) {
            File launcherPath = new File(dir, launcherDir);
            if (launcherPath.exists() && launcherPath.isDirectory()) {
                SwingUtilities.invokeLater(() -> setTruncatedStatus("Scanning launcher: " + launcherPath.getAbsolutePath()));
                searchDirectory(launcherPath, originalGameName, normalizedGameName, results);
            }
        }

        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                searchDirectory(subDir, originalGameName, normalizedGameName, results);
            }
        }
    }

    private static void searchDirectory(File directory, String originalGameName, String normalizedGameName, java.util.Set<String> results) {
        if (directory == null || !directory.exists() ||
            QuickLauncher_v2_Config.EXCLUDED_DIRS.contains(directory.getName()) ||
            directory.getAbsolutePath().toLowerCase().contains("recycle")) {
            return;
        }
        try {
            Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (searchCancelled) {
                        return FileVisitResult.TERMINATE; // Stop traversal if cancelled
                    }
                    if (QuickLauncher_v2_Config.EXCLUDED_DIRS.contains(dir.getFileName().toString()) ||
                        dir.toString().toLowerCase().contains("recycle")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (searchCancelled) {
                        return FileVisitResult.TERMINATE; // Stop traversal if cancelled
                    }
                    synchronized (QuickLauncher_v2.class) {
                        fileCount++;
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastUpdateTime >= QuickLauncher_v2_Config.UPDATE_INTERVAL_MS) {
                            SwingUtilities.invokeLater(() -> setTruncatedStatus("Scanning: " + file.toString()));
                            lastUpdateTime = currentTime;
                        }
                    }
                    String fileName = file.getFileName().toString().toLowerCase();
                    Path parent = file.getParent();
                    boolean isXboxGame = parent != null && parent.toString().toLowerCase().contains("xboxgames");
                    boolean isDiscordRelated = originalGameName.contains("discord") || 
                                             normalizedGameName.contains("discord") ||
                                             (parent != null && parent.getFileName().toString().toLowerCase().contains("discord"));
                    
                    if (isDiscordRelated && !fileName.endsWith(".lnk")) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (isXboxGame && parent != null) {
                        String parentName = parent.getFileName().toString().toLowerCase();
                        if (parentName.contains(originalGameName) || parentName.contains(normalizedGameName)) {
                            synchronized (results) {
                                String gameLaunchHelper = Paths.get(parent.toString(), "Content", "gamelaunchhelper.exe").toString();
                                if (Files.exists(Paths.get(gameLaunchHelper))) {
                                    System.out.println("Found Xbox game: " + gameLaunchHelper + " for " + (parentName.contains(originalGameName) ? originalGameName : normalizedGameName));
                                    results.add(new File(gameLaunchHelper).getAbsolutePath());
                                } else {
                                    System.out.println("Found Xbox game: " + file.toString() + " for " + (parentName.contains(originalGameName) ? originalGameName : normalizedGameName));
                                    results.add(file.toFile().getAbsolutePath());
                                }
                            }
                        }
                    } else if (QuickLauncher_v2_Config.EXECUTABLE_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
                        String parentName = parent != null ? parent.getFileName().toString().toLowerCase() : "";
                        if (fileName.contains(originalGameName) || fileName.contains(normalizedGameName) ||
                            parentName.contains(originalGameName) || parentName.contains(normalizedGameName)) {
                            synchronized (results) {
                                System.out.println("Found non-Xbox game: " + file.toString() + " for " + (fileName.contains(originalGameName) || parentName.contains(originalGameName) ? originalGameName : normalizedGameName));
                                results.add(file.toFile().getAbsolutePath());
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    System.err.println("Failed to access file: " + file.toString() + ", error: " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Error scanning directory: " + directory.getAbsolutePath() + ", error: " + e.getMessage());
            SwingUtilities.invokeLater(() -> setTruncatedStatus("Error scanning drive: " + directory.getAbsolutePath()));
        }
    }
}

class QuickLauncher_v2_Config {
    public static final String KEYWORDS_FILE = "src\\keywords.txt";
    public static final String[] LAUNCHER_DIRS = {
        "Program Files", "Program Files (x86)", "Steam\\steamapps\\common",
        "Epic Games", "XboxGames", "Ubisoft\\Ubisoft Game Launcher\\games", "Games"
    };
    public static final Set<String> EXCLUDED_DIRS = Set.of(
        "Windows", "ProgramData", "System Volume Information", "$Recycle.Bin", "Recycle", "Windows Defender Advanced Threat Protection", "WindowsApps", "PerfLogs", "Voiceover", "inetpub", "OneDriveTemp"
    );
    public static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(".exe", ".lnk", ".bat");
    public static final long UPDATE_INTERVAL_MS = 500;
}