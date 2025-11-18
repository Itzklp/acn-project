#!/bin/bash
# ==========================================
# Automated Experiment Script for Figure 2
# Runs 6 Protocols x 5 Densities = 30 Runs
# ==========================================

# Define the Total Node Densities (Paper: 50, 100, 150, 200, 250)
DENSITIES="50 100 150 200 250"

# ------------------------------------------
# Helper Functions
# ------------------------------------------

run_sim() {
    ROUTER=$1
    TOTAL_NODES=$2
    # 3 groups (p, c, w). No Trams.
    PER_GROUP=$((TOTAL_NODES / 3))
    REAL_TOTAL=$((PER_GROUP * 3))
    MAX_HOST_ID=$((REAL_TOTAL - 1))

    # Changed | to - to prevent shell error
    echo "[Experiment] $ROUTER - Nodes: ~$REAL_TOTAL (Grp: $PER_GROUP)"
    cp -f default_settings.txt current_run.txt
    
    echo "" >> current_run.txt
    echo "# --- Experiment Overrides ---" >> current_run.txt
    echo "Group.router = $ROUTER" >> current_run.txt
    echo "Group1.nrofHosts = $PER_GROUP" >> current_run.txt
    echo "Group2.nrofHosts = $PER_GROUP" >> current_run.txt
    echo "Group3.nrofHosts = $PER_GROUP" >> current_run.txt
    echo "Events1.hosts = 0,$MAX_HOST_ID" >> current_run.txt
    echo "Scenario.name = ${ROUTER}_${TOTAL_NODES}" >> current_run.txt
    
    if [ "$ROUTER" == "SprayAndWaitRouter" ]; then
        echo "SprayAndWaitRouter.nrofCopies = 8" >> current_run.txt
    fi
    
    sh one.sh current_run.txt
}

run_sim_emrt() {
    ROUTER=$1
    TOTAL_NODES=$2
    NS=$3
    MINIT=$4
    PER_GROUP=$((TOTAL_NODES / 3))
    REAL_TOTAL=$((PER_GROUP * 3))
    MAX_HOST_ID=$((REAL_TOTAL - 1))

    # Changed | to - to prevent shell error
    echo "[Experiment] $ROUTER - Nodes: ~$REAL_TOTAL (Grp: $PER_GROUP)"
    cp -f default_settings.txt current_run.txt
    
    echo "" >> current_run.txt
    echo "# --- Experiment Overrides ---" >> current_run.txt
    echo "Group.router = $ROUTER" >> current_run.txt
    echo "Group1.nrofHosts = $PER_GROUP" >> current_run.txt
    echo "Group2.nrofHosts = $PER_GROUP" >> current_run.txt
    echo "Group3.nrofHosts = $PER_GROUP" >> current_run.txt
    echo "Events1.hosts = 0,$MAX_HOST_ID" >> current_run.txt
    echo "Scenario.name = ${ROUTER}_${TOTAL_NODES}" >> current_run.txt
    echo "$NS.m_init = $MINIT" >> current_run.txt
    echo "$NS.alpha = 0.85" >> current_run.txt
    echo "$NS.updateInterval = 30" >> current_run.txt
    
    sh one.sh current_run.txt
}


# ------------------------------------------
# 1. Standard Protocols
# ------------------------------------------

for n in $DENSITIES; do
    run_sim SprayAndWaitRouter $n
done

for n in $DENSITIES; do
    run_sim EncounterBasedRouter $n
done

for n in $DENSITIES; do
    run_sim DBRPRouter $n
done

# ------------------------------------------
# 2. EMRT Protocols (Proposed)
# ------------------------------------------

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
echo "All simulations completed!"
echo "Check the 'reports/' folder."
echo "=========================================="