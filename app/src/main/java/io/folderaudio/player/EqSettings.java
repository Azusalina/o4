package io.folderaudio.player;

import android.content.Context;
import android.content.SharedPreferences;

final class EqSettings {
    static final int[] CENTERS = {25, 40, 63, 100, 160, 250, 400, 630,
            1000, 1600, 2500, 4000, 6300, 10000, 14000, 18000};
    final float[] values = new float[1 + 3 * CENTERS.length];
    boolean enabled;

    EqSettings() {
        enabled = true;
        for (int i = 0; i < CENTERS.length; i++) {
            values[1 + 3 * i] = CENTERS[i];
            values[3 + 3 * i] = 1.4f;
        }
    }

    static EqSettings load(Context context) {
        SharedPreferences prefs = prefs(context);
        EqSettings result = new EqSettings();
        result.enabled = prefs.getBoolean("enabled", true);
        for (int i = 0; i < result.values.length; i++) {
            result.values[i] = prefs.getFloat("value" + i, result.values[i]);
        }
        return result;
    }

    void save(Context context) {
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean("enabled", enabled);
        for (int i = 0; i < values.length; i++) editor.putFloat("value" + i, values[i]);
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("eq", Context.MODE_PRIVATE);
    }
}
