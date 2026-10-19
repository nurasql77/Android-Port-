package net.openbgi.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    static { System.loadLibrary("openbgi_android"); }

    private static native String nativeStatus();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(18);
        view.setText(nativeStatus());
        setContentView(view);
    }
}
