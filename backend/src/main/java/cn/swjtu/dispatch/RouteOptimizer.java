package cn.swjtu.dispatch;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans two-truck pickup-and-delivery routes. A truck may pick up several loads
 * before delivering them as long as the 20-bike capacity holds.
 */
public final class RouteOptimizer {
    private RouteOptimizer() {}

    public record Leg(int source, int destination, int quantity, int destinationNeed, int sourceSupply) {}
    public record Stop(int legIndex, boolean pickup, int region, int quantity) {}
    public record Route(int vehicle, List<Stop> stops) {}

    private static final int VEHICLES = 2;
    private static final int CAPACITY = 20;
    private static final int MAX_STOPS = 6;

    /** Returns two routes, or null when the solver is unavailable or cannot serve every leg. */
    public static List<Route> plan(double[][] km, List<Leg> legs) {
        if (legs.isEmpty()) return List.of(new Route(1, List.of()), new Route(2, List.of()));
        if (!loaded()) return null;
        try {
            int n = legs.size();
            int nodes = 1 + 2 * n;
            int[] demand = new int[nodes];
            int[] region = new int[nodes];
            for (int i = 0; i < n; i++) {
                Leg leg = legs.get(i);
                int pickup = 1 + 2 * i, delivery = pickup + 1;
                demand[pickup] = leg.quantity();
                demand[delivery] = -leg.quantity();
                region[pickup] = leg.source();
                region[delivery] = leg.destination();
            }
            RoutingIndexManager manager = new RoutingIndexManager(nodes, VEHICLES, 0);
            RoutingModel routing = new RoutingModel(manager);
            int transit = routing.registerTransitCallback((from, to) -> {
                int a = manager.indexToNode(from), b = manager.indexToNode(to);
                if (a == 0 || b == 0) return 0;
                return Math.round(km[region[a]][region[b]] * 1000);
            });
            routing.setArcCostEvaluatorOfAllVehicles(transit);
            int loads = routing.registerUnaryTransitCallback(from -> demand[manager.indexToNode(from)]);
            routing.addDimensionWithVehicleCapacity(loads, 0, new long[]{CAPACITY, CAPACITY}, false, "Load");
            int stops = routing.registerUnaryTransitCallback(from -> manager.indexToNode(from) == 0 ? 0 : 1);
            routing.addDimensionWithVehicleCapacity(stops, 0, new long[]{MAX_STOPS, MAX_STOPS}, true, "Stops");
            for (int i = 0; i < n; i++) {
                int pickup = 1 + 2 * i, delivery = pickup + 1;
                routing.addPickupAndDelivery(manager.nodeToIndex(pickup), manager.nodeToIndex(delivery));
            }
            RoutingSearchParameters parameters = main.defaultRoutingSearchParameters()
                    .toBuilder()
                    .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                    .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                    .setTimeLimit(com.google.protobuf.Duration.newBuilder().setNanos(300_000_000).build())
                    .build();
            Assignment solution = routing.solveWithParameters(parameters);
            if (solution == null) return null;
            List<Route> routes = new ArrayList<>();
            int served = 0;
            for (int vehicle = 0; vehicle < VEHICLES; vehicle++) {
                List<Stop> route = new ArrayList<>();
                long index = routing.start(vehicle);
                while (!routing.isEnd(index)) {
                    int node = manager.indexToNode(index);
                    if (node > 0) {
                        int legIndex = (node - 1) / 2;
                        boolean pickup = (node - 1) % 2 == 0;
                        route.add(new Stop(legIndex, pickup, region[node], demand[node]));
                        if (pickup) served++;
                    }
                    index = solution.value(routing.nextVar(index));
                }
                routes.add(new Route(vehicle + 1, route));
            }
            return served == n ? routes : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Loads the native solver in the background so the first plan does not stall. */
    public static void warmUp() {
        loaded();
    }

    public static boolean available() { return Boolean.TRUE.equals(available); }

    private static Boolean available;

    private static synchronized boolean loaded() {
        if (available == null) {
            try {
                Loader.loadNativeLibraries();
                available = true;
            } catch (Throwable failure) {
                available = false;
            }
        }
        return available;
    }
}
