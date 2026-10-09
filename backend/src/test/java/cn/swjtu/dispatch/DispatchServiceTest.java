package cn.swjtu.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static cn.swjtu.dispatch.Models.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:dispatch-test;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class DispatchServiceTest {
    @Autowired DispatchService service;
    @BeforeEach void reset() { service.reset("morning"); }

    @Test void planReservesCapacityAndCannotDoubleBookVehicles() {
        Snapshot s = service.generate();
        assertEquals(2, s.tasks().size());
        for (Task t : s.tasks()) { assertTrue(t.quantity() > 0 && t.quantity() <= 20); assertEquals("PLANNED", t.status()); }
        for (RegionView r : s.regions()) { assertTrue(r.stock() - r.reservedOut() >= 0); assertTrue(r.stock() + r.reservedIn() <= r.capacity()); }
        assertEquals(500, s.totalBikes());
        assertThrows(ResponseStatusException.class, () -> service.generate());
        assertEquals(2, service.snapshot().tasks().size());
    }
    @Test void pickupAndArrivalConserveBikesAndRejectRepeatedStart() {
        Task t = service.generate().tasks().getFirst();
        int initialDestination = service.snapshot().regions().stream().filter(r -> r.id() == t.destinationId()).findFirst().orElseThrow().stock();
        Snapshot started = service.action(t.id(), "start");
        assertEquals(500, started.totalBikes()); assertEquals(t.quantity(), started.transitBikes());
        assertEquals(500 - t.quantity(), started.groundBikes());
        assertThrows(ResponseStatusException.class, () -> service.action(t.id(), "start"));
        assertThrows(ResponseStatusException.class, () -> service.action(t.id(), "cancel"));
        Snapshot completed = started;
        for (int i = 0; i < Math.ceil(t.duration() / 5.0); i++) completed = service.advance();
        assertEquals(0, completed.transitBikes()); assertEquals(500, completed.totalBikes());
        assertEquals("COMPLETED", completed.tasks().stream().filter(task -> task.id().equals(t.id())).findFirst().orElseThrow().status());
        assertEquals(initialDestination + t.quantity(), completed.regions().stream().filter(r -> r.id() == t.destinationId()).findFirst().orElseThrow().stock());
    }
    @Test void incomingReservationsPreventCapacityReduction() {
        Task t = service.generate().tasks().getFirst();
        RegionView destination = service.snapshot().regions().stream().filter(r -> r.id() == t.destinationId()).findFirst().orElseThrow();
        assertThrows(ResponseStatusException.class, () -> service.update(destination.id(), new RegionUpdate("区域", destination.stock(), 0)));
        assertEquals(destination.capacity(), service.snapshot().regions().stream().filter(r -> r.id() == destination.id()).findFirst().orElseThrow().capacity());
    }
    @Test void cancellingReleasesVehicleAndReservations() {
        Task task = service.generate().tasks().getFirst();
        service.action(task.id(), "cancel");
        Snapshot replanned = service.generate();
        assertEquals(2, replanned.tasks().stream().filter(t -> t.status().equals("PLANNED")).count());
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
    @Test void experimentRejectsActiveDispatch() {
        service.generate(); assertThrows(ResponseStatusException.class, () -> service.experiment(42));
    }
    @Test void invalidScenarioDoesNotDestroyCurrentState() {
        service.generate(); Snapshot before = service.snapshot();
        assertThrows(ResponseStatusException.class, () -> service.reset("unknown"));
        assertEquals(before, service.snapshot());
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
