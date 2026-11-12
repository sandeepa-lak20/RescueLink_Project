package lk.rescuelink;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class TrackingService extends Service implements LocationListener {
  static volatile String activeId = "";
  private LocationManager manager;
  private ScheduledExecutorService worker;
  private Session session;
  private String id;
  private final AtomicReference<Location> latest = new AtomicReference<>();

  public IBinder onBind(Intent i) {
    return null;
  }

  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || "STOP".equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (worker != null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    try {
      session = Session.load(this);
      id = intent.getStringExtra("id");
      if (session == null || id == null || !session.role.equals("CITIZEN"))
        throw new Exception("No active citizen session");
      if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
              != PackageManager.PERMISSION_GRANTED
          && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
              != PackageManager.PERMISSION_GRANTED)
        throw new Exception("Location permission required");
      Notices.channels(this);
      Intent stop = new Intent(this, TrackingService.class).setAction("STOP");
      Notification n =
          new Notification.Builder(this, "tracking")
              .setSmallIcon(android.R.drawable.ic_menu_mylocation)
              .setContentTitle("Sharing emergency location")
              .setContentText("Incident " + id.substring(0, 8) + " • tap Stop to end sharing")
              .addAction(
                  new Notification.Action.Builder(
                          null,
                          "Stop sharing",
                          PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE))
                      .build())
              .build();
      if (Build.VERSION.SDK_INT >= 29)
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
      else startForeground(2, n);
      manager = getSystemService(LocationManager.class);
      boolean enabled = false;
      for (String provider :
          Arrays.asList(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
        if ((!provider.equals(LocationManager.GPS_PROVIDER)
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED)
            && manager.isProviderEnabled(provider)) {
          manager.requestLocationUpdates(provider, 10000, 10, this);
          enabled = true;
        }
      if (!enabled) throw new Exception("Enable device location first");
      activeId = id;
      worker = Executors.newSingleThreadScheduledExecutor();
      worker.scheduleWithFixedDelay(this::upload, 0, 10, TimeUnit.SECONDS);
    } catch (Exception e) {
      Notices.alert(this, "Location sharing stopped", e.getMessage());
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  public void onLocationChanged(Location location) {
    latest.set(location);
  }

  private void upload() {
    try {
      String status = LinkService.snapshot.getProperty("i." + id + ".status", "");
      if (status.equals("RESOLVED")) {
        stopSelf();
        return;
      }
      Location l = latest.get();
      if (l == null) return;
      Properties p = session.request("location");
      p.setProperty("request", UUID.randomUUID().toString());
      p.setProperty("id", id);
      p.setProperty("lat", String.valueOf(l.getLatitude()));
      p.setProperty("lon", String.valueOf(l.getLongitude()));
      Properties response = Api.call(session.url, p);
      latest.compareAndSet(l, null);
      getSystemService(NotificationManager.class)
          .notify(
              2,
              new Notification.Builder(this, "tracking")
                  .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                  .setContentTitle("Sharing emergency location")
                  .setContentText(
                      "Latest position sent • accuracy " + Math.round(l.getAccuracy()) + " m")
                  .addAction(
                      new Notification.Action.Builder(
                              null,
                              "Stop sharing",
                              PendingIntent.getService(
                                  this,
                                  1,
                                  new Intent(this, TrackingService.class).setAction("STOP"),
                                  PendingIntent.FLAG_IMMUTABLE))
                          .build())
                  .build());
    } catch (Api.Failure e) {
      if (e.code == 401 || e.code == 403 || e.code == 400) {
        Notices.alert(this, "Location sharing stopped", e.getMessage());
        stopSelf();
      }
    } catch (Exception ignored) {
      /* latest fix is retained, never an unbounded trail */
    }
  }

  public void onProviderEnabled(String p) {}

  public void onProviderDisabled(String p) {}

  public void onStatusChanged(String p, int s, Bundle b) {}

  public void onDestroy() {
    if (manager != null) manager.removeUpdates(this);
    if (worker != null) worker.shutdownNow();
    activeId = "";
    stopForeground(STOP_FOREGROUND_REMOVE);
    sendBroadcast(new Intent(LinkService.UPDATE).setPackage(getPackageName()));
    super.onDestroy();
  }
}
