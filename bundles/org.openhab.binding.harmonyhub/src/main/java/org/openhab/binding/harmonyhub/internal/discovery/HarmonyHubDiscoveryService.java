/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.harmonyhub.internal.discovery;

import static org.openhab.binding.harmonyhub.internal.HarmonyHubBindingConstants.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.harmonyhub.internal.handler.HarmonyHubHandler;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link HarmonyHubDiscoveryService} class discovers Harmony hubs and adds the results to the inbox.
 *
 * @author Dan Cunningham - Initial contribution
 * @author Wouter Born - Add null annotations
 */
@NonNullByDefault
@Component(service = DiscoveryService.class, immediate = true, configurationPid = "discovery.harmonyhub")
public class HarmonyHubDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(HarmonyHubDiscoveryService.class);

    // notice the port appended to the end of the string
    private static final String DISCOVERY_STRING = "_logitech-reverse-bonjour._tcp.local.\n%d";
    private static final int DISCOVERY_PORT = 5224;
    private static final int TIMEOUT = 15;
    private static final long REFRESH = 600;

    private boolean running;

    private @Nullable HarmonyServer server;

    private @Nullable ScheduledFuture<?> broadcastFuture;
    private @Nullable ScheduledFuture<?> discoveryFuture;
    private @Nullable ScheduledFuture<?> timeoutFuture;

    public HarmonyHubDiscoveryService() {
        super(HarmonyHubHandler.SUPPORTED_THING_TYPES_UIDS, TIMEOUT, true);
    }

    @Override
    public Set<ThingTypeUID> getSupportedThingTypes() {
        return HarmonyHubHandler.SUPPORTED_THING_TYPES_UIDS;
    }

    @Override
    public void startScan() {
        logger.debug("StartScan called");
        startDiscovery();
    }

    @Override
    protected void startBackgroundDiscovery() {
        logger.debug("Start Harmony Hub background discovery");
        ScheduledFuture<?> localDiscoveryFuture = discoveryFuture;
        if (localDiscoveryFuture == null || localDiscoveryFuture.isCancelled()) {
            logger.debug("Start Scan");
            discoveryFuture = scheduler.scheduleWithFixedDelay(this::startScan, 0, REFRESH, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void stopBackgroundDiscovery() {
        logger.debug("Stop HarmonyHub background discovery");
        ScheduledFuture<?> localDiscoveryFuture = discoveryFuture;
        if (localDiscoveryFuture != null && !localDiscoveryFuture.isCancelled()) {
            localDiscoveryFuture.cancel(true);
            discoveryFuture = null;
        }
        stopDiscovery();
    }

    /**
     * Starts discovery for Harmony Hubs
     */
    private synchronized void startDiscovery() {
        if (running) {
            return;
        }

        try {
            final HarmonyServer localServer = new HarmonyServer();
            localServer.start();
            server = localServer;

            broadcastFuture = scheduler.scheduleWithFixedDelay(() -> {
                sendDiscoveryMessage(String.format(DISCOVERY_STRING, localServer.getPort()));
            }, 0, 2, TimeUnit.SECONDS);

            timeoutFuture = scheduler.schedule(this::stopDiscovery, TIMEOUT, TimeUnit.SECONDS);

            running = true;
        } catch (IOException e) {
            logger.error("Could not start Harmony discovery server ", e);
        }
    }

    /**
     * Stops discovery of Harmony Hubs
     */
    private synchronized void stopDiscovery() {
        ScheduledFuture<?> localBroadcastFuture = broadcastFuture;
        if (localBroadcastFuture != null) {
            localBroadcastFuture.cancel(true);
        }

        ScheduledFuture<?> localTimeoutFuture = timeoutFuture;
        if (localTimeoutFuture != null) {
            localTimeoutFuture.cancel(true);
        }

        HarmonyServer localServer = server;
        if (localServer != null) {
            localServer.stop();
        }

        running = false;
    }

    /**
     * Send broadcast message over all active interfaces
     *
     * @param discoverString
     *            String to be used for the discovery
     */
    private void sendDiscoveryMessage(String discoverString) {
        try (DatagramSocket bcSend = new DatagramSocket()) {
            bcSend.setBroadcast(true);
            byte[] sendData = discoverString.getBytes();

            // Broadcast the message over all the network interfaces
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress[] broadcast = new InetAddress[] { InetAddress.getByName("224.0.0.1"),
                            InetAddress.getByName("255.255.255.255"), interfaceAddress.getBroadcast() };
                    for (InetAddress bc : broadcast) {
                        // Send the broadcast package!
                        if (bc != null) {
                            try {
                                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, bc,
                                        DISCOVERY_PORT);
                                bcSend.send(sendPacket);
                            } catch (IOException e) {
                                logger.debug("IO error during HarmonyHub discovery: {}", e.getMessage());
                            } catch (Exception e) {
                                logger.debug("{}", e.getMessage(), e);
                            }
                            logger.trace("Request packet sent to: {} Interface: {}", bc.getHostAddress(),
                                    networkInterface.getDisplayName());
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("IO error during HarmonyHub discovery: {}", e.getMessage());
        }
    }

    /**
     * Server which accepts TCP connections from Harmony Hubs during the discovery process
     *
     * @author Dan Cunningham - Initial contribution
     *
     */
    private class HarmonyServer {
        private final ServerSocket serverSocket;
        private final List<String> responses = new ArrayList<>();
        private boolean running;

        public HarmonyServer() throws IOException {
            serverSocket = new ServerSocket(0);
            logger.debug("Creating Harmony server on port {}", getPort());
        }

        public int getPort() {
            return serverSocket.getLocalPort();
        }

        public void start() {
            running = true;
            Thread localThread = new Thread(this::run, "HarmonyDiscoveryServer(tcp/" + getPort() + ")");
            localThread.start();
        }

        public void stop() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.error("Could not stop harmony discovery socket", e);
            }
        }

        private void run() {
            while (running) {
                try (Socket socket = serverSocket.accept();
                        Reader isr = new InputStreamReader(socket.getInputStream());
                        BufferedReader in = new BufferedReader(isr)) {
                    String input;
                    while ((input = in.readLine()) != null) {
                        if (!running) {
                            break;
                        }
                        logger.trace("READ {}", input);
                        Properties properties = readProperties(input);

                        String friendlyName = properties.getProperty("friendlyName");
                        if (!responses.contains(friendlyName)) {
                            responses.add(friendlyName);
                            hubDiscovered(properties);
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        logger.debug("Error connecting with found hub", e);
                    }
                }
            }
        }

        private Properties readProperties(String input) throws IOException {
            String propsString = input.replaceAll(";", "\n");
            propsString = propsString.replaceAll(":", "=");
            Properties properties = new Properties();
            properties.load(new StringReader(propsString));
            return properties;
        }

    }

    private void hubDiscovered(Properties properties) {
        String ip = properties.getProperty("ip");
        String friendlyName = properties.getProperty("friendlyName");
        String thingId = properties.getProperty("host_name").replaceAll("[^A-Za-z0-9\\-_]", "");

        logger.trace("Adding HarmonyHub {} ({}) at host {}", friendlyName, thingId, ip);

        ThingUID uid = new ThingUID(HARMONY_HUB_THING_TYPE, thingId);
        // @formatter:off
        thingDiscovered(DiscoveryResultBuilder.create(uid)
                .withLabel("HarmonyHub " + friendlyName)
                .withProperty(HUB_PROPERTY_HOST, ip)
                .withProperty(HUB_PROPERTY_NAME, friendlyName)
                .build());
        // @formatter:on
    }
}
