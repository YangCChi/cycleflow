package cn.swjtu.dispatch;

import static cn.swjtu.dispatch.Models.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Caches the directed 12x12 driving distance/time matrix returned by Amap Web Service. */
@Service
public class RoadMatrix {
    private static final Logger log = LoggerFactory.getLogger(RoadMatrix.class);
    private static final int REGIONS = 12;
    private static final int TOTAL = REGIONS * (REGIONS - 1);
    private static final int BATCH = 3;
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicBoolean loading = new AtomicBoolean();
    private final String key;

    public RoadMatrix(JdbcTemplate db, TransactionTemplate tx, @Value("${amap.web-key:}") String key) {
        this.db = db;
        this.tx = tx;
        this.key = key == null ? "" : key.strip();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (configured() && !ready()) refreshAsync();
    }

    public boolean configured() { return !key.isBlank(); }
    public boolean ready() { return entries() >= TOTAL; }
    public boolean loading() { return loading.get(); }
    public int entries() { return db.queryForObject("SELECT COUNT(*) FROM road_matrix", Integer.class); }

    public RoadMatrixStatus status() {
        return new RoadMatrixStatus(configured(), entries(), TOTAL, updatedAt(), loading());
    }

    public String label() {
        if (!ready()) {
            if (loading()) return "模拟网格距离 · 道路矩阵加载中";
            return configured() ? "模拟网格距离 · 道路矩阵未就绪" : "模拟网格距离";
        }
        String updated = updatedAt();
        return updated == null ? "高德道路矩阵" : "高德道路矩阵 · " + updated;
    }

    public String updatedAt() {
        List<String> values = db.query("SELECT state_value FROM app_state WHERE state_key='matrix_updated_at'", (rs, row) -> rs.getString(1));
        return values.isEmpty() ? null : values.getFirst();
    }

    public double[][] kmMatrix() {
        double[][] matrix = new double[REGIONS + 1][REGIONS + 1];
        db.query("SELECT origin_id,destination_id,distance_km FROM road_matrix", rs -> {
            matrix[rs.getInt(1)][rs.getInt(2)] = rs.getDouble(3);
        });
        return matrix;
    }

    public int[][] minuteMatrix() {
        int[][] matrix = new int[REGIONS + 1][REGIONS + 1];
        db.query("SELECT origin_id,destination_id,duration_min FROM road_matrix", rs -> {
            matrix[rs.getInt(1)][rs.getInt(2)] = rs.getInt(3);
        });
        return matrix;
    }

    public void refreshAsync() {
        if (!configured() || loading.getAndSet(true)) return;
        Thread worker = new Thread(() -> {
            try { refresh(); } finally { loading.set(false); }
        }, "road-matrix");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean refreshNow() {
        if (!configured() || loading.getAndSet(true)) return false;
        try { return refresh(); } finally { loading.set(false); }
    }

    public synchronized boolean refresh() {
        if (!configured()) return false;
        List<Region> regions = db.query("SELECT * FROM regions ORDER BY id", (rs, row) -> new Region(rs.getInt("id"), rs.getString("name"), rs.getString("kind"), rs.getInt("stock"), rs.getInt("capacity"), rs.getInt("safety_stock"), rs.getDouble("x"), rs.getDouble("y")));
        if (regions.size() != REGIONS) {
            log.warn("道路矩阵刷新跳过：区域数量为 {}", regions.size());
            return false;
        }
        List<Object[]> rows = new ArrayList<>();
        for (Region destination : regions) {
            List<Region> sources = regions.stream().filter(region -> region.id() != destination.id()).toList();
            for (int start = 0; start < sources.size(); start += BATCH) {
                List<Region> batch = sources.subList(start, Math.min(start + BATCH, sources.size()));
                StringBuilder origins = new StringBuilder();
                for (Region source : batch) {
                    double[] point = gcj(source);
                    if (!origins.isEmpty()) origins.append('|');
                    origins.append(point[0]).append(',').append(point[1]);
                }
                JsonNode response = request(origins.toString(), gcj(destination));
                if (response == null) return false;
                for (JsonNode item : response.path("results")) {
                    int index = item.path("origin_id").asInt(-1) - 1;
                    if (index < 0 || index >= batch.size()) continue;
                    double distance = item.path("distance").asDouble(0) / 1000.0;
                    int duration = (int) Math.round(item.path("duration").asDouble(0) / 60.0);
                    if (distance <= 0 || duration <= 0) continue;
                    rows.add(new Object[]{batch.get(index).id(), destination.id(), Math.round(distance * 10) / 10.0, Math.max(1, duration)});
                }
                pause();
            }
        }
        if (rows.size() < TOTAL) {
            log.warn("道路矩阵不完整：{}/{}", rows.size(), TOTAL);
            return false;
        }
        tx.executeWithoutResult(status -> {
            db.update("DELETE FROM road_matrix");
            db.batchUpdate("INSERT INTO road_matrix(origin_id,destination_id,distance_km,duration_min) VALUES (?,?,?,?)", rows);
            state("matrix_updated_at", LocalDateTime.now().format(DISPLAY));
        });
        log.info("道路矩阵已更新：{}/{} 条", rows.size(), TOTAL);
        return true;
    }

    private JsonNode request(String origins, double[] destination) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String url = "https://restapi.amap.com/v3/distance?key=" + encode(key)
                        + "&origins=" + encode(origins)
                        + "&destination=" + encode(destination[0] + "," + destination[1])
                        + "&type=1";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.warn("高德距离接口 HTTP {}", response.statusCode());
                    return null;
                }
                JsonNode root = json.readTree(response.body());
                if ("1".equals(root.path("status").asText())) return root;
                String info = root.path("info").asText();
                if (attempt == 0 && info.contains("QPS")) {
                    pause(1600);
                    continue;
                }
                log.warn("高德距离接口失败：{}", info);
                return null;
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().replace(key, "***");
                log.warn("高德距离接口异常：{}", message);
                return null;
            }
        }
        return null;
    }

    private void state(String key, String value) {
        int count = db.update("UPDATE app_state SET state_value=? WHERE state_key=?", value, key);
        if (count == 0) db.update("INSERT INTO app_state VALUES (?,?)", key, value);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static void pause() { pause(1100); }

    private static void pause(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static double[] gcj(Region region) {
        double[] wgs = DemoLocations.forRegion(region.id());
        return wgs84ToGcj02(wgs[1], wgs[0]);
    }

    private static double[] wgs84ToGcj02(double lng, double lat) {
        if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) return new double[]{lng, lat};
        double pi = Math.PI, axis = 6378245.0, ee = 0.00669342162296594323;
        double dLat = transformLat(lng - 105, lat - 35);
        double dLng = transformLng(lng - 105, lat - 35);
        double radLat = lat / 180 * pi;
        double magic = Math.sin(radLat);
        magic = 1 - ee * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180) / ((axis * (1 - ee)) / (magic * sqrtMagic) * pi);
        dLng = (dLng * 180) / (axis / sqrtMagic * Math.cos(radLat) * pi);
        return new double[]{lng + dLng, lat + dLat};
    }

    private static double transformLat(double x, double y) {
        double ret = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        ret += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
        ret += (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        ret += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
        ret += (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
        return ret;
    }
}
