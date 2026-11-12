package lk.rescuelink;

import android.content.*;
import android.graphics.*;
import android.util.LruCache;
import android.view.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Native Java map; downloads only visible OSM tiles and caches them on disk. */
final class TileMapView extends View {
  interface Pick {
    void selected(double lat, double lon);
  }

  private double cx, cy, markerLat, markerLon;
  private int zoom = 12;
  private float downX, downY, lastX, lastY;
  private boolean moved;
  private final boolean selectable;
  private final Pick pick;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ThreadPoolExecutor downloads =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(48));
  private final Set<String> loading = ConcurrentHashMap.newKeySet();
  private volatile boolean failed;
  private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
  private final LruCache<String, Bitmap> memory =
      new LruCache<>(8 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) {
          return b.getByteCount();
        }
      };

  TileMapView(Context c, double lat, double lon, boolean editable, Pick callback) {
    super(c);
    selectable = editable;
    pick = callback;
    markerLat = lat;
    markerLon = lon;
    center(lat, lon);
    setBackgroundColor(Color.rgb(229, 238, 237));
  }

  private double world() {
    return 256.0 * (1 << zoom);
  }

  private double px(double lon) {
    return (lon + 180) / 360 * world();
  }

  private double py(double lat) {
    lat = Math.max(-85.0511, Math.min(85.0511, lat));
    double r = Math.toRadians(lat);
    return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * world();
  }

  private double lon(double x) {
    double v = x / world() * 360 - 180;
    return ((v + 180) % 360 + 360) % 360 - 180;
  }

  private double lat(double y) {
    return Math.toDegrees(
        Math.atan(Math.sinh(Math.PI * (1 - 2 * Math.max(0, Math.min(world(), y)) / world()))));
  }

  void center(double la, double lo) {
    cx = px(lo);
    cy = py(la);
    invalidate();
  }

  void marker(double lat, double lon) {
    markerLat = lat;
    markerLon = lon;
    center(lat, lon);
  }

  void zoom(int delta) {
    double la = lat(cy), lo = lon(cx);
    zoom = Math.max(2, Math.min(18, zoom + delta));
    cx = px(lo);
    cy = py(la);
    invalidate();
  }

  double latitude() {
    return markerLat;
  }

  double longitude() {
    return markerLon;
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    double left = cx - getWidth() / 2.0, top = cy - getHeight() / 2.0;
    int n = 1 << zoom;
    for (int x = (int) Math.floor(left / 256);
        x <= (int) Math.floor((left + getWidth()) / 256);
        x++)
      for (int y = (int) Math.floor(top / 256);
          y <= (int) Math.floor((top + getHeight()) / 256);
          y++) {
        if (y < 0 || y >= n) continue;
        int tx = ((x % n) + n) % n;
        String key = zoom + "_" + tx + "_" + y;
        float sx = (float) (x * 256 - left), sy = (float) (y * 256 - top);
        Bitmap tile = memory.get(key);
        if (tile != null) c.drawBitmap(tile, sx, sy, paint);
        else {
          paint.setColor(Color.rgb(207, 222, 223));
          paint.setStyle(Paint.Style.STROKE);
          c.drawRect(sx, sy, sx + 256, sy + 256, paint);
          paint.setStyle(Paint.Style.FILL);
          fetch(key, zoom, tx, y);
        }
      }
    double mx = px(markerLon) - left;
    while (mx > getWidth() + world() / 2) mx -= world();
    while (mx < -world() / 2) mx += world();
    float my = (float) (py(markerLat) - top);
    paint.setColor(Color.WHITE);
    c.drawCircle((float) mx, my, 13, paint);
    paint.setColor(Color.rgb(221, 47, 64));
    c.drawCircle((float) mx, my, 10, paint);
    if (failed) {
      paint.setColor(Color.rgb(16, 46, 70));
      paint.setTextSize(30);
      c.drawText("Map tiles unavailable — check internet", 16, 40, paint);
    }
  }

  private void fetch(String key, int z, int x, int y) {
    if (retryAfter.getOrDefault(key, 0L) > System.currentTimeMillis() || !loading.add(key)) return;
    try {
      downloads.execute(
          () -> {
            File dir = new File(getContext().getCacheDir(), "tiles");
            dir.mkdirs();
            File file = new File(dir, key + ".png"), expiry = new File(dir, key + ".expires");
            try {
              long expires = 0;
              String etag = "", modified = "";
              if (expiry.exists())
                try (BufferedReader r = new BufferedReader(new FileReader(expiry))) {
                  expires = Long.parseLong(r.readLine());
                  String line = r.readLine();
                  if (line != null) etag = line;
                  line = r.readLine();
                  if (line != null) modified = line;
                }
              Bitmap b = null;
              if (file.exists() && expires > System.currentTimeMillis())
                b = BitmapFactory.decodeFile(file.getPath());
              if (b == null) {
                HttpURLConnection connection =
                    (HttpURLConnection)
                        new URL("https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png")
                            .openConnection();
                connection.setRequestProperty(
                    "User-Agent", "RescueLink-Android/1.0 (Java coursework map)");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                if (file.exists()) {
                  if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
                  if (!modified.isEmpty())
                    connection.setRequestProperty("If-Modified-Since", modified);
                }
                try {
                  int code = connection.getResponseCode();
                  if (code != 200 && code != 304) throw new IOException("Tile HTTP " + code);
                  long ttl = 7L * 24 * 60 * 60 * 1000;
                  String cc = connection.getHeaderField("Cache-Control");
                  if (cc != null)
                    for (String part : cc.split(","))
                      if (part.trim().startsWith("max-age="))
                        try {
                          ttl = Math.max(ttl, Long.parseLong(part.trim().substring(8)) * 1000);
                        } catch (Exception ignored) {
                        }
                  if (code == 304) b = BitmapFactory.decodeFile(file.getPath());
                  else {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = connection.getInputStream()) {
                      byte[] chunk = new byte[8192];
                      int count;
                      while ((count = in.read(chunk)) != -1) {
                        if (out.size() + count > 512000) throw new IOException("Tile too large");
                        out.write(chunk, 0, count);
                      }
                    }
                    byte[] bytes = out.toByteArray();
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (b == null) throw new IOException("Invalid tile");
                    try (OutputStream f = new FileOutputStream(file)) {
                      f.write(bytes);
                    }
                  }
                  if (b == null) throw new IOException("Invalid cached tile");
                  String responseTag = connection.getHeaderField("ETag"),
                      responseModified = connection.getHeaderField("Last-Modified");
                  try (PrintWriter w = new PrintWriter(expiry)) {
                    w.println(System.currentTimeMillis() + ttl);
                    w.println(responseTag == null ? etag : responseTag);
                    w.println(responseModified == null ? modified : responseModified);
                  }
                } finally {
                  connection.disconnect();
                }
              }
              memory.put(key, b);
              failed = false;
              trimCache(dir);
            } catch (Exception e) {
              failed = true;
              retryAfter.put(key, System.currentTimeMillis() + 60000);
            } finally {
              loading.remove(key);
              postInvalidate();
            }
          });
    } catch (RejectedExecutionException e) {
      loading.remove(key);
    }
  }

  private synchronized void trimCache(File dir) {
    File[] files = dir.listFiles((d, name) -> name.endsWith(".png"));
    if (files == null) return;
    long total = 0;
    for (File f : files) total += f.length();
    if (total <= 40 * 1024 * 1024) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    for (File f : files) {
      long size = f.length();
      if (f.delete()) {
        new File(dir, f.getName().replace(".png", ".expires")).delete();
        total -= size;
      }
      if (total < 32 * 1024 * 1024) break;
    }
  }

  public boolean onTouchEvent(android.view.MotionEvent e) {
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN:
        downX = lastX = e.getX();
        downY = lastY = e.getY();
        moved = false;
        return true;
      case MotionEvent.ACTION_MOVE:
        float dx = e.getX() - lastX, dy = e.getY() - lastY;
        if (Math.hypot(e.getX() - downX, e.getY() - downY) > 12) moved = true;
        cx -= dx;
        cy = Math.max(0, Math.min(world(), cy - dy));
        lastX = e.getX();
        lastY = e.getY();
        invalidate();
        return true;
      case MotionEvent.ACTION_UP:
        if (!moved && selectable) {
          markerLat = lat(cy + e.getY() - getHeight() / 2.0);
          markerLon = lon(cx + e.getX() - getWidth() / 2.0);
          pick.selected(markerLat, markerLon);
          performClick();
          invalidate();
        }
        return true;
      default:
        return true;
    }
  }

  public boolean performClick() {
    super.performClick();
    return true;
  }

  protected void onDetachedFromWindow() {
    downloads.shutdownNow();
    super.onDetachedFromWindow();
  }
}
