#!/bin/bash

DENSITIES="50 100 150 200 250"

run_sim_emrt() {
    ROUTER=$1
    TOTAL_NODES=$2
    NS=$3
    MINIT=$4

    PER_GROUP=$((TOTAL_NODES / 3))
    REAL_TOTAL=$((PER_GROUP * 3))
    MAX_HOST_ID=$((REAL_TOTAL - 1))

    echo "[AUTO] Running $ROUTER with $TOTAL_NODES nodes..."

    cp -f default_settings.txt current_run.txt

    {
        echo ""
        echo "# --- Experiment Overrides ---"
        echo "Group.router = $ROUTER"
        echo "Group1.nrofHosts = $PER_GROUP"
        echo "Group2.nrofHosts = $PER_GROUP"
        echo "Group3.nrofHosts = $PER_GROUP"
        echo "Events1.hosts = 0,$MAX_HOST_ID"
        echo "Scenario.name = ${ROUTER}_${TOTAL_NODES}"
        echo "$NS.m_init = $MINIT"
        echo "$NS.alpha = 0.85"
        echo "$NS.updateInterval = 30"
    } >> current_run.txt

    # CORRECT BATCH MODE COMMAND
    sh one.sh -b 1 current_run.txt
}

# EMRT PROTOCOLS
for n in $DENSITIES; do
    run_sim_emrt SprayAndWaitEMRTRouter $n SprayAndWaitEMRT 8
done

for n in $DENSITIES; do
    run_sim_emrt EBREMRTRouter $n EBREMRT 11
done

for n in $DENSITIES; do
    run_sim_emrt DBRPEMRTRouter $n DBRPEMRT 4
done

echo ""
echo "=========================================="
echo "All EMRT simulations completed automatically!"
echo "=========================================="
