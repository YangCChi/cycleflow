package cn.swjtu.dispatch;

/**
 * Map positions for the fixed demo regions. These are illustrative markers,
 * not surveyed shared-bicycle parking locations. IDs 2 and 3 use public
 * transport station coordinates as geographic references.
 */
final class DemoLocations {
    private DemoLocations() {}

    static double[] forRegion(int id) {
        return switch (id) {
            case 1 -> new double[] {30.7642, 103.9774};
            case 2 -> new double[] {30.7612337, 103.9811368};
            case 3 -> new double[] {30.7595296, 103.9704587};
            case 4 -> new double[] {30.7672, 103.9770};
            case 5 -> new double[] {30.7700, 103.9745};
            case 6 -> new double[] {30.7650, 103.9685};
            case 7 -> new double[] {30.7733, 103.9725};
            case 8 -> new double[] {30.7702, 103.9660};
            case 9 -> new double[] {30.7580, 103.9848};
            case 10 -> new double[] {30.7630, 103.9730};
            case 11 -> new double[] {30.7575, 103.9745};
            case 12 -> new double[] {30.7750, 103.9850};
            default -> throw new IllegalArgumentException("没有区域 " + id + " 的演示地图位置");
        };
    }
}
