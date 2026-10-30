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
    @GetMapping("/health") public Map<String,Object> health() {
        return Map.of("status", "ok", "planner", RouteOptimizer.available(), "matrix", matrix.status());
    }
    @GetMapping("/state") public Snapshot state() { return service.snapshot(); }
    @PatchMapping("/regions/{id}") public Snapshot update(@PathVariable int id, @RequestBody RegionUpdate update) { return service.update(id, update); }
    @GetMapping("/regions/{id}/forecast") public List<ForecastPoint> forecast(@PathVariable int id) { return service.forecast(id); }
    @PostMapping("/dispatch/generate") public Snapshot generate() { return service.generate(); }
    @PostMapping("/dispatch/{id}/{action}") public Snapshot action(@PathVariable String id, @PathVariable String action) { return service.action(id, action); }
    @PostMapping("/simulation/advance") public Snapshot advance(@RequestParam(defaultValue = "5") int minutes) { return service.advance(minutes); }
    @PostMapping("/simulation/reset") public Snapshot reset(@RequestBody Map<String,String> body) { return service.reset(body.getOrDefault("scenario", "morning")); }
    @GetMapping("/experiments") public ExperimentResult experiment(@RequestParam(defaultValue="42") long seed) { return service.experiment(seed); }
    @GetMapping("/experiments/summary") public ExperimentSummary experimentSummary(@RequestParam(defaultValue="42") long seed, @RequestParam(defaultValue="5") int runs) { return service.experimentSummary(seed, runs); }
    @GetMapping("/dispatch/compare") public PlanComparison comparePlans() { return service.planComparison(); }
    @GetMapping("/dispatch/compare/summary") public List<PlanComparisonRow> compareAllPlans() { return service.planComparisonSummary(); }
    @GetMapping("/rider/state") public RiderState riderState() { return service.riderState(); }
    @GetMapping("/rider/records") public RiderRecords riderRecords() { return service.riderRecords(); }
    @GetMapping("/data/users") public List<AppUser> appUsers() { return service.appUsers(); }
    @PostMapping("/rider/accept/{offerId}") public RiderState acceptRide(@PathVariable String offerId) { return service.acceptRide(offerId); }
    @PostMapping("/rider/complete/{rideId}") public RiderState completeRide(@PathVariable String rideId) { return service.completeRide(rideId); }
    @GetMapping("/road-matrix") public RoadMatrixStatus roadMatrix() { return matrix.status(); }
    @PostMapping("/road-matrix/refresh") public RoadMatrixStatus refreshRoadMatrix() { matrix.refreshNow(); return matrix.status(); }
}
