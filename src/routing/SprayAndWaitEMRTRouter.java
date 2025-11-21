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
 * Spray and Wait router with Enhanced Message Replication Technique (EMRT)
 * 
 * Based on the paper:
 * "Enhanced Message Replication Technique for DTN Routing Protocols"
 * by Hasan et al., Sensors 2023
 * 
 * This router dynamically adjusts the number of copies for each message
 * based on network conditions using the EMRT technique.
 */
public class SprayAndWaitEMRTRouter extends ActiveRouter {
    
    /** identifier for the initial number of copies setting ({@value}) */
    public static final String NROF_COPIES = "nrofCopies";
    /** identifier for the binary-mode setting ({@value}) */
    public static final String BINARY_MODE = "binaryMode";
    /** SprayAndWait router's setting namespace ({@value}) */
    public static final String SPRAYWAIT_NS = "SprayAndWaitEMRTRouter";
    /** Message property key for remaining copies */
    public static final String MSG_COUNT_PROPERTY = SPRAYWAIT_NS + ".copies";
    
    protected int initialNrofCopies;
    protected boolean isBinary;
    
    /** EMRT utility for dynamic replica calculation */
    protected EMRTUtility emrt;
    
    /** Track when messages were created to manage energy */
    protected Map<String, Double> messageCreationTimes;
    
    /**
     * Constructor. Creates a new message router based on the settings in
     * the given Settings object.
     * @param s The settings object
     */
    public SprayAndWaitEMRTRouter(Settings s) {
        super(s);
        Settings swSettings = new Settings(SPRAYWAIT_NS);
        
        initialNrofCopies = swSettings.getInt(NROF_COPIES);
        
        if (swSettings.contains(BINARY_MODE)) {
            isBinary = swSettings.getBoolean(BINARY_MODE);
        } else {
            isBinary = false;
        }
        
        // Initialize EMRT
        this.emrt = new EMRTUtility(initialNrofCopies);
        
        this.messageCreationTimes = new HashMap<>();
    }
    
    /**
     * Copy constructor.
     * @param r The router prototype where setting values are copied from
     */
    protected SprayAndWaitEMRTRouter(SprayAndWaitEMRTRouter r) {
        super(r);
        this.initialNrofCopies = r.initialNrofCopies;
        this.isBinary = r.isBinary;
        this.emrt = new EMRTUtility(r.emrt);
        this.messageCreationTimes = new HashMap<>();
    }
    
    @Override
    public int receiveMessage(Message m, DTNHost from) {
        return super.receiveMessage(m, from);
    }
    
    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        
        // Get or initialize the number of copies for this message
        Integer nrofCopies = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);
        
        if (nrofCopies == null) {
            // This is a new message, calculate initial copies using EMRT
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
        
        // Set the number of copies for this message
        msg.addProperty(MSG_COUNT_PROPERTY, nrofCopies);
        
        // Track message creation time
        messageCreationTimes.put(msg.getId(), SimClock.getTime());
        
        // Update energy (simulate energy consumption for message creation)
        double currentEnergy = emrt.getEnergy();
        emrt.setEnergy(currentEnergy - 0.01); // Small energy cost for creating message
        
        addToMessages(msg, true);
        return true;
    }
    
    @Override
    public void update() {
        super.update();
        if (isTransferring() || !canStartTransfer()) {
            return;
        }
        
        // Try messages that could be delivered to final recipient
        if (exchangeDeliverableMessages() != null) {
            return;
        }
        
        // Try to send messages to other nodes
        tryOtherMessages();
    }
    
    /**
     * Tries to send all other messages to all connected hosts ordered by
     * their delivery probability
     * @return The return value of {@link #tryMessagesForConnected(List)}
     */
    private Connection tryOtherMessages() {
        List<Message> messages = new ArrayList<Message>(getMessageCollection());
        
        // Sort by message creation time (older first)
        Collections.sort(messages, new MessageComparator());
        
        // For all messages
        for (Message m : messages) {
            // For all connected hosts
            for (Connection con : getConnections()) {
                DTNHost other = con.getOtherNode(getHost());
                SprayAndWaitEMRTRouter othRouter = (SprayAndWaitEMRTRouter) other.getRouter();
                
                if (othRouter.isTransferring()) {
                    continue; // Skip hosts that are transferring
                }
                
                if (othRouter.hasMessage(m.getId())) {
                    continue; // Skip messages the other has
                }
                
                // Try to forward message
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
     * Determines whether a message should be forwarded to a host
     */
    protected boolean shouldForwardMessage(Message m, DTNHost otherHost) {
        Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
        
        if (nrofCopies == null) {
            // If no copy count, use EMRT to calculate
            nrofCopies = emrt.calculateReplicas(m);
            m.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
        }
        
        if (isBinary) {
            // Binary Spray and Wait
            if (nrofCopies > 1) {
                return true;
            }
        } else {
            // Normal Spray and Wait
            if (nrofCopies > 1) {
                return true;
            }
        }
        
        return false;
    }
    
    @Override
    protected int startTransfer(Message m, Connection con) {
        int retVal = super.startTransfer(m, con);
        
        if (retVal == RCV_OK) {
            // Transferring, update copy counts
            Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
            
            if (nrofCopies == null) {
                nrofCopies = emrt.calculateReplicas(m);
            }
            
            if (isBinary) {
                // Binary mode: split copies
                int copiesLeft = (int) Math.ceil(nrofCopies / 2.0);
                int copiesToOther = (int) Math.floor(nrofCopies / 2.0);
                
                m.updateProperty(MSG_COUNT_PROPERTY, copiesLeft);
                
                // Other node will get copiesToOther copies
                // (This will be set when they receive the message)
            } else {
                // Normal mode: give away one copy
                m.updateProperty(MSG_COUNT_PROPERTY, nrofCopies - 1);
            }
            
            // Update energy (simulate transmission cost)
            double currentEnergy = emrt.getEnergy();
            emrt.setEnergy(currentEnergy - 0.05); // Energy cost for transmission
        }
        
        return retVal;
    }
    
    @Override
    protected void transferDone(Connection con) {
        // Update EMRT on encounter
        DTNHost otherHost = con.getOtherNode(getHost());
        emrt.updateOnEncounter(otherHost);
        
        super.transferDone(con);
    }
    
    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        
        if (con.isUp()) {
            // Connection established, update EMRT
            DTNHost otherHost = con.getOtherNode(getHost());
            emrt.updateOnEncounter(otherHost);
        }
    }
    
    @Override
    public SprayAndWaitEMRTRouter replicate() {
        return new SprayAndWaitEMRTRouter(this);
    }
    
    /**
     * Comparator for sorting messages by creation time
     */
    private class MessageComparator implements Comparator<Message> {
        public int compare(Message m1, Message m2) {
            double t1 = m1.getCreationTime();
            double t2 = m2.getCreationTime();
            
            if (t1 < t2) {
                return -1;
            } else if (t1 > t2) {
                return 1;
            } else {
                return 0;
            }
        }
    }
}