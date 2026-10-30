package cn.swjtu.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static cn.swjtu.dispatch.Models.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:dispatch-test;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class DispatchServiceTest {
    @Autowired DispatchService service;
    @Autowired JdbcTemplate db;
    @BeforeEach void reset() {
        db.update("DELETE FROM road_matrix");
        db.update("DELETE FROM app_state WHERE state_key='matrix_updated_at'");
        service.reset("morning");
    }

    @Test void planReservesCapacityAndCannotDoubleBookVehicles() {
        Snapshot s = service.generate();
        assertTrue(s.tasks().size() >= 2);
        for (Task t : s.tasks()) { assertTrue(t.quantity() > 0 && t.quantity() <= 20); assertEquals("PLANNED", t.status()); }
        for (RegionView r : s.regions()) { assertTrue(r.stock() - r.reservedOut() >= 0); assertTrue(r.stock() + r.reservedIn() <= r.capacity()); }
        assertEquals(500, s.totalBikes());
        for (int vehicle = 1; vehicle <= 2; vehicle++) {
            final int v = vehicle;
            List<Integer> sequences = s.tasks().stream().filter(t -> t.vehicle() == v).map(Task::sequence).sorted().toList();
            if (!sequences.isEmpty()) {
                assertEquals(java.util.stream.IntStream.rangeClosed(1, sequences.size()).boxed().toList(), sequences);
                assertTrue(sequences.size() <= 3);
            }
        }
        assertThrows(ResponseStatusException.class, () -> service.generate());
        assertEquals(s.tasks().size(), service.snapshot().tasks().size());
    }
    @Test void advanceCanMoveOneMinuteAtATime() {
        int before = service.snapshot().minute();
        Snapshot after = service.advance(1);
        assertEquals(before + 1, after.minute());
        assertThrows(ResponseStatusException.class, () -> service.advance(0));
        assertThrows(ResponseStatusException.class, () -> service.advance(61));
    }

    @Test void adminRolesGateMutations() {
        AdminLogin viewer = service.adminLogin("viewer", "123456");
        assertThrows(ResponseStatusException.class, () -> service.requireOperator(viewer.token()));
        AdminLogin operator = service.adminLogin("operator", "123456");
        assertEquals("OPERATOR", service.requireOperator(operator.token()).role());
        service.adminLogout(operator.token());
        assertThrows(ResponseStatusException.class, () -> service.requireOperator(operator.token()));
    }

    @Test void riderAcceptAndCompleteMovesOneBikeAndPays() {
        RiderLogin login = service.login("rider01", "123456");
        assertNotNull(login.token());
        assertThrows(ResponseStatusException.class, () -> service.login("rider01", "wrong"));
        RiderState state = service.riderState(login.token());
        assertFalse(state.offers().isEmpty());
        RiderOffer offer = state.offers().get(0);
        int total = service.snapshot().totalBikes();
        state = service.acceptRide(login.token(), offer.id());
        assertNotNull(state.activeRide());
        assertThrows(ResponseStatusException.class, () -> service.acceptRide(login.token(), offer.id()));
        state = service.completeRide(login.token(), state.activeRide().id());
        assertNull(state.activeRide());
        assertEquals(1, state.completedRides());
        assertTrue(state.walletCents() >= offer.amountCents());
        assertEquals(total, service.snapshot().totalBikes());
        assertEquals("COMPLETED", state.history().get(0).status());
        assertEquals(1, service.riderRecords().rides().size());
        assertEquals(offer.amountCents(), service.riderRecords().walletCents());
        assertEquals(4, service.appUsers().size());
        assertTrue(service.appUsers().get(0).walletCents() >= offer.amountCents());
        service.logout(login.token());
        assertThrows(ResponseStatusException.class, () -> service.riderState(login.token()));
    }

    @Test void pickupAndArrivalConserveBikesAndRejectRepeatedStart() {
        Task t = service.generate().tasks().stream().filter(task -> task.sequence() == 1).findFirst().orElseThrow();
        int initialDestination = service.snapshot().regions().stream().filter(r -> r.id() == t.destinationId()).findFirst().orElseThrow().stock();
        Snapshot started = service.action(t.id(), "start");
        assertEquals(500, started.totalBikes()); assertEquals(t.quantity(), started.transitBikes());
        assertEquals(500 - t.quantity(), started.groundBikes());
        assertThrows(ResponseStatusException.class, () -> service.action(t.id(), "start"));
        assertThrows(ResponseStatusException.class, () -> service.action(t.id(), "cancel"));
        Snapshot completed = started;
        for (int i = 0; i < 60 && !status(t.id()).equals("COMPLETED"); i++) completed = service.advance();
        assertEquals("COMPLETED", status(t.id()));
        assertTrue(completed.regions().stream().filter(r -> r.id() == t.destinationId()).findFirst().orElseThrow().stock() >= initialDestination + t.quantity());
        for (int i = 0; i < 60 && completed.tasks().stream().anyMatch(task -> task.status().equals("RUNNING")); i++) completed = service.advance();
        assertEquals(0, completed.transitBikes()); assertEquals(500, completed.totalBikes());
    }
    @Test void incomingReservationsPreventCapacityReduction() {
        Task t = service.generate().tasks().getFirst();
        RegionView destination = service.snapshot().regions().stream().filter(r -> r.id() == t.destinationId()).findFirst().orElseThrow();
        assertThrows(ResponseStatusException.class, () -> service.update(destination.id(), new RegionUpdate("区域", destination.stock(), 0)));
        assertEquals(destination.capacity(), service.snapshot().regions().stream().filter(r -> r.id() == destination.id()).findFirst().orElseThrow().capacity());
    }
    @Test void cancellingReleasesVehicleAndReservations() {
        Task task = service.generate().tasks().stream().filter(t -> t.sequence() == 1).findFirst().orElseThrow();
        Snapshot cancelled = service.action(task.id(), "cancel");
        for (Task leg : cancelled.tasks()) if (task.tripId().equals(leg.tripId())) assertEquals("CANCELLED", leg.status());
        Snapshot replanned = service.generate();
        assertTrue(replanned.tasks().stream().filter(t -> t.status().equals("PLANNED")).count() >= 2);
        assertEquals(500, replanned.totalBikes());
    }
    @Test void editValidatesAndPersistsSettings() {
        service.update(1, new RegionUpdate("西门测试区域", 85, 30));
        RegionView r = service.snapshot().regions().getFirst();
        assertEquals("西门测试区域", r.name()); assertEquals(85, r.capacity()); assertEquals(30, r.safetyStock());
        assertThrows(ResponseStatusException.class, () -> service.update(1, new RegionUpdate("", 90, 30)));
        assertThrows(ResponseStatusException.class, () -> service.update(1, new RegionUpdate("区域", 20, 30)));
    }
    @Test void experimentIsReproducibleAndConservesEveryBikeInAllScenarios() {
        for (String scenario : DemandModel.SCENARIOS) {
            service.reset(scenario);
            ExperimentResult a = service.experiment(42), b = service.experiment(42);
            assertEquals(a,b); assertEquals(3, a.results().size());
            for (Experiment r : a.results()) {
                assertEquals(1260, r.requested()); assertEquals(500, r.endingTotal());
                assertTrue(r.served() <= r.requested()); assertTrue(r.serviceRate() >= 0 && r.serviceRate() <= 100);
            }
            assertEquals(500, service.snapshot().groundBikes()); assertTrue(service.snapshot().tasks().isEmpty());
        }
    }
    @Test void roadMatrixIsUsedForExperimentsAndDispatch() {
        for (int from = 1; from <= 12; from++) for (int to = 1; to <= 12; to++) {
            if (from != to) db.update("INSERT INTO road_matrix(origin_id,destination_id,distance_km,duration_min) VALUES (?,?,?,?)", from, to, 2.0, 10);
        }
        db.update("INSERT INTO app_state(state_key,state_value) VALUES ('matrix_updated_at','10-23 15:40')");
        ExperimentResult experiment = service.experiment(42);
        assertTrue(experiment.model().contains("高德道路矩阵"));
        for (Experiment result : experiment.results()) assertEquals(500, result.endingTotal());
        Snapshot s = service.generate();
        assertTrue(s.distanceModel().contains("高德道路矩阵"));
        for (Task task : s.tasks()) assertEquals(2.0, task.distance());
    }
    @Test void generatedTasksExplainWhyTheyWerePlanned() {
        Snapshot s = service.generate();
        assertTrue(s.tasks().size() >= 2);
        for (Task t : s.tasks()) {
            assertNotNull(t.reason());
            assertFalse(t.reason().isBlank());
            assertTrue(t.reason().contains("缺口"));
            assertTrue(t.reason().contains("第 " + t.sequence() + " 单"));
            assertNotNull(t.deadlineMinute());
            assertTrue(t.reason().contains("最晚"));
        }
        assertEquals(s.tasks().size(), service.snapshot().tasks().stream().filter(t -> t.reason() != null).count());
    }
    @Test void multiSeedSummaryIsReproducibleAndKeepsEveryRunConsistent() {
        ExperimentSummary a = service.experimentSummary(42, 3), b = service.experimentSummary(42, 3);
        assertEquals(a, b);
        assertEquals(3, a.runCount()); assertEquals(3, a.results().size()); assertEquals(3, a.runs().size());
        assertEquals(List.of(42L, 43L, 44L), a.runs().stream().map(ExperimentRun::seed).toList());
        for (PolicySummary policy : a.results()) {
            assertTrue(policy.serviceRateMin() <= policy.serviceRateAvg());
            assertTrue(policy.serviceRateAvg() <= policy.serviceRateMax());
            assertTrue(policy.serviceRateMin() >= 0 && policy.serviceRateMax() <= 100);
        }
        for (ExperimentRun run : a.runs()) {
            assertEquals(3, run.results().size());
            for (Experiment result : run.results()) {
                assertEquals(1260, result.requested()); assertEquals(500, result.endingTotal());
            }
        }
        assertEquals(500, service.snapshot().groundBikes());
        assertTrue(service.snapshot().tasks().isEmpty());
    }
    @Test void multiSeedSummaryRejectsOutOfRangeRuns() {
        assertThrows(ResponseStatusException.class, () -> service.experimentSummary(42, 0));
        assertThrows(ResponseStatusException.class, () -> service.experimentSummary(42, 11));
        assertThrows(ResponseStatusException.class, () -> service.experimentSummary(999999, 5));
    }
    @Test void experimentRejectsActiveDispatch() {
        service.generate(); assertThrows(ResponseStatusException.class, () -> service.experiment(42));
    }
    @Test void invalidScenarioDoesNotDestroyCurrentState() {
        service.generate(); Snapshot before = service.snapshot();
        assertThrows(ResponseStatusException.class, () -> service.reset("unknown"));
        assertEquals(before, service.snapshot());
    }
    @Test void plannerComparisonCoversAllScenarios() {
        List<PlanComparisonRow> rows = service.planComparisonSummary();
        assertEquals(3, rows.size());
        assertEquals(Set.of("morning", "evening", "weekend"), new HashSet<>(rows.stream().map(PlanComparisonRow::scenario).toList()));
        for (PlanComparisonRow row : rows) {
            if (row.legs() == 0) continue;
            assertTrue(row.greedyKm() > 0 && row.greedyMinutes() > 0);
            if (row.ortoolsKm() != null) assertTrue(row.ortoolsKm() > 0 && row.ortoolsMinutes() > 0);
        }
    }

    @Test void plannerComparisonReportsBothPlans() {
        PlanComparison comparison = service.planComparison();
        assertTrue(comparison.legs() >= 2);
        assertTrue(comparison.greedyKm() > 0);
        assertTrue(comparison.greedyMinutes() > 0);
        if (comparison.ortoolsAvailable()) {
            assertTrue(comparison.ortoolsKm() > 0);
            assertTrue(comparison.ortoolsMinutes() > 0);
        }
    }

    @Test void orToolsPlannerCouplesLoadsWithinCapacity() {
        double[][] km = new double[13][13];
        for (int from = 1; from <= 12; from++) for (int to = 1; to <= 12; to++) if (from != to) km[from][to] = Math.abs(from - to);
        List<RouteOptimizer.Leg> legs = List.of(
                new RouteOptimizer.Leg(1, 12, 10, 10, 10),
                new RouteOptimizer.Leg(2, 11, 10, 10, 10),
                new RouteOptimizer.Leg(3, 10, 10, 10, 10),
                new RouteOptimizer.Leg(4, 9, 10, 10, 10));
        List<RouteOptimizer.Route> routes = RouteOptimizer.plan(km, legs);
        assertNotNull(routes, "OR-Tools planner should be available");
        assertEquals(2, routes.size());
        assertEquals(4, routes.stream().flatMap(route -> route.stops().stream()).filter(RouteOptimizer.Stop::pickup).count());
        for (RouteOptimizer.Route route : routes) {
            int load = 0;
            for (RouteOptimizer.Stop stop : route.stops()) {
                load += stop.quantity();
                assertTrue(load >= 0 && load <= 20, "load must stay within capacity");
            }
            assertEquals(0, load, "route must end empty");
        }
    }

    @Test void canCancelRemainingStopsOfRunningTrip() {
        Snapshot s = service.generate();
        Map<String, List<Task>> trips = new TreeMap<>();
        for (Task t : s.tasks()) if (t.tripId() != null) trips.computeIfAbsent(t.tripId(), k -> new ArrayList<>()).add(t);
        List<Task> trip = trips.values().stream().filter(legs -> legs.size() >= 2).findFirst().orElseThrow();
        Task first = trip.stream().min(Comparator.comparingInt(Task::pickupSeq)).orElseThrow();
        service.action(first.id(), "start");
        Task later = trip.stream().filter(t -> !t.id().equals(first.id())).filter(t -> t.status().equals("PLANNED")).findFirst().orElseThrow();
        service.action(later.id(), "cancel");
        assertEquals("CANCELLED", status(later.id()));
        assertEquals("RUNNING", status(first.id()));
        for (int i = 0; i < 60 && service.snapshot().tasks().stream().anyMatch(t -> t.status().equals("RUNNING")); i++) service.advance();
        assertEquals("COMPLETED", status(first.id()));
        assertEquals(500, service.snapshot().totalBikes());
    }

    @Test void tripRunsItsStopsUntilDone() {
        Snapshot s = service.generate();
        Map<String, List<Task>> trips = new TreeMap<>();
        for (Task t : s.tasks()) if (t.tripId() != null) trips.computeIfAbsent(t.tripId(), k -> new ArrayList<>()).add(t);
        List<Task> trip = trips.values().stream().filter(legs -> legs.size() >= 2).findFirst().orElseThrow();
        Task first = trip.stream().min(Comparator.comparingInt(Task::pickupSeq)).orElseThrow();
        for (Task t : trip) {
            assertTrue(t.pickupSeq() < t.deliverySeq());
            assertTrue(t.reason().contains("站取") && t.reason().contains("站送"));
        }
        service.action(first.id(), "start");
        assertEquals("RUNNING", status(first.id()));
        for (int i = 0; i < 100 && service.snapshot().tasks().stream().anyMatch(t -> t.status().equals("PLANNED") || t.status().equals("RUNNING")); i++) service.advance();
        Snapshot done = service.snapshot();
        for (Task t : trip) assertEquals("COMPLETED", done.tasks().stream().filter(x -> x.id().equals(t.id())).findFirst().orElseThrow().status());
        assertEquals(500, done.totalBikes());
        assertEquals(0, done.transitBikes());
    }

    private String status(String id) {
        return service.snapshot().tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow().status();
    }

    @Test void demoMapPositionsAreStableAndInXipuArea() {
        Snapshot before = service.snapshot();
        assertEquals(12, before.regions().size());
        for (RegionView region : before.regions()) {
            assertTrue(region.latitude() > 30.75 && region.latitude() < 30.78);
            assertTrue(region.longitude() > 103.96 && region.longitude() < 104.00);
        }
        service.update(1, new RegionUpdate("自定义区域名", 70, 28));
        assertEquals(before.regions().getFirst().latitude(), service.snapshot().regions().getFirst().latitude());
        assertEquals(before.regions().getFirst().longitude(), service.snapshot().regions().getFirst().longitude());
    }
}
