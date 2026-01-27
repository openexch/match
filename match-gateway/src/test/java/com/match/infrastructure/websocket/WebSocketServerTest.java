package com.match.infrastructure.websocket;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for WebSocketServer start/stop/isRunning lifecycle.
 * Uses a random high port to avoid conflicts.
 */
public class WebSocketServerTest {

    private int getRandomPort() {
        return 18000 + (int) (Math.random() * 10000);
    }

    @Test
    public void testConstructorWithPort() {
        SubscriptionManager subManager = new SubscriptionManager();
        WebSocketServer server = new WebSocketServer(19999, subManager);
        assertEquals(19999, server.getPort());
        server.close();
    }

    @Test
    public void testConstructorDefault() {
        SubscriptionManager subManager = new SubscriptionManager();
        WebSocketServer server = new WebSocketServer(subManager);
        assertEquals(8081, server.getPort());
        server.close();
    }

    @Test
    public void testGetSubscriptionManager() {
        SubscriptionManager subManager = new SubscriptionManager();
        WebSocketServer server = new WebSocketServer(19999, subManager);
        assertSame(subManager, server.getSubscriptionManager());
        server.close();
    }

    @Test
    public void testIsRunning_FalseBeforeStart() {
        SubscriptionManager subManager = new SubscriptionManager();
        WebSocketServer server = new WebSocketServer(getRandomPort(), subManager);
        assertFalse(server.isRunning());
        server.close();
    }

    @Test
    public void testStartAndIsRunning() throws InterruptedException {
        int port = getRandomPort();
        SubscriptionManager subManager = new SubscriptionManager();
        WebSocketServer server = new WebSocketServer(port, subManager);
        try {
            server.start();
            assertTrue(server.isRunning());
        } finally {
            server.close();
        }
    }

    @Test
    public void testStartThenClose() throws InterruptedException {
        int port = getRandomPort();
        SubscriptionManager subManager = new SubscriptionManager();
        WebSocketServer server = new WebSocketServer(port, subManager);
        server.start();
        assertTrue(server.isRunning());
        server.close();
        // Close should not throw
    }

    @Test
    public void testCloseBeforeStart_NoCrash() {
        SubscriptionManager subManager = new SubscriptionManager();
        WebSocketServer server = new WebSocketServer(getRandomPort(), subManager);
        server.close(); // Never started — should not crash
    }
}
