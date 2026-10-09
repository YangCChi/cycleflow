package cn.swjtu.dispatch;

import static cn.swjtu.dispatch.Models.*;
import java.util.*;

/** Replays identical generated requests for each policy. Successful rides alone create returns. */
public final class Simulation {
    private Simulation() {}
    private record Request(int minute, int source, int destination, int duration) {}
    private record Arrival(int minute, int destination, int quantity, boolean truck) {}

    public static ExperimentResult run(List<Region> regions, String scenario, int start, long seed) {
        Random random = new Random(seed);
        List<Request> requests = new ArrayList<>();
        for (int minute = 0; minute < 180; minute++) {
            for (int j = 0; j < 7; j++) {
                int source = weighted(regions, scenario, start + minute, true, random);
                int destination = weighted(regions, scenario, start + minute, false, random);
                if (source == destination) destination = (destination + 1) % regions.size();
                requests.add(new Request(minute, source, destination, 5 + random.nextInt(12)));
            }
        }
        List<Experiment> results = new ArrayList<>();
        for (String strategy : List.of("none", "threshold", "predictive")) results.add(replay(regions, requests, scenario, start, strategy));
        return new ExperimentResult(seed, scenario, 180, "规则需求模型 · 模拟网格距离 · 运输车取车点就近待命假设", results);
    }
    private static int weighted(List<Region> regions, String scenario, int minute, boolean out, Random random) {
        int total = regions.stream().mapToInt(r -> DemandModel.demand(r, scenario, minute)[out ? 0 : 1]).sum();
        int pick = random.nextInt(total);
        for (int i = 0; i < regions.size(); i++) {
            pick -= DemandModel.demand(regions.get(i), scenario, minute)[out ? 0 : 1];
            if (pick < 0) return i;
        }
        return regions.size() - 1;
    }
    private static Experiment replay(List<Region> regions, List<Request> requests, String scenario, int start, String strategy) {
        int[] stock = regions.stream().mapToInt(Region::stock).toArray();
        int initialTotal = Arrays.stream(stock).sum();
        List<Arrival> arrivals = new ArrayList<>();
        int served = 0, shortageMinutes = 0, moved = 0, requestIndex = 0;
        double distance = 0;
        for (int minute = 0; minute < 180; minute++) {
            List<Arrival> deferred = new ArrayList<>();
            Iterator<Arrival> iterator = arrivals.iterator();
            while (iterator.hasNext()) {
                Arrival a = iterator.next();
                if (a.minute() <= minute) {
                    if (stock[a.destination()] + a.quantity() <= regions.get(a.destination()).capacity()) stock[a.destination()] += a.quantity();
                    else deferred.add(new Arrival(minute + 1, a.destination(), a.quantity(), a.truck()));
                    iterator.remove();
                }
            }
            arrivals.addAll(deferred);
            if (minute % 15 == 0 && !strategy.equals("none")) {
                int trucks = 2 - (int)arrivals.stream().filter(Arrival::truck).count();
                int[] target = new int[stock.length];
                int[] incoming = new int[stock.length];
                for (Arrival a : arrivals) if (a.truck()) incoming[a.destination()] += a.quantity();
                for (int i = 0; i < stock.length; i++) target[i] = strategy.equals("threshold") ? regions.get(i).safetyStock() : DemandModel.target(regions.get(i), scenario, start + minute);
                for (int v = 0; v < trucks; v++) {
                    int destination = -1, worst = 0;
                    for (int i = 0; i < stock.length; i++) if (target[i] - stock[i] - incoming[i] > worst) { worst = target[i] - stock[i] - incoming[i]; destination = i; }
                    if (destination < 0) break;
                    int source = -1; double nearest = Double.MAX_VALUE;
                    for (int i = 0; i < stock.length; i++) {
                        double d = DemandModel.distance(regions.get(i), regions.get(destination));
                        if (i != destination && stock[i] > target[i] && d < nearest) { source = i; nearest = d; }
                    }
                    if (source < 0) break;
                    int quantity = Math.min(20, Math.min(worst, stock[source] - target[source]));
                    quantity = Math.min(quantity, regions.get(destination).capacity() - stock[destination] - incoming[destination]);
                    if (quantity <= 0) break;
                    stock[source] -= quantity; incoming[destination] += quantity;
                    arrivals.add(new Arrival(minute + DemandModel.duration(nearest, quantity), destination, quantity, true));
                    moved += quantity; distance += nearest;
                }
            }
            while (requestIndex < requests.size() && requests.get(requestIndex).minute() == minute) {
                Request r = requests.get(requestIndex++);
                if (stock[r.source()] > 0) {
                    stock[r.source()]--; served++;
                    arrivals.add(new Arrival(minute + r.duration(), r.destination(), 1, false));
                }
            }
            for (int count : stock) if (count == 0) shortageMinutes++;
        }
        int ending = Arrays.stream(stock).sum() + arrivals.stream().mapToInt(Arrival::quantity).sum();
        if (ending != initialTotal) throw new IllegalStateException("仿真车辆不守恒");
        return new Experiment(strategy, requests.size(), served, Math.round(1000.0 * served / requests.size()) / 10.0, shortageMinutes, Math.round(distance * 10) / 10.0, moved, ending);
    }
}
