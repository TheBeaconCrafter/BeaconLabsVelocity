package org.bcnlab.beaconLabsVelocity.util;

import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DependencyTracker {

    private final Set<String> linkSupportedServers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void markSupported(String serverName) {
        linkSupportedServers.add(serverName.toLowerCase());
    }

    public void markUnsupported(String serverName) {
        linkSupportedServers.remove(serverName.toLowerCase());
    }

    public boolean isSupported(String serverName) {
        return linkSupportedServers.contains(serverName.toLowerCase());
    }

    public boolean isSupported(RegisteredServer server) {
        return isSupported(server.getServerInfo().getName());
    }

    public boolean isSupported(ServerConnection server) {
        return isSupported(server.getServerInfo().getName());
    }
}
