package cn.swjtu.dispatch;

import static cn.swjtu.dispatch.Models.*;
import java.util.*;

/** Explicit scenario model for a prototype, not a trained model or observed local demand. */
public final class DemandModel {
    private DemandModel() {}
    public static final Set<String> SCENARIOS = Set.of("morning", "evening", "weekend");
    public static int[] demand(Region region, String scenario, int minute) {
        double[] base = switch (region.kind()) {
            case "gate" -> new double[]{16, 7};
            case "metro" -> new double[]{20, 6};
            case "residential" -> new double[]{6, 13};
            default -> new double[]{7, 9};
        };
        if (scenario.equals("evening")) { double swap = base[0]; base[0] = base[1]; base[1] = swap; }
        if (scenario.equals("weekend")) base = region.kind().equals("commercial") ? new double[]{19, 8} : new double[]{8, 10};
        double wave = 1 + 0.2 * Math.sin((minute - 450) * Math.PI / 120 + region.id() * 0.35);
        return new int[]{Math.max(1, (int)Math.round(base[0] * wave)), Math.max(1, (int)Math.round(base[1] / wave))};
    }
    public static int target(Region r, String scenario, int minute) {
        int[] flow = demand(r, scenario, minute);
        return Math.max(r.safetyStock(), Math.min(r.capacity(), r.safetyStock() + flow[0] - flow[1]));
    }
    public static double distance(Region a, Region b) {
        // Demonstration grid distance with a detour factor; never presented as a surveyed road distance.
        return Math.round((Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y())) * 0.0042 * 10) / 10.0;
    }
    public static int duration(double distance, int quantity) { return Math.max(5, (int)Math.ceil(distance / 18 * 60 + quantity * 0.25 + 2)); }
    public static String time(int minute) { return "%02d:%02d".formatted((minute / 60) % 24, minute % 60); }
}
