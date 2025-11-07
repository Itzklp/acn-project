/*
 * Copyright 2025 Your Name
 * Released under GPLv3. See LICENSE.txt for details.
 */

package routing;

import java.util.*;
import core.*;

/**
 * Destination-Based Routing Protocol (DBRP)
 * Reference:
 * S. Iranmanesh, R. Raad, and K.-W. Chin,
 * "A novel destination-based routing protocol (DBRP) in DTNs,"
 * Proc. ISCIT 2012, pp. 325–330.
 *
 * Key Features:
 *  - Each node keeps encounter weights for every other node.
 *  - Encounters with the destination increase weight more strongly.
 *  - Weights are aged (decayed) over time.
 *  - Forwarding occurs only to nodes with higher weight to the destination.
 *  - Replication quota (maxReplicas) controls message copies.
 */
public class DBRPRouter extends ActiveRouter {

    /** Settings namespace */
    public static final String DBRP_NS = "DBRPRouter";

    /** Config keys */
    private static final String WEIGHT_INC_S = "weightIncrement";
    private static final String AGING_FACTOR_S = "agingFactor";
    private static final String DEST_FACTOR_S = "destWeightFactor";
    private static final String MAX_REPLICA_S = "maxReplicas";

    /** Message property key */
    private static final String MSG_REPLICA_COUNT = DBRP_NS + ".replicas";

    /** Parameters */
    protected double weightIncrement;     // increment on each encounter
    protected double agingFactor;         // decay rate per update
    protected double destWeightFactor;    // multiplier when meeting destination
    protected int maxReplicas;            // number of replicas allowed

    /** Table of encounter weights: Host -> Weight */
    protected HashMap<DTNHost, Double> encounterWeights;

    /**
     * Constructor for initialization from settings.
     */
    public DBRPRouter(Settings s) {
        super(s);
        Settings dbrp = new Settings(DBRP_NS);

        this.weightIncrement = dbrp.getDouble(WEIGHT_INC_S, 0.1);
        this.agingFactor = dbrp.getDouble(AGING_FACTOR_S, 0.98);
        this.destWeightFactor = dbrp.getDouble(DEST_FACTOR_S, 5.0);
        this.maxReplicas = dbrp.getInt(MAX_REPLICA_S, 4);

        this.encounterWeights = new HashMap<>();
    }

    /**
     * Copy constructor used when replicating router for new nodes.
     */
    protected DBRPRouter(DBRPRouter r) {
        super(r);
        this.weightIncrement = r.weightIncrement;
        this.agingFactor = r.agingFactor;
        this.destWeightFactor = r.destWeightFactor;
        this.maxReplicas = r.maxReplicas;
        this.encounterWeights = new HashMap<>(r.encounterWeights);
    }

    /**
     * Called when a node creates a new message.
     */
    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        msg.setTtl(this.msgTtl);
        msg.addProperty(MSG_REPLICA_COUNT, maxReplicas);
        addToMessages(msg, true);
        return true;
    }

    /**
     * Called when receiving a message from another node.
     */
    @Override
    public int receiveMessage(Message m, DTNHost from) {
        updateEncounterWeight(from, m.getTo());
        return super.receiveMessage(m, from);
    }

    /**
     * Called when a message has been successfully transferred.
     */
    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        if (m != null) {
            updateEncounterWeight(from, m.getTo());
        }
        return m;
    }

    /**
     * Periodic update — ages weights and tries forwarding.
     */
    @Override
    public void update() {
        super.update();

        // Apply decay to encounter weights
        ageEncounterWeights();

        if (!canStartTransfer() || isTransferring()) {
            return;
        }

        Collection<Message> msgCollection = new ArrayList<>(getMessageCollection());
        for (Message msg : msgCollection) {
            for (Connection con : getConnections()) {
                DTNHost other = con.getOtherNode(getHost());
                tryForward(msg, con, other);
            }
        }
    }

    /**
     * Update encounter weight when meeting another node.
     */
    protected void updateEncounterWeight(DTNHost encountered, DTNHost dest) {
        double oldW = encounterWeights.getOrDefault(encountered, 0.0);
        double inc = weightIncrement;

        // If encountered node is the destination, boost increment
        if (encountered.equals(dest)) {
            inc *= destWeightFactor;
        }

        double newW = Math.min(1.0, oldW + inc);
        encounterWeights.put(encountered, newW);
    }

    /**
     * Apply exponential decay (aging) to encounter weights.
     */
    protected void ageEncounterWeights() {
        for (DTNHost h : new ArrayList<>(encounterWeights.keySet())) {
            double w = encounterWeights.get(h);
            w *= agingFactor;
            if (w < 0.001) encounterWeights.remove(h);
            else encounterWeights.put(h, w);
        }
    }

        /**
     * Forwarding decision — only forward if other node has higher weight
     * for the destination. Uses replica splitting like Spray-and-Wait.
     */
    protected void tryForward(Message msg, Connection con, DTNHost other) {
        if (isTransferring() || other == null) return;

        DTNHost dest = msg.getTo();

        // Direct delivery if destination encountered
        if (other.equals(dest)) {
            startTransfer(msg, con);
            return;
        }

        // Check remaining replicas
        int replicas = (int) msg.getProperty(MSG_REPLICA_COUNT);
        if (replicas <= 1) {
            return; // last copy - only deliver directly
        }

        double myWeight = encounterWeights.getOrDefault(dest, 0.0);
        double otherWeight = 0.0;

        if (other.getRouter() instanceof DBRPRouter) {
            DBRPRouter r = (DBRPRouter) other.getRouter();
            otherWeight = r.encounterWeights.getOrDefault(dest, 0.0);
        }

        // Forward if the other node has higher destination weight
        if (otherWeight > myWeight) {
            // Split replicas
            int newReplicas = replicas / 2;
            msg.updateProperty(MSG_REPLICA_COUNT, replicas - newReplicas);

            // Create a new copy properly
            Message copy = new Message(getHost(), dest, msg.getId(), msg.getSize());
            copy.setTtl(msg.getTtl());
            copy.addProperty(MSG_REPLICA_COUNT, newReplicas);
            copy.setAppID(msg.getAppID());
            copy.setResponseSize(msg.getResponseSize());

            // Debug log (optional)
            System.out.println("[DBRP] " + getHost() + " -> " + other +
                               " forwarded msg " + msg.getId() +
                               " (myW=" + myWeight + ", otherW=" + otherWeight + ")");

            // Initiate transfer
            addToMessages(copy, true);
            startTransfer(copy, con);
        }
    }


    /**
     * Replicate router for a new node instance.
     */
    @Override
    public DBRPRouter replicate() {
        return new DBRPRouter(this);
    }
}
