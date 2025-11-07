/*
 * Copyright 2025 Your Name
 * Released under GPLv3. See LICENSE.txt for details.
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;

/**
 * Corrected Encounter-Based Routing (EBR) implementation based on:
 * Nelson, S.C.; Bakht, M.; Kravets, R. "Encounter-based routing in DTNs".
 * INFOCOM 2009, pp. 846–854.
 *
 * Features:
 * - Delivery probability table per host
 * - Aging/decay of probabilities
 * - Transitivity updates
 * - Forwarding if probability at neighbor is higher or unknown
 */
public class EncounterBasedRouter extends ActiveRouter {

    /** Namespace & message property keys */
    public static final String EBR_NS = "EncounterBasedRouter";
    public static final String MSG_DELIVERY_PROB = EBR_NS + ".deliveryProb";

    /** Configuration parameters */
    protected double deliveryIncrement;   // Increment when encountering a host
    protected double agingFactor;         // Decay factor (0 < agingFactor < 1)
    protected double transitivityFactor;  // Transitivity factor (0 < transitivityFactor < 1)

    /** Delivery probability table: DTNHost -> probability */
    protected HashMap<DTNHost, Double> deliveryProbabilities;

    public EncounterBasedRouter(Settings s) {
        super(s);
        Settings ebrSettings = new Settings(EBR_NS);

        deliveryIncrement = ebrSettings.getDouble("deliveryIncrement", 0.1);
        agingFactor = ebrSettings.getDouble("agingFactor", 0.98);
        transitivityFactor = ebrSettings.getDouble("transitivityFactor", 0.5);

        deliveryProbabilities = new HashMap<>();
    }

    protected EncounterBasedRouter(EncounterBasedRouter r) {
        super(r);
        this.deliveryIncrement = r.deliveryIncrement;
        this.agingFactor = r.agingFactor;
        this.transitivityFactor = r.transitivityFactor;
        this.deliveryProbabilities = new HashMap<>(r.deliveryProbabilities);
    }

    @Override
    public int receiveMessage(Message m, DTNHost from) {
        updateDeliveryProbability(from);
        return super.receiveMessage(m, from);
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        updateDeliveryProbability(from);
        return msg;
    }

    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        msg.setTtl(this.msgTtl);
        msg.addProperty(MSG_DELIVERY_PROB, 0.0);
        addToMessages(msg, true);
        return true;
    }

    @Override
    public void update() {
        super.update();

        if (!canStartTransfer() || isTransferring()) return;

        // Age delivery probabilities every update
        ageDeliveryProbabilities();

        // Get messages in queue order
        @SuppressWarnings("unchecked")
        Collection<Message> messages = new ArrayList<>(getMessageCollection());

        // Try forwarding each message to each connected node
        for (Message msg : messages) {
            for (Connection con : getConnections()) {
                tryMessageToConnection(msg, con);
            }
        }
    }

    /** Update probability to encountered host and apply transitivity */
    protected void updateDeliveryProbability(DTNHost encountered) {
        // Increment probability to encountered host
        double oldProb = deliveryProbabilities.getOrDefault(encountered, 0.0);
        oldProb = Math.min(1.0, oldProb + deliveryIncrement);
        deliveryProbabilities.put(encountered, oldProb);

        // Apply transitivity using the other host's table
        if (encountered.getRouter() instanceof EncounterBasedRouter) {
            EncounterBasedRouter otherRouter = (EncounterBasedRouter) encountered.getRouter();
            for (DTNHost host : otherRouter.deliveryProbabilities.keySet()) {
                if (host.equals(getHost())) continue; // skip self

                double myProb = deliveryProbabilities.getOrDefault(host, 0.0);
                double otherProb = otherRouter.deliveryProbabilities.get(host);
                double newProb = myProb + transitivityFactor * otherProb * (1 - myProb);
                deliveryProbabilities.put(host, Math.min(1.0, newProb));
            }
        }
    }

    /** Age probabilities for all known nodes */
    protected void ageDeliveryProbabilities() {
        for (DTNHost host : deliveryProbabilities.keySet()) {
            double prob = deliveryProbabilities.get(host);
            prob *= agingFactor;
            deliveryProbabilities.put(host, prob);
        }
    }

    /**
     * Forward message to connected node if it has higher probability of delivery
     * or if the neighbor does not yet have probability info.
     */
    protected void tryMessageToConnection(Message msg, Connection con) {
        DTNHost other = con.getOtherNode(getHost());
        DTNHost dest = msg.getTo();

        double myProb = deliveryProbabilities.getOrDefault(dest, 0.0);
        double otherProb = 0.0;

        if (other.getRouter() instanceof EncounterBasedRouter) {
            EncounterBasedRouter otherRouter = (EncounterBasedRouter) other.getRouter();
            otherProb = otherRouter.deliveryProbabilities.getOrDefault(dest, 0.0);
        }

        // Forward if neighbor is more likely to deliver OR has no info yet
        if (otherProb > myProb || otherProb == 0.0) {
            startTransfer(msg, con);
        }
    }

    @Override
    public EncounterBasedRouter replicate() {
        return new EncounterBasedRouter(this);
    }
}
