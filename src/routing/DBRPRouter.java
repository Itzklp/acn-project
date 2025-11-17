package routing;

import java.util.*;
import core.*;

/**
 * Destination-Based Routing Protocol (DBRP)
 * * Based on: S. Iranmanesh et al. (2012) and Hasan et al. (2023).
 * * Corrections made:
 * 1. Updates weights on connection (encounters), not just message receipt.
 * 2. Removes message-dependent logic from weight calculation.
 * 3. Implements Binary Spraying for efficient replica distribution.
 */
public class DBRPRouter extends ActiveRouter {

    /** Settings namespace */
    public static final String DBRP_NS = "DBRPRouter";

    /** Config keys */
    private static final String WEIGHT_INC_S = "weightIncrement";
    private static final String AGING_FACTOR_S = "agingFactor";
    private static final String MAX_REPLICA_S = "maxReplicas";

    /** Message property key */
    private static final String MSG_REPLICA_COUNT = DBRP_NS + ".replicas";

    /** Parameters */
    protected double weightIncrement;     // increment on each encounter
    protected double agingFactor;         // decay rate per update
    protected int maxReplicas;            // number of replicas allowed

    /** Table of encounter weights: Host -> Weight (0.0 to 1.0) */
    protected HashMap<DTNHost, Double> encounterWeights;

    public DBRPRouter(Settings s) {
        super(s);
        Settings dbrp = new Settings(DBRP_NS);

        this.weightIncrement = dbrp.getDouble(WEIGHT_INC_S, 0.1);
        this.agingFactor = dbrp.getDouble(AGING_FACTOR_S, 0.98);
        this.maxReplicas = dbrp.getInt(MAX_REPLICA_S, 4);

        this.encounterWeights = new HashMap<>();
    }

    protected DBRPRouter(DBRPRouter r) {
        super(r);
        this.weightIncrement = r.weightIncrement;
        this.agingFactor = r.agingFactor;
        this.maxReplicas = r.maxReplicas;
        this.encounterWeights = new HashMap<>(r.encounterWeights);
    }

    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        msg.setTtl(this.msgTtl);
        msg.addProperty(MSG_REPLICA_COUNT, maxReplicas);
        addToMessages(msg, true);
        return true;
    }

    /**
     * CRITICAL FIX: Update weights when a connection is established (Encounter).
     */
    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);

        if (con.isUp()) {
            DTNHost other = con.getOtherNode(getHost());
            updateEncounterWeight(other);
        }
    }

    /**
     * Update encounter weight when meeting a node.
     * In DBRP, meeting a node increases our "weight" (likelihood) of delivering to it.
     */
    protected void updateEncounterWeight(DTNHost encountered) {
        double oldW = encounterWeights.getOrDefault(encountered, 0.0);
        // Simple additive increase, capped at 1.0
        double newW = Math.min(1.0, oldW + weightIncrement);
        encounterWeights.put(encountered, newW);
    }

    /**
     * Periodic update — ages weights and tries forwarding.
     */
    @Override
    public void update() {
        super.update();

        if (!canStartTransfer() || isTransferring()) {
            return;
        }

        // Apply decay to encounter weights
        ageEncounterWeights();

        // Try to forward messages
        // We create a copy of the collection to avoid ConcurrentModificationException
        Collection<Message> msgCollection = new ArrayList<>(getMessageCollection());
        for (Message msg : msgCollection) {
            for (Connection con : getConnections()) {
                DTNHost other = con.getOtherNode(getHost());
                tryForward(msg, con, other);
            }
        }
    }

    protected void ageEncounterWeights() {
        Iterator<Map.Entry<DTNHost, Double>> it = encounterWeights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<DTNHost, Double> entry = it.next();
            double w = entry.getValue() * agingFactor;
            if (w < 0.001) {
                it.remove(); // Remove negligible weights to save memory
            } else {
                entry.setValue(w);
            }
        }
    }

    /**
     * Forwarding decision:
     * 1. Deliver if destination.
     * 2. Forward if neighbor has higher weight for destination.
     * 3. Split replicas (Binary Spray).
     */
    protected void tryForward(Message msg, Connection con, DTNHost other) {
        if (isTransferring() || other == null) return;

        DTNHost dest = msg.getTo();

        // 1. Direct Delivery
        if (other.equals(dest)) {
            startTransfer(msg, con);
            return;
        }

        // 2. Check Replicas
        int replicas = 1;
        Object replicasObj = msg.getProperty(MSG_REPLICA_COUNT);
        if (replicasObj != null) {
            replicas = (int) replicasObj;
        }

        if (replicas <= 1) {
            return; // Only 1 copy left, wait for direct delivery
        }

        // 3. Compare Weights (Gradient)
        double myWeight = encounterWeights.getOrDefault(dest, 0.0);
        double otherWeight = 0.0;

        if (other.getRouter() instanceof DBRPRouter) {
            DBRPRouter r = (DBRPRouter) other.getRouter();
            // Access other router's weight for the message destination
            otherWeight = r.encounterWeights.getOrDefault(dest, 0.0);
        }

        // Forward if neighbor is better
        if (otherWeight > myWeight) {
            // Binary Spray Logic
            int newReplicas = replicas / 2;
            msg.updateProperty(MSG_REPLICA_COUNT, replicas - newReplicas);

            Message copy = msg.replicate();
            copy.updateProperty(MSG_REPLICA_COUNT, newReplicas);

            addToMessages(copy, true);
            startTransfer(copy, con);
        }
    }

    @Override
    public DBRPRouter replicate() {
        return new DBRPRouter(this);
    }
}