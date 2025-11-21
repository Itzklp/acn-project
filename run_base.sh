#!/bin/bash

DENSITIES="150 200 250"

run_sim_base() {
    ROUTER=$1
    TOTAL_NODES=$2

    PER_GROUP=$((TOTAL_NODES / 3))
    REAL_TOTAL=$((PER_GROUP * 3))
    MAX_HOST_ID=$((REAL_TOTAL - 1))

    echo "[BASE] Running $ROUTER with $TOTAL_NODES nodes..."

    REPORT_DIR="reports/base/${ROUTER}/${TOTAL_NODES}"
    mkdir -p "$REPORT_DIR"

    CONFIG_FILE="configs/base_${ROUTER}_${TOTAL_NODES}.txt"
    mkdir -p configs
    cp base_config.txt "$CONFIG_FILE"

    {
        echo ""
        echo "# --- Base Experiment Overrides ---"
        echo "Group.router = $ROUTER"
        echo "Group1.nrofHosts = $PER_GROUP"
        echo "Group2.nrofHosts = $PER_GROUP"
        echo "Group3.nrofHosts = $PER_GROUP"
        echo "Events1.hosts = 0,$MAX_HOST_ID"
        echo "Events1.tohosts = 0,$MAX_HOST_ID"
        echo "Scenario.name = ${ROUTER}_${TOTAL_NODES}_BASE"
        echo "Report.reportDir = $REPORT_DIR"
    } >> "$CONFIG_FILE"

    # Router-Specific Parameters
    if [[ "$ROUTER" == "SprayAndWaitRouter" ]]; then
        {
            echo "SprayAndWaitRouter.nrofCopies = 8"
            echo "SprayAndWaitRouter.binaryMode = true"
        } >> "$CONFIG_FILE"
    fi

    if [[ "$ROUTER" == "EncounterBasedRouter" ]]; then
        {
            echo "EncounterBasedRouter.initMax = 10"
            echo "EncounterBasedRouter.updateInterval = 30"
            echo "EncounterBasedRouter.gamma = 0.85"
        } >> "$CONFIG_FILE"
    fi

    if [[ "$ROUTER" == "DBRPRouter" ]]; then
        {
            echo "DBRPRouter.initCopies = 11"
            echo "DBRPRouter.updateInterval = 30"
        } >> "$CONFIG_FILE"
    fi

    sh one.sh -b 1 "$CONFIG_FILE"
}

echo "=========================================="
echo "Running BASE Protocol Simulations"
echo "=========================================="

# for n in $DENSITIES; do
#     run_sim_base SprayAndWaitRouter $n
# done

for n in $DENSITIES; do
    run_sim_base EncounterBasedRouter $n
done

# for n in $DENSITIES; do
#     run_sim_base DBRPRouter $n
# done

echo ""
echo "=========================================="
echo "All BASE simulations completed!"
echo "=========================================="
