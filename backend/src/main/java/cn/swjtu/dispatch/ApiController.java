package cn.swjtu.dispatch;

import static cn.swjtu.dispatch.Models.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final DispatchService service;
    private final RoadMatrix matrix;
    public ApiController(DispatchService service, RoadMatrix matrix) { this.service = service; this.matrix = matrix; }
    @PostMapping("/admin/login") public AdminLogin adminLogin(@RequestBody Map<String,String> body) { return service.adminLogin(body.get("username"), body.get("password")); }
    @PostMapping("/admin/logout") public Map<String,Object> adminLogout(@RequestHeader(value="X-Admin-Token", required=false) String token) { service.adminLogout(token); return Map.of("ok", true); }
    @GetMapping("/admin/me") public AdminUser adminMe(@RequestHeader(value="X-Admin-Token", required=false) String token) { return service.admin(token); }
    @GetMapping("/health") public Map<String,Object> health() {
        return Map.of("status", "ok", "planner", RouteOptimizer.available(), "matrix", matrix.status());
    }
    @GetMapping("/state") public Snapshot state() { return service.snapshot(); }
    @PatchMapping("/regions/{id}") public Snapshot update(@RequestHeader(value="X-Admin-Token", required=false) String admin, @PathVariable int id, @RequestBody RegionUpdate update) { service.requireOperator(admin); return service.update(id, update); }
    @GetMapping("/regions/{id}/forecast") public List<ForecastPoint> forecast(@PathVariable int id) { return service.forecast(id); }
    @PostMapping("/dispatch/generate") public Snapshot generate(@RequestHeader(value="X-Admin-Token", required=false) String admin) { service.requireOperator(admin); return service.generate(); }
    @PostMapping("/dispatch/{id}/{action}") public Snapshot action(@RequestHeader(value="X-Admin-Token", required=false) String admin, @PathVariable String id, @PathVariable String action) { service.requireOperator(admin); return service.action(id, action); }
    @PostMapping("/simulation/advance") public Snapshot advance(@RequestHeader(value="X-Admin-Token", required=false) String admin, @RequestParam(defaultValue = "5") int minutes) { service.requireOperator(admin); return service.advance(minutes); }
    @PostMapping("/simulation/reset") public Snapshot reset(@RequestHeader(value="X-Admin-Token", required=false) String admin, @RequestBody Map<String,String> body) { service.requireOperator(admin); return service.reset(body.getOrDefault("scenario", "morning")); }
    @GetMapping("/experiments") public ExperimentResult experiment(@RequestParam(defaultValue="42") long seed) { return service.experiment(seed); }
    @GetMapping("/experiments/summary") public ExperimentSummary experimentSummary(@RequestParam(defaultValue="42") long seed, @RequestParam(defaultValue="5") int runs) { return service.experimentSummary(seed, runs); }
    @GetMapping("/dispatch/compare") public PlanComparison comparePlans() { return service.planComparison(); }
    @GetMapping("/dispatch/compare/summary") public List<PlanComparisonRow> compareAllPlans() { return service.planComparisonSummary(); }
    @PostMapping("/rider/login") public RiderLogin login(@RequestBody Map<String,String> body) { return service.login(body.get("username"), body.get("password")); }
    @PostMapping("/rider/logout") public Map<String,Object> logout(@RequestHeader(value="X-Rider-Token", required=false) String token) { service.logout(token); return Map.of("ok", true); }
    @GetMapping("/rider/state") public RiderState riderState(@RequestHeader(value="X-Rider-Token", required=false) String token) { return service.riderState(token); }
    @GetMapping("/rider/records") public RiderRecords riderRecords(@RequestHeader(value="X-Admin-Token", required=false) String admin) { service.admin(admin); return service.riderRecords(); }
    @GetMapping("/data/users") public List<AppUser> appUsers(@RequestHeader(value="X-Admin-Token", required=false) String admin) { service.admin(admin); return service.appUsers(); }
    @PostMapping("/rider/accept/{offerId}") public RiderState acceptRide(@RequestHeader(value="X-Rider-Token", required=false) String token, @PathVariable String offerId) { return service.acceptRide(token, offerId); }
    @PostMapping("/rider/complete/{rideId}") public RiderState completeRide(@RequestHeader(value="X-Rider-Token", required=false) String token, @PathVariable String rideId) { return service.completeRide(token, rideId); }
    @GetMapping("/road-matrix") public RoadMatrixStatus roadMatrix() { return matrix.status(); }
    @PostMapping("/road-matrix/refresh") public RoadMatrixStatus refreshRoadMatrix(@RequestHeader(value="X-Admin-Token", required=false) String admin) { service.requireOperator(admin); matrix.refreshNow(); return matrix.status(); }
}
