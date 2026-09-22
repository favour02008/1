package com.favour.cast;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.net.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalMediaServer {
    private final Context context;
    private final Uri uri;
    private ServerSocket server;
    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int port = 8765;
    private String fileName = "video.mp4";
    private long length = -1;

    public LocalMediaServer(Context context, Uri uri) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        Cursor c = context.getContentResolver().query(uri, null, null, null, null);
        if (c != null) {
            try {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (c.moveToFirst()) {
                    if (n >= 0) fileName = c.getString(n);
                    if (s >= 0 && !c.isNull(s)) length = c.getLong(s);
                }
            } finally { c.close(); }
        }
    }

    public void start() throws IOException {
        if (running.get()) return;
        server = new ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"));
        running.set(true);
        thread = new Thread(this::serve, "LocalMediaServer");
        thread.start();
    }

    public String getUrl() {
        return "http://" + localIp() + ":" + port + "/" +
                URLEncoder.encode(fileName).replace("+", "%20");
    }

    public void stop() {
        running.set(false);
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }

    private void serve() {
        while (running.get()) {
            try {
                Socket socket = server.accept();
                new Thread(() -> handle(socket)).start();
            } catch (IOException ignored) {}
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(10000);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String request = r.readLine();
            if (request == null) { socket.close(); return; }

            long start = 0;
            long end = length > 0 ? length - 1 : -1;
            String line;
            while (!(line = r.readLine()).isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.startsWith("bytes=")) {
                        String[] p = value.substring(6).split("-", 2);
                        try { start = Long.parseLong(p[0]); } catch (Exception ignored) {}
                        if (p.length > 1 && !p[1].isEmpty()) {
                            try { end = Long.parseLong(p[1]); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Unable to open video");
            if (start > 0) longSkip(in, start);

            long total = length > 0 ? length : -1;
            long count = total > 0 ? Math.max(0, end - start + 1) : -1;
            OutputStream out = socket.getOutputStream();
            String mime = context.getContentResolver().getType(uri);
            if (mime == null) mime = "video/mp4";

            StringBuilder h = new StringBuilder();
            h.append(start > 0 ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(mime).append("\r\n");
            if (count >= 0) h.append("Content-Length: ").append(count).append("\r\n");
            if (total > 0) h.append("Accept-Ranges: bytes\r\nContent-Range: bytes ")
                    .append(start).append("-").append(end).append("/").append(total).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes("UTF-8"));

            byte[] buf = new byte[64 * 1024];
            long remaining = count;
            int n;
            while ((n = in.read(buf)) != -1 && (remaining < 0 || remaining > 0)) {
                if (remaining >= 0 && n > remaining) n = (int) remaining;
                out.write(buf, 0, n);
                if (remaining >= 0) remaining -= n;
            }
            out.flush();
            in.close();
            socket.close();
        } catch (Exception ignored) {
            try { socket.close(); } catch (Exception ignored2) {}
        }
    }

    private static void longSkip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() == -1) break;
                skipped = 1;
            }
            n -= skipped;
        }
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address)
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
