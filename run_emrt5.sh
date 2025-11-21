#!/bin/bash

DENSITIES="250"

# Function to run EMRT simulations
run_sim_emrt() {
    ROUTER=$1
    TOTAL_NODES=$2
    NS=$3       # Namespace (SprayAndWaitEMRT, EBREMRT…)
    MINIT=$4    # Initial copies (m_init)

    PER_GROUP=$((TOTAL_NODES / 3))
    REAL_TOTAL=$((PER_GROUP * 3))
    MAX_HOST_ID=$((REAL_TOTAL - 1))

    echo "[AUTO] Running $ROUTER with $TOTAL_NODES nodes..."

    # -------------------------------------------
    # Create unique report & config directories
    # -------------------------------------------
    REPORT_DIR="reports/emrt/${ROUTER}/${TOTAL_NODES}"
    mkdir -p "$REPORT_DIR"

    CONFIG_FILE="configs/emrt_${ROUTER}_${TOTAL_NODES}.txt"
    mkdir -p configs

    # Copy original EMRT base configuration
    cp emrt_config.txt "$CONFIG_FILE"

    # -------------------------------------------
    # Append experiment overrides
    # -------------------------------------------
    {
        echo ""
        echo "# --- Experiment Overrides ---"
        echo "Group.router = $ROUTER"
        echo "Group1.nrofHosts = $PER_GROUP"
        echo "Group2.nrofHosts = $PER_GROUP"
        echo "Group3.nrofHosts = $PER_GROUP"
        echo "Events1.hosts = 0,$MAX_HOST_ID"
        echo "Events1.tohosts = 0,$MAX_HOST_ID"
        echo "Scenario.name = ${ROUTER}_${TOTAL_NODES}"
        echo "Report.reportDir = $REPORT_DIR"

        # EMRT parameters
        echo "$NS.m_init = $MINIT"
        echo "$NS.alpha = 0.85"
        echo "$NS.updateInterval = 30"
    } >> "$CONFIG_FILE"

    # -------------------------------------------
    # RUN SIMULATION USING CORRECT CONFIG
    # -------------------------------------------
    sh one.sh -b 1 "$CONFIG_FILE"
}

echo ""
echo "=========================================="
echo "Running EMRT Protocol Simulations"
echo "=========================================="

# SprayAndWait EMRT
# for n in 250; do
#     run_sim_emrt SprayAndWaitEMRTRouter $n SprayAndWaitEMRT 8
# done

# EBR EMRT
for n in $DENSITIES; do
    run_sim_emrt EBREMRTRouter $n EBREMRT 11
done

# # DBRP EMRT
# for n in $DENSITIES; do
#     run_sim_emrt DBRPEMRTRouter $n DBRPEMRT 4
# done

echo ""
echo "=========================================="
echo "All EMRT simulations completed!"
echo "=========================================="
