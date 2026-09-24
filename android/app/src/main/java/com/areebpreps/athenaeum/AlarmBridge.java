// Athenaeum F4: native side of the external alarm.
// Add this class next to your existing AndroidDownloader bridge, then in the
// activity that builds the WebView (right where you register AndroidDownloader):
//
//     webView.addJavascriptInterface(new AlarmBridge(this), "AndroidAlarm");
//
// AndroidManifest.xml, inside <manifest> (normal permission, no runtime prompt):
//
//     <uses-permission android:name="com.android.alarm.permission.SET_ALARM" />
//
// Then rebuild the APK. Change the package line to match your app.
package com.areebpreps.athenaeum;

import android.app.Activity;
import android.content.Intent;
import android.provider.AlarmClock;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import java.util.ArrayList;

public class AlarmBridge {
    private final Activity activity;
    public AlarmBridge(Activity activity) { this.activity = activity; }

    /** daysJson: e.g. "[5]" = Thursday, using Calendar.DAY_OF_WEEK (Sun=1..Sat=7). "[]" = one-time. */
    @JavascriptInterface
    public boolean setAlarm(int hour, int minutes, String label, String daysJson) {
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            JSONArray arr = new JSONArray(daysJson == null ? "[]" : daysJson);
            if (arr.length() > 0) {
                ArrayList<Integer> days = new ArrayList<>();
                for (int k = 0; k < arr.length(); k++) days.add(arr.getInt(k));
                i.putExtra(AlarmClock.EXTRA_DAYS, days);
            }
            if (i.resolveActivity(activity.getPackageManager()) == null) return false;
            activity.startActivity(i);
            return true;
        } catch (Exception e) {
            return false; // the web side then falls back to the browser paths / .ics
        }
    }
}
