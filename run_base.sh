#!/bin/bash
# ==========================================
# Automated Experiment Script (BASE ONLY)
# Runs: SprayAndWait, EBR, DBRP
# Across 5 densities — Batch Mode Enabled
# ==========================================

DENSITIES="50 100 150 200 250"

# ------------------------------------------
# Helper function for BASE protocols
# ------------------------------------------
run_sim() {
    ROUTER=$1
    TOTAL_NODES=$2
    
    PER_GROUP=$((TOTAL_NODES / 3))
    REAL_TOTAL=$((PER_GROUP * 3))
    MAX_HOST_ID=$((REAL_TOTAL - 1))

    echo "[BASE] Running $ROUTER with ~$REAL_TOTAL nodes..."

    cp -f default_settings.txt current_run.txt
    
    {
        echo ""
        echo "# --- BASE Protocol Overrides ---"
        echo "Group.router = $ROUTER"
        echo "Group1.nrofHosts = $PER_GROUP"
        echo "Group2.nrofHosts = $PER_GROUP"
        echo "Group3.nrofHosts = $PER_GROUP"
        echo "Events1.hosts = 0,$MAX_HOST_ID"
        echo "Scenario.name = ${ROUTER}_${TOTAL_NODES}"
    } >> current_run.txt

    # Spray-and-Wait has a special parameter
    if [ "$ROUTER" == "SprayAndWaitRouter" ]; then
        echo "SprayAndWaitRouter.nrofCopies = 8" >> current_run.txt
    fi

    # 🚀 Run The ONE in non-interactive batch mode
    sh one.sh -b 1 current_run.txt
}

# ------------------------------------------
# Run BASE Protocols Only
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

echo ""
echo "=========================================="
echo "All BASE protocol simulations completed!"
echo "Check the 'reports/' folder."
echo "=========================================="
