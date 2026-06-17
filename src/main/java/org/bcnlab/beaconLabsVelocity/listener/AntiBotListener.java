package org.bcnlab.beaconLabsVelocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import org.bcnlab.beaconLabsVelocity.service.AntiBotService;
import org.slf4j.Logger;

import java.net.InetSocketAddress;

public class AntiBotListener {

    private final AntiBotService antiBotService;
    private final Logger logger;

    public AntiBotListener(AntiBotService antiBotService, Logger logger) {
        this.antiBotService = antiBotService;
        this.logger = logger;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        InetSocketAddress remoteAddress = player.getRemoteAddress();
        
        if (remoteAddress != null) {
            String ip = remoteAddress.getAddress().getHostAddress();
            // Fire async IP check. The AntiBotService will kick the player if needed.
            antiBotService.checkIpAsync(ip, player.getUniqueId(), player.getUsername());
        }
    }
}
