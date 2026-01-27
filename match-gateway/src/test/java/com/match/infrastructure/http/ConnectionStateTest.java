package com.match.infrastructure.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for ConnectionState enum.
 */
public class ConnectionStateTest {

    @Test
    public void testConnectionState_NotConnectedExists() {
        assertEquals(ConnectionState.NOT_CONNECTED, ConnectionState.valueOf("NOT_CONNECTED"));
    }

    @Test
    public void testConnectionState_ConnectedExists() {
        assertEquals(ConnectionState.CONNECTED, ConnectionState.valueOf("CONNECTED"));
    }

    @Test
    public void testConnectionState_ValuesCount() {
        ConnectionState[] values = ConnectionState.values();
        assertEquals(2, values.length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConnectionState_InvalidValue_Throws() {
        ConnectionState.valueOf("DISCONNECTED");
    }
}
