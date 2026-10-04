package test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.util.prefs.Preferences;

final class RailwayGamePanel extends JPanel {
    private static final int WIDTH = 1440;
    private static final int HEIGHT = 900;
    private static final int MAP_X = 258;
    private static final int MAP_Y = 105;
    private static final int MAP_W = 910;
    private static final int MAP_H = 610;
    private static final int BOARD_X = 1184;
    private static final int BOARD_W = 242;

    private static final Color NIGHT = new Color(25, 40, 59);
    private static final Color NIGHT_LIGHT = new Color(38, 67, 91);
    private static final Color PAPER = new Color(249, 252, 255);
    private static final Color INK = new Color(31, 48, 65);
    private static final Color MUTED = new Color(105, 127, 144);
    private static final Color BLUE = new Color(60, 145, 198);
    private static final Color RUST = new Color(192, 91, 67);
    private static final Color TRACK = new Color(48, 61, 77);

    RailwayWorld world = new RailwayWorld();
    private Tool tool = Tool.CURSOR;
    private RailwayWorld.Train selectedTrain = world.trains.get(0);
    private RailwayWorld.Contract selectedContract = world.contracts.get(0);
    private RailwayWorld.Station selectedStation;
    boolean guideOpen;
    GuideTopic guideTopic = GuideTopic.OVERVIEW;
    private int selectedStopIndex = -1;
    private RailwayWorld.Node trackStart;
    private RailwayWorld.Node pointerWorld;
    private double cameraX;
    private double cameraY;
    private double zoom = 0.62;
    private boolean running = true;
    private boolean fastForward;
    private boolean panning;
    private int lastPanX;
    private int lastPanY;
    private String message = "Select an item with the cursor, or choose a railway tool to begin building.";
    private long messageAt = System.currentTimeMillis();

