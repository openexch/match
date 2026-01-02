#!/bin/bash

# Load Test Runner Script
# Quick launcher for various load testing scenarios

set -e

JAR_PATH="match/target/cluster-engine-1.0.jar"
MAIN_CLASS="com.match.loadtest.LoadGenerator"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "╔════════════════════════════════════════════════════════════╗"
echo "║        Matching Engine Load Test Runner                   ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Check if JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${RED}✗ JAR file not found: $JAR_PATH${NC}"
    echo "  Please build the project first:"
    echo "    cd match && mvn clean package"
    exit 1
fi

# Function to run load test
run_test() {
    local rate=$1
    local duration=$2
    local threads=$3
    local scenario=$4
    local description=$5

    echo -e "${GREEN}→ Running: $description${NC}"
    echo "  Rate: $rate orders/sec, Duration: ${duration}s, Threads: $threads, Scenario: $scenario"
    echo ""

    java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
        --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
        --add-opens java.base/java.nio=ALL-UNNAMED \
        -Xms1g -Xmx1g \
        -XX:+UseZGC \
        -XX:+AlwaysPreTouch \
        -XX:+PerfDisableSharedMem \
        -Djava.net.preferIPv4Stack=true \
        -Daeron.socket.so_sndbuf=2097152 \
        -Daeron.socket.so_rcvbuf=2097152 \
        -cp "$JAR_PATH" "$MAIN_CLASS" \
        --rate "$rate" \
        --duration "$duration" \
        --threads "$threads" \
        --scenario "$scenario" \
        --hosts "localhost,localhost,localhost" \
        --no-ui

    echo ""
    echo -e "${GREEN}✓ Test completed${NC}"
    echo ""
}

# Parse command line argument
case "${1:-menu}" in
    baseline)
        run_test 1000 60 4 BALANCED "Baseline Performance Test"
        ;;

    market-maker)
        run_test 5000 120 8 MARKET_MAKER "Market Maker Simulation"
        ;;

    stress)
        run_test 10000 60 16 AGGRESSIVE "High Throughput Stress Test"
        ;;

    spike)
        run_test 3000 180 8 SPIKE "Spike Traffic Test"
        ;;

    deep-book)
        run_test 4000 300 8 DEEP_BOOK "Deep Order Book Test"
        ;;

    endurance)
        run_test 2000 3600 8 BALANCED "1-Hour Endurance Test"
        ;;

    quick)
        run_test 1000 10 4 BALANCED "Quick Smoke Test"
        ;;

    progressive)
        echo -e "${YELLOW}→ Running Progressive Load Test${NC}"
        echo "  Testing incrementally increasing rates to find limits"
        echo ""

        for rate in 1000 2000 5000 8000 10000; do
            threads=$((rate / 800))
            [ "$threads" -lt 4 ] && threads=4
            [ "$threads" -gt 16 ] && threads=16

            echo -e "${GREEN}Testing rate: $rate orders/sec with $threads threads${NC}"
            run_test "$rate" 60 "$threads" AGGRESSIVE "Progressive Test - ${rate} msg/s"

            echo "Cooling down for 30 seconds..."
            sleep 30
        done
        ;;

    custom)
        if [ $# -lt 5 ]; then
            echo -e "${RED}✗ Usage: $0 custom <rate> <duration> <threads> <scenario>${NC}"
            echo "  Example: $0 custom 3000 120 8 MARKET_MAKER"
            exit 1
        fi
        run_test "$2" "$3" "$4" "$5" "Custom Load Test"
        ;;

    help|--help|-h)
        echo "Usage: $0 [test-type]"
        echo ""
        echo "Available test types:"
        echo "  baseline      - Baseline performance (1k orders/s, 60s)"
        echo "  market-maker  - Market maker simulation (5k orders/s, 120s)"
        echo "  stress        - High throughput stress (10k orders/s, 60s)"
        echo "  spike         - Spike traffic pattern (3k orders/s, 180s)"
        echo "  deep-book     - Deep order book test (4k orders/s, 300s)"
        echo "  endurance     - 1-hour endurance test (2k orders/s, 3600s)"
        echo "  quick         - Quick smoke test (1k orders/s, 10s)"
        echo "  progressive   - Progressive load test (1k to 10k orders/s)"
        echo "  custom        - Custom parameters (see below)"
        echo ""
        echo "Custom test usage:"
        echo "  $0 custom <rate> <duration> <threads> <scenario>"
        echo "  Example: $0 custom 3000 120 8 MARKET_MAKER"
        echo ""
        echo "Available scenarios:"
        echo "  BALANCED      - Normal market conditions"
        echo "  MARKET_MAKER  - High-frequency market making"
        echo "  AGGRESSIVE    - High volatility trading"
        echo "  SPIKE         - Burst traffic patterns"
        echo "  DEEP_BOOK     - Deep order book building"
        echo ""
        ;;

    menu|*)
        echo "Select a load test to run:"
        echo ""
        echo "  1. Quick Smoke Test (10 seconds)"
        echo "  2. Baseline Performance Test"
        echo "  3. Market Maker Simulation"
        echo "  4. High Throughput Stress Test"
        echo "  5. Spike Traffic Test"
        echo "  6. Deep Order Book Test"
        echo "  7. Progressive Load Test"
        echo "  8. 1-Hour Endurance Test"
        echo "  9. Custom Test"
        echo ""
        echo -n "Enter choice [1-9]: "
        read -r choice

        case $choice in
            1) $0 quick ;;
            2) $0 baseline ;;
            3) $0 market-maker ;;
            4) $0 stress ;;
            5) $0 spike ;;
            6) $0 deep-book ;;
            7) $0 progressive ;;
            8) $0 endurance ;;
            9)
                echo ""
                echo -n "Rate (orders/sec): "
                read -r rate
                echo -n "Duration (seconds): "
                read -r duration
                echo -n "Threads: "
                read -r threads
                echo -n "Scenario [BALANCED|MARKET_MAKER|AGGRESSIVE|SPIKE|DEEP_BOOK]: "
                read -r scenario
                $0 custom "$rate" "$duration" "$threads" "$scenario"
                ;;
            *)
                echo -e "${RED}✗ Invalid choice${NC}"
                exit 1
                ;;
        esac
        ;;
esac
