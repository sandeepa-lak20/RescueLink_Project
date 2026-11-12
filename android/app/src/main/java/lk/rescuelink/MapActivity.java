package lk.rescuelink;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.util.Locale;

public class MapActivity extends Activity {
  private TileMapView map;
  private TextView coordinate;
  private boolean registered;
  private final BroadcastReceiver updates =
      new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
          String id = getIntent().getStringExtra("id");
          if (id == null || id.isEmpty() || !getIntent().getBooleanExtra("readonly", false)) return;
          try {
            double lat = Double.parseDouble(LinkService.snapshot.getProperty("i." + id + ".lat")),
                lon = Double.parseDouble(LinkService.snapshot.getProperty("i." + id + ".lon"));
            map.marker(lat, lon);
            show(lat, lon);
          } catch (Exception ignored) {
          }
        }
      };

  protected void onResume() {
    super.onResume();
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(
          updates, new IntentFilter(LinkService.UPDATE), Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(updates, new IntentFilter(LinkService.UPDATE));
    registered = true;
  }

  protected void onPause() {
    if (registered) {
      unregisterReceiver(updates);
      registered = false;
    }
    super.onPause();
  }

  protected void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(12, 12, 12, 12);
    boolean readOnly = getIntent().getBooleanExtra("readonly", false);
    double lat = getIntent().getDoubleExtra("lat", 6.032),
        lon = getIntent().getDoubleExtra("lon", 80.216);
    TextView title = new TextView(this);
    title.setText(
        readOnly ? "Emergency position • drag to pan" : "Tap map to select emergency location");
    title.setTextSize(19);
    root.addView(title);
    coordinate = new TextView(this);
    root.addView(coordinate);
    map = new TileMapView(this, lat, lon, !readOnly, (a, b) -> show(a, b));
    root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
    show(lat, lon);
    LinearLayout controls = new LinearLayout(this);
    Button plus = new Button(this);
    plus.setText("Zoom +");
    plus.setOnClickListener(v -> map.zoom(1));
    controls.addView(plus);
    Button minus = new Button(this);
    minus.setText("Zoom −");
    minus.setOnClickListener(v -> map.zoom(-1));
    controls.addView(minus);
    root.addView(controls);
    TextView attribution = new TextView(this);
    attribution.setText("© OpenStreetMap contributors • view licence");
    attribution.setOnClickListener(
        v ->
            startActivity(
                new Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.openstreetmap.org/copyright"))));
    root.addView(attribution);
    Button use = new Button(this);
    use.setText(readOnly ? "Close map" : "Use this location");
    use.setOnClickListener(
        v -> {
          if (!readOnly)
            setResult(
                RESULT_OK,
                new Intent().putExtra("lat", map.latitude()).putExtra("lon", map.longitude()));
          finish();
        });
    root.addView(use);
    InsetsUi.apply(root);
    setContentView(root);
  }

  private void show(double lat, double lon) {
    coordinate.setText(String.format(Locale.US, "%.6f, %.6f", lat, lon));
  }
}
