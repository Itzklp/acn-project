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
 * Encounter-Based Routing (EBR) with Enhanced Message Replication Technique (EMRT)
 * 
 * Based on the paper:
 * "Enhanced Message Replication Technique for DTN Routing Protocols"
 * by Hasan et al., Sensors 2023
 * 
 * EBR forwards messages based on encounter history. EMRT enhances this by
 * dynamically adjusting the number of replicas based on network conditions.
 */
public class EBREMRTRouter extends ActiveRouter {
    
    /** EBR router's setting namespace */
    public static final String EBR_NS = "EBREMRTRouter";
    /** Initial number of copies setting */
    public static final String NROF_COPIES = "nrofCopies";
    /** Message property key for remaining copies */
    public static final String MSG_COUNT_PROPERTY = EBR_NS + ".copies";
    /** Encounter rate decay factor */
    public static final String ALPHA_DECAY = "alphaDecay";
    
    /** Default initial number of copies (from paper: 11) */
    private static final int DEFAULT_NROF_COPIES = 11;
    /** Default decay factor */
    private static final double DEFAULT_ALPHA_DECAY = 0.98;
    
    protected int initialNrofCopies;
    protected double alphaDecay;
    
    /** EMRT utility for dynamic replica calculation */
    protected EMRTUtility emrt;
    
    /** Encounter history: Maps host to encounter value */
    protected Map<DTNHost, Double> encounterHistory;
    
    /** Last encounter time with each host */
    protected Map<DTNHost, Double> lastEncounterTime;
    
    /**
     * Constructor. Creates a new message router based on the settings in
     * the given Settings object.
     * @param s The settings object
     */
    public EBREMRTRouter(Settings s) {
        super(s);
        Settings ebrSettings = new Settings(EBR_NS);
        
        if (ebrSettings.contains(NROF_COPIES)) {
            initialNrofCopies = ebrSettings.getInt(NROF_COPIES);
        } else {
            initialNrofCopies = DEFAULT_NROF_COPIES;
        }
        
        if (ebrSettings.contains(ALPHA_DECAY)) {
            alphaDecay = ebrSettings.getDouble(ALPHA_DECAY);
        } else {
            alphaDecay = DEFAULT_ALPHA_DECAY;
        }
        
        // Initialize EMRT
        this.emrt = new EMRTUtility(initialNrofCopies);
        
        this.encounterHistory = new HashMap<>();
        this.lastEncounterTime = new HashMap<>();
    }
    
    /**
     * Copy constructor.
     * @param r The router prototype where setting values are copied from
     */
    protected EBREMRTRouter(EBREMRTRouter r) {
        super(r);
        this.initialNrofCopies = r.initialNrofCopies;
        this.alphaDecay = r.alphaDecay;
        this.emrt = new EMRTUtility(r.emrt);
        this.encounterHistory = new HashMap<>();
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
            
            // Update encounter history
            updateEncounterValue(otherHost);
            
            // Update EMRT
            emrt.updateOnEncounter(otherHost);
            
            // Decay all encounter values
            decayEncounterValues();
        }
    }
    
    /**
     * Update encounter value for a host
     */
    private void updateEncounterValue(DTNHost host) {
        double currentTime = SimClock.getTime();
        Double lastTime = lastEncounterTime.get(host);
        Double currentValue = encounterHistory.get(host);
        
        if (currentValue == null) {
            currentValue = 0.0;
        }
        
        // Increment encounter value
        currentValue += 1.0;
        
        encounterHistory.put(host, currentValue);
        lastEncounterTime.put(host, currentTime);
    }
    
    /**
     * Decay all encounter values
     */
    private void decayEncounterValues() {
        for (DTNHost host : encounterHistory.keySet()) {
            double value = encounterHistory.get(host);
            value *= alphaDecay;
            encounterHistory.put(host, value);
        }
    }
    
    /**
     * Get encounter value for a host
     */
    private double getEncounterValue(DTNHost host) {
        Double value = encounterHistory.get(host);
        return (value != null) ? value : 0.0;
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
        
        // Try to forward messages based on encounter history
        tryForwardMessages();
    }
    
    /**
     * Try to forward messages to connected hosts based on encounter values
     */
    private Connection tryForwardMessages() {
        List<Message> messages = new ArrayList<Message>(getMessageCollection());
        
        // Sort by encounter value (higher first)
        Collections.sort(messages, new EncounterValueComparator());
        
        // For all messages
        for (Message m : messages) {
            // For all connected hosts
            for (Connection con : getConnections()) {
                DTNHost other = con.getOtherNode(getHost());
                EBREMRTRouter othRouter = (EBREMRTRouter) other.getRouter();
                
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
        
        // Get encounter values
        double myEncounter = getEncounterValueForDestination(m.getTo());
        
        EBREMRTRouter othRouter = (EBREMRTRouter) otherHost.getRouter();
        double otherEncounter = othRouter.getEncounterValueForDestination(m.getTo());
        
        // Forward if other has better encounter history with destination
        return otherEncounter > myEncounter;
    }
    
    /**
     * Get encounter value for the destination of a message
     * This considers both direct encounters and transitive encounters
     */
    private double getEncounterValueForDestination(DTNHost destination) {
        double directValue = getEncounterValue(destination);
        
        // Consider transitive encounters (simplified)
        double transitiveValue = 0.0;
        int transitiveCount = 0;
        
        for (Map.Entry<DTNHost, Double> entry : encounterHistory.entrySet()) {
            DTNHost intermediateHost = entry.getKey();
            if (intermediateHost != destination) {
                EBREMRTRouter intermRouter = (EBREMRTRouter) intermediateHost.getRouter();
                double intermValue = intermRouter.getEncounterValue(destination);
                if (intermValue > 0) {
                    transitiveValue += intermValue * entry.getValue();
                    transitiveCount++;
                }
            }
        }
        
        if (transitiveCount > 0) {
            transitiveValue /= transitiveCount;
        }
        
        // Combine direct and transitive (weight direct more heavily)
        return 0.7 * directValue + 0.3 * transitiveValue;
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
    public EBREMRTRouter replicate() {
        return new EBREMRTRouter(this);
    }
    
    /**
     * Comparator for sorting messages by encounter value
     */
    private class EncounterValueComparator implements Comparator<Message> {
        public int compare(Message m1, Message m2) {
            DTNHost dest1 = m1.getTo();
            DTNHost dest2 = m2.getTo();
            
            double ev1 = getEncounterValueForDestination(dest1);
            double ev2 = getEncounterValueForDestination(dest2);
            
            // Higher encounter value first
            if (ev1 > ev2) {
                return -1;
            } else if (ev1 < ev2) {
                return 1;
            } else {
                return 0;
            }
        }
    }
}