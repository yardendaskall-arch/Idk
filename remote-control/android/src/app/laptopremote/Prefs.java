package app.laptopremote;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings and the remembered connection. Stored in app-private storage (backups are disabled). */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("laptop_remote", Context.MODE_PRIVATE);
    }

    String host() {
        return sp.getString("host", "");
    }

    int port() {
        return sp.getInt("port", 8765);
    }

    String pin() {
        return sp.getString("pin", "");
    }

    void saveConnection(String host, int port, String pin) {
        sp.edit().putString("host", host).putInt("port", port).putString("pin", pin).apply();
    }

    private static String fpKey(String host, int port) {
        return "fp:" + host.toLowerCase(java.util.Locale.US) + ":" + port;
    }

    /** SHA-256 of the certificate the user confirmed for this laptop, or null if never confirmed. */
    String fingerprint(String host, int port) {
        return sp.getString(fpKey(host, port), null);
    }

    void setFingerprint(String host, int port, String fp) {
        sp.edit().putString(fpKey(host, port), fp).apply();
    }

    void clearFingerprint(String host, int port) {
        sp.edit().remove(fpKey(host, port)).apply();
    }

    float speed() {
        return sp.getFloat("speed", 1.0f);
    }

    void setSpeed(float v) {
        sp.edit().putFloat("speed", v).apply();
    }

    boolean naturalScroll() {
        return sp.getBoolean("natural", true);
    }

    void setNaturalScroll(boolean v) {
        sp.edit().putBoolean("natural", v).apply();
    }

    /** 0 = low, 1 = medium, 2 = high */
    int screenQuality() {
        return sp.getInt("screen_quality", 1);
    }

    void setScreenQuality(int v) {
        sp.edit().putInt("screen_quality", v).apply();
    }
}
