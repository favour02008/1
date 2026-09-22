package com.favour.cast;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

public class UpnpClient {
    private static final String SSDP = "239.255.255.250";
    private static final int PORT = 1900;

    public static List<UpnpDevice> discover(int timeoutMs) {
        Map<String, UpnpDevice> result = new LinkedHashMap<>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(500);
            String[] targets = {"urn:schemas-upnp-org:device:MediaRenderer:1", "ssdp:all"};
            for (String st : targets) {
                String request = "M-SEARCH * HTTP/1.1\\r\\n" +
                        "HOST: " + SSDP + ":" + PORT + "\\r\\n" +
                        "MAN: \\"ssdp:discover\\"\\r\\n" +
                        "MX: 2\\r\\n" +
                        "ST: " + st + "\\r\\n\\r\\n";
                byte[] data = request.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(SSDP), PORT));
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < end) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String response = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    String location = header(response, "location");
                    if (location == null || result.containsKey(location)) continue;
                    UpnpDevice d = loadRenderer(location);
                    if (d != null) result.put(location, d);
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) socket.close();
        }
        return new ArrayList<>(result.values());
    }

    private static String header(String text, String name) {
        for (String line : text.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name))
                return line.substring(colon + 1).trim();
        }
        return null;
    }

    private static UpnpDevice loadRenderer(String location) {
        try {
            URL u = new URL(location);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestMethod("GET");
            String xml = read(c.getInputStream());
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            String name = text(doc, "friendlyName");
            NodeList services = doc.getElementsByTagName("service");
            for (int i = 0; i < services.getLength(); i++) {
                Element s = (Element) services.item(i);
                String type = text(s, "serviceType");
                if (type != null && type.contains("AVTransport")) {
                    String control = text(s, "controlURL");
                    if (control == null) continue;
                    URL controlUrl = new URL(u, control);
                    return new UpnpDevice(location, u.getHost(),
                            name == null ? "DLNA TV" : name, controlUrl.toString(), type);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String text(Document d, String tag) {
        NodeList n = d.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    private static String read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\\n');
        r.close();
        return b.toString();
    }

    public static boolean setMedia(UpnpDevice d, String url, String mime) {
        String meta = "<DIDL-Lite xmlns:didl=\\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\\" xmlns:dc=\\"http://purl.org/dc/elements/1.1/\\" xmlns:upnp=\\"urn:schemas-upnp-org:metadata-1-0/upnp/\\">" +
                "<item id=\\"0\\" parentID=\\"-1\\" restricted=\\"1\\"><dc:title>LG Cast Video</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\\"http-get:*:" + esc(mime) + ":*\\">" +
                esc(url) + "</res></item></DIDL-Lite>";
        String body = "<u:SetAVTransportURI xmlns:u=\\"" + d.serviceType + "\\">" +
                "<InstanceID>0</InstanceID><CurrentURI>" + esc(url) + "</CurrentURI>" +
                "<CurrentURIMetaData>" + esc(meta) + "</CurrentURIMetaData></u:SetAVTransportURI>";
        return soap(d, "SetAVTransportURI", body);
    }

    public static boolean play(UpnpDevice d) {
        return soap(d, "Play", "<u:Play xmlns:u=\\"" + d.serviceType + "\\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>");
    }

    public static boolean pause(UpnpDevice d) {
        return soap(d, "Pause", "<u:Pause xmlns:u=\\"" + d.serviceType + "\\"><InstanceID>0</InstanceID></u:Pause>");
    }

    public static boolean stop(UpnpDevice d) {
        return soap(d, "Stop", "<u:Stop xmlns:u=\\"" + d.serviceType + "\\"><InstanceID>0</InstanceID></u:Stop>");
    }

    private static boolean soap(UpnpDevice d, String action, String body) {
        try {
            String envelope = "<?xml version=\\"1.0\\" encoding=\\"utf-8\\"?>" +
                    "<s:Envelope xmlns:s=\\"http://schemas.xmlsoap.org/soap/envelope/\\" s:encodingStyle=\\"http://schemas.xmlsoap.org/soap/encoding/\\">" +
                    "<s:Body>" + body + "</s:Body></s:Envelope>";
            HttpURLConnection c = (HttpURLConnection) new URL(d.controlUrl).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(7000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/xml; charset=\\"utf-8\\"");
            c.setRequestProperty("SOAPAction", "\\"" + d.serviceType + "#" + action + "\\"");
            byte[] bytes = envelope.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
