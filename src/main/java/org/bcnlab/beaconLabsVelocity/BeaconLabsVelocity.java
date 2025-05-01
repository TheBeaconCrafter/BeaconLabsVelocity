package org.bcnlab.beaconLabsVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

@Plugin(id = "beaconlabsvelocity", name = "BeaconLabsVelocity", version = "1.0.0", url = "bcnlab.org", authors = {"Vincent Wackler"})
public class BeaconLabsVelocity {

    @Inject
    private Logger logger;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
    }
}
