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
 * Assigns selected pickup-delivery legs to the two trucks and orders each route.
 * The objective is loaded distance plus the empty run between a delivery and the next pickup.
 */
public final class RouteOptimizer {
    private RouteOptimizer() {}

    public record Leg(int source, int destination, int quantity, int destinationNeed, int sourceSupply) {}

    private static Boolean available;

    /** Returns one ordered route per vehicle, or null when OR-Tools is unavailable. */
    public static List<List<Leg>> plan(double[][] km, List<Leg> legs) {
        if (legs.isEmpty()) return List.of(List.of(), List.of());
        if (!loaded()) return null;
        try {
            int n = legs.size();
            int nodes = n + 1;
            int vehicles = 2;
            double[][] cost = new double[nodes][nodes];
            for (int i = 1; i < nodes; i++) {
                Leg to = legs.get(i - 1);
                for (int j = 1; j < nodes; j++) {
                    Leg from = legs.get(j - 1);
                    cost[i][j] = km[from.destination()][to.source()] + km[to.source()][to.destination()];
                }
            }
            RoutingIndexManager manager = new RoutingIndexManager(nodes, vehicles, 0);
            RoutingModel routing = new RoutingModel(manager);
            int transit = routing.registerTransitCallback((from, to) ->
                    Math.round(cost[manager.indexToNode(from)][manager.indexToNode(to)] * 1000));
            routing.setArcCostEvaluatorOfAllVehicles(transit);
            int stops = routing.registerUnaryTransitCallback(from -> manager.indexToNode(from) == 0 ? 0 : 1);
            routing.addDimensionWithVehicleCapacity(stops, 0, new long[]{2, 2}, true, "Stops");
            RoutingSearchParameters parameters = main.defaultRoutingSearchParameters()
                    .toBuilder()
                    .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                    .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                    .setTimeLimit(com.google.protobuf.Duration.newBuilder().setNanos(250_000_000).build())
                    .build();
            Assignment solution = routing.solveWithParameters(parameters);
            if (solution == null) return null;
            List<List<Leg>> routes = new ArrayList<>();
            for (int vehicle = 0; vehicle < vehicles; vehicle++) {
                List<Leg> route = new ArrayList<>();
                long index = routing.start(vehicle);
                while (!routing.isEnd(index)) {
                    int node = manager.indexToNode(index);
                    if (node > 0) route.add(legs.get(node - 1));
                    index = solution.value(routing.nextVar(index));
                }
                routes.add(route);
            }
            return routes.stream().mapToInt(List::size).sum() == n ? routes : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Loads the native solver in the background so the first plan does not stall. */
    public static void warmUp() {
        loaded();
    }

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
