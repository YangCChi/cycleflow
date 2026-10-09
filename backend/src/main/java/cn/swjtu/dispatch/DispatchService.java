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
    public DispatchService(JdbcTemplate db, TransactionTemplate tx) { this.db = db; this.tx = tx; }

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
        return db.query("SELECT * FROM dispatch_tasks ORDER BY created_minute DESC, id DESC", (rs, row) -> new Task(rs.getString("id"), rs.getInt("source_id"), rs.getInt("destination_id"), rs.getInt("quantity"), rs.getInt("vehicle"), rs.getString("status"), rs.getDouble("distance"), rs.getInt("duration"), rs.getInt("created_minute"), (Integer)rs.getObject("started_minute"), (Integer)rs.getObject("completed_minute")));
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
            return new RegionView(r.id(), r.name(), r.kind(), r.stock(), r.capacity(), r.safetyStock(), r.x(), r.y(), flow[0], flow[1], target, shortage, surplus, status, reservedOut, reservedIn);
        }).toList();
        int ground = views.stream().mapToInt(RegionView::stock).sum();
        int transit = tasks.stream().filter(t -> t.status().equals("RUNNING")).mapToInt(Task::quantity).sum();
        int completed = (int)tasks.stream().filter(t -> t.status().equals("COMPLETED")).count();
        double distance = tasks.stream().filter(t -> t.status().equals("COMPLETED")).mapToDouble(Task::distance).sum();
        return new Snapshot(views, tasks, now, scenario, ground + transit, ground, transit, completed, Math.round(distance * 10) / 10.0);
    }

    public synchronized Snapshot reset(String scenario) {
        if (!DemandModel.SCENARIOS.contains(scenario)) throw error("未知场景");
        return tx.execute(status -> {
            db.update("DELETE FROM dispatch_tasks"); db.update("DELETE FROM regions");
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
            Map<Integer,Integer> supply = new HashMap<>(), need = new HashMap<>();
            for (RegionView r : snapshot.regions()) {
                supply.put(r.id(), Math.max(0, r.stock() - r.reservedOut() - r.target()));
                need.put(r.id(), Math.min(r.shortage(), r.capacity() - r.stock() - r.reservedIn()));
            }
            boolean generated = false;
            for (int vehicle = 1; vehicle <= 2; vehicle++) {
                final int v = vehicle;
                if (snapshot.tasks().stream().anyMatch(t -> t.vehicle() == v && active(t))) continue;
                Region destination = all.stream().filter(r -> need.get(r.id()) > 0).max(Comparator.comparingInt(r -> need.get(r.id()))).orElse(null);
                if (destination == null) break;
                Region source = all.stream().filter(r -> supply.get(r.id()) > 0 && r.id() != destination.id()).min(Comparator.comparingDouble(r -> DemandModel.distance(r, destination))).orElse(null);
                if (source == null) break;
                int quantity = Math.min(20, Math.min(need.get(destination.id()), supply.get(source.id())));
                double distance = DemandModel.distance(source, destination);
                db.update("INSERT INTO dispatch_tasks(id,source_id,destination_id,quantity,vehicle,status,distance,duration,created_minute) VALUES (?,?,?,?,?,?,?,?,?)", "JD-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(), source.id(), destination.id(), quantity, vehicle, "PLANNED", distance, DemandModel.duration(distance, quantity), minute());
                supply.compute(source.id(), (k,n) -> n - quantity); need.compute(destination.id(), (k,n) -> n - quantity); generated = true;
            }
            if (!generated) throw error(snapshot.tasks().stream().anyMatch(DispatchService::active) ? "运输车已有待执行或在途任务，请先完成当前任务" : "当前没有可执行的调度任务：供需已平衡或没有富余车辆");
            return snapshot();
        });
    }

    public synchronized Snapshot action(String id, String action) {
        return tx.execute(status -> {
            Task task = tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> error("任务不存在"));
            if (action.equals("start")) {
                if (!task.status().equals("PLANNED")) throw error("只有待执行任务可以开始");
                Region source = region(task.sourceId());
                if (source.stock() < task.quantity()) throw error("源区域库存不足，请重新生成方案");
                db.update("UPDATE regions SET stock=stock-? WHERE id=?", task.quantity(), task.sourceId());
                db.update("UPDATE dispatch_tasks SET status='RUNNING',started_minute=? WHERE id=?", minute(), id);
            } else if (action.equals("cancel")) {
                if (!task.status().equals("PLANNED")) throw error("仅待执行任务可取消");
                db.update("UPDATE dispatch_tasks SET status='CANCELLED' WHERE id=?", id);
            } else throw error("不支持的操作");
            return snapshot();
        });
    }

    public synchronized Snapshot advance() {
        return tx.execute(status -> {
            int now = minute() + 5; state("minute", now);
            for (Task t : tasks()) {
                if (t.status().equals("RUNNING") && t.startedMinute() + t.duration() <= now) {
                    Region destination = region(t.destinationId());
                    if (destination.stock() + t.quantity() > destination.capacity()) throw error("目的区域容量不足");
                    db.update("UPDATE regions SET stock=stock+? WHERE id=?", t.quantity(), t.destinationId());
                    db.update("UPDATE dispatch_tasks SET status='COMPLETED',completed_minute=? WHERE id=?", now, t.id());
                }
            }
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
        if (tasks().stream().anyMatch(DispatchService::active)) throw error("请先完成或取消调度任务，再运行独立实验");
        return Simulation.run(regions(), state("scenario"), minute(), seed);
    }
}
