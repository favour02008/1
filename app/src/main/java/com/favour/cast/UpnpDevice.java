package com.favour.cast;

public class UpnpDevice {
    public final String location;
    public final String host;
    public final String friendlyName;
    public final String controlUrl;
    public final String serviceType;

    public UpnpDevice(String location, String host, String friendlyName, String controlUrl, String serviceType) {
        this.location = location;
        this.host = host;
        this.friendlyName = friendlyName;
        this.controlUrl = controlUrl;
        this.serviceType = serviceType;
    }
}
