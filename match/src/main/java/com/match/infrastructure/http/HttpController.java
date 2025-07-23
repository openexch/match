package com.match.infrastructure.http;// HttpAeronGateway.java

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.match.infrastructure.generated.sbe.*;
import com.sun.net.httpserver.HttpServer;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.SystemEpochClock;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HttpController implements EgressListener, AutoCloseable, Agent {

    private static final long HEARTBEAT_INTERVAL = 250;
    private long lastHeartbeatTime = Long.MIN_VALUE;
    private static final int HTTP_PORT = 8080;
    private ConnectionState connectionState = ConnectionState.NOT_CONNECTED;
    // Gson nesnesini bir kere oluşturup yeniden kullanmak en verimlisidir.
    private static final Gson gson = new Gson();

    private final ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(512); // Buffer'ı biraz büyüttük

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final CreateOrderEncoder createOrderEncoder = new CreateOrderEncoder();

    private final MediaDriver mediaDriver;
    private final AeronCluster cluster;
    private final HttpServer server;
    
    // Rate limiting and flow control
    private final AtomicInteger requestsInFlight = new AtomicInteger(0);
    private final AtomicLong lastBackpressureTime = new AtomicLong(0);
    private static final int MAX_REQUESTS_IN_FLIGHT = 1000;
    private static final long BACKPRESSURE_COOLDOWN_MS = 100;

    public HttpController() throws IOException {

        final List<String> hostnames = Arrays.asList("172.16.202.2,172.16.202.3,172.16.202.4".split(","));
        final String ingressEndpoints = io.aeron.samples.cluster.ClusterConfig.ingressEndpoints(
                hostnames, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        this.mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context().threadingMode(ThreadingMode.SHARED).dirDeleteOnStart(true).dirDeleteOnShutdown(true));
        final AeronCluster.Context clusterCtx = new AeronCluster.Context().
                egressListener(this).
                egressChannel("aeron:udp?endpoint=172.16.202.10:9091").
                ingressChannel("aeron:udp?term-length=64k").
                aeronDirectoryName(mediaDriver.aeronDirectoryName()).
                ingressEndpoints(ingressEndpoints);

        System.out.println("Connecting to Aeron Cluster...");
        this.cluster = AeronCluster.connect(clusterCtx);
        System.out.println("Connected to Cluster");
        connectionState = ConnectionState.CONNECTED;

        this.server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        this.server.createContext("/order", (exchange) -> {
            String responseText = "Order accepted and forwarded to cluster.";
            int statusCode = 202;

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    // Gelen JSON'ı Order sınıfına dönüştür
                    Order order = gson.fromJson(reader, Order.class);

                    if (order == null || order.market == null) {
                        responseText = "Error: Invalid or empty JSON body.";
                        statusCode = 400; // Bad Request
                    } else {
                        // Rate limiting check
                        if (requestsInFlight.get() >= MAX_REQUESTS_IN_FLIGHT) {
                            responseText = "Error: Server is overloaded. Please retry later.";
                            statusCode = 503; // Service Unavailable
                        } else if (System.currentTimeMillis() - lastBackpressureTime.get() < BACKPRESSURE_COOLDOWN_MS) {
                            responseText = "Error: Cluster is experiencing backpressure. Please slow down.";
                            statusCode = 429; // Too Many Requests
                        } else {
                            requestsInFlight.incrementAndGet();
                            try {
                                sendMessage(order);
                            } finally {
                                requestsInFlight.decrementAndGet();
                            }
                        }
                    }
                } catch (JsonSyntaxException e) {
                    responseText = "Error: Malformed JSON.";
                    statusCode = 400; // Bad Request
                } catch (Exception e) {
                    responseText = "Error processing request: " + e.getMessage();
                    statusCode = 500;
                    e.printStackTrace();
                }
            } else {
                responseText = "Error: Only POST method is supported.";
                statusCode = 405;
            }

            exchange.sendResponseHeaders(statusCode, responseText.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseText.getBytes());
            }
        });

        this.server.setExecutor(null);
        this.server.start();
        System.out.println("🚀 JSON HTTP Gateway started on http://localhost:" + HTTP_PORT);
    }

    // Diğer metotlar (startPolling, sendMessage, onMessage, vb.) bir öncekiyle aynı...

    public void startPolling() {
        final IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(100);
        while (!cluster.isClosed()) {
            idleStrategy.idle(cluster.pollEgress());
        }
    }

    public void sendMessage(Order order) {
        if (cluster == null || cluster.isClosed()) {
            throw new IllegalStateException("Cluster is not connected.");
        }

        createOrderEncoder.wrapAndApplyHeader(buffer,0, headerEncoder);

        createOrderEncoder.userId(order.userId);
        createOrderEncoder.market(order.market);
        createOrderEncoder.orderType(order.toOrderType());
        createOrderEncoder.orderSide(order.toOrderSide());
        createOrderEncoder.price(order.price);
        createOrderEncoder.quantity(order.quantity);
        createOrderEncoder.totalPrice(order.totalPrice);

        final int length = MessageHeaderEncoder.ENCODED_LENGTH + createOrderEncoder.encodedLength();
        long result;
        int retryCount = 0;
        final int maxRetries = 3;
        final long retryDelayMs = 10;
        
        while ((result = cluster.offer(buffer, 0, length)) < 0) {
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED) {
                throw new IllegalStateException("Cluster connection lost: " + result);
            }
            
            if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                lastBackpressureTime.set(System.currentTimeMillis());
                if (++retryCount > maxRetries) {
                    throw new RuntimeException("Message rejected due to backpressure after " + maxRetries + " retries");
                }
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while handling backpressure", e);
                }
            } else {
                throw new RuntimeException("Failed to send message to cluster: " + result);
            }
        }
    }

    @Override
    public void onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer, int offset, int length, Header header) {
        final String response = buffer.getStringUtf8(offset);
        System.out.printf("⬅️ Response from cluster (Session ID: %d): '%s'%n", clusterSessionId, response);
    }

    @Override
    public void onNewLeader(long clusterSessionId, long leadershipTermId, int leaderMemberId, String ingressEndpoints) {
        System.out.printf("ℹ️ New cluster leader: memberId=%d, termId=%d, endpoints=%s%n",
                leaderMemberId, leadershipTermId, ingressEndpoints);
    }

    @Override
    public void close() {
        // Stop accepting new requests immediately
        if (server != null) {
            server.stop(0); // 0 means stop immediately
        }
        
        // Close cluster connection
        if (cluster != null && !cluster.isClosed()) {
            try {
                cluster.close();
            } catch (Exception e) {
                // Log but don't rethrow - we want to continue cleanup
                e.printStackTrace();
            }
        }
        
        // Close media driver
        if (mediaDriver != null) {
            mediaDriver.close();
        }
    }

    public static void main(String[] args) throws IOException {
        final HttpController gateway = new HttpController();
        gateway.startPolling();
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
    }

    @Override
    public int doWork() throws Exception {
        //send cluster heartbeat roughly every 250ms
        final long now = SystemEpochClock.INSTANCE.time();
        if (now >= (lastHeartbeatTime + HEARTBEAT_INTERVAL))
        {
            lastHeartbeatTime = now;
            if (connectionState == ConnectionState.CONNECTED)
            {
                cluster.sendKeepAlive();
            }
        }

        //poll outbound messages from the cluster
        if (null != cluster && !cluster.isClosed())
        {
            cluster.pollEgress();
        }

        //always sleep
        return 0;
    }

    @Override
    public String roleName() {
        return "cluster-http-agent";
    }
}