package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** App-private, checksum-verified MBTiles. No coordinates, tile IDs or region selections are logged. */
public final class OfflineMaps {
    public static final class Region {
        public final String id, name, version, url, sha256;
        public final long bytes;
        Region(JSONObject j) throws Exception {
            id = j.getString("id"); name = j.getString("name"); version = j.getString("version");
            url = j.getString("url"); sha256 = j.getString("sha256"); bytes = j.getLong("bytes");
            if (!id.matches("[a-z0-9-]+") || !sha256.matches("[0-9a-f]{64}")
                    || !url.startsWith("https://") || bytes <= 0) throw new IOException("Invalid map catalog");
        }
    }
    public static final class Storage {
        public final String id, name;
        public final File directory;
        Storage(String id, String name, File directory) { this.id = id; this.name = name; this.directory = directory; }
        public boolean internal() { return "internal".equals(id); }
        public String volumeId() { return internal() ? "" : id.substring("usb:".length()); }
        private File spaceRoot() {
            File root = directory;
            while (root != null && !root.exists()) root = root.getParentFile();
            return root;
        }
        public long freeBytes() { File root = spaceRoot(); return root == null ? 0 : root.getUsableSpace(); }
        public long totalBytes() { File root = spaceRoot(); return root == null ? 0 : root.getTotalSpace(); }
    }
    public static final class Coverage {
        public final Region region;
        public final int minZoom, maxZoom;
        Coverage(Region r, int min, int max) { region = r; minZoom = min; maxZoom = max; }
    }
    private static OfflineMaps instance;
    public static synchronized OfflineMaps get(Context c) {
        if (instance == null) instance = new OfflineMaps(c.getApplicationContext());
        return instance;
    }
    public interface Listener { void changed(); }
    private final Context ctx;
    private File dir;
    private final SharedPreferences preferences;
    private String storageId;
    private List<Storage> storageChoices = new ArrayList<>();
    private long nextStorageCheck;
    private final List<Region> catalog = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, SQLiteDatabase> databases = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String active = "";
    private volatile int state; // 0 idle, 1 downloading, 2 verifying, 3 paused, 4 failed
    private volatile long received;
    private volatile boolean cancel;
    private volatile long revision;

