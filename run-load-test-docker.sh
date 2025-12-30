#!/bin/bash

# Load Test Runner Script - Docker Version
# Runs load tests from inside the Docker network

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "╔════════════════════════════════════════════════════════════╗"
echo "║     Matching Engine Load Test Runner (Docker)             ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Navigate to docker directory
cd docker

# Function to run load test inside Docker
run_docker_test() {
    local rate=$1
    local duration=$2
    local threads=$3
    local scenario=$4
    local description=$5

    echo -e "${GREEN}→ Running: $description${NC}"
    echo "  Rate: $rate orders/sec, Duration: ${duration}s, Threads: $threads, Scenario: $scenario"
    echo ""

    docker compose exec load-generator java \
        --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
        --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
        -cp /home/aeron/jar/cluster.jar \
        com.match.loadtest.LoadGenerator \
        --rate "$rate" \
        --duration "$duration" \
        --threads "$threads" \
        --scenario "$scenario" \
        --hosts "172.16.202.2,172.16.202.3,172.16.202.4"

    echo ""
    echo -e "${GREEN}✓ Test completed${NC}"
    echo ""
}

# Parse command line argument
case "${1:-menu}" in
    baseline)
        run_docker_test 1000 60 4 BALANCED "Baseline Performance Test"
        ;;

    market-maker)
        run_docker_test 5000 120 8 MARKET_MAKER "Market Maker Simulation"
        ;;

    stress)
        run_docker_test 10000 60 16 AGGRESSIVE "High Throughput Stress Test"
        ;;

    spike)
        run_docker_test 3000 180 8 SPIKE "Spike Traffic Test"
        ;;

    deep-book)
        run_docker_test 4000 300 8 DEEP_BOOK "Deep Order Book Test"
        ;;

    endurance)
        run_docker_test 2000 3600 8 BALANCED "1-Hour Endurance Test"
        ;;

    quick)
        run_docker_test 1000 10 4 BALANCED "Quick Smoke Test"
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
            run_docker_test "$rate" 60 "$threads" AGGRESSIVE "Progressive Test - ${rate} msg/s"

            echo "Cooling down for 30 seconds..."
            sleep 30
        done
        ;;

    custom)
        if [ $# -lt 5 ]; then
            echo "Usage: $0 custom <rate> <duration> <threads> <scenario>"
            echo "  Example: $0 custom 3000 120 8 MARKET_MAKER"
            exit 1
        fi
        run_docker_test "$2" "$3" "$4" "$5" "Custom Load Test"
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
                echo "Invalid choice"
                exit 1
                ;;
        esac
        ;;
esac
