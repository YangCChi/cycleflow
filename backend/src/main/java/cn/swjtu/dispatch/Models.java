package cn.swjtu.dispatch;

import java.util.List;

public final class Models {
    private Models() {}
    public record Region(int id, String name, String kind, int stock, int capacity, int safetyStock, double x, double y) {}
    public record RegionView(int id, String name, String kind, int stock, int capacity, int safetyStock,
                             double x, double y, double latitude, double longitude,
                             int predictedOut, int predictedIn, int target,
                             int shortage, int surplus, String status, int reservedOut, int reservedIn) {}
    public record Task(String id, int sourceId, int destinationId, int quantity, int vehicle, String status,
                       double distance, int duration, int createdMinute, Integer startedMinute, Integer completedMinute,
                       String reason, String tripId, int sequence, Integer pickupSeq, Integer deliverySeq, Integer deadlineMinute) {}
    public record Snapshot(List<RegionView> regions, List<Task> tasks, int minute, String scenario,
                           int totalBikes, int groundBikes, int transitBikes, int completedTasks, double totalDistance,
                           String distanceModel) {}
    public record RoadMatrixStatus(boolean configured, int entries, int total, String updatedAt, boolean loading) {}
    public record ForecastPoint(String time, int outflow, int inflow, int projectedStock) {}
    public record RiderOffer(String id, int fromId, int toId, int amountCents, double km) {}
    public record RiderRide(String id, int fromId, int toId, int amountCents, String status, int createdMinute, Integer completedMinute, Integer userId) {}
    public record RiderState(int walletCents, int completedRides, List<RiderOffer> offers, RiderRide activeRide, List<RiderRide> history) {}
    public record RiderRecords(int walletCents, int completedRides, List<RiderRide> rides) {}
    public record AppUser(int id, String username, String nickname, String phone, String status, int createdMinute,
                          int walletCents, int completedRides, int activeRides, Integer lastRideMinute) {}
    public record RegionUpdate(String name, Integer capacity, Integer safetyStock) {}
    public record Experiment(String strategy, int requested, int served, double serviceRate,
                             int shortageMinutes, double distance, int moved, int endingTotal) {}
    public record ExperimentResult(long seed, String scenario, int durationMinutes, String model,
                                   List<Experiment> results) {}
    public record PolicySummary(String strategy, double serviceRateAvg, double serviceRateMin, double serviceRateMax,
                                double shortageMinutesAvg, double distanceAvg, double movedAvg) {}
    public record PlanComparisonRow(String scenario, int samples, int legs,
                                    double greedyKm, double greedyKmMin, double greedyKmMax, int greedyMinutes,
                                    Double ortoolsKm, Double ortoolsKmMin, Double ortoolsKmMax, Integer ortoolsMinutes) {}
    public record PlanComparison(int legs, double greedyKm, int greedyMinutes,
                                 Double ortoolsKm, Integer ortoolsMinutes, boolean ortoolsAvailable) {}
    public record ExperimentRun(long seed, List<Experiment> results) {}
    public record ExperimentSummary(long baseSeed, int runCount, String scenario, int durationMinutes, String model,
                                    List<PolicySummary> results, List<ExperimentRun> runs) {}
}
