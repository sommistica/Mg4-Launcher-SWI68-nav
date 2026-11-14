import com.mg4.launcher.svc.MapStoragePolicy;

public final class MapStoragePolicyTest {
    public static void main(String[] args) {
        long reserve = MapStoragePolicy.reserve(true);
        require(!MapStoragePolicy.fits(reserve + 99, 100, 0, reserve));
        require(MapStoragePolicy.fits(reserve + 100, 100, 0, reserve));
        require(MapStoragePolicy.fits(reserve + 40, 100, 60, reserve));
        require(!MapStoragePolicy.fits(reserve - 1, 100, 100, reserve));
        require(!MapStoragePolicy.fits(reserve + 40, 100, 101, reserve));
        require(!MapStoragePolicy.fits(-1, 100, 0, reserve));
        require(!MapStoragePolicy.fits(Long.MAX_VALUE, Long.MAX_VALUE, 0, 1));
        require(MapStoragePolicy.fits(Long.MAX_VALUE, Long.MAX_VALUE, 1, 1));
        require(MapStoragePolicy.reserve(false) < reserve);
        System.out.println("Map storage headroom checks passed");
    }
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
}