    RailwayGamePanel() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setMinimumSize(new Dimension(1050, 700));
        setBackground(PAPER);
        setFocusable(true);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                Point logical = logicalPoint(event.getX(), event.getY());
                int x = logical.x;
                int y = logical.y;
                if (x < 0 || x > WIDTH || y < 0 || y > HEIGHT) {
                    return;
                }
                if (!guideOpen && event.getButton() != MouseEvent.BUTTON1 && insideMap(x, y)) {
                    panning = true;
                    lastPanX = event.getX();
                    lastPanY = event.getY();
                    return;
                }
                handleClick(x, y);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                panning = false;
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                Point logical = logicalPoint(event.getX(), event.getY());
                pointerWorld = insideMap(logical.x, logical.y)
                    ? worldPoint(logical.x, logical.y) : null;
                if (tool == Tool.TRACK) {
                    repaint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (panning) {
                    double scale = interfaceScale();
                    cameraX -= (event.getX() - lastPanX) / (scale * zoom);
                    cameraY -= (event.getY() - lastPanY) / (scale * zoom);
                    lastPanX = event.getX();
                    lastPanY = event.getY();
                    clampCamera();
                    repaint();
                } else {
                    Point logical = logicalPoint(event.getX(), event.getY());
                    pointerWorld = insideMap(logical.x, logical.y)
                            ? worldPoint(logical.x, logical.y) : null;
                    repaint();
                }
            }
        });
        addMouseWheelListener(event -> {
            if (guideOpen) {
                return;
            }
            Point logical = logicalPoint(event.getX(), event.getY());
            if (insideMap(logical.x, logical.y)) {
                zoomAt(logical.x, logical.y, event.getWheelRotation() < 0 ? 1.14 : 1 / 1.14);
                repaint();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.isControlDown() && event.getKeyCode() == KeyEvent.VK_S) {
                    saveGame();
                    return;
                }
                if (event.isControlDown() && event.getKeyCode() == KeyEvent.VK_N) {
                    confirmNewRailway();
                    return;
                }
                if (event.isControlDown() && event.getKeyCode() == KeyEvent.VK_O) {
                    loadGame();
                    return;
                }
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE && guideOpen) {
                    guideOpen = false;
                    repaint();
                    return;
                }
                if (guideOpen) {
                    return;
                }
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_1 -> chooseTool(Tool.CURSOR);
                    case KeyEvent.VK_2 -> chooseTool(Tool.TRACK);
                    case KeyEvent.VK_3 -> chooseTool(Tool.SIGNAL);
                    case KeyEvent.VK_4 -> chooseTool(Tool.STATION);
                    case KeyEvent.VK_5 -> chooseTool(Tool.TRAIN);
                    case KeyEvent.VK_6 -> chooseTool(Tool.SCHEDULE);
                    case KeyEvent.VK_7 -> chooseTool(Tool.DEMOLISH);
                    case KeyEvent.VK_F1 -> {
                        guideOpen = true;
                        guideTopic = GuideTopic.OVERVIEW;
                    }
                    case KeyEvent.VK_F5 -> quickSave();
                    case KeyEvent.VK_SPACE -> running = !running;
                    case KeyEvent.VK_ESCAPE -> trackStart = null;
                    case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> removeSelectedStop();
                    case KeyEvent.VK_UP -> moveSelectedStop(-1);
                    case KeyEvent.VK_DOWN -> moveSelectedStop(1);
                    case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS -> zoomAt(
                            MAP_X + MAP_W / 2, MAP_Y + MAP_H / 2, 1.14);
                    case KeyEvent.VK_MINUS -> zoomAt(
                            MAP_X + MAP_W / 2, MAP_Y + MAP_H / 2, 1 / 1.14);
                    default -> {
                    }
                }
                repaint();
            }
        });

        new Timer(100, event -> {
            if (running) {
                world.advanceTicks(fastForward ? 3 : 1);
            }
            repaint();
        }).start();
    }

    private double interfaceScale() {
        return Math.max(0.0001, Math.min(getWidth() / (double) WIDTH, getHeight() / (double) HEIGHT));
    }

    private Point logicalPoint(int x, int y) {
        double scale = interfaceScale();
        double offsetX = (getWidth() - WIDTH * scale) / 2;
        double offsetY = (getHeight() - HEIGHT * scale) / 2;
        return new Point((int) Math.floor((x - offsetX) / scale),
                (int) Math.floor((y - offsetY) / scale));
    }

    private boolean insideMap(int x, int y) {
        return x >= MAP_X && x <= MAP_X + MAP_W && y >= MAP_Y && y <= MAP_Y + MAP_H;
    }

    Tool currentTool() {
        return tool;
    }

    private void handleClick(int x, int y) {
        if (guideOpen) {
            handleGuideClick(x, y);
            repaint();
            return;
        }
        if (y < 79 && x >= 878 && x <= 1236) {
            if (x < 947) {
                guideOpen = true;
                guideTopic = GuideTopic.OVERVIEW;
            } else if (x < 1015) {
                confirmNewRailway();
            } else if (x < 1083) {
                saveGame();
            } else if (x < 1151) {
                loadGame();
            } else {
                quickSave();
            }
            return;
        }
        if (y < 79 && x >= 1242) {
            if (x >= 1326) {
                fastForward = !fastForward;
            } else {
                running = !running;
            }
            repaint();
            return;
        }

        if (x >= 13 && x < 235) {
            Tool[] tools = Tool.values();
            for (int index = 0; index < tools.length; index++) {
                int buttonY = 120 + index * 41;
                if (y >= buttonY && y <= buttonY + 37) {
                    chooseTool(tools[index]);
                    return;
                }
            }
            if (selectedTrain != null && y >= 584 && y < 689) {
                int index = (y - 584) / 21;
                if (index >= 0 && index < selectedTrain.stops.size()) {
                    selectedStopIndex = index;
                    if (x < 144) {
                        announce("Stop " + (index + 1) + " selected.");
                    } else if (x < 166) {
                        moveSelectedStop(-1);
                    } else if (x < 188) {
                        moveSelectedStop(1);
                    } else {
                        removeSelectedStop();
                    }
                }
                repaint();
                return;
            }
            if (selectedTrain != null && y >= 695 && y <= 723) {
                if (x >= 130) {
                    if (world.addCarriage(selectedTrain)) {
                        announce("Carriage added to " + selectedTrain.name + ". Freight capacity increased.");
                    } else {
                        announce("A carriage costs $" + RailwayWorld.CARRIAGE_COST + ". Check balance and capacity.");
                    }
                } else {
                    world.setScheduleLoop(selectedTrain, !selectedTrain.looping);
                    announce(selectedTrain.name + " schedule "
                            + (selectedTrain.looping ? "will repeat." : "will run once."));
                }
                repaint();
                return;
            }
        }

        if (x >= BOARD_X && x <= BOARD_X + BOARD_W && y >= 130 && y < 695) {
            int contractIndex = (y - 146) / 132;
            RailwayWorld.Contract contract = world.contractAt(contractIndex);
            if (contract != null) {
                selectedContract = contract;
                int cardY = 146 + contractIndex * 132;
                if (y >= cardY + 96 && contract.state == RailwayWorld.ContractState.AVAILABLE) {
                    if (world.acceptContract(contract)) {
                        announce("Order accepted: " + contract.name + ". Add both stations to a train timetable.");
                    }
                }
                repaint();
                return;
            }
        }

        if (!insideMap(x, y)) {
            if (y >= 765 && y < 824) {
                int index = (x - 273) / 300;
                if (index >= 0 && index < world.trains.size()) {
                    selectedTrain = world.trains.get(index);
                    selectedStopIndex = -1;
                    announce(selectedTrain.name + " selected. Set its stops with the timetable tool.");
                }
            }
            repaint();
            return;
        }

        if (zoomControlClick(x, y)) {
            repaint();
            return;
        }

        RailwayWorld.Node point = worldPoint(x, y);
        RailwayWorld.Train clickedTrain = world.trainAt(point, 24 / zoom);
        if (clickedTrain != null && tool == Tool.DEMOLISH) {
            RailwayWorld.DemolitionResult result = world.demolishTrain(clickedTrain);
            announce(demolitionMessage(result));
            if (result == RailwayWorld.DemolitionResult.TRAIN_REMOVED && selectedTrain == clickedTrain) {
                selectedTrain = world.trains.get(0);
                selectedStopIndex = -1;
            }
            repaint();
            return;
        }
        if (clickedTrain != null && tool != Tool.TRACK) {
            selectedTrain = clickedTrain;
            selectedStation = null;
            selectedStopIndex = -1;
            announce(selectedTrain.name + " selected.");
            repaint();
            return;
        }

        switch (tool) {
            case CURSOR -> {
                selectedStation = world.nearestStation(point, 32 / zoom);
                announce(selectedStation == null ? "Cursor selected. Choose a tool or click a train or station."
                        : selectedStation.name + " selected. Produces " + selectedStation.produces.label + ".");
            }
            case TRACK -> placeTrack(point);
            case SIGNAL -> {
                if (world.toggleSignal(point)) {
                    announce("Signal changed. Trains wait when its protected section is occupied.");
                } else {
                    announce("Signals need a nearby rail and cost $" + RailwayWorld.SIGNAL_COST + ".");
                }
            }
            case STATION -> {
                RailwayWorld.Station station = world.buildStation(point);
                announce(station == null ? "Station placement failed. Check location and funds."
                        : station.name + " founded. Draw track to connect it to the network.");
            }
            case TRAIN -> {
                RailwayWorld.Train train = world.deployTrain(point);
                if (train == null) {
                    announce("Place a train beside a rail. A locomotive costs $" + RailwayWorld.TRAIN_COST + ".");
                } else {
                    selectedTrain = train;
                    selectedStopIndex = -1;
                    announce(train.name + " deployed. Select Timetable and add station stops.");
                }
            }
            case SCHEDULE -> {
                RailwayWorld.Station station = world.nearestStation(point, 30 / zoom);
                if (station == null) {
                    announce("Click directly on a station marker to add a timetable stop.");
                } else if (selectedTrain == null) {
                    announce("Select a train before adding timetable stops.");
                } else if (world.addScheduleStop(selectedTrain, station)) {
                    selectedStopIndex = selectedTrain.stops.size() - 1;
                    announce(station.name + " added to " + selectedTrain.name + "'s timetable.");
                } else if (selectedTrain.stops.contains(station.position)) {
                    announce(station.name + " is already on this timetable.");
                } else {
                    announce("This timetable has 20 stops. Remove one before adding another.");
                }
            }
            case DEMOLISH -> announce(demolitionMessage(world.demolishAt(point, 28 / zoom)));
        }
        repaint();
    }

    private void handleGuideClick(int x, int y) {
        if (x >= 1168 && x <= 1212 && y >= 145 && y <= 185) {
            guideOpen = false;
            return;
        }
        if (x < 220 || x > 1220 || y < 130 || y > 770) {
            guideOpen = false;
            return;
        }
        if (x >= 238 && x <= 440 && y >= 211 && y < 211 + GuideTopic.values().length * 43) {
            int index = (y - 211) / 43;
            if (index >= 0 && index < GuideTopic.values().length) {
                guideTopic = GuideTopic.values()[index];
            }
        }
    }

    private void confirmNewRailway() {
        boolean wasRunning = running;
        running = false;
        int result = JOptionPane.showConfirmDialog(this,
                "Start a completely new railway? The previous save file will be kept, but unsaved changes will be lost.",
                "New railway", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        running = wasRunning;
        if (result == JOptionPane.OK_OPTION) {
            startNewRailway();
        }
        repaint();
    }

    void startNewRailway() {
        world = new RailwayWorld();
        selectedTrain = world.trains.get(0);
        selectedContract = world.contracts.get(0);
        selectedStation = null;
        tool = Tool.CURSOR;
        selectedStopIndex = -1;
        trackStart = null;
        pointerWorld = null;
        cameraX = 0;
        cameraY = 0;
        zoom = 0.62;
        running = true;
        fastForward = false;
        guideOpen = false;
        panning = false;
        clearLastSavePath();
        announce("New railway started. Save it as a new file when you are ready.");
        repaint();
    }

    private void clearLastSavePath() {
        try {
            Preferences.userNodeForPackage(App.class).remove("lastSavePath");
        } catch (SecurityException ignored) {
        }
    }

    private void moveSelectedStop(int direction) {
        if (selectedTrain == null || selectedStopIndex < 0
                || !world.moveScheduleStop(selectedTrain, selectedStopIndex, direction)) {
            announce("Choose a timetable stop that can move in that direction.");
            return;
        }
        selectedStopIndex += direction;
        announce("Stop order updated. The train will finish its current leg first.");
    }

    private void removeSelectedStop() {
        if (selectedTrain == null) {
            return;
        }
        if (selectedStopIndex < 0 || selectedStopIndex >= selectedTrain.stops.size()) {
            selectedStopIndex = selectedTrain.stops.size() - 1;
        }
        if (!world.removeScheduleStop(selectedTrain, selectedStopIndex)) {
            announce("There are no stops to remove.");
            return;
        }
        selectedStopIndex = Math.min(selectedStopIndex, selectedTrain.stops.size() - 1);
        announce("Stop removed from " + selectedTrain.name + "'s timetable.");
    }

    private String demolitionMessage(RailwayWorld.DemolitionResult result) {
        return switch (result) {
            case TRAIN_REMOVED -> "Locomotive sold for a partial refund.";
            case STATION_REMOVED -> "Station removed for a partial refund.";
            case SIGNAL_REMOVED -> "Signal removed for a partial refund.";
            case TRACK_REMOVED -> "Track section removed for a partial refund.";
            case NOTHING_THERE -> "Click a train, station, signal, or track section to remove it.";
            case TRACK_IN_USE -> "That section is carrying a train or one of its carriages.";
            case STATION_IN_USE -> "Remove this station from schedules and active contracts first.";
            case TRAIN_CARRYING_CARGO -> "Deliver or unload this train's freight before selling it.";
            case LAST_TRAIN -> "Keep at least one locomotive in service.";
        };
    }

    private void placeTrack(RailwayWorld.Node point) {
        RailwayWorld.Node snapped = world.snapTrack(point, 15 / zoom);
        if (snapped != null) {
            point = snapped;
        }
        if (trackStart == null) {
            trackStart = point;
            announce("Track start set. Click another point; keep clicking to draw connected sections.");
            return;
        }
        if (trackStart.equals(point)) {
            trackStart = null;
            announce("Track drawing finished.");
            return;
        }
        if (world.buildTrack(trackStart, point)) {
            trackStart = point;
            announce("Rail laid. Continue from the new endpoint or press Esc to finish.");
        } else {
            trackStart = null;
            announce("Track could not be built. Each section costs $" + RailwayWorld.TRACK_COST + ".");
        }
    }

    private boolean zoomControlClick(int x, int y) {
        int left = MAP_X + MAP_W - 112;
        int top = MAP_Y + MAP_H - 48;
        if (x < left || x > left + 104 || y < top || y > top + 40) {
            return false;
        }
        if (x < left + 37) {
            zoomAt(MAP_X + MAP_W / 2, MAP_Y + MAP_H / 2, 1 / 1.14);
        } else if (x > left + 67) {
            zoomAt(MAP_X + MAP_W / 2, MAP_Y + MAP_H / 2, 1.14);
        }
        return true;
    }

    private RailwayWorld.Node worldPoint(int x, int y) {
        return new RailwayWorld.Node(cameraX + (x - MAP_X) / zoom,
                cameraY + (y - MAP_Y) / zoom);
    }

    private void zoomAt(int x, int y, double factor) {
        double worldX = cameraX + (x - MAP_X) / zoom;
        double worldY = cameraY + (y - MAP_Y) / zoom;
        zoom = Math.max(0.35, Math.min(2.4, zoom * factor));
        cameraX = worldX - (x - MAP_X) / zoom;
        cameraY = worldY - (y - MAP_Y) / zoom;
        clampCamera();
    }

    private void clampCamera() {
        cameraX = Math.max(0, Math.min(RailwayWorld.WIDTH - MAP_W / zoom, cameraX));
        cameraY = Math.max(0, Math.min(RailwayWorld.HEIGHT - MAP_H / zoom, cameraY));
    }

    private void chooseTool(Tool next) {
        tool = next;
        trackStart = null;
        switch (tool) {
            case CURSOR -> announce("CURSOR  /  Select a train or station without building.");
            case TRACK -> announce("TRACK  /  Click any point to begin a freeform route.");
            case SIGNAL -> announce("SIGNAL  /  Place a block signal beside a rail.");
            case STATION -> announce("STATION  /  Found a new stop anywhere on the map.");
            case TRAIN -> announce("TRAIN  /  Deploy a locomotive on existing rail.");
            case SCHEDULE -> announce("TIMETABLE  /  Click stations in order to set the train's circuit.");
            case DEMOLISH -> announce("REMOVE  /  Click a train, station, signal, or track section.");
        }
        repaint();
    }

    private void announce(String text) {
        message = text;
        messageAt = System.currentTimeMillis();
    }

    private void saveGame() {
        JFileChooser chooser = saveChooser();
        boolean wasRunning = running;
        running = false;
        int result;
        try {
            result = chooser.showSaveDialog(this);
        } finally {
            running = wasRunning;
        }
        if (result != JFileChooser.APPROVE_OPTION) {
            repaint();
            return;
        }
        File selectedFile = chooser.getSelectedFile();
        Path path = selectedFile.toPath();
        if (!selectedFile.getName().toLowerCase(Locale.ROOT).endsWith(".lmsave")) {
            path = path.resolveSibling(selectedFile.getName() + ".lmsave");
        }
        try {
            world.save(path, currentViewState());
            rememberLastSave(path);
            announce("Game saved to " + path.getFileName() + ".");
        } catch (IOException exception) {
            announce("Save failed: " + exception.getMessage());
        }
        repaint();
    }

    void quickSave() {
        Path path = lastSavedPath();
        if (path == null) {
            saveGame();
            return;
        }
        try {
            world.save(path, currentViewState());
            rememberLastSave(path);
            announce("Quick-saved to " + path.getFileName() + ".");
        } catch (IOException exception) {
            announce("Quick save failed: " + exception.getMessage());
        }
        repaint();
    }

    private Path lastSavedPath() {
        try {
            String savedPath = Preferences.userNodeForPackage(App.class).get("lastSavePath", "");
            return savedPath.isBlank() ? null : Path.of(savedPath);
        } catch (IllegalArgumentException | SecurityException exception) {
            return null;
        }
    }

    private void loadGame() {
        JFileChooser chooser = saveChooser();
        boolean wasRunning = running;
        running = false;
        int result;
        try {
            result = chooser.showOpenDialog(this);
        } finally {
            running = wasRunning;
        }
        if (result != JFileChooser.APPROVE_OPTION) {
            repaint();
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Replace the current railway with the selected save?",
                "Load saved railway", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            repaint();
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        if (restoreSavedGame(path)) {
            rememberLastSave(path);
            announce("Saved railway loaded.");
        }
        repaint();
    }

    void restoreLastSavedGame() {
        try {
            String savedPath = Preferences.userNodeForPackage(App.class).get("lastSavePath", "");
            if (savedPath.isBlank()) {
                return;
            }
            Path path = Path.of(savedPath);
            if (!Files.isRegularFile(path) || !restoreSavedGame(path)) {
                announce("Last save could not be restored. A new railway has been started.");
            } else {
                announce("Last saved railway restored.");
            }
        } catch (IllegalArgumentException | SecurityException exception) {
            announce("Last save could not be restored. A new railway has been started.");
        }
    }

    boolean restoreSavedGame(Path path) {
        try {
            RailwayWorld.LoadedGame loaded = RailwayWorld.load(path);
            world = loaded.world();
            RailwayWorld.ViewState view = loaded.view();
            selectedTrain = trainById(view.selectedTrainId());
            selectedContract = contractById(view.selectedContractId());
            selectedStation = null;
            cameraX = view.cameraX();
            cameraY = view.cameraY();
            zoom = view.zoom();
            running = view.running();
            fastForward = view.fastForward();
            selectedStopIndex = -1;
            trackStart = null;
            pointerWorld = null;
            tool = Tool.CURSOR;
            clampCamera();
            return true;
        } catch (IOException exception) {
            announce("Load failed: " + exception.getMessage());
            return false;
        }
    }

    private void rememberLastSave(Path path) {
        try {
            Preferences.userNodeForPackage(App.class)
                    .put("lastSavePath", path.toAbsolutePath().toString());
        } catch (SecurityException ignored) {
        }
    }

    private JFileChooser saveChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Last Mile saves (*.lmsave)", "lmsave"));
        chooser.setSelectedFile(new File("last-mile.lmsave"));
        return chooser;
    }

    private RailwayWorld.ViewState currentViewState() {
        return new RailwayWorld.ViewState(cameraX, cameraY, zoom, selectedTrain.id,
                selectedContract.id, running, fastForward);
    }

    private RailwayWorld.Train trainById(int id) {
        for (RailwayWorld.Train train : world.trains) {
            if (train.id == id) {
                return train;
            }
        }
        return world.trains.get(0);
    }

    private RailwayWorld.Contract contractById(int id) {
        for (RailwayWorld.Contract contract : world.contracts) {
            if (contract.id == id) {
                return contract;
            }
        }
        return world.contracts.get(0);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        double scale = interfaceScale();
        g.translate((getWidth() - WIDTH * scale) / 2, (getHeight() - HEIGHT * scale) / 2);
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawHeader(g);
        drawTools(g);
        drawMap(g);
        drawFreightBoard(g);
        drawRoster(g);
        if (guideOpen) {
            drawGuide(g);
        }
        g.dispose();
    }

    private void drawHeader(Graphics2D g) {
        g.setColor(NIGHT);
        g.fillRect(0, 0, WIDTH, 82);
        g.setColor(new Color(71, 145, 190));
        g.fillRoundRect(18, 17, 47, 47, 10, 10);
        g.setColor(PAPER);
        g.setStroke(new BasicStroke(2.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(27, 47, 56, 47);
        g.drawLine(31, 35, 31, 56);
        g.drawLine(40, 35, 40, 56);
        g.drawLine(49, 35, 49, 56);
        g.setFont(new Font("Dialog", Font.BOLD, 19));
        g.drawString("LAST MILE", 76, 39);
        g.setColor(new Color(168, 204, 222));
        g.setFont(new Font("Dialog", Font.PLAIN, 10));
        g.drawString("FREIGHT RAIL COMPANY", 77, 57);

        g.setColor(new Color(155, 197, 218));
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("RAILWAY TIME", 355, 27);
        g.setColor(PAPER);
        g.setFont(new Font("Dialog", Font.BOLD, 22));
        g.drawString(String.format("%02d:%02d", world.minutes / 60, world.minutes % 60), 354, 55);
        g.setColor(new Color(183, 212, 228));
        g.setFont(new Font("Dialog", Font.PLAIN, 11));
        g.drawString("DAY " + world.day, 447, 53);

        g.setColor(new Color(155, 197, 218));
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("COMPANY FUNDS", 584, 27);
        g.setColor(new Color(226, 192, 109));
        g.setFont(new Font("Dialog", Font.BOLD, 20));
        g.drawString(String.format("$%,d", world.cash), 582, 55);

        g.setColor(new Color(155, 197, 218));
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("ORDERS DELIVERED", 779, 27);
        g.setColor(PAPER);
        g.setFont(new Font("Dialog", Font.BOLD, 20));
        g.drawString(String.format("%02d", world.deliveries), 779, 55);

        drawHeaderButton(g, 878, 21, 68, 40, "BOOK", false);
        drawHeaderButton(g, 950, 21, 64, 40, "NEW", false);
        drawHeaderButton(g, 1018, 21, 64, 40, "SAVE", false);
        drawHeaderButton(g, 1086, 21, 64, 40, "LOAD", false);
        drawHeaderButton(g, 1154, 21, 82, 40, "QUICK SAVE", false);
        drawHeaderButton(g, 1242, 21, 78, 40, running ? "PAUSE" : "RESUME", running);
        drawHeaderButton(g, 1326, 21, 54, 40, fastForward ? "3x" : "1x", fastForward);
        g.setColor(new Color(166, 202, 221));
        g.setFont(new Font("Dialog", Font.PLAIN, 10));
        g.drawString("F1", 904, 73);
        g.drawString("CTRL+N", 963, 73);
        g.drawString("CTRL+S", 1025, 73);
        g.drawString("CTRL+O", 1093, 73);
        g.drawString("F5", 1187, 73);
        g.drawString("SPACE", 1264, 73);
    }

    private void drawTools(Graphics2D g) {
        g.setColor(new Color(237, 247, 252));
        g.fillRect(0, 82, 244, HEIGHT - 82);
        g.setColor(new Color(203, 224, 237));
        g.drawLine(243, 82, 243, HEIGHT);
        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("COMPANY WORKSHOP", 18, 111);

        Tool[] tools = Tool.values();
        String[] titles = {"Cursor", "Lay track", "Block signals", "Found station",
            "Buy locomotive", "Set timetable", "Remove objects"};
        String[] subtitles = {"Select trains & stops", "$12 per section", "$55 each", "$175 each",
            "$425 each", "ADD STOPS ON MAP", "50% refund"};
        for (int index = 0; index < tools.length; index++) {
            int y = 120 + index * 41;
            boolean active = tool == tools[index];
            g.setColor(active ? new Color(220, 239, 249) : PAPER);
            g.fillRoundRect(12, y, 220, 37, 7, 7);
            g.setColor(active ? BLUE : new Color(207, 226, 239));
            g.drawRoundRect(12, y, 220, 37, 7, 7);
            drawToolGlyph(g, tools[index], 33, y + 18, active ? BLUE : MUTED);
            g.setColor(INK);
            g.setFont(new Font("Dialog", Font.BOLD, 10));
            g.drawString(titles[index], 53, y + 16);
            g.setColor(MUTED);
            g.setFont(new Font("Dialog", Font.PLAIN, 8));
            g.drawString(subtitles[index], 53, y + 29);
            if (active) {
                g.setColor(BLUE);
                g.fillRoundRect(224, y + 10, 4, 17, 3, 3);
            }
        }

        drawTrainDetails(g);
    }

    private void drawTrainDetails(Graphics2D g) {
        int x = 12;
        int y = 426;
        g.setColor(PAPER);
        g.fillRoundRect(x, y, 220, 303, 8, 8);
        g.setColor(new Color(205, 228, 241));
        g.drawRoundRect(x, y, 220, 303, 8, 8);
        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("SELECTED LOCOMOTIVE", x + 14, y + 21);
        if (selectedTrain == null) {
            g.setColor(INK);
            g.setFont(new Font("Dialog", Font.PLAIN, 12));
            g.drawString("Select a train on the map", x + 14, y + 49);
            return;
        }
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 16));
        g.drawString(selectedTrain.name, x + 14, y + 48);
        g.setColor(BLUE);
        g.setFont(new Font("Dialog", Font.PLAIN, 10));
        g.drawString("NEXT STOP  /  " + world.nextStopName(selectedTrain), x + 14, y + 68);

        g.setColor(new Color(229, 242, 250));
        g.drawLine(x + 14, y + 82, x + 206, y + 82);
        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("FREIGHT HOLD", x + 14, y + 100);
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 12));
        g.drawString(selectedTrain.load() + " / " + (selectedTrain.carriages * 4) + " crates", x + 14, y + 119);
        int capacity = selectedTrain.carriages * 4;
        int loaded = selectedTrain.load();
        g.setColor(new Color(232, 243, 250));
        g.fillRoundRect(x + 14, y + 127, 190, 6, 4, 4);
        if (loaded > 0) {
            g.setColor(new Color(201, 149, 78));
            g.fillRoundRect(x + 14, y + 127, 190 * loaded / capacity, 6, 4, 4);
        }

        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("TIMETABLE  " + selectedTrain.stops.size() + "/"
            + RailwayWorld.MAX_SCHEDULE_STOPS, x + 14, y + 153);
        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.PLAIN, 8));
        g.drawString("select row  /  ^ up  v down  x remove", x + 76, y + 153);
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.PLAIN, 9));
        if (selectedTrain.stops.isEmpty()) {
            g.drawString("Choose Set timetable, then click stations.", x + 14, y + 177);
        } else {
            for (int index = 0; index < selectedTrain.stops.size(); index++) {
                RailwayWorld.Station station = world.stationAt(selectedTrain.stops.get(index));
                int rowY = y + 158 + index * 21;
                if (index == selectedStopIndex) {
                    g.setColor(new Color(220, 239, 249));
                    g.fillRoundRect(x + 8, rowY, 202, 20, 4, 4);
                }
                g.setColor(index == 0 ? BLUE : MUTED);
                g.fillOval(x + 15, rowY + 7, 6, 6);
                g.setColor(INK);
                g.drawString((index + 1) + ". " + (station == null ? "Open line" : station.name),
                        x + 27, rowY + 14);
                if (selectedTrain.stops.get(index).equals(selectedTrain.destination)) {
                    g.setColor(new Color(173, 126, 53));
                    g.setFont(new Font("Dialog", Font.BOLD, 7));
                    g.drawString("NEXT", x + 101, rowY + 13);
                    g.setFont(new Font("Dialog", Font.PLAIN, 9));
                }
                drawScheduleAction(g, x + 132, rowY + 1, "^");
                drawScheduleAction(g, x + 154, rowY + 1, "v");
                drawScheduleAction(g, x + 176, rowY + 1, "x");
            }
        }

        int controlY = y + 270;
        g.setColor(selectedTrain.looping ? new Color(220, 239, 249) : new Color(235, 232, 218));
        g.fillRoundRect(x + 12, controlY, 98, 25, 5, 5);
        g.setColor(selectedTrain.looping ? BLUE : new Color(163, 128, 72));
        g.drawRoundRect(x + 12, controlY, 98, 25, 5, 5);
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString(selectedTrain.looping ? "REPEAT: ON" : "REPEAT: OFF", x + 25, controlY + 16);
        g.setColor(new Color(220, 239, 249));
        g.fillRoundRect(x + 118, controlY, 90, 25, 5, 5);
        g.setColor(BLUE);
        g.drawRoundRect(x + 118, controlY, 90, 25, 5, 5);
        g.drawString("+ CAR  $" + RailwayWorld.CARRIAGE_COST, x + 126, controlY + 16);
    }

    private void drawScheduleAction(Graphics2D g, int x, int y, String label) {
        g.setColor(new Color(247, 251, 254));
        g.fillRoundRect(x, y, 19, 18, 4, 4);
        g.setColor(new Color(209, 231, 242));
        g.drawRoundRect(x, y, 19, 18, 4, 4);
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 11));
        g.drawString(label, x + 6, y + 13);
    }

    private void drawMap(Graphics2D g) {
        g.setColor(new Color(252, 254, 255));
        g.fillRoundRect(MAP_X, MAP_Y, MAP_W, MAP_H, 10, 10);
        g.setColor(new Color(226, 240, 248));
        g.drawRoundRect(MAP_X, MAP_Y, MAP_W, MAP_H, 10, 10);

        Graphics2D worldGraphics = (Graphics2D) g.create();
        worldGraphics.clipRect(MAP_X + 1, MAP_Y + 1, MAP_W - 2, MAP_H - 2);
        worldGraphics.translate(MAP_X - cameraX * zoom, MAP_Y - cameraY * zoom);
        worldGraphics.scale(zoom, zoom);
        drawTerrain(worldGraphics);
        drawTracks(worldGraphics);
        drawSelectedRoute(worldGraphics);
        drawTrackPreview(worldGraphics);
        for (RailwayWorld.Station station : world.stations) {
            drawStation(worldGraphics, station);
        }
        for (RailwayWorld.Node signal : world.signals) {
            drawSignal(worldGraphics, signal);
        }
        for (RailwayWorld.Train train : world.trains) {
            drawTrain(worldGraphics, train);
        }
        worldGraphics.dispose();

        g.setColor(new Color(254, 253, 246, 237));
        g.fillRoundRect(MAP_X + 14, MAP_Y + 13, 203, 36, 7, 7);
        g.setColor(new Color(221, 238, 248));
        g.drawRoundRect(MAP_X + 14, MAP_Y + 13, 203, 36, 7, 7);
        g.setColor(RUST);
        g.fillOval(MAP_X + 25, MAP_Y + 26, 8, 8);
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 10));
        g.drawString("PINE COAST DIVISION", MAP_X + 41, MAP_Y + 36);

        drawZoomControls(g);
        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.PLAIN, 9));
        g.drawString(world.tracks.size() + " sections    " + world.stations.size()
                + " stops    " + world.signals.size() + " signals",
                MAP_X + 14, MAP_Y + MAP_H + 16);
        g.drawString("WHEEL ZOOM  /  RIGHT-DRAG TO MOVE", MAP_X + MAP_W - 227, MAP_Y + MAP_H + 16);
    }

    private void drawTerrain(Graphics2D g) {
        g.setColor(new Color(235, 246, 252));
        g.fillRect(0, 0, RailwayWorld.WIDTH, RailwayWorld.HEIGHT);
        g.setColor(new Color(225, 241, 249));
        g.fillOval(95, 160, 840, 490);
        g.fillOval(1150, 1020, 900, 570);
        g.fillOval(2070, 200, 820, 460);
        g.setColor(new Color(244, 249, 252));
        g.fillOval(800, 90, 600, 430);
        g.fillOval(100, 1120, 780, 620);
        g.fillOval(2110, 1300, 760, 520);

        Path2D.Double river = new Path2D.Double();
        river.moveTo(1410, -80);
        river.curveTo(1270, 350, 1760, 480, 1550, 840);
        river.curveTo(1340, 1180, 1770, 1400, 1670, 1710);
        river.curveTo(1600, 1900, 1950, 2040, 1990, 2290);
        g.setColor(new Color(178, 220, 240));
        g.setStroke(new BasicStroke(150, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(river);
        g.setColor(new Color(195, 230, 248));
        g.setStroke(new BasicStroke(125, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(river);

        for (int ring = 0; ring < 5; ring++) {
            g.setColor(new Color(207, 228, 241, 125));
            g.setStroke(new BasicStroke(1));
            g.drawOval(120 + ring * 17, 145 + ring * 12, 770 - ring * 34, 450 - ring * 25);
            g.drawOval(1900 + ring * 20, 1160 + ring * 13, 710 - ring * 39, 520 - ring * 26);
        }

        g.setStroke(new BasicStroke(13, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10, new float[]{26, 18}, 0));
        g.setColor(new Color(198, 218, 232));
        g.draw(new QuadCurve2D.Double(-60, 910, 910, 700, 1860, 860));
        g.draw(new QuadCurve2D.Double(580, 2100, 1350, 1510, 2640, 1530));
        g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10, new float[]{26, 18}, 0));
        g.setColor(new Color(250, 248, 231));
        g.draw(new QuadCurve2D.Double(-60, 910, 910, 700, 1860, 860));
        g.draw(new QuadCurve2D.Double(580, 2100, 1350, 1510, 2640, 1530));

        for (int index = 0; index < 210; index++) {
            int x = 45 + (index * 173) % 3090;
            int y = 65 + (index * 347 + index * index * 3) % 2070;
            if (x > 1250 && x < 1850 && y > 250 && y < 1830) {
                continue;
            }
            g.setColor(index % 4 == 0 ? new Color(127, 180, 207) : new Color(153, 198, 224));
            g.fillOval(x, y, 14, 9);
            g.setColor(new Color(188, 219, 237));
            g.fillOval(x + 4, y - 4, 7, 8);
        }

        mapLabel(g, "NORTHFIELD", 110, 345);
        mapLabel(g, "CEDAR COUNTRY", 280, 590);
        mapLabel(g, "SALT COAST", 1860, 330);
        mapLabel(g, "FERN LOWLANDS", 430, 1000);
        mapLabel(g, "REDSTONE", 2130, 1050);
        mapLabel(g, "PINE COAST", 2250, 1880);
    }

    private void mapLabel(Graphics2D g, String text, int x, int y) {
        g.setColor(new Color(100, 144, 174));
        g.setFont(new Font("Dialog", Font.BOLD, 13));
        g.drawString(text, x, y);
    }

    private void drawTracks(Graphics2D g) {
        for (RailwayWorld.Edge edge : world.tracks) {
            double x1 = edge.first().x();
            double y1 = edge.first().y();
            double x2 = edge.second().x();
            double y2 = edge.second().y();
            Line2D line = new Line2D.Double(x1, y1, x2, y2);
            g.setStroke(new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(199, 215, 225));
            g.draw(line);
            g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(TRACK);
            g.draw(line);
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(new Color(207, 225, 236));
            double length = edge.first().distance(edge.second());
            int sleepers = Math.max(1, (int) (length / 19));
            double acrossX = -(y2 - y1) / length * 5;
            double acrossY = (x2 - x1) / length * 5;
            for (int index = 1; index < sleepers; index++) {
                double amount = index / (double) sleepers;
                double x = x1 + (x2 - x1) * amount;
                double y = y1 + (y2 - y1) * amount;
                g.draw(new Line2D.Double(x - acrossX, y - acrossY, x + acrossX, y + acrossY));
            }
        }
    }

    private void drawSelectedRoute(Graphics2D g) {
        if (selectedTrain == null || selectedTrain.path.size() < 2) {
            return;
        }
        Path2D.Double route = new Path2D.Double();
        route.moveTo(selectedTrain.position.x(), selectedTrain.position.y());
        for (int index = selectedTrain.pathIndex; index < selectedTrain.path.size(); index++) {
            RailwayWorld.Node node = selectedTrain.path.get(index);
            route.lineTo(node.x(), node.y());
        }
        g.setColor(new Color(227, 177, 88, 155));
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(route);
    }

    private void drawTrackPreview(Graphics2D g) {
        if (trackStart == null || pointerWorld == null) {
            return;
        }
        g.setColor(new Color(63, 152, 202, 190));
        g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10, new float[]{7, 6}, 0));
        g.draw(new Line2D.Double(trackStart.x(), trackStart.y(), pointerWorld.x(), pointerWorld.y()));
        g.fillOval((int) trackStart.x() - 6, (int) trackStart.y() - 6, 12, 12);
    }

    private void drawStation(Graphics2D g, RailwayWorld.Station station) {
        int x = (int) Math.round(station.position.x());
        int y = (int) Math.round(station.position.y());
        if (station == selectedStation) {
            g.setColor(new Color(60, 145, 198, 55));
            g.fillOval(x - 37, y - 37, 74, 74);
            g.setColor(BLUE);
            g.setStroke(new BasicStroke(2));
            g.drawOval(x - 37, y - 37, 74, 74);
        }
        g.setColor(new Color(51, 71, 88, 45));
        g.fillRoundRect(x - 29, y - 13, 58, 34, 6, 6);
        g.setColor(new Color(76, 128, 159));
        g.fillRoundRect(x - 26, y - 17, 52, 30, 5, 5);
        g.setColor(new Color(210, 233, 247));
        g.fillRect(x - 20, y - 11, 40, 6);
        g.setColor(PAPER);
        g.fillRect(x - 14, y - 3, 5, 10);
        g.fillRect(x - 2, y - 3, 5, 10);
        g.fillRect(x + 10, y - 3, 5, 10);
        g.setColor(TRACK);
        g.fillOval(x - 7, y + 8, 5, 5);
        g.fillOval(x + 2, y + 8, 5, 5);

        g.setColor(new Color(254, 253, 245, 238));
        g.fillRoundRect(x - 42, y - 42, 84, 17, 5, 5);
        g.setColor(new Color(205, 226, 239));
        g.drawRoundRect(x - 42, y - 42, 84, 17, 5, 5);
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 10));
        drawCentered(g, station.name, x, y - 30, 76);
        drawStockDots(g, station, x, y + 20);
    }

    private void drawStockDots(Graphics2D g, RailwayWorld.Station station, int x, int y) {
        g.setColor(station.produces.color);
        g.fillOval(x - 4, y, 7, 7);
        g.setColor(new Color(250, 249, 240, 230));
        g.setFont(new Font("Dialog", Font.BOLD, 8));
        g.drawString(Integer.toString(station.stockOf(station.produces)), x + 6, y + 7);
    }

    private void drawSignal(Graphics2D g, RailwayWorld.Node signal) {
        int x = (int) Math.round(signal.x());
        int y = (int) Math.round(signal.y());
        boolean blocked = false;
        for (RailwayWorld.Train train : world.trains) {
            if (train.position.equals(signal) && train.pathIndex < train.path.size() - 1) {
                for (RailwayWorld.Train other : world.trains) {
                    if (other != train && other.pathIndex < other.path.size() - 1
                            && new RailwayWorld.Edge(train.path.get(train.pathIndex),
                                    train.path.get(train.pathIndex + 1)).equals(
                                            new RailwayWorld.Edge(other.path.get(other.pathIndex),
                                                    other.path.get(other.pathIndex + 1)))) {
                        blocked = true;
                    }
                }
            }
        }
        g.setColor(TRACK);
        g.setStroke(new BasicStroke(2));
        g.drawLine(x + 18, y + 6, x + 18, y - 28);
        g.setColor(blocked ? RUST : new Color(67, 157, 209));
        g.fillRoundRect(x + 12, y - 31, 13, 13, 4, 4);
        g.setColor(PAPER);
        g.drawRoundRect(x + 12, y - 31, 13, 13, 4, 4);
    }

    private void drawTrain(Graphics2D g, RailwayWorld.Train train) {
        RailwayWorld.Node position = train.positionAt();
        double heading = heading(train);
        if (train == selectedTrain) {
            g.setColor(new Color(219, 166, 80, 65));
            g.fillOval((int) position.x() - 26, (int) position.y() - 26, 52, 52);
        }
        for (int car = train.carriages; car >= 1; car--) {
            double[] trailing = carriagePosition(train, car);
            drawCar(g, trailing[0], trailing[1], trailing[2], false, false);
        }
        drawCar(g, position.x(), position.y(), heading, true, train == selectedTrain);
        g.setColor(new Color(255, 254, 246));
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString(train.name, (int) position.x() - 22, (int) position.y() + 33);
    }

    double[] carriagePosition(RailwayWorld.Train train, int index) {
        double remaining = index * 31;
        if (train.path.size() >= 2 && train.pathIndex < train.path.size() - 1) {
            RailwayWorld.Node from = train.path.get(train.pathIndex);
            RailwayWorld.Node to = train.path.get(train.pathIndex + 1);
            RailwayWorld.Node front = train.positionAt();
            double distanceOnCurrentSegment = front.distance(from);
            if (remaining <= distanceOnCurrentSegment && distanceOnCurrentSegment > 0) {
                double fraction = remaining / distanceOnCurrentSegment;
                return new double[]{front.x() + (from.x() - front.x()) * fraction,
                    front.y() + (from.y() - front.y()) * fraction,
                    Math.atan2(to.y() - from.y(), to.x() - from.x())};
            }
            remaining -= distanceOnCurrentSegment;
        }

        List<RailwayWorld.Node> trail = train.railTrail;
        for (int trailIndex = trail.size() - 1; trailIndex > 0; trailIndex--) {
            RailwayWorld.Node start = trail.get(trailIndex - 1);
            RailwayWorld.Node end = trail.get(trailIndex);
            double segmentLength = start.distance(end);
            if (remaining <= segmentLength && segmentLength > 0) {
                double fraction = remaining / segmentLength;
                return new double[]{end.x() + (start.x() - end.x()) * fraction,
                    end.y() + (start.y() - end.y()) * fraction,
                    Math.atan2(end.y() - start.y(), end.x() - start.x())};
            }
            remaining -= segmentLength;
        }
        RailwayWorld.Node oldestPoint = trail.isEmpty() ? train.position : trail.get(0);
        double trailHeading = trail.size() < 2 ? 0 : Math.atan2(
                trail.get(1).y() - trail.get(0).y(), trail.get(1).x() - trail.get(0).x());
        return new double[]{oldestPoint.x(), oldestPoint.y(), trailHeading};
    }

    private double heading(RailwayWorld.Train train) {
        if (train.path.size() < 2) {
            return 0;
        }
        int segment = Math.min(train.pathIndex, train.path.size() - 2);
        RailwayWorld.Node from = train.path.get(segment);
        RailwayWorld.Node to = train.path.get(segment + 1);
        return Math.atan2(to.y() - from.y(), to.x() - from.x());
    }

    static double uprightHeading(double heading) {
        if (heading > Math.PI / 2) {
            return heading - Math.PI;
        }
        if (heading < -Math.PI / 2) {
            return heading + Math.PI;
        }
        return heading;
    }

    static boolean needsHorizontalFlip(double heading) {
        return heading > Math.PI / 2 || heading < -Math.PI / 2;
    }

    private void drawCar(Graphics2D g, double x, double y, double angle,
            boolean locomotive, boolean selected) {
        Graphics2D car = (Graphics2D) g.create();
        car.translate(x, y);
        boolean flipped = needsHorizontalFlip(angle);
        car.rotate(uprightHeading(angle));
        if (flipped) {
            car.scale(-1, 1);
        }
        car.setColor(new Color(46, 59, 74));
        car.fillRoundRect(locomotive ? -17 : -13, -9, locomotive ? 34 : 26, 18, 6, 6);
        car.setColor(locomotive && selected ? new Color(207, 150, 62)
                : locomotive ? new Color(59, 138, 186) : new Color(125, 162, 184));
        car.fillRoundRect(locomotive ? -14 : -10, -7, locomotive ? 28 : 20, 14, 4, 4);
        car.setColor(new Color(212, 235, 247));
        car.fillRoundRect(-8, -5, 7, 6, 2, 2);
        car.fillRoundRect(2, -5, 7, 6, 2, 2);
        car.setColor(new Color(45, 57, 72));
        car.fillOval(-9, 6, 6, 6);
        car.fillOval(4, 6, 6, 6);
        if (locomotive) {
            car.setColor(new Color(255, 231, 157));
            car.fillOval(12, -2, 4, 4);
        }
        car.dispose();
    }

    private void drawZoomControls(Graphics2D g) {
        int x = MAP_X + MAP_W - 114;
        int y = MAP_Y + MAP_H - 49;
        g.setColor(new Color(251, 251, 244, 238));
        g.fillRoundRect(x, y, 102, 36, 7, 7);
        g.setColor(new Color(213, 231, 242));
        g.drawRoundRect(x, y, 102, 36, 7, 7);
        drawZoomButton(g, x + 4, y + 4, "-");
        drawZoomButton(g, x + 70, y + 4, "+");
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 10));
        g.drawString(Math.round(zoom * 100) + "%", x + 36, y + 22);
    }

    private void drawZoomButton(Graphics2D g, int x, int y, String text) {
        g.setColor(new Color(241, 248, 252));
        g.fillRoundRect(x, y, 28, 28, 5, 5);
        g.setColor(new Color(209, 231, 242));
        g.drawRoundRect(x, y, 28, 28, 5, 5);
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 17));
        g.drawString(text, x + 9, y + 20);
    }

    private void drawFreightBoard(Graphics2D g) {
        g.setColor(new Color(244, 249, 252));
        g.fillRect(1178, 82, 262, HEIGHT - 82);
        g.setColor(new Color(207, 226, 240));
        g.drawLine(1178, 82, 1178, HEIGHT);
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 14));
        g.drawString("FREIGHT BOARD", BOARD_X, 113);
        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.PLAIN, 9));
        g.drawString("TAKE ORDERS. KEEP THE LINE MOVING.", BOARD_X, 129);

        for (int index = 0; index < world.contracts.size(); index++) {
            RailwayWorld.Contract contract = world.contracts.get(index);
            int y = 146 + index * 132;
            boolean selected = contract == selectedContract;
            g.setColor(selected ? new Color(250, 253, 255) : new Color(245, 245, 234));
            g.fillRoundRect(BOARD_X, y, BOARD_W, 122, 7, 7);
            g.setColor(selected ? new Color(153, 195, 216) : new Color(207, 227, 242));
            g.drawRoundRect(BOARD_X, y, BOARD_W, 122, 7, 7);

            g.setColor(contract.goods.color);
            g.fillRoundRect(BOARD_X + 12, y + 12, 4, 30, 3, 3);
            g.setColor(INK);
            g.setFont(new Font("Dialog", Font.BOLD, 11));
            g.drawString(contract.name, BOARD_X + 24, y + 23);
            g.setColor(statusColor(contract.state));
            g.setFont(new Font("Dialog", Font.BOLD, 8));
            g.drawString(shortStatus(contract.state), BOARD_X + 24, y + 38);

            g.setColor(contract.goods.color);
            g.setFont(new Font("Dialog", Font.BOLD, 11));
            g.drawString(contract.amount + " crates  /  " + contract.goods.label,
                    BOARD_X + 13, y + 59);
            g.setColor(MUTED);
            g.setFont(new Font("Dialog", Font.PLAIN, 9));
            g.drawString(contract.origin.name + "  >  " + contract.destination.name,
                    BOARD_X + 13, y + 76);
            g.setColor(INK);
            g.setFont(new Font("Dialog", Font.BOLD, 11));
            g.drawString("$" + contract.reward, BOARD_X + 13, y + 102);
            g.setColor(MUTED);
            g.setFont(new Font("Dialog", Font.PLAIN, 8));
            g.drawString(String.format("DUE %02d:%02d  /  DAY %d",
                    contract.deadlineMinute / 60, contract.deadlineMinute % 60,
                    contract.deadlineDay), BOARD_X + 66, y + 102);

            if (contract.state == RailwayWorld.ContractState.AVAILABLE) {
                g.setColor(new Color(57, 133, 180));
                g.fillRoundRect(BOARD_X + 160, y + 87, 70, 24, 5, 5);
                g.setColor(Color.WHITE);
                g.setFont(new Font("Dialog", Font.BOLD, 9));
                g.drawString("ACCEPT", BOARD_X + 177, y + 103);
            } else {
                int progress = contract.amount == 0 ? 0 : 132 * contract.delivered / contract.amount;
                g.setColor(new Color(234, 245, 252));
                g.fillRoundRect(BOARD_X + 124, y + 93, 103, 5, 3, 3);
                if (progress > 0) {
                    g.setColor(statusColor(contract.state));
                    g.fillRoundRect(BOARD_X + 124, y + 93, Math.min(103, progress), 5, 3, 3);
                }
            }
        }

        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.PLAIN, 9));
        g.drawString("Orders pay on delivery.", BOARD_X, 691);
    }

    private void drawRoster(Graphics2D g) {
        g.setColor(new Color(249, 252, 255));
        g.fillRect(244, 738, WIDTH - 244, HEIGHT - 738);
        g.setColor(new Color(207, 226, 240));
        g.drawLine(244, 738, WIDTH, 738);
        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("ACTIVE LOCOMOTIVES", 266, 760);

        for (int index = 0; index < world.trains.size() && index < 3; index++) {
            RailwayWorld.Train train = world.trains.get(index);
            int x = 266 + index * 301;
            boolean active = train == selectedTrain;
            g.setColor(active ? new Color(226, 242, 250) : new Color(252, 251, 245));
            g.fillRoundRect(x, 772, 284, 56, 6, 6);
            g.setColor(active ? new Color(166, 205, 223) : new Color(234, 245, 252));
            g.drawRoundRect(x, 772, 284, 56, 6, 6);
            g.setColor(active ? BLUE : MUTED);
            g.fillOval(x + 12, 786, 8, 8);
            g.setColor(INK);
            g.setFont(new Font("Dialog", Font.BOLD, 11));
            g.drawString(train.name, x + 29, 795);
            g.setColor(MUTED);
            g.setFont(new Font("Dialog", Font.PLAIN, 9));
            g.drawString("To " + world.nextStopName(train) + "    " + train.load()
                    + "/" + train.carriages * 4 + " crates", x + 29, 813);
            if (active) {
                g.setColor(BLUE);
                g.setFont(new Font("Dialog", Font.BOLD, 8));
                g.drawString("SELECTED", x + 218, 793);
            }
        }

        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.PLAIN, 9));
        String shownMessage = System.currentTimeMillis() - messageAt > 9000
                ? "Click an order to inspect it. Accepted freight follows your train timetables."
                : message;
        g.drawString(shownMessage, 266, 858);
        g.drawString("1-6 TOOLS   SPACE PAUSE   ESC FINISH TRACK", 1080, 858);
    }

    private void drawCentered(Graphics2D g, String text, int centerX, int y, int maxWidth) {
        FontMetrics metrics = g.getFontMetrics();
        while (metrics.stringWidth(text) > maxWidth && text.length() > 4) {
            text = text.substring(0, text.length() - 1);
            metrics = g.getFontMetrics();
        }
        g.drawString(text, centerX - metrics.stringWidth(text) / 2, y);
    }

    private Color statusColor(RailwayWorld.ContractState state) {
        return switch (state) {
            case AVAILABLE -> new Color(105, 127, 144);
            case ACCEPTED, LOADING -> new Color(179, 128, 55);
            case IN_TRANSIT -> new Color(58, 116, 145);
            case COMPLETE -> new Color(52, 145, 195);
            case EXPIRED -> RUST;
        };
    }

    private String shortStatus(RailwayWorld.ContractState state) {
        return switch (state) {
            case AVAILABLE -> "NEW ORDER";
            case ACCEPTED -> "ACCEPTED";
            case LOADING -> "LOADING";
            case IN_TRANSIT -> "IN TRANSIT";
            case COMPLETE -> "DELIVERED";
            case EXPIRED -> "EXPIRED";
        };
    }

    private void drawHeaderButton(Graphics2D g, int x, int y, int width, int height,
            String label, boolean active) {
        g.setColor(active ? new Color(66, 145, 195) : NIGHT_LIGHT);
        g.fillRoundRect(x, y, width, height, 7, 7);
        g.setColor(new Color(123, 167, 197));
        g.drawRoundRect(x, y, width, height, 7, 7);
        g.setColor(PAPER);
        g.setFont(new Font("Dialog", Font.BOLD, 10));
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(label, x + (width - metrics.stringWidth(label)) / 2, y + 25);
    }

    private void drawToolGlyph(Graphics2D g, Tool tool, int x, int y, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (tool) {
            case CURSOR -> {
                g.drawLine(x - 7, y - 11, x + 7, y + 3);
                g.drawLine(x + 7, y + 3, x + 1, y + 3);
                g.drawLine(x + 1, y + 3, x + 4, y + 10);
                g.drawLine(x + 4, y + 10, x + 1, y + 12);
                g.drawLine(x + 1, y + 12, x - 2, y + 5);
                g.drawLine(x - 2, y + 5, x - 6, y + 9);
                g.drawLine(x - 6, y + 9, x - 7, y - 11);
            }
            case TRACK -> {
                g.drawLine(x - 8, y + 7, x + 8, y - 7);
                g.drawLine(x - 8, y + 2, x + 8, y - 12);
                g.drawLine(x - 4, y + 3, x - 1, y + 7);
                g.drawLine(x + 3, y - 3, x + 6, y + 1);
            }
            case SIGNAL -> {
                g.drawLine(x, y + 10, x, y - 8);
                g.drawOval(x - 4, y - 12, 8, 8);
                g.fillOval(x - 1, y - 9, 3, 3);
            }
            case STATION -> {
                g.drawRect(x - 8, y - 7, 16, 14);
                g.drawLine(x - 5, y + 6, x - 5, y - 4);
                g.drawLine(x + 5, y + 6, x + 5, y - 4);
            }
            case TRAIN -> {
                g.drawRoundRect(x - 9, y - 5, 18, 11, 4, 4);
                g.drawLine(x - 5, y - 2, x + 2, y - 2);
                g.fillOval(x - 6, y + 4, 4, 4);
                g.fillOval(x + 3, y + 4, 4, 4);
            }
            case SCHEDULE -> {
                g.drawRect(x - 7, y - 9, 14, 18);
                g.drawLine(x - 3, y - 4, x + 3, y - 4);
                g.drawLine(x - 3, y, x + 3, y);
                g.drawLine(x - 3, y + 4, x + 3, y + 4);
            }
            case DEMOLISH -> {
                g.drawLine(x - 8, y - 6, x + 8, y - 6);
                g.drawLine(x - 5, y - 9, x + 5, y - 9);
                g.drawLine(x - 6, y - 5, x - 4, y + 8);
                g.drawLine(x + 6, y - 5, x + 4, y + 8);
                g.drawLine(x - 4, y + 8, x + 4, y + 8);
                g.drawLine(x - 2, y - 2, x - 1, y + 5);
                g.drawLine(x + 2, y - 2, x + 1, y + 5);
            }
        }
    }

    private void drawGuide(Graphics2D g) {
        g.setColor(new Color(14, 25, 41, 165));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        int x = 220;
        int y = 130;
        int width = 1000;
        int height = 640;
        g.setColor(new Color(250, 249, 241));
        g.fillRoundRect(x, y, width, height, 12, 12);
        g.setColor(new Color(207, 226, 240));
        g.drawRoundRect(x, y, width, height, 12, 12);
        g.setColor(NIGHT);
        g.fillRoundRect(x, y, width, 70, 12, 12);
        g.fillRect(x, y + 48, width, 22);
        g.setColor(PAPER);
        g.setFont(new Font("Dialog", Font.BOLD, 18));
        g.drawString("FIELD GUIDE", x + 25, y + 32);
        g.setColor(new Color(183, 216, 230));
        g.setFont(new Font("Dialog", Font.PLAIN, 10));
        g.drawString("RAILWAY OPERATIONS MANUAL", x + 26, y + 51);
        drawHeaderButton(g, x + width - 62, y + 18, 39, 35, "X", false);

        g.setColor(new Color(244, 249, 252));
        g.fillRoundRect(x + 13, y + 84, 207, height - 99, 8, 8);
        for (int index = 0; index < GuideTopic.values().length; index++) {
            GuideTopic topic = GuideTopic.values()[index];
            int rowY = y + 81 + index * 43;
            boolean active = topic == guideTopic;
            g.setColor(active ? new Color(220, 239, 249) : new Color(244, 249, 252));
            g.fillRoundRect(x + 20, rowY + 3, 193, 37, 6, 6);
            if (active) {
                g.setColor(BLUE);
                g.fillRoundRect(x + 20, rowY + 11, 3, 20, 2, 2);
            }
            g.setColor(active ? INK : MUTED);
            g.setFont(new Font("Dialog", active ? Font.BOLD : Font.PLAIN, 11));
            g.drawString(topic.label, x + 34, rowY + 26);
        }

        int contentX = x + 252;
        int contentY = y + 125;
        int contentWidth = 710;
        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.BOLD, 9));
        g.drawString("FIELD GUIDE  /  " + guideTopic.label.toUpperCase(Locale.ROOT),
                contentX, contentY - 22);
        g.setColor(INK);
        g.setFont(new Font("Dialog", Font.BOLD, 23));
        g.drawString(guideTopic.title, contentX, contentY + 14);
        g.setColor(new Color(215, 231, 240));
        g.drawLine(contentX, contentY + 31, contentX + contentWidth, contentY + 31);

        g.setColor(new Color(50, 74, 96));
        g.setFont(new Font("Dialog", Font.PLAIN, 14));
        int textY = contentY + 67;
        for (String paragraph : guideTopic.body.split("\\n")) {
            if (paragraph.isBlank()) {
                textY += 13;
            } else {
                textY = drawGuideParagraph(g, paragraph, contentX, textY, contentWidth, 22);
            }
        }

        g.setColor(MUTED);
        g.setFont(new Font("Dialog", Font.PLAIN, 9));
        g.drawString("Choose a topic on the left  /  Press Esc or click X to close",
                contentX, y + height - 24);
    }

    private int drawGuideParagraph(Graphics2D g, String paragraph, int x, int y,
            int maxWidth, int lineHeight) {
        FontMetrics metrics = g.getFontMetrics();
        StringBuilder line = new StringBuilder();
        for (String word : paragraph.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) > maxWidth && !line.isEmpty()) {
                g.drawString(line.toString(), x, y);
                y += lineHeight;
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            g.drawString(line.toString(), x, y);
            y += lineHeight;
        }
        return y;
    }

    enum Tool {
        CURSOR, TRACK, SIGNAL, STATION, TRAIN, SCHEDULE, DEMOLISH
    }

        enum GuideTopic {
        OVERVIEW("Overview", "Run a regional freight railway",
            "The game opens in Cursor mode so map clicks select trains and stations without building anything. Choose a tool when you are ready to act.\n\n"
            + "Accept a contract, make sure a train visits its origin and destination, then collect the delivery payment. Juniper starts with timber aboard."),
        TRACK("Track", "Build connected rail anywhere on the map",
            "Choose Lay track, click a starting point, then click an endpoint. Keep clicking to extend the line; press Escape to finish.\n\n"
            + "Track costs $12 per section. Nearby endpoints snap together and crossings connect as junctions. Rails occupied by a train or its carriages cannot be removed."),
        STATIONS("Stations", "Found stops and produce goods",
            "Choose Found station and click anywhere on the map. A station close to a rail snaps to it; otherwise, build track to connect it later.\n\n"
            + "Stations produce their listed good over time. Trains load accepted contract cargo when they stop at its origin, and unload it at the destination."),
        TRAINS("Trains & cars", "Deploy locomotives and increase capacity",
            "Choose Buy locomotive and click near a rail. A train starts with two carriages and carries four crates per carriage.\n\n"
            + "Use + CAR in its details to add capacity for $85, up to ten carriages. Click a locomotive on the map or in the roster to select it."),
        TIMETABLE("Timetables", "Choose and order station stops",
                "Select Set timetable, then click stations on the map to add them to the selected train's route. A timetable can have up to 20 stops.\n\n"
            + "Use the row arrows to reorder stops and x to remove one. Repeat toggles between a continuous circuit and a one-shot route. Changes do not interrupt the current leg."),
        FREIGHT("Freight contracts", "Turn deliveries into company funds",
            "Click an available order on the Freight Board to accept it. Check its goods, origin, destination, crate count, reward, and deadline.\n\n"
            + "Schedule a train to visit both ends. The origin must have stock and the train needs enough free capacity. Orders pay when every crate is delivered; missed deadlines expire."),
        SIGNALS("Block signals", "Keep trains from sharing a protected section",
            "Choose Block signals and click near a rail to place or remove a signal. Signals cost $55.\n\n"
            + "A train waits if another train is already on the same track section next to a signal. Unsignalled sections do not get this protection."),
        DEMOLITION("Remove objects", "Retire or dismantle railway assets",
            "Choose Remove objects, then click a locomotive, station, signal, or rail section. Removed assets return a partial refund.\n\n"
            + "Trains carrying freight and the last locomotive cannot be sold. Stations used by active orders or train routes, and track used by trains or carriages, are protected."),
        SAVING("Save & load", "Keep a railway and resume it later",
            "Use Save and Load in the header, or press Ctrl+S and Ctrl+O. Save files use the .lmsave extension.\n\n"
            + "A save includes your map, stations, stock, contracts, schedules, train positions, cargo, and current view. Loading asks before replacing the current railway."),
        MAP("Map & controls", "Navigate the map and operate the clock",
            "Scroll over the map or use its +/− buttons to zoom. Right-drag to pan. The map stays proportional when the window is resized.\n\n"
            + "Press 1 for Cursor and 2–7 for railway tools, Ctrl+N to start a fresh railway, Space to pause or resume, and F1 to open this guide. Press Escape to close the guide or finish drawing track.");

        final String label;
        final String title;
        final String body;

        GuideTopic(String label, String title, String body) {
            this.label = label;
            this.title = title;
            this.body = body;
        }
        }
}