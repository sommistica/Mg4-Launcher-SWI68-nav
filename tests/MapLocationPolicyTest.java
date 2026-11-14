import com.mg4.launcher.svc.MapLocationPolicy;

public final class MapLocationPolicyTest {
    public static void main(String[] args) {
        check(MapLocationPolicy.fresh(0, true, 5));
        check(MapLocationPolicy.fresh(30_000, true, 200));
        check(!MapLocationPolicy.fresh(30_001, true, 5));
        check(!MapLocationPolicy.fresh(-1, true, 5));
        check(!MapLocationPolicy.fresh(0, false, 5));
        check(!MapLocationPolicy.fresh(0, true, Float.NaN));
        check(!MapLocationPolicy.fresh(0, true, -1));
        check(!MapLocationPolicy.fresh(0, true, 201));
        // A rendered map survives the point at which its position becomes stale.
        check(MapLocationPolicy.retainRenderedMap(true, 30_001));
        check(MapLocationPolicy.retainRenderedMap(true, 120_000));
        check(!MapLocationPolicy.retainRenderedMap(true, 120_001));
        check(!MapLocationPolicy.retainRenderedMap(false, 1000));
        check(!MapLocationPolicy.retainRenderedMap(true, -1));
        check(MapLocationPolicy.INACTIVE_RENDERER_MS > 3000);
        System.out.println("Location freshness and rendered-map retention checks passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
