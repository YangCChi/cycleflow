package cn.swjtu.dispatch;

import java.util.List;

public final class Models {
    private Models() {}
    public record Region(int id, String name, String kind, int stock, int capacity, int safetyStock, double x, double y) {}
    public record RegionView(int id, String name, String kind, int stock, int capacity, int safetyStock,
                             double x, double y, int predictedOut, int predictedIn, int target,
                             int shortage, int surplus, String status, int reservedOut, int reservedIn) {}
    public record Task(String id, int sourceId, int destinationId, int quantity, int vehicle, String status,
                       double distance, int duration, int createdMinute, Integer startedMinute, Integer completedMinute) {}
    public record Snapshot(List<RegionView> regions, List<Task> tasks, int minute, String scenario,
                           int totalBikes, int groundBikes, int transitBikes, int completedTasks, double totalDistance) {}
    public record ForecastPoint(String time, int outflow, int inflow, int projectedStock) {}
    public record RegionUpdate(String name, Integer capacity, Integer safetyStock) {}
    public record Experiment(String strategy, int requested, int served, double serviceRate,
                             int shortageMinutes, double distance, int moved, int endingTotal) {}
    public record ExperimentResult(long seed, String scenario, int durationMinutes, String model,
                                   List<Experiment> results) {}
}
