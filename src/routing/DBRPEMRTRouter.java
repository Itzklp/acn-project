package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import routing.util.EMRTUtility;

/**
 * Destination-Based Routing Protocol (DBRP) with Enhanced Message Replication Technique (EMRT)
 * 
 * Based on the paper:
 * "Enhanced Message Replication Technique for DTN Routing Protocols"
 * by Hasan et al., Sensors 2023
 * 
 * DBRP assigns weights to nodes based on encounters with the destination.
 * Nodes that have encountered the destination more frequently get higher weights.
 * EMRT enhances this by dynamically adjusting the number of replicas.
 */
public class DBRPEMRTRouter extends ActiveRouter {
    
    /** DBRP router's setting namespace */
    public static final String DBRP_NS = "DBRPEMRTRouter";
    /** Initial number of copies setting */
    public static final String NROF_COPIES = "nrofCopies";
    /** Message property key for remaining copies */
    public static final String MSG_COUNT_PROPERTY = DBRP_NS + ".copies";
    /** Weight decay factor */
    public static final String ALPHA_DECAY = "alphaDecay";
    /** Encounter weight for destination encounters */
    public static final String DEST_WEIGHT = "destinationWeight";
    /** Encounter weight for non-destination encounters */
    public static final String NON_DEST_WEIGHT = "nonDestinationWeight";
    
    /** Default initial number of copies (from paper: similar to EBR, 11) */
    private static final int DEFAULT_NROF_COPIES = 11;
    /** Default decay factor */
    private static final double DEFAULT_ALPHA_DECAY = 0.98;
    /** Default weight for destination encounters */
    private static final double DEFAULT_DEST_WEIGHT = 2.0;
    /** Default weight for non-destination encounters */
    private static final double DEFAULT_NON_DEST_WEIGHT = 1.0;
    
    protected int initialNrofCopies;
    protected double alphaDecay;
    protected double destWeight;
    protected double nonDestWeight;
    
    /** EMRT utility for dynamic replica calculation */
    protected EMRTUtility emrt;
    
    /** Node weights: Maps host to weight value */
    protected Map<DTNHost, Double> nodeWeights;
    
    /** Destination encounter count: Maps host to number of times we've seen them */
    protected Map<DTNHost, Integer> destinationEncounters;
    
    /** Last encounter time with each host */
    protected Map<DTNHost, Double> lastEncounterTime;
    
    /**
     * Constructor. Creates a new message router based on the settings in
     * the given Settings object.
     * @param s The settings object
     */
    public DBRPEMRTRouter(Settings s) {
        super(s);
        Settings dbrpSettings = new Settings(DBRP_NS);
        
        if (dbrpSettings.contains(NROF_COPIES)) {
            initialNrofCopies = dbrpSettings.getInt(NROF_COPIES);
        } else {
            initialNrofCopies = DEFAULT_NROF_COPIES;
        }
        
        if (dbrpSettings.contains(ALPHA_DECAY)) {
            alphaDecay = dbrpSettings.getDouble(ALPHA_DECAY);
        } else {
            alphaDecay = DEFAULT_ALPHA_DECAY;
        }
        
        if (dbrpSettings.contains(DEST_WEIGHT)) {
            destWeight = dbrpSettings.getDouble(DEST_WEIGHT);
        } else {
            destWeight = DEFAULT_DEST_WEIGHT;
        }
        
        if (dbrpSettings.contains(NON_DEST_WEIGHT)) {
            nonDestWeight = dbrpSettings.getDouble(NON_DEST_WEIGHT);
        } else {
            nonDestWeight = DEFAULT_NON_DEST_WEIGHT;
        }
        
        // Initialize EMRT
        this.emrt = new EMRTUtility(initialNrofCopies);
        
        this.nodeWeights = new HashMap<>();
        this.destinationEncounters = new HashMap<>();
        this.lastEncounterTime = new HashMap<>();
    }
    