    private OfflineMaps(Context c) {
        ctx = c;
        preferences = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        storageId = preferences.getString("map_storage", "internal");
        refreshStorage();
        try (InputStream in = c.getAssets().open("offline-map/catalog.json")) {
            JSONArray a = new JSONObject(new String(read(in, 100_000), "UTF-8")).getJSONArray("regions");
            for (int i = 0; i < a.length(); i++) catalog.add(new Region(a.getJSONObject(i)));
        } catch (Exception ignored) { MapDiagnostics.failure(MapDiagnostics.Failure.PACKAGE); }
        publishStorageDiagnostics();
    }
    public List<Region> regions() { return Collections.unmodifiableList(catalog); }
    public synchronized void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public synchronized void removeListener(Listener l) { listeners.remove(l); }
    private void changed() {
        main.post(() -> {
            List<Listener> copy;
            synchronized (OfflineMaps.this) { copy = new ArrayList<>(listeners); }
            for (Listener l : copy) l.changed();
        });
    }
    /** Only app-specific directories on mounted Android-reported volumes; never scan camera folders. */
    public synchronized void refreshStorage() {
        if (SystemClock.elapsedRealtime() < nextStorageCheck) return;
        nextStorageCheck = SystemClock.elapsedRealtime() + 2000;
        List<Storage> found = new ArrayList<>();
        found.add(new Storage("internal", "", new File(ctx.getFilesDir(), "offline-maps")));
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            File[] dirs = ctx.getExternalFilesDirs(null);
            if (dirs != null && sm != null) for (File external : dirs) {
                if (external == null) continue;
                StorageVolume volume = sm.getStorageVolume(external);
                if (volume == null || !volume.isRemovable() || volume.getUuid() == null
                        || !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(external))) continue;
                String id = "usb:" + volume.getUuid();
                boolean duplicate = false; for (Storage existing : found) duplicate |= existing.id.equals(id);
                if (!duplicate) found.add(new Storage(id, volume.getDescription(ctx), new File(external, "offline-maps")));
            }
        } catch (Exception ignored) { MapDiagnostics.failure(MapDiagnostics.Failure.STORAGE_ACCESS); }
        String before = ""; for (Storage storage : storageChoices) before += storage.id + ";";
        String after = ""; for (Storage storage : found) after += storage.id + ";";
        storageChoices = found;
        File next = null;
        for (Storage storage : found) if (storage.id.equals(storageId)) next = storage.directory;
        if (next != null && !next.isDirectory() && !next.mkdirs()) next = null;
        if (!Objects.equals(next, dir)) {
            if (busy()) cancel = true;
            releaseDatabases(); dir = next; revision++;
        }
        publishStorageDiagnostics();
        if (!before.equals(after) || next == null) changed();
    }
    private void publishStorageDiagnostics() {
        int removable = 0, installed = 0;
        for (Storage storage : storageChoices) if (!storage.internal()) removable++;
        for (Region region : catalog) if (dir != null && file(region).isFile()) installed++;
        MapDiagnostics.storage("internal".equals(storageId), dir != null, removable,
                dir == null ? 0 : dir.getUsableSpace(), installed);
        snapshotDisk();
    }
    public synchronized void snapshotDisk() {
        long mapFiles = 0, partialFiles = 0;
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) for (File file : files) {
            if (file.getName().endsWith(".mbtiles")) mapFiles += file.length();
            else if (file.getName().endsWith(".part")) partialFiles += file.length();
        }
        MapDiagnostics.mapDisk(dir == null ? 0 : dir.getTotalSpace(), dir == null ? 0 : dir.getUsableSpace(), mapFiles, partialFiles);
    }
    public synchronized List<Storage> storages() { return new ArrayList<>(storageChoices); }
    public synchronized String storageId() { return storageId; }
    public synchronized boolean storageAvailable() { return dir != null; }
    public synchronized boolean selectStorage(String id) {
        if (busy()) return false;
        for (Storage storage : storageChoices) if (storage.id.equals(id)) {
            if ((!storage.directory.isDirectory() && !storage.directory.mkdirs()) || !storage.directory.canWrite()) return false;
            if (id.equals(storageId)) return true;
            releaseDatabases(); storageId = id; dir = storage.directory;
            preferences.edit().putString("map_storage", id).apply();
            active = ""; state = 0; received = 0; revision++;
            MapDiagnostics.download(MapDiagnostics.Download.IDLE); publishStorageDiagnostics(); changed(); return true;
        }
        return false;
    }
    public synchronized void releaseDatabases() {
        for (SQLiteDatabase db : databases.values()) db.close();
        databases.clear();
    }
    private File root() {
        if (dir == null) throw new IllegalStateException("Map storage unavailable");
        return dir;
    }
    private File file(Region r) { return new File(root(), r.id + ".mbtiles"); }
    private File partial(Region r) { return new File(root(), r.id + ".part"); }
    private File partialStamp(Region r) { return new File(root(), r.id + ".part.sha256"); }
    private File stamp(Region r) { return new File(root(), r.id + ".version"); }
    public long revision() { return revision; }
    public synchronized boolean installed(Region r) { return dir != null && file(r).isFile(); }
    public boolean busy() { return state == 1 || state == 2; }
    public boolean active(Region r) { return active.equals(r.id); }
    public int state(Region r) { return active(r) ? state : 0; }
    public synchronized long downloaded(Region r) { return dir == null ? 0 : active(r) ? received : partial(r).length(); }
    public synchronized long available() { return dir == null ? 0 : dir.getUsableSpace(); }
    public synchronized long used() { long n = 0; File[] fs = dir == null ? null : dir.listFiles(); if (fs != null) for (File f : fs) n += f.length(); return n; }
    public synchronized boolean current(Region r) {
        if (!installed(r)) return false;
        try (InputStream in = new FileInputStream(stamp(r))) {
            return r.sha256.equals(new String(read(in, 100), "UTF-8"));
        } catch (Exception e) { return false; }
    }
    public boolean wifi() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities cap = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    public void pause() { cancel = true; }
    public synchronized void delete(Region r) {
        if (busy() || dir == null) return;
        SQLiteDatabase db = databases.remove(r.id); if (db != null) db.close();
        file(r).delete(); partial(r).delete(); partialStamp(r).delete(); stamp(r).delete();
        revision++; publishStorageDiagnostics(); changed();
    }
    public synchronized boolean download(Region r, boolean wifiOnly) {
        if (busy()) return false;
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs()) || !dir.canWrite()) {
            active = r.id; state = 4; MapDiagnostics.failure(MapDiagnostics.Failure.STORAGE_ACCESS);
            MapDiagnostics.download(MapDiagnostics.Download.FAILED); changed(); return false;
        }
        cancel = false; active = r.id; received = partial(r).length(); state = 1;
        MapDiagnostics.download(MapDiagnostics.Download.DOWNLOADING); changed();
        new Thread(() -> {
            MapDiagnostics.Failure stage = MapDiagnostics.Failure.DOWNLOAD;
            try {
                transfer(r, wifiOnly);
                if (cancel) throw new InterruptedIOException();
                state = 2; MapDiagnostics.download(MapDiagnostics.Download.VERIFYING); changed();
                stage = MapDiagnostics.Failure.CHECKSUM;
                String hash = hash(partial(r));
                if (!hash.equals(r.sha256)) { partial(r).delete(); throw new IOException("Checksum mismatch"); }
                stage = MapDiagnostics.Failure.PACKAGE;
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(partial(r).getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                    validate(db);
                }
                if (cancel) throw new InterruptedIOException();
                stage = MapDiagnostics.Failure.STORAGE_ACCESS;
                synchronized (OfflineMaps.this) {
                    SQLiteDatabase db = databases.remove(r.id); if (db != null) db.close();
                    Os.rename(partial(r).getPath(), file(r).getPath()); // atomic replacement; old map survives failures
                    try (OutputStream out = new FileOutputStream(stamp(r))) { out.write(r.sha256.getBytes("UTF-8")); }
                    partialStamp(r).delete();
                    revision++;
                }
                state = 0; MapDiagnostics.download(MapDiagnostics.Download.INSTALLED);
            } catch (InterruptedIOException e) { state = 3; MapDiagnostics.download(MapDiagnostics.Download.PAUSED);
            } catch (Exception e) {
                state = 4; MapDiagnostics.failure(e instanceof DownloadFailure ? ((DownloadFailure) e).reason : stage);
                MapDiagnostics.download(MapDiagnostics.Download.FAILED);
            } finally { synchronized (OfflineMaps.this) { publishStorageDiagnostics(); } changed(); }
        }, "offline-map-download").start();
        return true;
    }
    private static final class DownloadFailure extends IOException {
        final MapDiagnostics.Failure reason;
        DownloadFailure(MapDiagnostics.Failure reason) { this.reason = reason; }
    }
    private void transfer(Region r, boolean wifiOnly) throws Exception {
        if (wifiOnly && !wifi()) throw new InterruptedIOException();
        String previous = "";
        try (InputStream in = new FileInputStream(partialStamp(r))) { previous = new String(read(in, 100), "UTF-8"); }
        catch (IOException ignored) {}
        if (!r.sha256.equals(previous)) partial(r).delete();
        try (OutputStream out = new FileOutputStream(partialStamp(r))) { out.write(r.sha256.getBytes("UTF-8")); }
        long offset = partial(r).length();
        if (offset > r.bytes) { partial(r).delete(); offset = 0; }
        if (!MapStoragePolicy.fits(root().getUsableSpace(), r.bytes, offset,
                MapStoragePolicy.reserve("internal".equals(storageId)))) throw new DownloadFailure(MapDiagnostics.Failure.STORAGE_SPACE);
        if (offset == r.bytes) return;
        HttpURLConnection c = (HttpURLConnection) new URL(r.url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "MG4Launcher-offline-maps");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
        try {
            int code = c.getResponseCode(); MapDiagnostics.http(code);
            if (!"https".equals(c.getURL().getProtocol())) throw new IOException("HTTPS required");
            if (code == 200) offset = 0; // server ignored Range; restart rather than append
            else if (code != 206 || !String.valueOf(c.getHeaderField("Content-Range")).startsWith("bytes " + offset + "-"))
                throw new IOException("Download unavailable");
            received = offset;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(partial(r), offset > 0)) {
                byte[] buf = new byte[65536]; int n; long last = 0;
                while ((n = in.read(buf)) != -1) {
                    if (cancel || (wifiOnly && !wifi())) throw new InterruptedIOException();
                    received += n;
                    if (received > r.bytes) throw new IOException("Map exceeds catalog size");
                    out.write(buf, 0, n);
                    if (System.currentTimeMillis() - last > 500) { changed(); last = System.currentTimeMillis(); }
                }
                out.getFD().sync();
            }
            if (received != r.bytes) throw new IOException("Incomplete download");
        } finally { c.disconnect(); }
    }
    private String hash(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) { if (cancel) throw new InterruptedIOException(); d.update(b, 0, n); }
        }
        StringBuilder s = new StringBuilder(); for (byte b : d.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString();
    }
    private static String metadata(SQLiteDatabase db, String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE name=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }
    private static void validate(SQLiteDatabase db) throws Exception {
        try (Cursor c = db.rawQuery("PRAGMA quick_check", null)) {
            if (!c.moveToFirst() || !"ok".equals(c.getString(0))) throw new IOException("Invalid database");
        }
        if (!"pbf".equals(metadata(db, "format"))) throw new IOException("Vector tiles required");
        String scheme = metadata(db, "scheme");
        if (!scheme.isEmpty() && !"tms".equals(scheme)) throw new IOException("TMS tiles required");
        bounds(db);
        JSONObject j = new JSONObject(metadata(db, "json"));
        JSONArray layers = j.getJSONArray("vector_layers"); boolean streets = false;
        for (int i = 0; i < layers.length(); i++) streets |= "streets".equals(layers.getJSONObject(i).getString("id"));
        if (!streets) throw new IOException("Unsupported tile schema");
        try (Cursor c = db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null)) {
            if (!c.moveToFirst() || c.getBlob(0).length == 0) throw new IOException("Empty map");
        }
    }
    private static double[] bounds(SQLiteDatabase db) throws Exception {
        String[] b = metadata(db, "bounds").split(",");
        if (b.length != 4) throw new IOException("Missing bounds");
        double[] v = new double[4]; for (int i = 0; i < 4; i++) { v[i] = Double.parseDouble(b[i]); if (!Double.isFinite(v[i])) throw new IOException(); }
        if (v[0] < -180 || v[2] > 180 || v[1] < -90 || v[3] > 90 || v[0] >= v[2] || v[1] >= v[3]) throw new IOException();
        return v;
    }
    private SQLiteDatabase database(Region r) throws Exception {
        SQLiteDatabase db = databases.get(r.id);
        if (db == null) {
            db = SQLiteDatabase.openDatabase(file(r).getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try {
                db.execSQL("PRAGMA cache_size=-1024");
                try (Cursor result = db.rawQuery("PRAGMA mmap_size=0", null)) { result.moveToFirst(); }
                databases.put(r.id, db);
            } catch (Exception error) { db.close(); throw error; }
        }
        return db;
    }
    /** An actual tile is required as well as bounding-box coverage (extracts may have holes). */
    public synchronized Coverage coverage(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 85.051129 || Math.abs(lon) > 180) return null;
        for (Region r : catalog) if (installed(r)) {
            try {
                SQLiteDatabase db = database(r); double[] b = bounds(db);
                if (lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3]) continue;
                int max = Integer.parseInt(metadata(db, "maxzoom")), min = Integer.parseInt(metadata(db, "minzoom"));
                if (min < 0 || max > 22 || min > max) continue;
                int n = 1 << max;
                int x = Math.min(n - 1, (int) Math.floor((lon + 180) / 360 * n));
                double rad = Math.toRadians(lat);
                int y = Math.min(n - 1, (int) Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n));
                try (Cursor c = db.rawQuery("SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
                        new String[]{"" + max, "" + x, "" + (n - 1 - y)})) {
                    if (c.moveToFirst()) return new Coverage(r, min, max);
                }
            } catch (Exception ignored) { MapDiagnostics.failure(MapDiagnostics.Failure.PACKAGE); }
        }
        return null;
    }
    public synchronized byte[] tile(String id, int z, int x, int y) {
        if (z < 0 || z > 22 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) return null;
        for (Region r : catalog) if (r.id.equals(id) && installed(r)) {
            try (Cursor c = database(r).rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                    new String[]{"" + z, "" + x, "" + ((1 << z) - 1 - y)})) {
                if (!c.moveToFirst()) return new byte[0];
                byte[] b = c.getBlob(0);
                if (b.length > 1 && (b[0] & 255) == 31 && (b[1] & 255) == 139)
                    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) { return read(in, 8_000_000); }
                return b;
            } catch (Exception ignored) { MapDiagnostics.failure(MapDiagnostics.Failure.TILE_SOURCE); return null; }
        }
        return null;
    }
    public static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IOException("Resource too large"); out.write(b, 0, n); }
        return out.toByteArray();
    }
}
