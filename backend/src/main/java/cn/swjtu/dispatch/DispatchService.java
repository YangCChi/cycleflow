package cn.swjtu.dispatch;

import static cn.swjtu.dispatch.Models.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PostConstruct;

@Service
public class DispatchService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RoadMatrix roadMatrix;
    public DispatchService(JdbcTemplate db, TransactionTemplate tx, RoadMatrix roadMatrix) { this.db = db; this.tx = tx; this.roadMatrix = roadMatrix; }

    @PostConstruct public void init() {
        if (db.queryForObject("SELECT COUNT(*) FROM regions", Integer.class) == 0) reset("morning");
    }
    private void state(String key, Object value) {
        int count = db.update("UPDATE app_state SET state_value=? WHERE state_key=?", value.toString(), key);
        if (count == 0) db.update("INSERT INTO app_state VALUES (?,?)", key, value.toString());
    }
    private String state(String key) { return db.queryForObject("SELECT state_value FROM app_state WHERE state_key=?", String.class, key); }
    private int minute() { return Integer.parseInt(state("minute")); }
    private List<Region> regions() {
        return db.query("SELECT * FROM regions ORDER BY id", (rs, row) -> new Region(rs.getInt("id"), rs.getString("name"), rs.getString("kind"), rs.getInt("stock"), rs.getInt("capacity"), rs.getInt("safety_stock"), rs.getDouble("x"), rs.getDouble("y")));
    }
    private Region region(int id) { return regions().stream().filter(r -> r.id() == id).findFirst().orElseThrow(() -> error("停车区域不存在")); }
    private List<Task> tasks() {
        return db.query("SELECT * FROM dispatch_tasks ORDER BY created_minute DESC, id DESC", (rs, row) -> new Task(rs.getString("id"), rs.getInt("source_id"), rs.getInt("destination_id"), rs.getInt("quantity"), rs.getInt("vehicle"), rs.getString("status"), rs.getDouble("distance"), rs.getInt("duration"), rs.getInt("created_minute"), (Integer)rs.getObject("started_minute"), (Integer)rs.getObject("completed_minute"), rs.getString("reason"), rs.getString("trip_id"), rs.getInt("sequence_no"), (Integer)rs.getObject("pickup_seq"), (Integer)rs.getObject("delivery_seq"), (Integer)rs.getObject("deadline_minute")));
    }
    private static ResponseStatusException error(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static boolean active(Task t) { return t.status().equals("PLANNED") || t.status().equals("RUNNING"); }

    public synchronized Snapshot snapshot() {
        List<Task> tasks = tasks();
        String scenario = state("scenario"); int now = minute();
        List<RegionView> views = regions().stream().map(r -> {
            int reservedOut = tasks.stream().filter(t -> t.sourceId() == r.id() && t.status().equals("PLANNED")).mapToInt(Task::quantity).sum();
            int reservedIn = tasks.stream().filter(t -> t.destinationId() == r.id() && active(t)).mapToInt(Task::quantity).sum();
            int[] flow = DemandModel.demand(r, scenario, now);
            int target = DemandModel.target(r, scenario, now);
            int available = r.stock() - reservedOut + reservedIn;
            int shortage = Math.max(0, target - available), surplus = Math.max(0, available - target);
            String status = shortage > 0 ? "shortage" : r.stock() >= r.capacity() * 0.85 ? "crowded" : "balanced";
            double[] location = DemoLocations.forRegion(r.id());
            return new RegionView(r.id(), r.name(), r.kind(), r.stock(), r.capacity(), r.safetyStock(), r.x(), r.y(), location[0], location[1], flow[0], flow[1], target, shortage, surplus, status, reservedOut, reservedIn);
        }).toList();
        int ground = views.stream().mapToInt(RegionView::stock).sum();
        int transit = tasks.stream().filter(t -> t.status().equals("RUNNING")).mapToInt(Task::quantity).sum();
        int completed = (int)tasks.stream().filter(t -> t.status().equals("COMPLETED")).count();
        double distance = tasks.stream().filter(t -> t.status().equals("COMPLETED")).mapToDouble(Task::distance).sum();
        return new Snapshot(views, tasks, now, scenario, ground + transit, ground, transit, completed, Math.round(distance * 10) / 10.0, roadMatrix.label());
    }

    public synchronized Snapshot reset(String scenario) {
        if (!DemandModel.SCENARIOS.contains(scenario)) throw error("未知场景");
        return tx.execute(status -> {
            db.update("DELETE FROM dispatch_tasks"); db.update("DELETE FROM dispatch_trips"); db.update("DELETE FROM regions");
            Object[][] rows = {
                {1,"西门外示范点","gate",18,70,28,620.,355.},
                {2,"兴业北街接驳点","metro",14,65,25,405.,370.},
                {3,"犀浦站接驳点","metro",8,55,22,225.,555.},
                {4,"校园路生活区 A","residential",52,85,32,450.,170.},
                {5,"校园路生活区 B","residential",66,90,34,225.,240.},
                {6,"周边社区示范点 A","residential",72,95,35,115.,385.},
                {7,"周边社区示范点 B","residential",54,80,30,285.,80.},
                {8,"周边社区示范点 C","residential",62,90,32,95.,130.},
                {9,"校区南侧接驳点","gate",45,70,28,775.,500.},
                {10,"校园路商业点 A","commercial",40,70,24,455.,525.},
                {11,"周边商业示范点 B","commercial",38,70,25,85.,550.},
                {12,"校区北侧接驳点","gate",31,65,26,780.,90.}
            };
            for (Object[] row : rows) db.update("INSERT INTO regions VALUES (?,?,?,?,?,?,?,?)", row);
            state("scenario", scenario); state("minute", scenario.equals("evening") ? 1050 : scenario.equals("weekend") ? 660 : 450);
            return snapshot();
        });
    }

    public synchronized Snapshot update(int id, RegionUpdate update) {
        return tx.execute(status -> {
            Region r = region(id);
            if (update.name() == null || update.name().isBlank() || update.name().strip().length() > 40 || update.capacity() == null || update.safetyStock() == null) throw error("请填写完整的区域信息，名称不超过 40 字");
            int incoming = tasks().stream().filter(t -> active(t) && t.destinationId() == id).mapToInt(Task::quantity).sum();
            if (update.capacity() < r.stock() + incoming || update.capacity() > 500 || update.safetyStock() < 0 || update.safetyStock() > update.capacity()) throw error("容量须容纳现有及待入库车辆，安全库存不能超过容量（最大 500）");
            db.update("UPDATE regions SET name=?,capacity=?,safety_stock=? WHERE id=?", update.name().strip(), update.capacity(), update.safetyStock(), id);
            return snapshot();
        });
    }

    public synchronized Snapshot generate() {
        return tx.execute(status -> {
            Snapshot snapshot = snapshot();
            List<Region> all = regions();
            MatrixData matrix = matrixData(all);
            List<Integer> freeVehicles = new ArrayList<>();
            for (int vehicle = 1; vehicle <= 2; vehicle++) {
                final int v = vehicle;
                if (snapshot.tasks().stream().noneMatch(t -> t.vehicle() == v && active(t))) freeVehicles.add(vehicle);
            }
            if (freeVehicles.isEmpty()) throw error("运输车已有待执行或在途任务，请先完成当前任务");
            List<RouteOptimizer.Leg> legs = candidateLegs(snapshot, all, matrix, 3 * freeVehicles.size());
            if (legs.isEmpty()) throw error("当前没有可执行的调度任务：供需已平衡或没有富余车辆");
            int[] latest = new int[legs.size()];
            for (int i = 0; i < legs.size(); i++) latest[i] = slaMinutes(region(legs.get(i).destination()).kind());
            List<RouteOptimizer.Route> routes = freeVehicles.size() == 2
                    ? RouteOptimizer.plan(matrix.km(), legs) : null;
            boolean optimized = routes != null;
            if (routes == null) routes = serialRoutes(legs, freeVehicles);
            routes = applyDeadlines(routes, latest, matrix);
            if (routes.stream().allMatch(route -> route.stops().isEmpty())) throw error("所有候选搬运都赶不上送达时限，请放宽时限或调整库存");
            for (RouteOptimizer.Route route : routes) {
                if (route.stops().isEmpty()) continue;
                String tripId = "TRIP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
                db.update("INSERT INTO dispatch_trips(id,vehicle,status,start_minute,created_minute) VALUES (?,?,?,?,?)", tripId, route.vehicle(), "PLANNED", null, minute());
                Map<Integer,Integer> pickupAt = new HashMap<>(), deliveryAt = new HashMap<>();
                for (int i = 0; i < route.stops().size(); i++) {
                    RouteOptimizer.Stop stop = route.stops().get(i);
                    (stop.pickup() ? pickupAt : deliveryAt).put(stop.legIndex(), i + 1);
                }
                List<Integer> order = new ArrayList<>(pickupAt.keySet());
                order.sort(Comparator.comparingInt(pickupAt::get));
                for (int i = 0; i < order.size(); i++) {
                    int legIndex = order.get(i);
                    RouteOptimizer.Leg leg = legs.get(legIndex);
                    double distance = matrix.km()[leg.source()][leg.destination()];
                    int duration = matrix.minutes() == null
                            ? DemandModel.duration(distance, leg.quantity())
                            : Math.max(5, matrix.minutes()[leg.source()][leg.destination()] + (int) Math.ceil(leg.quantity() * 0.25) + 2);
                    int deadline = minute() + latest[legIndex];
                    String reason = "第 %d 单：目的区域缺口 %d 辆；源区域可调出 %d 辆；%s；第 %d 站取、第 %d 站送；最晚 %s 送达"
                            .formatted(i + 1, leg.destinationNeed(), leg.sourceSupply(), optimized ? "OR-Tools 载量耦合" : "就近回退",
                                    pickupAt.get(legIndex), deliveryAt.get(legIndex), clock(deadline));
                    db.update("INSERT INTO dispatch_tasks(id,source_id,destination_id,quantity,vehicle,status,distance,duration,created_minute,reason,trip_id,sequence_no,pickup_seq,delivery_seq,deadline_minute) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            "JD-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(), leg.source(), leg.destination(), leg.quantity(), route.vehicle(), "PLANNED",
                            distance, duration, minute(), reason, tripId, i + 1, pickupAt.get(legIndex), deliveryAt.get(legIndex), deadline);
                }
            }
            return snapshot();
        });
    }

    public synchronized Snapshot action(String id, String action) {
        return tx.execute(status -> {
            Task task = tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> error("任务不存在"));
            if (task.tripId() == null || task.pickupSeq() == null) throw error("任务缺少行程信息，请重新生成方案");
            TripRow trip = trip(task.tripId());
            if (action.equals("start")) {
                if (!trip.status().equals("PLANNED")) throw error("行程已开始，请等待站点推进");
                db.update("UPDATE dispatch_trips SET status='RUNNING',start_minute=? WHERE id=?", minute(), trip.id());
                applyStops(trip.id());
            } else if (action.equals("cancel")) {
                if (trip.status().equals("RUNNING")) throw error("行程已在执行中，无法取消");
                if (!trip.status().equals("PLANNED")) throw error("行程已结束");
                db.update("UPDATE dispatch_tasks SET status='CANCELLED' WHERE trip_id=? AND status='PLANNED'", trip.id());
                db.update("UPDATE dispatch_trips SET status='CANCELLED' WHERE id=?", trip.id());
            } else throw error("不支持的操作");
            return snapshot();
        });
    }

    public synchronized Snapshot advance() { return advance(5); }

    public synchronized Snapshot advance(int minutes) {
        if (minutes < 1 || minutes > 60) throw error("推进分钟数须在 1～60 之间");
        return tx.execute(status -> {
            state("minute", minute() + minutes);
            for (TripRow trip : trips()) if (trip.status().equals("RUNNING")) applyStops(trip.id());
            return snapshot();
        });
    }

    public synchronized List<ForecastPoint> forecast(int id) {
        Region r = region(id); int stock = r.stock();
        List<ForecastPoint> points = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int[] demand = DemandModel.demand(r, state("scenario"), minute() + i * 15);
            int out = (int)Math.round(demand[0] / 2.0), in = (int)Math.round(demand[1] / 2.0);
            stock += in - out;
            points.add(new ForecastPoint(DemandModel.time(minute() + (i + 1) * 15), out, in, stock));
        }
        return points;
    }

    public synchronized ExperimentResult experiment(long seed) {
        ensureNoActiveTasks();
        List<Region> all = regions();
        MatrixData matrix = matrixData(all);
        return Simulation.run(all, state("scenario"), minute(), seed, matrix.km(), matrix.minutes(), matrix.label());
    }

    public synchronized ExperimentSummary experimentSummary(long baseSeed, int runCount) {
        if (runCount < 1 || runCount > 10) throw error("重复次数须在 1～10 之间");
        if (baseSeed < 0 || baseSeed + runCount - 1 > 999999) throw error("随机种子须在 0～999999 之间");
        ensureNoActiveTasks();
        String scenario = state("scenario");
        int now = minute();
        List<Region> all = regions();
        MatrixData matrix = matrixData(all);
        List<ExperimentRun> runs = new ArrayList<>();
        String model = "";
        for (int i = 0; i < runCount; i++) {
            long seed = baseSeed + i;
            ExperimentResult result = Simulation.run(all, scenario, now, seed, matrix.km(), matrix.minutes(), matrix.label());
            if (model.isEmpty()) model = result.model();
            runs.add(new ExperimentRun(seed, result.results()));
        }
        List<String> strategies = List.of("none", "threshold", "predictive");
        List<PolicySummary> summaries = new ArrayList<>();
        for (int i = 0; i < strategies.size(); i++) {
            final int index = i;
            DoubleSummaryStatistics rates = runs.stream()
                    .mapToDouble(run -> run.results().get(index).serviceRate()).summaryStatistics();
            double shortageAvg = runs.stream().mapToDouble(run -> run.results().get(index).shortageMinutes()).average().orElse(0);
            double distanceAvg = runs.stream().mapToDouble(run -> run.results().get(index).distance()).average().orElse(0);
            double movedAvg = runs.stream().mapToDouble(run -> run.results().get(index).moved()).average().orElse(0);
            summaries.add(new PolicySummary(strategies.get(i), round1(rates.getAverage()), round1(rates.getMin()),
                    round1(rates.getMax()), round1(shortageAvg), round1(distanceAvg), round1(movedAvg)));
        }
        return new ExperimentSummary(baseSeed, runCount, scenario, 180, model, summaries, runs);
    }

    private void ensureNoActiveTasks() {
        if (tasks().stream().anyMatch(DispatchService::active)) throw error("请先完成或取消调度任务，再运行独立实验");
    }

    private record TripRow(String id, int vehicle, String status, Integer startMinute) {}

    private List<TripRow> trips() {
        return db.query("SELECT * FROM dispatch_trips ORDER BY created_minute, id", (rs, row) ->
                new TripRow(rs.getString("id"), rs.getInt("vehicle"), rs.getString("status"), (Integer) rs.getObject("start_minute")));
    }

    private TripRow trip(String id) {
        return trips().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> error("行程不存在，请重新生成方案"));
    }

    private List<RouteOptimizer.Route> serialRoutes(List<RouteOptimizer.Leg> legs, List<Integer> vehicles) {
        List<RouteOptimizer.Route> routes = new ArrayList<>();
        List<List<RouteOptimizer.Stop>> stops = new ArrayList<>();
        for (int i = 0; i < vehicles.size(); i++) stops.add(new ArrayList<>());
        for (int i = 0; i < legs.size(); i++) {
            RouteOptimizer.Leg leg = legs.get(i);
            List<RouteOptimizer.Stop> route = stops.get(i % vehicles.size());
            route.add(new RouteOptimizer.Stop(i, true, leg.source(), leg.quantity()));
            route.add(new RouteOptimizer.Stop(i, false, leg.destination(), -leg.quantity()));
        }
        for (int i = 0; i < vehicles.size(); i++) routes.add(new RouteOptimizer.Route(vehicles.get(i), stops.get(i)));
        return routes;
    }

    private void applyStops(String tripId) {
        TripRow trip = trip(tripId);
        if (!trip.status().equals("RUNNING") || trip.startMinute() == null) return;
        List<Task> legs = tasks().stream().filter(t -> tripId.equals(t.tripId())).toList();
        record StopRef(int sequence, Task task, boolean pickup, int region) {}
        List<StopRef> stops = new ArrayList<>();
        for (Task leg : legs) {
            if (leg.pickupSeq() == null || leg.deliverySeq() == null) continue;
            stops.add(new StopRef(leg.pickupSeq(), leg, true, leg.sourceId()));
            stops.add(new StopRef(leg.deliverySeq(), leg, false, leg.destinationId()));
        }
        stops.sort(Comparator.comparingInt(StopRef::sequence));
        MatrixData matrix = matrixData(regions());
        int now = minute(), arrival = trip.startMinute(), previous = -1;
        for (StopRef stop : stops) {
            if (previous >= 0) arrival += travelMinutes(matrix, previous, stop.region());
            previous = stop.region();
            if (arrival > now) break;
            Task current = tasks().stream().filter(t -> t.id().equals(stop.task().id())).findFirst().orElseThrow();
            if (stop.pickup() && current.status().equals("PLANNED")) {
                Region source = region(current.sourceId());
                if (source.stock() >= current.quantity()) {
                    db.update("UPDATE regions SET stock=stock-? WHERE id=?", current.quantity(), current.sourceId());
                    db.update("UPDATE dispatch_tasks SET status='RUNNING',started_minute=? WHERE id=?", arrival, current.id());
                } else {
                    db.update("UPDATE dispatch_tasks SET status='CANCELLED',reason=? WHERE id=?", "取车时源区域库存不足，该单取消", current.id());
                }
            } else if (!stop.pickup() && current.status().equals("RUNNING")) {
                Region destination = region(current.destinationId());
                if (destination.stock() + current.quantity() > destination.capacity()) throw error("目的区域容量不足");
                db.update("UPDATE regions SET stock=stock+? WHERE id=?", current.quantity(), current.destinationId());
                db.update("UPDATE dispatch_tasks SET status='COMPLETED',completed_minute=? WHERE id=?", arrival, current.id());
            }
            arrival += serviceMinutes(current.quantity());
        }
        if (tasks().stream().noneMatch(t -> tripId.equals(t.tripId()) && active(t))) {
            db.update("UPDATE dispatch_trips SET status='COMPLETED' WHERE id=?", tripId);
        }
    }

    private static int slaMinutes(String kind) {
        return switch (kind) {
            case "metro" -> 45;
            case "gate" -> 60;
            default -> 75;
        };
    }

    private List<RouteOptimizer.Leg> candidateLegs(Snapshot snapshot, List<Region> all, MatrixData matrix, int maxLegs) {
        Map<Integer,Integer> supply = new HashMap<>(), need = new HashMap<>();
        for (RegionView r : snapshot.regions()) {
            supply.put(r.id(), Math.max(0, r.stock() - r.reservedOut() - r.target()));
            need.put(r.id(), Math.min(r.shortage(), r.capacity() - r.stock() - r.reservedIn()));
        }
        List<RouteOptimizer.Leg> legs = new ArrayList<>();
        while (legs.size() < maxLegs) {
            Region destination = all.stream().filter(r -> need.get(r.id()) > 0)
                    .max(Comparator.comparingInt(r -> need.get(r.id()))).orElse(null);
            if (destination == null || need.get(destination.id()) < 5) break;
            Region source = all.stream().filter(r -> supply.get(r.id()) > 0 && r.id() != destination.id())
                    .min(Comparator.comparingDouble(r -> matrix.km()[r.id()][destination.id()])).orElse(null);
            if (source == null) break;
            int destinationNeed = need.get(destination.id()), sourceSupply = supply.get(source.id());
            int quantity = Math.min(20, Math.min(destinationNeed, sourceSupply));
            legs.add(new RouteOptimizer.Leg(source.id(), destination.id(), quantity, destinationNeed, sourceSupply));
            supply.compute(source.id(), (k,n) -> n - quantity);
            need.compute(destination.id(), (k,n) -> n - quantity);
        }
        return legs;
    }

    private List<RouteOptimizer.Leg> candidateLegs(List<Region> all, MatrixData matrix, int maxLegs, String scenario, int now, double stockFactor) {
        Map<Integer,Integer> supply = new HashMap<>(), need = new HashMap<>();
        for (Region r : all) {
            int target = DemandModel.target(r, scenario, now);
            int stock = (int) Math.round(r.stock() * stockFactor);
            supply.put(r.id(), Math.max(0, stock - target));
            need.put(r.id(), Math.max(0, target - stock));
        }
        List<RouteOptimizer.Leg> legs = new ArrayList<>();
        while (legs.size() < maxLegs) {
            Region destination = all.stream().filter(r -> need.get(r.id()) > 0)
                    .max(Comparator.comparingInt(r -> need.get(r.id()))).orElse(null);
            if (destination == null || need.get(destination.id()) < 5) break;
            Region source = all.stream().filter(r -> supply.get(r.id()) > 0 && r.id() != destination.id())
                    .min(Comparator.comparingDouble(r -> matrix.km()[r.id()][destination.id()])).orElse(null);
            if (source == null) break;
            int destinationNeed = need.get(destination.id()), sourceSupply = supply.get(source.id());
            int quantity = Math.min(20, Math.min(destinationNeed, sourceSupply));
            legs.add(new RouteOptimizer.Leg(source.id(), destination.id(), quantity, destinationNeed, sourceSupply));
            supply.compute(source.id(), (k,n) -> n - quantity);
            need.compute(destination.id(), (k,n) -> n - quantity);
        }
        return legs;
    }

    public synchronized PlanComparison planComparison() {
        List<Region> all = regions();
        MatrixData matrix = matrixData(all);
        List<RouteOptimizer.Leg> legs = candidateLegs(all, matrix, 6, state("scenario"), minute(), 1.0);
        if (legs.isEmpty()) throw error("当前没有可对比的搬运需求");
        List<RouteOptimizer.Route> greedy = serialRoutes(legs, List.of(1, 2));
        List<RouteOptimizer.Route> optimized = RouteOptimizer.plan(matrix.km(), legs);
        return new PlanComparison(legs.size(), routeKm(greedy, matrix), routeMinutes(greedy, matrix),
                optimized == null ? null : routeKm(optimized, matrix),
                optimized == null ? null : routeMinutes(optimized, matrix),
                optimized != null);
    }

    public synchronized List<PlanComparisonRow> planComparisonSummary() {
        List<Region> all = regions();
        MatrixData matrix = matrixData(all);
        int now = minute();
        List<PlanComparisonRow> rows = new ArrayList<>();
        for (String scenario : DemandModel.SCENARIOS) {
            List<double[]> greedy = new ArrayList<>(), optimized = new ArrayList<>();
            int legsTotal = 0;
            for (double factor : new double[]{0.95, 1.0, 1.05}) {
                List<RouteOptimizer.Leg> legs = candidateLegs(all, matrix, 6, scenario, now, factor);
                if (legs.isEmpty()) continue;
                legsTotal += legs.size();
                List<RouteOptimizer.Route> greedyRoutes = serialRoutes(legs, List.of(1, 2));
                greedy.add(new double[]{routeKm(greedyRoutes, matrix), routeMinutes(greedyRoutes, matrix)});
                List<RouteOptimizer.Route> optimizedRoutes = RouteOptimizer.plan(matrix.km(), legs);
                if (optimizedRoutes != null) optimized.add(new double[]{routeKm(optimizedRoutes, matrix), routeMinutes(optimizedRoutes, matrix)});
            }
            if (greedy.isEmpty()) {
                rows.add(new PlanComparisonRow(scenario, 0, 0, 0, 0, 0, 0, null, null, null, null));
                continue;
            }
            int samples = greedy.size();
            rows.add(new PlanComparisonRow(scenario, samples, Math.round((float) legsTotal / samples),
                    mean(greedy, 0), min(greedy, 0), max(greedy, 0), (int) Math.round(mean(greedy, 1)),
                    optimized.isEmpty() ? null : mean(optimized, 0),
                    optimized.isEmpty() ? null : min(optimized, 0),
                    optimized.isEmpty() ? null : max(optimized, 0),
                    optimized.isEmpty() ? null : (int) Math.round(mean(optimized, 1))));
        }
        return rows;
    }

    private static double mean(List<double[]> values, int index) {
        return Math.round(values.stream().mapToDouble(v -> v[index]).average().orElse(0) * 10) / 10.0;
    }

    private static double min(List<double[]> values, int index) {
        return values.stream().mapToDouble(v -> v[index]).min().orElse(0);
    }

    private static double max(List<double[]> values, int index) {
        return values.stream().mapToDouble(v -> v[index]).max().orElse(0);
    }

    private double routeKm(List<RouteOptimizer.Route> routes, MatrixData matrix) {
        double total = 0;
        for (RouteOptimizer.Route route : routes) {
            int previous = -1;
            for (RouteOptimizer.Stop stop : route.stops()) {
                if (previous >= 0 && previous != stop.region()) total += matrix.km()[previous][stop.region()];
                previous = stop.region();
            }
        }
        return Math.round(total * 10) / 10.0;
    }

    private int routeMinutes(List<RouteOptimizer.Route> routes, MatrixData matrix) {
        int total = 0;
        for (RouteOptimizer.Route route : routes) {
            int previous = -1, time = 0;
            for (RouteOptimizer.Stop stop : route.stops()) {
                if (previous >= 0) time += travelMinutes(matrix, previous, stop.region());
                previous = stop.region();
                time += serviceMinutes(Math.abs(stop.quantity()));
            }
            total = Math.max(total, time);
        }
        return total;
    }

    private List<RouteOptimizer.Route> applyDeadlines(List<RouteOptimizer.Route> routes, int[] latest, MatrixData matrix) {
        List<RouteOptimizer.Route> kept = new ArrayList<>();
        for (RouteOptimizer.Route route : routes) {
            List<RouteOptimizer.Stop> stops = new ArrayList<>(route.stops());
            boolean changed = true;
            while (changed && !stops.isEmpty()) {
                changed = false;
                int time = 0, previous = -1;
                for (RouteOptimizer.Stop stop : stops) {
                    if (previous >= 0) time += travelMinutes(matrix, previous, stop.region());
                    previous = stop.region();
                    time += serviceMinutes(Math.abs(stop.quantity()));
                    if (!stop.pickup() && time > latest[stop.legIndex()]) {
                        int leg = stop.legIndex();
                        stops.removeIf(candidate -> candidate.legIndex() == leg);
                        changed = true;
                        break;
                    }
                }
            }
            if (!stops.isEmpty()) kept.add(new RouteOptimizer.Route(route.vehicle(), stops));
        }
        return kept;
    }

    private static String clock(int minute) {
        return "%02d:%02d".formatted((minute / 60) % 24, minute % 60);
    }

    private int serviceMinutes(int quantity) {
        return 2 + (int) Math.ceil(Math.abs(quantity) / 10.0);
    }

    private int travelMinutes(MatrixData matrix, int from, int to) {
        if (from == to) return 0;
        if (matrix.minutes() != null) return Math.max(1, matrix.minutes()[from][to]);
        return Math.max(1, DemandModel.duration(matrix.km()[from][to], 1));
    }

    private MatrixData matrixData(List<Region> all) {
        if (roadMatrix.ready()) return new MatrixData(roadMatrix.kmMatrix(), roadMatrix.minuteMatrix(), roadMatrix.label());
        double[][] km = new double[13][13];
        for (Region source : all) for (Region destination : all) {
            if (source.id() != destination.id()) km[source.id()][destination.id()] = DemandModel.distance(source, destination);
        }
        return new MatrixData(km, null, roadMatrix.label());
    }

    private record MatrixData(double[][] km, int[][] minutes, String label) {}

    private static double round1(double value) { return Math.round(value * 10) / 10.0; }
}