    /**
     * Copy constructor.
     * @param r The router prototype where setting values are copied from
     */
    protected DBRPEMRTRouter(DBRPEMRTRouter r) {
        super(r);
        this.initialNrofCopies = r.initialNrofCopies;
        this.alphaDecay = r.alphaDecay;
        this.destWeight = r.destWeight;
        this.nonDestWeight = r.nonDestWeight;
        this.emrt = new EMRTUtility(r.emrt);
        this.nodeWeights = new HashMap<>();
        this.destinationEncounters = new HashMap<>();
        this.lastEncounterTime = new HashMap<>();
    }
    
    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        
        // Initialize copies if needed
        Integer nrofCopies = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);
        if (nrofCopies == null) {
            nrofCopies = emrt.calculateReplicas(msg);
            msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
        }
        
        return msg;
    }
    
    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        
        // Use EMRT to calculate dynamic number of copies
        int nrofCopies = emrt.calculateReplicas(msg);
        msg.addProperty(MSG_COUNT_PROPERTY, nrofCopies);
        
        // Update energy
        double currentEnergy = emrt.getEnergy();
        emrt.setEnergy(currentEnergy - 0.01);
        
        addToMessages(msg, true);
        return true;
    }
    
    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        
        if (con.isUp()) {
            DTNHost otherHost = con.getOtherNode(getHost());
            
            // Update node weight
            updateNodeWeight(otherHost);
            
            // Update EMRT
            emrt.updateOnEncounter(otherHost);
            
            // Decay all weights
            decayNodeWeights();
        }
    }
    
    /**
     * Update weight for a node based on whether it's been seen as a destination
     */
    private void updateNodeWeight(DTNHost host) {
        double currentTime = SimClock.getTime();
        Double currentWeight = nodeWeights.get(host);
        
        if (currentWeight == null) {
            currentWeight = 0.0;
        }
        
        // Check if this host has been a destination for any of our messages
        boolean isDestination = false;
        for (Message m : getMessageCollection()) {
            if (m.getTo() == host) {
                isDestination = true;
                
                // Increment destination encounter count
                Integer destCount = destinationEncounters.get(host);
                if (destCount == null) {
                    destCount = 0;
                }
                destinationEncounters.put(host, destCount + 1);
                break;
            }
        }
        
        // Update weight based on encounter type
        if (isDestination) {
            currentWeight += destWeight;
        } else {
            currentWeight += nonDestWeight;
        }
        
        nodeWeights.put(host, currentWeight);
        lastEncounterTime.put(host, currentTime);
    }
    
    /**
     * Decay all node weights
     */
    private void decayNodeWeights() {
        for (DTNHost host : nodeWeights.keySet()) {
            double weight = nodeWeights.get(host);
            weight *= alphaDecay;
            nodeWeights.put(host, weight);
        }
    }
    
    /**
     * Get weight for a host
     */
    public double getNodeWeight(DTNHost host) {
        Double weight = nodeWeights.get(host);
        return (weight != null) ? weight : 0.0;
    }
    
    /**
     * Get destination encounter count for a host
     */
    public int getDestinationEncounterCount(DTNHost host) {
        Integer count = destinationEncounters.get(host);
        return (count != null) ? count : 0;
    }
    
    @Override
    public void update() {
        super.update();
        
        if (isTransferring() || !canStartTransfer()) {
            return;
        }
        
        // Try to deliver messages to final recipients
        if (exchangeDeliverableMessages() != null) {
            return;
        }
        
        // Try to forward messages based on node weights
        tryForwardMessages();
    }
    
    /**
     * Try to forward messages to connected hosts based on weights
     */
    private Connection tryForwardMessages() {
        List<Message> messages = new ArrayList<Message>(getMessageCollection());
        
        // Sort by destination weight (higher first)
        Collections.sort(messages, new WeightComparator());
        
        // For all messages
        for (Message m : messages) {
            // For all connected hosts
            for (Connection con : getConnections()) {
                DTNHost other = con.getOtherNode(getHost());
                DBRPEMRTRouter othRouter = (DBRPEMRTRouter) other.getRouter();
                
                if (othRouter.isTransferring()) {
                    continue;
                }
                
                if (othRouter.hasMessage(m.getId())) {
                    continue;
                }
                
                // Check if we should forward this message
                if (shouldForwardMessage(m, other)) {
                    // Try to start transfer
                    if (startTransfer(m, con) == RCV_OK) {
                        return con;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Determine if a message should be forwarded to a host
     */
    protected boolean shouldForwardMessage(Message m, DTNHost otherHost) {
        Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
        
        if (nrofCopies == null) {
            nrofCopies = emrt.calculateReplicas(m);
            m.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
        }
        
        // Only forward if we have copies left
        if (nrofCopies <= 1) {
            return false;
        }
        
        DTNHost destination = m.getTo();
        
        // Get weights for destination
        double myWeight = getWeightForDestination(destination);
        
        DBRPEMRTRouter othRouter = (DBRPEMRTRouter) otherHost.getRouter();
        double otherWeight = othRouter.getWeightForDestination(destination);
        
        // Forward if other has better weight for destination
        return otherWeight > myWeight;
    }
    
    /**
     * Get weight for a destination
     * This considers both the node weight and destination encounters
     */
    private double getWeightForDestination(DTNHost destination) {
        double baseWeight = getNodeWeight(destination);
        int destEncounters = getDestinationEncounterCount(destination);
        
        // Bonus for having encountered the destination
        double encounterBonus = destEncounters * destWeight;
        
        // Calculate transitive weight through intermediaries
        double transitiveWeight = 0.0;
        int transitiveCount = 0;
        
        for (Map.Entry<DTNHost, Double> entry : nodeWeights.entrySet()) {
            DTNHost intermediateHost = entry.getKey();
            if (intermediateHost != destination) {
                DBRPEMRTRouter intermRouter = (DBRPEMRTRouter) intermediateHost.getRouter();
                int intermDestEncounters = intermRouter.getDestinationEncounterCount(destination);
                if (intermDestEncounters > 0) {
                    transitiveWeight += intermDestEncounters * entry.getValue();
                    transitiveCount++;
                }
            }
        }
        
        if (transitiveCount > 0) {
            transitiveWeight /= transitiveCount;
        }
        
        // Combine base weight, encounter bonus, and transitive weight
        return baseWeight + encounterBonus + (0.3 * transitiveWeight);
    }
    
    @Override
    protected int startTransfer(Message m, Connection con) {
        int retVal = super.startTransfer(m, con);
        
        if (retVal == RCV_OK) {
            // Update copy count
            Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
            
            if (nrofCopies == null) {
                nrofCopies = emrt.calculateReplicas(m);
            }
            
            // Give away one copy
            m.updateProperty(MSG_COUNT_PROPERTY, nrofCopies - 1);
            
            // Update energy
            double currentEnergy = emrt.getEnergy();
            emrt.setEnergy(currentEnergy - 0.05);
        }
        
        return retVal;
    }
    
    @Override
    public DBRPEMRTRouter replicate() {
        return new DBRPEMRTRouter(this);
    }
    
    /**
     * Comparator for sorting messages by destination weight
     */
    private class WeightComparator implements Comparator<Message> {
        public int compare(Message m1, Message m2) {
            DTNHost dest1 = m1.getTo();
            DTNHost dest2 = m2.getTo();
            
            double weight1 = getWeightForDestination(dest1);
            double weight2 = getWeightForDestination(dest2);
            
            // Higher weight first
            if (weight1 > weight2) {
                return -1;
            } else if (weight1 < weight2) {
                return 1;
            } else {
                return 0;
            }
        }
    }
}