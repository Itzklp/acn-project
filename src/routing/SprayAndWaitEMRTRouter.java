package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

/**
 * Implementation of the Enhanced Message Replication Technique (EMRT) applied to
 * Spray and Wait Routing.
 * * Based on: Hasan, S. et al. "Enhanced Message Replication Technique for DTN Routing Protocols", 
 * Sensors 2023, 23, 922.
 * * Logic:
 * 1. Message Creation: Calculate initial copies (Mi) based on Node Capability (EMRT).
 * 2. Spray Phase: Binary Spraying (give half copies to neighbor) if copies > 1.
 * 3. Wait Phase: Direct delivery only if copies == 1.
 */
public class SprayAndWaitEMRTRouter extends ActiveRouter {

    /** Namespace for settings */
    public static final String SNW_EMRT_NS = "SprayAndWaitEMRT";
    
    /** Parameter for initial base copies (m_init) - Default: 8 (from paper for SnW) */
    public static final String M_INIT_S = "m_init";
    
    /** Parameter for alpha (smoothing factor) - Default: 0.85 (from paper) */
    public static final String ALPHA_S = "alpha";
    
    /** Parameter for update interval in seconds - Default: 30s (from paper) */
    public static final String UPDATE_INTERVAL_S = "updateInterval";

    /** Message property key for the number of replicas */
    public static final String NUM_REPLICAS_KEY = "SnWEMRT.replicas";

    // EMRT Parameters
    private int mInit;
    private double alpha;
    private double updateInterval;

    // State variables
    private double lastUpdateTime;
    private int cwc; // Current Window Counter (encounters in current interval)
    private double ev; // Encounter Value (smoothed history)
    
    // Buffer history for B_avg (Average Buffer of encountered nodes)
    private double currentWindowBufferSum;
    private int currentWindowBufferCount;
    private double bAvg; // Moving average of buffer availability

    public SprayAndWaitEMRTRouter(Settings s) {
        super(s);
        Settings ns = new Settings(SNW_EMRT_NS);
        this.mInit = ns.getInt(M_INIT_S, 8); // Paper uses 8 for Spray & Wait
        this.alpha = ns.getDouble(ALPHA_S, 0.85);
        this.updateInterval = ns.getDouble(UPDATE_INTERVAL_S, 30.0);
        
        this.cwc = 0;
        this.ev = 0.0;
        this.lastUpdateTime = 0.0;
        this.currentWindowBufferSum = 0.0;
        this.currentWindowBufferCount = 0;
        this.bAvg = 0.5; // Initialize with assumed 50% availability
    }

    protected SprayAndWaitEMRTRouter(SprayAndWaitEMRTRouter r) {
        super(r);
        this.mInit = r.mInit;
        this.alpha = r.alpha;
        this.updateInterval = r.updateInterval;
        this.cwc = r.cwc;
        this.ev = r.ev;
        this.lastUpdateTime = r.lastUpdateTime;
        this.bAvg = r.bAvg;
        this.currentWindowBufferSum = r.currentWindowBufferSum;
        this.currentWindowBufferCount = r.currentWindowBufferCount;
    }

    @Override
    public void update() {
        super.update();
        
        // Algorithm 1: Update EV and B_avg periodically
        if (SimClock.getTime() - lastUpdateTime >= updateInterval) {
            // Update EV: EV = alpha * CWC + (1 - alpha) * EV
            this.ev = this.alpha * this.cwc + (1 - this.alpha) * this.ev;
            
            // Update B_avg (Average buffer of nodes encountered in this window)
            if (this.currentWindowBufferCount > 0) {
                double windowAvg = this.currentWindowBufferSum / this.currentWindowBufferCount;
                this.bAvg = this.alpha * windowAvg + (1 - this.alpha) * this.bAvg;
            }
            
            // Reset counters for next window
            this.cwc = 0;
            this.currentWindowBufferSum = 0.0;
            this.currentWindowBufferCount = 0;
            this.lastUpdateTime = SimClock.getTime();
        }

        // Try to transfer messages
        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        // Try to exchange messages with all connected hosts
        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(getHost());
            List<Message> messages = new ArrayList<>(getMessageCollection());
            
            for (Message m : messages) {
                tryMessageToConnection(m, con, other);
            }
        }
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        
        if (con.isUp()) {
            // Increment CWC (Current Window Counter) for every new connection
            this.cwc++;
            
            // Collect buffer info from the other node for B_avg
            DTNHost other = con.getOtherNode(getHost());
            double freeBufferRatio = (double) other.getRouter().getFreeBufferSize() / other.getRouter().getBufferSize();
            
            this.currentWindowBufferSum += freeBufferRatio;
            this.currentWindowBufferCount++;
        }
    }

    /**
     * Creates a new message with a dynamic number of initial replicas 
     * calculated using the EMRT formula.
     */
    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        msg.setTtl(this.msgTtl);
        
        // --- EMRT FORMULA IMPLEMENTATION ---
        // Formula: Mi = m_init * (EVs + B_avg) / (TTL_i + Es)
        
        // Normalization:
        double bVal = this.bAvg * 100.0; // Buffer percentage (0-100)
        double ttlVal = msg.getTtl() / 60; // Scale TTL to Hours (60 min = 1.0 hour)
        double energyVal = 5.0; // Assumed energy percentage (0-100)
        
        // Calculate M_i
        double numerator = this.ev + bVal;
        double denominator = ttlVal + energyVal;
        
        if (denominator < 1.0) denominator = 1.0;
        
        double miDouble = this.mInit * (numerator / denominator);
        
        // Set the initial replicas
        int initialCopies = (int) Math.round(miDouble);
        if (initialCopies < 1) initialCopies = 1;
        
        msg.addProperty(NUM_REPLICAS_KEY, initialCopies);

        addToMessages(msg, true);
        return true;
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        // Ensure received messages have the replica key initialized if missing
        if (msg.getProperty(NUM_REPLICAS_KEY) == null) {
            msg.addProperty(NUM_REPLICAS_KEY, 1);
        }
        return msg;
    }

    /**
     * Try to transfer message to other node using Spray and Wait logic.
     */
    protected void tryMessageToConnection(Message msg, Connection con, DTNHost other) {
        if (other == null || isTransferring()) return;

        DTNHost dest = msg.getTo();

        // 1. Direct Delivery (Wait Phase / Destination Encounter)
        if (other == dest) {
            startTransfer(msg, con);
            return;
        }

        // 2. Check Replicas
        Integer replicas = (Integer) msg.getProperty(NUM_REPLICAS_KEY);
        if (replicas == null) replicas = 1;

        // If only 1 copy, we wait for destination (handled above)
        if (replicas <= 1) {
            return;
        }

        // 3. Spray Phase (Binary Spraying)
        // If we have > 1 copies, we share half with the encountered node
        int copiesToTransfer = replicas / 2;

        // Only transfer if we can give at least 1 copy
        if (copiesToTransfer > 0) {
            // Update our own copy count
            msg.updateProperty(NUM_REPLICAS_KEY, replicas - copiesToTransfer);

            // Create the copy for the other node
            Message copy = msg.replicate();
            copy.updateProperty(NUM_REPLICAS_KEY, copiesToTransfer);

            startTransfer(copy, con);
        }
    }

    @Override
    public SprayAndWaitEMRTRouter replicate() {
        return new SprayAndWaitEMRTRouter(this);
    }
}