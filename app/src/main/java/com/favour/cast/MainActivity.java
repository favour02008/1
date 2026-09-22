package com.favour.cast;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private LinearLayout deviceList;
    private TextView status;
    private EditText urlInput;
    private Button castButton, scanButton;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<UpnpDevice> devices = new ArrayList<>();
    private UpnpDevice selectedDevice;
    private LocalMediaServer mediaServer;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestMediaPermission();
        scan();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("LG Cast");
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Put your phone and LG TV on the same Wi-Fi. This app casts supported video using DLNA/UPnP.");
        root.addView(hint);

        scanButton = new Button(this);
        scanButton.setText("SCAN FOR TVS");
        scanButton.setOnClickListener(v -> scan());
        root.addView(scanButton);

        status = new TextView(this);
        status.setText("Searching…");
        status.setPadding(0, 14, 0, 14);
        root.addView(status);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList);

        TextView label = new TextView(this);
        label.setText("Video URL");
        label.setPadding(0, 18, 0, 4);
        root.addView(label);

        urlInput = new EditText(this);
        urlInput.setHint("https://example.com/video.mp4");
        urlInput.setSingleLine(true);
        root.addView(urlInput);

        Button choose = new Button(this);
        choose.setText("CHOOSE VIDEO FROM PHONE");
        choose.setOnClickListener(v -> chooseVideo());
        root.addView(choose);

        castButton = new Button(this);
        castButton.setText("CAST TO TV");
        castButton.setEnabled(false);
        castButton.setOnClickListener(v -> cast());
        root.addView(castButton);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button play = new Button(this); play.setText("PLAY");
        Button pause = new Button(this); pause.setText("PAUSE");
        Button stop = new Button(this); stop.setText("STOP");
        controls.addView(play); controls.addView(pause); controls.addView(stop);
        root.addView(controls);

        play.setOnClickListener(v -> control("Play"));
        pause.setOnClickListener(v -> control("Pause"));
        stop.setOnClickListener(v -> control("Stop"));

        TextView note = new TextView(this);
        note.setText("Note: this is media casting, not full Android screen mirroring.");
        note.setPadding(0, 18, 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void requestMediaPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO}, 42);
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 42);
        }
    }

    private void scan() {
        scanButton.setEnabled(false);
        status.setText("Searching local Wi-Fi for DLNA/UPnP TVs…");
        deviceList.removeAllViews();
        devices.clear();
        selectedDevice = null;
        castButton.setEnabled(false);

        executor.execute(() -> {
            List<UpnpDevice> found = UpnpClient.discover(3500);
            runOnUiThread(() -> {
                devices.addAll(found);
                if (found.isEmpty()) {
                    status.setText("No TV found. Check Wi-Fi and enable SmartShare/DLNA on the TV.");
                } else {
                    status.setText(found.size() + " device(s) found");
                    for (UpnpDevice d : found) addDeviceButton(d);
                }
                scanButton.setEnabled(true);
            });
        });
    }

    private void addDeviceButton(UpnpDevice d) {
        Button b = new Button(this);
        b.setText(d.friendlyName + "\n" + d.host);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            selectedDevice = d;
            castButton.setEnabled(true);
            status.setText("Selected: " + d.friendlyName);
        });
        deviceList.addView(b);
    }

    private void chooseVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("video/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, 100);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            try {
                if (mediaServer != null) mediaServer.stop();
                mediaServer = new LocalMediaServer(this, uri);
                mediaServer.start();
                urlInput.setText(mediaServer.getUrl());
                status.setText("Video ready. Select the TV and press CAST TO TV.");
            } catch (Exception e) {
                status.setText("Could not prepare video: " + e.getMessage());
            }
        }
    }

    private void cast() {
        if (selectedDevice == null) { status.setText("Select a TV first."); return; }
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) { status.setText("Enter a video URL or choose a video."); return; }

        castButton.setEnabled(false);
        status.setText("Sending video to " + selectedDevice.friendlyName + "…");
        executor.execute(() -> {
            boolean ok = UpnpClient.setMedia(selectedDevice, url, "video/mp4");
            if (ok) ok = UpnpClient.play(selectedDevice);
            final boolean result = ok;
            runOnUiThread(() -> {
                castButton.setEnabled(true);
                status.setText(result ? "Playing on TV." : "TV rejected the media. Try an MP4 video.");
            });
        });
    }

    private void control(String action) {
        if (selectedDevice == null) { status.setText("Select a TV first."); return; }
        executor.execute(() -> {
            boolean ok = "Play".equals(action) ? UpnpClient.play(selectedDevice)
                    : "Pause".equals(action) ? UpnpClient.pause(selectedDevice)
                    : UpnpClient.stop(selectedDevice);
            runOnUiThread(() -> status.setText(ok ? action + " sent." : "TV did not accept " + action + "."));
        });
    }

    @Override protected void onDestroy() {
        if (mediaServer != null) mediaServer.stop();
        executor.shutdownNow();
        super.onDestroy();
    }
}
