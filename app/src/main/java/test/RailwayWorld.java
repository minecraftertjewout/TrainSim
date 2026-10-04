package test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RailwayWorld {
    static final int WIDTH = 3200;
    static final int HEIGHT = 2200;
    static final int TRACK_COST = 12;
    static final int SIGNAL_COST = 55;
    static final int STATION_COST = 175;
    static final int TRAIN_COST = 425;
    static final int CARRIAGE_COST = 85;
    static final int MAX_SCHEDULE_STOPS = 20;
    private static final int SAVE_MAGIC = 0x4c4d5252;
    private static final int SAVE_VERSION = 2;
    private static final int MAX_SAVED_ENTRIES = 100_000;
    private static final double MAX_TRAIL_LENGTH = 1200;
    private static final int MAX_VISIBLE_CONTRACTS = 4;
    private static final int CONTRACT_REPLENISH_THRESHOLD = 2;

    final List<Edge> tracks = new ArrayList<>();
    final List<Station> stations = new ArrayList<>();
    final Set<Node> signals = new LinkedHashSet<>();
    final List<Train> trains = new ArrayList<>();
    final List<Contract> contracts = new ArrayList<>();
    int cash = 1450;
    int day = 1;
    int minutes = 8 * 60;
    int deliveries;
    int clockTicks;
    int nextStationId = 1;
    int nextTrainId = 2;

    RailwayWorld() {
        Node northfield = new Node(175, 440);
        Node central = new Node(490, 440);
        Node harbor = new Node(1010, 440);
        Node southernJunction = new Node(490, 760);
        Node meadow = new Node(700, 760);
        Node eastJunction = new Node(1010, 760);
        Node quarry = new Node(1325, 760);
        addInitialTrack(northfield, central);
        addInitialTrack(central, harbor);
        addInitialTrack(central, southernJunction);
        addInitialTrack(southernJunction, meadow);
        addInitialTrack(meadow, eastJunction);
        addInitialTrack(eastJunction, harbor);
        addInitialTrack(eastJunction, quarry);

        Station pine = addInitialStation(northfield, "Northfield", Goods.TIMBER);
        Station hub = addInitialStation(central, "Cedar Junction", Goods.MAIL);
        Station port = addInitialStation(harbor, "Salt Wharf", Goods.FOOD);
        Station park = addInitialStation(meadow, "Fern Valley", Goods.FOOD);
        Station mine = addInitialStation(quarry, "Redstone Quarry", Goods.ORE);

        contracts.add(new Contract(1, "Coastal lumber", pine, port, Goods.TIMBER, 6, 360, minutes + 480, true));
        contracts.add(new Contract(2, "Market provisions", port, park, Goods.FOOD, 5, 315, minutes + 690, false));
        contracts.add(new Contract(3, "The morning post", hub, port, Goods.MAIL, 4, 280, minutes + 810, false));
        contracts.add(new Contract(4, "Foundry supply", mine, hub, Goods.ORE, 5, 410, minutes + 990, false));

        signals.add(central);
        signals.add(eastJunction);

        Node initialPosition = snapToTrack(new Node(280, 440), 0).orElseThrow();
        Train train = new Train(1, "Juniper", initialPosition);
        train.railTrail.add(northfield);
        train.railTrail.add(initialPosition);
        train.stops.addAll(List.of(harbor, northfield));
        trains.add(train);
        dispatchNextStop(train);
        loadAtStation(train, pine);
    }

    private RailwayWorld(boolean empty) {
    }

    void save(Path path, ViewState view) throws IOException {
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "last-mile-", ".tmp");
        boolean moved = false;
        try {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(SAVE_MAGIC);
                output.writeInt(SAVE_VERSION);
                output.writeInt(cash);
                output.writeInt(day);
                output.writeInt(minutes);
                output.writeInt(clockTicks);
                output.writeInt(deliveries);
                output.writeInt(nextStationId);
                output.writeInt(nextTrainId);

                output.writeInt(tracks.size());
                for (Edge edge : tracks) {
                    writeNode(output, edge.first);
                    writeNode(output, edge.second);
                }

                output.writeInt(stations.size());
                for (Station station : stations) {
                    output.writeInt(station.id);
                    writeNode(output, station.position);
                    output.writeUTF(station.name);
                    output.writeInt(station.produces.ordinal());
                    for (Goods goods : Goods.values()) {
                        output.writeInt(station.stockOf(goods));
                    }
                }

                output.writeInt(signals.size());
                for (Node signal : signals) {
                    writeNode(output, signal);
                }

                output.writeInt(contracts.size());
                for (Contract contract : contracts) {
                    output.writeInt(contract.id);
                    output.writeUTF(contract.name);
                    output.writeInt(contract.origin.id);
                    output.writeInt(contract.destination.id);
                    output.writeInt(contract.goods.ordinal());
                    output.writeInt(contract.amount);
                    output.writeInt(contract.reward);
                    output.writeInt(contract.deadlineMinute);
                    output.writeInt(contract.deadlineDay);
                    output.writeInt(contract.loaded);
                    output.writeInt(contract.delivered);
                    output.writeInt(contract.state.ordinal());
                }

                output.writeInt(trains.size());
                for (Train train : trains) {
                    output.writeInt(train.id);
                    output.writeUTF(train.name);
                    writeNode(output, train.position);
                    writeNode(output, train.destination);
                    writeNodes(output, train.stops);
                    writeNodes(output, train.path);
                    writeNodes(output, train.railTrail);
                    output.writeInt(train.pathIndex);
                    output.writeInt(train.scheduleIndex);
                    output.writeInt(train.dwellTicks);
                    output.writeInt(train.carriages);
                    output.writeBoolean(train.looping);
                    output.writeDouble(train.progress);
                    output.writeInt(train.cargo.size());
                    for (Shipment shipment : train.cargo) {
                        output.writeInt(shipment.contract.id);
                        output.writeInt(shipment.amount);
                    }
                }

                output.writeDouble(view.cameraX);
                output.writeDouble(view.cameraY);
                output.writeDouble(view.zoom);
                output.writeInt(view.selectedTrainId);
                output.writeInt(view.selectedContractId);
                output.writeBoolean(view.running);
                output.writeBoolean(view.fastForward);
            }

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static LoadedGame load(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != SAVE_MAGIC || (version != 1 && version != SAVE_VERSION)) {
                throw new IOException("This is not a supported Last Mile save file.");
            }

            RailwayWorld world = new RailwayWorld(true);
            world.cash = readNonNegative(input, "company funds");
            world.day = readBounded(input, 1, 1_000_000, "day");
            world.minutes = readBounded(input, 0, 1439, "railway time");
            world.clockTicks = readBounded(input, 0, 9, "clock ticks");
            world.deliveries = readNonNegative(input, "deliveries");
            world.nextStationId = readBounded(input, 1, Integer.MAX_VALUE, "next station id");
            world.nextTrainId = readBounded(input, 1, Integer.MAX_VALUE, "next train id");

            int trackCount = readCount(input, "track sections");
            for (int index = 0; index < trackCount; index++) {
                Node first = readNode(input);
                Node second = readNode(input);
                if (first.equals(second) || world.tracks.contains(new Edge(first, second))) {
                    throw new IOException("The save file contains invalid track sections.");
                }
                world.tracks.add(new Edge(first, second));
            }

            Map<Integer, Station> stationsById = new HashMap<>();
            int stationCount = readCount(input, "stations");
            for (int index = 0; index < stationCount; index++) {
                int id = readBounded(input, 1, Integer.MAX_VALUE, "station id");
                Node position = readNode(input);
                String name = input.readUTF();
                Goods produces = readEnum(input, Goods.values(), "station goods");
                Station station = new Station(id, position, name, produces);
                for (Goods goods : Goods.values()) {
                    station.stock.put(goods, readBounded(input, 0, 1_000_000, "station stock"));
                }
                if (stationsById.put(id, station) != null) {
                    throw new IOException("The save file contains duplicate station ids.");
                }
                world.stations.add(station);
            }

            int signalCount = readCount(input, "signals");
            for (int index = 0; index < signalCount; index++) {
                if (!world.signals.add(readNode(input))) {
                    throw new IOException("The save file contains duplicate signals.");
                }
            }

            Map<Integer, Contract> contractsById = new HashMap<>();
            int contractCount = readCount(input, "contracts");
            for (int index = 0; index < contractCount; index++) {
                int id = readBounded(input, 1, Integer.MAX_VALUE, "contract id");
                String name = input.readUTF();
                Station origin = stationsById.get(input.readInt());
                Station destination = stationsById.get(input.readInt());
                Goods goods = readEnum(input, Goods.values(), "contract goods");
                int amount = readBounded(input, 1, 1_000_000, "contract amount");
                int reward = readNonNegative(input, "contract reward");
                int deadlineMinute = readBounded(input, 0, 1439, "contract deadline");
                int deadlineDay = readBounded(input, 1, 1_000_000, "contract deadline day");
                int loaded = readBounded(input, 0, amount, "loaded freight");
                int delivered = readBounded(input, 0, amount, "delivered freight");
                ContractState state = readEnum(input, ContractState.values(), "contract status");
                if (origin == null || destination == null) {
                    throw new IOException("A contract references a missing station.");
                }
                Contract contract = new Contract(id, name, origin, destination, goods, amount,
                        reward, (deadlineDay - 1) * 1440 + deadlineMinute, false);
                contract.loaded = loaded;
                contract.delivered = delivered;
                contract.state = state;
                if (contractsById.put(id, contract) != null) {
                    throw new IOException("The save file contains duplicate contract ids.");
                }
                world.contracts.add(contract);
            }

            int trainCount = readCount(input, "trains");
            for (int index = 0; index < trainCount; index++) {
                int id = readBounded(input, 1, Integer.MAX_VALUE, "train id");
                String name = input.readUTF();
                Train train = new Train(id, name, readNode(input));
                train.destination = readNode(input);
                train.stops.addAll(readNodes(input, MAX_SCHEDULE_STOPS, "timetable stops"));
                for (Node stop : train.stops) {
                    if (world.stationAt(stop) == null) {
                        throw new IOException("A train timetable references a missing station.");
                    }
                }
                train.path = readNodes(input, MAX_SAVED_ENTRIES, "active train route");
                if (version >= 2) {
                    train.railTrail.addAll(readNodes(input, MAX_SAVED_ENTRIES, "train rail history"));
                    if (train.railTrail.isEmpty()
                            || !train.railTrail.get(train.railTrail.size() - 1).equals(train.position)) {
                        throw new IOException("The save file contains invalid train rail history.");
                    }
                } else {
                    world.seedTrail(train);
                }
                train.pathIndex = readBounded(input, 0, MAX_SAVED_ENTRIES, "route progress");
                train.scheduleIndex = readBounded(input, 0, MAX_SCHEDULE_STOPS, "timetable progress");
                train.dwellTicks = readNonNegative(input, "station dwell");
                train.carriages = readBounded(input, 1, 10, "carriages");
                train.looping = input.readBoolean();
                train.progress = input.readDouble();
                if (!Double.isFinite(train.progress) || train.progress < 0 || train.progress >= 1) {
                    throw new IOException("The save file contains invalid train movement.");
                }
                int shipmentCount = readCount(input, "shipments");
                for (int shipmentIndex = 0; shipmentIndex < shipmentCount; shipmentIndex++) {
                    Contract contract = contractsById.get(input.readInt());
                    int amount = readBounded(input, 1, 1_000_000, "shipment amount");
                    if (contract == null) {
                        throw new IOException("A train shipment references a missing contract.");
                    }
                    train.cargo.add(new Shipment(contract, amount));
                }
                world.trains.add(train);
            }

            ViewState view = new ViewState(input.readDouble(), input.readDouble(), input.readDouble(),
                    input.readInt(), input.readInt(), input.readBoolean(), input.readBoolean());
            if (!Double.isFinite(view.cameraX) || !Double.isFinite(view.cameraY)
                    || !Double.isFinite(view.zoom) || view.cameraX < 0 || view.cameraX > WIDTH
                    || view.cameraY < 0 || view.cameraY > HEIGHT || view.zoom < 0.35 || view.zoom > 2.4
                    || world.trains.stream().noneMatch(train -> train.id == view.selectedTrainId)
                    || world.contracts.stream().noneMatch(contract -> contract.id == view.selectedContractId)) {
                throw new IOException("The save file contains invalid map or selection settings.");
            }
            if (input.read() != -1) {
                throw new IOException("The save file contains unexpected trailing data.");
            }
            return new LoadedGame(world, view);
        } catch (EOFException | IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new IOException("The save file is incomplete or damaged.", exception);
        }
    }

    private static void writeNode(DataOutputStream output, Node node) throws IOException {
        output.writeDouble(node.x);
        output.writeDouble(node.y);
    }

    private static void writeNodes(DataOutputStream output, List<Node> nodes) throws IOException {
        output.writeInt(nodes.size());
        for (Node node : nodes) {
            writeNode(output, node);
        }
    }

    private static Node readNode(DataInputStream input) throws IOException {
        double x = input.readDouble();
        double y = input.readDouble();
        Node node = new Node(x, y);
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > WIDTH || y < 0 || y > HEIGHT) {
            throw new IOException("The save file contains an invalid map position.");
        }
        return node;
    }

    private static List<Node> readNodes(DataInputStream input, int maximum, String description)
            throws IOException {
        int count = readBounded(input, 0, maximum, description);
        ArrayList<Node> nodes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            nodes.add(readNode(input));
        }
        return nodes;
    }

    private static int readCount(DataInputStream input, String description) throws IOException {
        return readBounded(input, 0, MAX_SAVED_ENTRIES, description);
    }

    private static int readNonNegative(DataInputStream input, String description) throws IOException {
        return readBounded(input, 0, Integer.MAX_VALUE, description);
    }

    private static int readBounded(DataInputStream input, int minimum, int maximum, String description)
            throws IOException {
        int value = input.readInt();
        if (value < minimum || value > maximum) {
            throw new IOException("Invalid " + description + " in save file.");
        }
        return value;
    }

    private static <T> T readEnum(DataInputStream input, T[] values, String description) throws IOException {
        int index = readBounded(input, 0, values.length - 1, description);
        return values[index];
    }

    record ViewState(double cameraX, double cameraY, double zoom, int selectedTrainId,
            int selectedContractId, boolean running, boolean fastForward) {
    }

    record LoadedGame(RailwayWorld world, ViewState view) {
    }

    boolean buildTrack(Node from, Node to) {
        if (cash < TRACK_COST || !addTrack(from, to)) {
            return false;
        }
        cash -= TRACK_COST;
        return true;
    }

    boolean addTrack(Node from, Node to) {
        if (!insideWorld(from) || !insideWorld(to) || from.equals(to)) {
            return false;
        }
        Edge proposed = new Edge(from, to);
        if (tracks.contains(proposed)) {
            return false;
        }

        ArrayList<Node> points = new ArrayList<>(List.of(from, to));
        ArrayList<Edge> split = new ArrayList<>();
        ArrayList<Node> crossings = new ArrayList<>();
        for (Edge track : tracks) {
            Node crossing = intersection(proposed, track);
            if (crossing != null) {
                points.add(crossing);
                if (!crossing.equals(track.first) && !crossing.equals(track.second)) {
                    split.add(track);
                    crossings.add(crossing);
                }
            }
        }
        for (int index = 0; index < split.size(); index++) {
            Edge track = split.get(index);
            Node crossing = crossings.get(index);
            tracks.remove(track);
            tracks.add(new Edge(track.first, crossing));
            tracks.add(new Edge(crossing, track.second));
        }
        points.sort((left, right) -> Double.compare(parameter(proposed, left), parameter(proposed, right)));
        for (int index = 0; index < points.size() - 1; index++) {
            Edge section = new Edge(points.get(index), points.get(index + 1));
            if (!section.first.equals(section.second) && !tracks.contains(section)) {
                tracks.add(section);
            }
        }
        return true;
    }

    DemolitionResult demolishAt(Node point, double radius) {
        Station station = nearestStation(point, radius);
        if (station != null) {
            return demolishStation(station);
        }
        Node signal = nearestSignal(point, radius);
        if (signal != null) {
            signals.remove(signal);
            cash += SIGNAL_COST / 2;
            return DemolitionResult.SIGNAL_REMOVED;
        }
        Edge track = nearestTrack(point, radius);
        if (track == null) {
            return DemolitionResult.NOTHING_THERE;
        }
        if (trackInUse(track)) {
            return DemolitionResult.TRACK_IN_USE;
        }
        tracks.remove(track);
        signals.removeIf(signalNode -> !hasTrackAt(signalNode));
        cash += TRACK_COST / 2;
        return DemolitionResult.TRACK_REMOVED;
    }

    DemolitionResult demolishTrain(Train train) {
        if (train == null || !trains.contains(train)) {
            return DemolitionResult.NOTHING_THERE;
        }
        if (trains.size() == 1) {
            return DemolitionResult.LAST_TRAIN;
        }
        if (!train.cargo.isEmpty()) {
            return DemolitionResult.TRAIN_CARRYING_CARGO;
        }
        trains.remove(train);
        cash += TRAIN_COST / 2;
        return DemolitionResult.TRAIN_REMOVED;
    }

    private DemolitionResult demolishStation(Station station) {
        for (Contract contract : contracts) {
                if ((contract.state == ContractState.ACCEPTED || contract.state == ContractState.LOADING
                    || contract.state == ContractState.IN_TRANSIT)
                    && (contract.origin == station || contract.destination == station)) {
                return DemolitionResult.STATION_IN_USE;
            }
        }
        for (Train train : trains) {
            if (train.stops.contains(station.position) || train.path.contains(station.position)
                    || train.destination.equals(station.position)) {
                return DemolitionResult.STATION_IN_USE;
            }
        }
        stations.remove(station);
        contracts.removeIf(contract -> contract.origin == station || contract.destination == station);
        cash += STATION_COST / 2;
        return DemolitionResult.STATION_REMOVED;
    }

    private boolean trackInUse(Edge track) {
        for (Train train : trains) {
            for (int index = 0; index < train.path.size() - 1; index++) {
                if (track.equals(new Edge(train.path.get(index), train.path.get(index + 1)))) {
                    return true;
                }
            }
            double trailingLength = train.carriages * 31.0;
            for (int index = train.railTrail.size() - 1; index > 0 && trailingLength > 0; index--) {
                Node end = train.railTrail.get(index);
                Node start = train.railTrail.get(index - 1);
                if (track.equals(new Edge(start, end))) {
                    return true;
                }
                trailingLength -= start.distance(end);
            }
        }
        return false;
    }

    private boolean hasTrackAt(Node point) {
        for (Edge track : tracks) {
            if (track.first.equals(point) || track.second.equals(point)) {
                return true;
            }
        }
        return false;
    }

    private Node nearestSignal(Node point, double radius) {
        Node nearest = null;
        double nearestDistance = radius;
        for (Node signal : signals) {
            double distance = signal.distance(point);
            if (distance <= nearestDistance) {
                nearest = signal;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private Edge nearestTrack(Node point, double radius) {
        Edge nearest = null;
        double nearestDistance = radius;
        for (Edge track : tracks) {
            double distance = point.distance(project(point, track));
            if (distance <= nearestDistance) {
                nearest = track;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    Station buildStation(Node point) {
        if (cash < STATION_COST || !insideWorld(point)) {
            return null;
        }
        Node position = snapToTrack(point, 24).orElse(point);
        if (stationAt(position) != null) {
            return null;
        }
        ArrayList<Station> existingStations = new ArrayList<>(stations);
        cash -= STATION_COST;
        int id = nextStationId++;
        Station station = new Station(id, position, "Siding " + id, Goods.MAIL);
        station.stock.put(Goods.MAIL, 8);
        stations.add(station);
        generateStationContracts(station, existingStations);
        return station;
    }

    boolean toggleSignal(Node point) {
        var position = snapToTrack(point, 24);
        if (position.isEmpty()) {
            return false;
        }
        if (signals.remove(position.get())) {
            cash += SIGNAL_COST / 2;
            return true;
        }
        if (cash < SIGNAL_COST) {
            return false;
        }
        cash -= SIGNAL_COST;
        signals.add(position.get());
        return true;
    }

    Train deployTrain(Node point) {
        if (cash < TRAIN_COST) {
            return null;
        }
        var position = snapToTrack(point, 28);
        if (position.isEmpty()) {
            return null;
        }
        cash -= TRAIN_COST;
        Train train = new Train(nextTrainId, "Local " + String.format("%02d", nextTrainId), position.get());
        seedTrail(train);
        nextTrainId++;
        trains.add(train);
        return train;
    }

    boolean addCarriage(Train train) {
        if (!trains.contains(train) || train.carriages >= 10 || cash < CARRIAGE_COST) {
            return false;
        }
        cash -= CARRIAGE_COST;
        train.carriages++;
        return true;
    }

    boolean acceptContract(Contract contract) {
        if (!contracts.contains(contract) || contract.state != ContractState.AVAILABLE) {
            return false;
        }
        contract.state = ContractState.ACCEPTED;
        return true;
    }

    boolean addScheduleStop(Train train, Station station) {
        if (!trains.contains(train) || !stations.contains(station)
                || train.stops.size() >= MAX_SCHEDULE_STOPS || train.stops.contains(station.position)) {
            return false;
        }
        train.stops.add(station.position);
        if (train.path.isEmpty()) {
            dispatchNextStop(train);
        }
        return true;
    }

    boolean moveScheduleStop(Train train, int index, int direction) {
        int destinationIndex = index + direction;
        if (!trains.contains(train) || index < 0 || index >= train.stops.size()
                || destinationIndex < 0 || destinationIndex >= train.stops.size()) {
            return false;
        }
        Node nextStop = nextScheduledStop(train);
        Node currentDestination = train.destination;
        boolean onActiveLeg = !train.path.isEmpty() && train.pathIndex < train.path.size() - 1;
        Node moved = train.stops.remove(index);
        train.stops.add(destinationIndex, moved);

        if (onActiveLeg && train.stops.contains(currentDestination)) {
            train.scheduleIndex = indexAfter(train.stops.indexOf(currentDestination), train);
        } else if (nextStop != null && train.stops.contains(nextStop)) {
            train.scheduleIndex = train.stops.indexOf(nextStop);
        } else {
            train.scheduleIndex = normalizeScheduleIndex(Math.min(index, train.stops.size()), train);
            if (train.path.isEmpty()) {
                dispatchNextStop(train);
            }
        }
        return true;
    }

    boolean removeScheduleStop(Train train, int index) {
        if (!trains.contains(train) || index < 0 || index >= train.stops.size()) {
            return false;
        }
        Node removed = train.stops.get(index);
        Node nextStop = nextScheduledStop(train);
        boolean onActiveLeg = !train.path.isEmpty() && train.pathIndex < train.path.size() - 1;
        boolean removingActiveDestination = onActiveLeg && train.destination.equals(removed);
        train.stops.remove(index);

        if (train.stops.isEmpty()) {
            train.scheduleIndex = 0;
            if (!onActiveLeg) {
                train.path = List.of();
                train.destination = train.position;
            }
        } else if (removingActiveDestination) {
            train.scheduleIndex = normalizeScheduleIndex(index, train);
        } else if (onActiveLeg && train.stops.contains(train.destination)) {
            train.scheduleIndex = indexAfter(train.stops.indexOf(train.destination), train);
        } else if (nextStop != null && train.stops.contains(nextStop)) {
            train.scheduleIndex = train.stops.indexOf(nextStop);
        } else {
            train.scheduleIndex = normalizeScheduleIndex(index, train);
            if (!onActiveLeg) {
                dispatchNextStop(train);
            }
        }
        return true;
    }

    void removeLastScheduleStop(Train train) {
        if (train != null) {
            removeScheduleStop(train, train.stops.size() - 1);
        }
    }

    void setScheduleLoop(Train train, boolean looping) {
        if (!trains.contains(train)) {
            return;
        }
        boolean onActiveLeg = !train.path.isEmpty() && train.pathIndex < train.path.size() - 1;
        Node currentDestination = train.destination;
        train.looping = looping;
        int currentIndex = train.stops.indexOf(currentDestination);
        if (onActiveLeg && currentIndex >= 0) {
            train.scheduleIndex = indexAfter(currentIndex, train);
        } else if (looping && !train.stops.isEmpty() && train.scheduleIndex >= train.stops.size()) {
            train.scheduleIndex = 0;
        }
        if (train.path.isEmpty()) {
            dispatchNextStop(train);
        }
    }

    void advanceTicks(int count) {
        for (int index = 0; index < count; index++) {
            tick();
        }
    }

    void tick() {
        clockTicks++;
        if (clockTicks >= 10) {
            clockTicks = 0;
            minutes++;
            if (minutes >= 24 * 60) {
                minutes -= 24 * 60;
                day++;
            }
            if (minutes % 3 == 0) {
                for (Station station : stations) {
                    station.stock.merge(station.produces, 1, (stock, produced) -> Math.min(30, stock + produced));
                }
            }
            if (minutes % 60 == 0) {
                replenishContracts();
            }
            for (Contract contract : contracts) {
                if (contract.state != ContractState.AVAILABLE && contract.state != ContractState.COMPLETE
                        && contract.state != ContractState.EXPIRED && minutesOnDay() > contract.deadlineMinute
                        && contract.deadlineDay <= day) {
                    contract.state = ContractState.EXPIRED;
                }
            }
        }

        for (Train train : trains) {
            if (train.dwellTicks > 0) {
                train.dwellTicks--;
                continue;
            }
            if (train.path.size() < 2 || train.pathIndex >= train.path.size() - 1) {
                if (!train.stops.isEmpty() && train.path.isEmpty()) {
                    dispatchNextStop(train);
                }
                continue;
            }
            Node from = train.path.get(train.pathIndex);
            Node to = train.path.get(train.pathIndex + 1);
            if ((signals.contains(from) || signals.contains(to)) && occupied(from, to, train)) {
                continue;
            }
            train.progress += 8 / Math.max(8, from.distance(to));
            if (train.progress >= 1) {
                train.position = to;
                recordTravelledSegment(train, from, to);
                train.progress = 0;
                train.pathIndex++;
                if (train.position.equals(train.destination)) {
                    arriveAtStop(train);
                }
            }
        }
    }

    private void seedTrail(Train train) {
        train.railTrail.clear();
        train.railTrail.add(train.position);
        Set<Node> visited = new HashSet<>();
        visited.add(train.position);
        Node current = train.position;
        double length = 0;
        while (length < MAX_TRAIL_LENGTH) {
            Edge longestTrack = null;
            Node previous = null;
            for (Edge edge : tracks) {
                Node other = edge.other(current);
                if (other != null && !visited.contains(other)
                        && (longestTrack == null || current.distance(other)
                                > longestTrack.first.distance(longestTrack.second))) {
                    longestTrack = edge;
                    previous = other;
                }
            }
            if (longestTrack == null || previous == null) {
                break;
            }
            length += current.distance(previous);
            train.railTrail.add(0, previous);
            visited.add(previous);
            current = previous;
        }
        trimTrail(train);
    }

    private void recordTravelledSegment(Train train, Node from, Node to) {
        if (train.railTrail.isEmpty()) {
            train.railTrail.add(from);
        } else if (!train.railTrail.get(train.railTrail.size() - 1).equals(from)) {
            train.railTrail.add(from);
        }
        if (!train.railTrail.get(train.railTrail.size() - 1).equals(to)) {
            train.railTrail.add(to);
        }
        trimTrail(train);
    }

    private void trimTrail(Train train) {
        int first = train.railTrail.size() - 1;
        double length = 0;
        while (first > 0) {
            double segmentLength = train.railTrail.get(first).distance(train.railTrail.get(first - 1));
            if (length + segmentLength > MAX_TRAIL_LENGTH && first < train.railTrail.size() - 1) {
                break;
            }
            length += segmentLength;
            first--;
        }
        if (first > 0) {
            train.railTrail.subList(0, first).clear();
        }
    }

    Station stationAt(Node node) {
        for (Station station : stations) {
            if (station.position.equals(node)) {
                return station;
            }
        }
        return null;
    }

    Station nearestStation(Node node, double radius) {
        Station nearest = null;
        double nearestDistance = radius;
        for (Station station : stations) {
            double distance = station.position.distance(node);
            if (distance <= nearestDistance) {
                nearest = station;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    Train trainAt(Node point, double radius) {
        for (Train train : trains) {
            if (train.positionAt().distance(point) <= radius) {
                return train;
            }
        }
        return null;
    }

    Node snapTrack(Node point, double radius) {
        return snapToTrack(point, radius).orElse(null);
    }

    Contract contractAt(int index) {
        List<Contract> visible = visibleContracts();
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
    }

    List<Contract> visibleContracts() {
        ArrayList<Contract> visible = new ArrayList<>();
        ArrayList<Contract> offers = new ArrayList<>();
        for (Contract contract : contracts) {
            if (contract.state == ContractState.ACCEPTED || contract.state == ContractState.LOADING
                    || contract.state == ContractState.IN_TRANSIT) {
                if (visible.size() < MAX_VISIBLE_CONTRACTS) {
                    visible.add(contract);
                }
            } else if (contract.state == ContractState.AVAILABLE) {
                offers.add(contract);
            }
        }
        int offerSlots = MAX_VISIBLE_CONTRACTS - visible.size();
        int firstOffer = Math.max(0, offers.size() - offerSlots);
        for (int index = firstOffer; index < offers.size(); index++) {
            visible.add(offers.get(index));
        }
        return visible;
    }

    private void generateStationContracts(Station newStation, List<Station> existingStations) {
        Station partner = existingStations.stream()
                .min((left, right) -> Double.compare(
                        left.position.distance(newStation.position),
                        right.position.distance(newStation.position)))
                .orElse(null);
        if (partner == null) {
            return;
        }
        addGeneratedContract(newStation, partner, newStation.produces);
        addGeneratedContract(partner, newStation, partner.produces);
    }

    private void replenishContracts() {
        long available = contracts.stream()
                .filter(contract -> contract.state == ContractState.AVAILABLE)
                .count();
        if (available >= CONTRACT_REPLENISH_THRESHOLD || stations.size() < 2) {
            return;
        }
        int originIndex = Math.floorMod(contracts.size() + deliveries, stations.size());
        Station origin = stations.get(originIndex);
        Station destination = stations.get((originIndex + 1) % stations.size());
        addGeneratedContract(origin, destination, origin.produces);
    }

    private void addGeneratedContract(Station origin, Station destination, Goods goods) {
        int nextId = contracts.stream().mapToInt(contract -> contract.id).max().orElse(0) + 1;
        int amount = 3 + nextId % 3;
        int distanceBonus = (int) (origin.position.distance(destination.position) / 40);
        int reward = amount * 45 + distanceBonus;
        int deadline = (day - 1) * 24 * 60 + minutes + 12 * 60;
        String name = goods.label + " run: " + origin.name + " to " + destination.name;
        contracts.add(new Contract(nextId, name, origin, destination, goods,
                amount, reward, deadline, false));
    }

    String nextStopName(Train train) {
        Station station = stationAt(train.destination);
        return station == null ? "No stop assigned" : station.name;
    }

    String statusText(Contract contract) {
        return switch (contract.state) {
            case AVAILABLE -> "AVAILABLE";
            case ACCEPTED -> "WAITING FOR PICKUP";
            case LOADING -> "LOADED " + contract.loaded + "/" + contract.amount;
            case IN_TRANSIT -> "IN TRANSIT " + contract.delivered + "/" + contract.amount;
            case COMPLETE -> "DELIVERED";
            case EXPIRED -> "EXPIRED";
        };
    }

    private int minutesOnDay() {
        return minutes;
    }

    private void arriveAtStop(Train train) {
        Station station = stationAt(train.position);
        if (station != null) {
            deliverAtStation(train, station);
            loadAtStation(train, station);
            train.dwellTicks = 14;
        }
        dispatchNextStop(train);
    }

    private void loadAtStation(Train train, Station station) {
        for (Contract contract : contracts) {
            if ((contract.state != ContractState.ACCEPTED && contract.state != ContractState.LOADING)
                    || contract.origin != station || contract.loaded >= contract.amount) {
                continue;
            }
            int currentLoad = 0;
            for (Shipment shipment : train.cargo) {
                currentLoad += shipment.amount;
            }
            int freeCapacity = train.carriages * 4 - currentLoad;
            int available = station.stock.getOrDefault(contract.goods, 0);
            int amount = Math.min(Math.min(freeCapacity, available), contract.amount - contract.loaded);
            if (amount <= 0) {
                continue;
            }
            station.stock.put(contract.goods, available - amount);
            contract.loaded += amount;
            train.cargo.add(new Shipment(contract, amount));
            contract.state = contract.loaded == contract.amount
                    ? ContractState.IN_TRANSIT : ContractState.LOADING;
        }
    }

    private void deliverAtStation(Train train, Station station) {
        for (Shipment shipment : new ArrayList<>(train.cargo)) {
            Contract contract = shipment.contract;
            if (contract.destination != station || contract.state == ContractState.EXPIRED) {
                continue;
            }
            contract.delivered += shipment.amount;
            train.cargo.remove(shipment);
            if (contract.delivered >= contract.amount) {
                contract.state = ContractState.COMPLETE;
                cash += contract.reward;
                deliveries++;
            } else {
                contract.state = ContractState.IN_TRANSIT;
            }
        }
    }

    private void dispatchNextStop(Train train) {
        if (train.stops.isEmpty() || (!train.looping && train.scheduleIndex >= train.stops.size())) {
            train.path = List.of();
            train.destination = train.position;
            return;
        }
        for (int attempt = 0; attempt < train.stops.size(); attempt++) {
            if (!train.looping && train.scheduleIndex >= train.stops.size()) {
                break;
            }
            int index = train.looping ? train.scheduleIndex % train.stops.size() : train.scheduleIndex;
            Node next = train.stops.get(index);
            train.scheduleIndex = train.looping ? (index + 1) % train.stops.size() : index + 1;
            List<Node> route = findRoute(train.position, next);
            if (!route.isEmpty() && !next.equals(train.position)) {
                train.destination = next;
                train.path = route;
                train.pathIndex = 0;
                train.progress = 0;
                return;
            }
        }
        train.path = List.of();
        train.destination = train.position;
    }

    private Node nextScheduledStop(Train train) {
        if (train.stops.isEmpty() || (!train.looping && train.scheduleIndex >= train.stops.size())) {
            return null;
        }
        return train.stops.get(train.looping ? train.scheduleIndex % train.stops.size() : train.scheduleIndex);
    }

    private int indexAfter(int index, Train train) {
        return train.looping ? (index + 1) % train.stops.size() : index + 1;
    }

    private int normalizeScheduleIndex(int index, Train train) {
        return train.looping ? index % train.stops.size() : Math.min(index, train.stops.size());
    }

    private List<Node> findRoute(Node start, Node target) {
        if (start.equals(target)) {
            return List.of(start);
        }
        ArrayDeque<Node> pending = new ArrayDeque<>();
        Map<Node, Node> previous = new HashMap<>();
        Set<Node> visited = new HashSet<>();
        pending.add(start);
        visited.add(start);
        while (!pending.isEmpty()) {
            Node current = pending.removeFirst();
            for (Edge edge : tracks) {
                Node next = edge.other(current);
                if (next == null || !visited.add(next)) {
                    continue;
                }
                previous.put(next, current);
                if (next.equals(target)) {
                    ArrayList<Node> route = new ArrayList<>();
                    for (Node node = target; node != null; node = previous.get(node)) {
                        route.add(0, node);
                    }
                    return route;
                }
                pending.addLast(next);
            }
        }
        return List.of();
    }

    private boolean occupied(Node from, Node to, Train train) {
        Edge wanted = new Edge(from, to);
        for (Train other : trains) {
            if (other == train || other.pathIndex >= other.path.size() - 1) {
                continue;
            }
            if (wanted.equals(new Edge(other.path.get(other.pathIndex), other.path.get(other.pathIndex + 1)))) {
                return true;
            }
        }
        return false;
    }

    private java.util.Optional<Node> snapToTrack(Node point, double radius) {
        Edge nearest = null;
        Node snapped = null;
        double bestDistance = radius;
        for (Edge edge : tracks) {
            Node projection = project(point, edge);
            double distance = point.distance(projection);
            if (distance <= bestDistance) {
                bestDistance = distance;
                nearest = edge;
                snapped = projection;
            }
        }
        if (nearest == null || snapped == null) {
            return java.util.Optional.empty();
        }
        if (!snapped.equals(nearest.first) && !snapped.equals(nearest.second)) {
            tracks.remove(nearest);
            tracks.add(new Edge(nearest.first, snapped));
            tracks.add(new Edge(snapped, nearest.second));
        }
        return java.util.Optional.of(snapped);
    }

    private Node intersection(Edge first, Edge second) {
        double ax = first.second.x - first.first.x;
        double ay = first.second.y - first.first.y;
        double bx = second.second.x - second.first.x;
        double by = second.second.y - second.first.y;
        double denominator = ax * by - ay * bx;
        if (Math.abs(denominator) < 0.000001) {
            return null;
        }
        double dx = second.first.x - first.first.x;
        double dy = second.first.y - first.first.y;
        double ta = (dx * by - dy * bx) / denominator;
        double tb = (dx * ay - dy * ax) / denominator;
        if (ta < 0 || ta > 1 || tb < 0 || tb > 1) {
            return null;
        }
        return new Node(first.first.x + ta * ax, first.first.y + ta * ay);
    }

    private double parameter(Edge edge, Node node) {
        double dx = edge.second.x - edge.first.x;
        double dy = edge.second.y - edge.first.y;
        return ((node.x - edge.first.x) * dx + (node.y - edge.first.y) * dy) / (dx * dx + dy * dy);
    }

    private Node project(Node point, Edge edge) {
        double dx = edge.second.x - edge.first.x;
        double dy = edge.second.y - edge.first.y;
        double fraction = ((point.x - edge.first.x) * dx + (point.y - edge.first.y) * dy) / (dx * dx + dy * dy);
        fraction = Math.max(0, Math.min(1, fraction));
        return new Node(edge.first.x + fraction * dx, edge.first.y + fraction * dy);
    }

    private boolean insideWorld(Node point) {
        return point != null && point.x >= 0 && point.x <= WIDTH && point.y >= 0 && point.y <= HEIGHT;
    }

    private Station addInitialStation(Node node, String name, Goods goods) {
        Station station = new Station(nextStationId++, node, name, goods);
        station.stock.put(goods, 16);
        stations.add(station);
        return station;
    }

    private void addInitialTrack(Node from, Node to) {
        tracks.add(new Edge(from, to));
    }

    static final class Station {
        final int id;
        final Node position;
        final String name;
        final Goods produces;
        final EnumMap<Goods, Integer> stock = new EnumMap<>(Goods.class);

        Station(int id, Node position, String name, Goods produces) {
            this.id = id;
            this.position = position;
            this.name = name;
            this.produces = produces;
        }

        int stockOf(Goods goods) {
            return stock.getOrDefault(goods, 0);
        }
    }

    static final class Train {
        final int id;
        final String name;
        final List<Node> stops = new ArrayList<>();
        final List<Node> railTrail = new ArrayList<>();
        final List<Shipment> cargo = new ArrayList<>();
        Node position;
        Node destination;
        List<Node> path = List.of();
        int pathIndex;
        int scheduleIndex;
        int dwellTicks;
        int carriages = 2;
        boolean looping = true;
        double progress;

        Train(int id, String name, Node position) {
            this.id = id;
            this.name = name;
            this.position = position;
            this.destination = position;
        }

        Node positionAt() {
            if (path.size() < 2 || pathIndex >= path.size() - 1) {
                return position;
            }
            Node from = path.get(pathIndex);
            Node to = path.get(pathIndex + 1);
            return new Node(from.x + (to.x - from.x) * progress,
                    from.y + (to.y - from.y) * progress);
        }

        int load() {
            int total = 0;
            for (Shipment shipment : cargo) {
                total += shipment.amount;
            }
            return total;
        }
    }

    static final class Contract {
        final int id;
        final String name;
        final Station origin;
        final Station destination;
        final Goods goods;
        final int amount;
        final int reward;
        final int deadlineMinute;
        final int deadlineDay;
        int loaded;
        int delivered;
        ContractState state;

        Contract(int id, String name, Station origin, Station destination, Goods goods,
                int amount, int reward, int deadlineTotalMinutes, boolean accepted) {
            this.id = id;
            this.name = name;
            this.origin = origin;
            this.destination = destination;
            this.goods = goods;
            this.amount = amount;
            this.reward = reward;
            this.deadlineMinute = deadlineTotalMinutes % (24 * 60);
            this.deadlineDay = deadlineTotalMinutes / (24 * 60) + 1;
            this.state = accepted ? ContractState.ACCEPTED : ContractState.AVAILABLE;
        }
    }

    record Shipment(Contract contract, int amount) {
    }

    record Node(double x, double y) {
        double distance(Node other) {
            return Math.hypot(x - other.x, y - other.y);
        }
    }

    record Edge(Node first, Node second) {
        Edge {
            if (compare(first, second) > 0) {
                Node swap = first;
                first = second;
                second = swap;
            }
        }

        Node other(Node node) {
            if (first.equals(node)) {
                return second;
            }
            return second.equals(node) ? first : null;
        }

        private static int compare(Node first, Node second) {
            int xOrder = Double.compare(first.x, second.x);
            return xOrder != 0 ? xOrder : Double.compare(first.y, second.y);
        }
    }

    enum Goods {
        TIMBER("Timber", new java.awt.Color(180, 113, 69)),
        FOOD("Produce", new java.awt.Color(79, 157, 204)),
        MAIL("Mail", new java.awt.Color(91, 133, 182)),
        ORE("Ore", new java.awt.Color(130, 119, 110));

        final String label;
        final java.awt.Color color;

        Goods(String label, java.awt.Color color) {
            this.label = label;
            this.color = color;
        }
    }

    enum ContractState {
        AVAILABLE, ACCEPTED, LOADING, IN_TRANSIT, COMPLETE, EXPIRED
    }

    enum DemolitionResult {
        TRAIN_REMOVED, STATION_REMOVED, SIGNAL_REMOVED, TRACK_REMOVED,
        NOTHING_THERE, TRACK_IN_USE, STATION_IN_USE, TRAIN_CARRYING_CARGO, LAST_TRAIN
    }
}