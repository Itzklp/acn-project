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
 * Destination-Based Routing Protocol (DBRP).
 * * Based on: Hasan, S. et al. "Enhanced Message Replication Technique for DTN Routing Protocols", 
 * Sensors 2023, 23, 922.
 * * Logic:
 * 1. Message Creation: Calculate initial copies (Mi) based on Node Capability (EMRT).
 * 2. Forwarding: Use standard DBRP gradient-based forwarding (inherits tryForward).
 * 3. Weighting: Use standard DBRP weight tables (inherits from DBRPRouter).
 */
public class DBRPEMRTRouter extends DBRPRouter {

    /** Namespace for settings */
    public static final String DBRPEMRT_NS = "DBRPEMRT";
    
    /** Parameter for initial base copies (m_init) - Default: 4 (from DBRP) */
    public static final String M_INIT_S = "m_init";
    
    /** Parameter for alpha (smoothing factor) - Default: 0.85 (from paper) */
    public static final String ALPHA_S = "alpha";
    
    /** Parameter for update interval in seconds - Default: 30s (from paper) */
    public static final String UPDATE_INTERVAL_S = "updateInterval";

    /** Message property key (MUST be same as parent) */
    private static final String MSG_REPLICA_COUNT = DBRPRouter.DBRP_NS + ".replicas";

    // EMRT Parameters
    private int mInit;
    private double alpha;
    private double updateInterval;

    // EMRT State variables
    private double lastUpdateTime;
    private int cwc; // Current Window Counter (encounters in current interval)
    private double ev; // Encounter Value (smoothed history)
    
    // Buffer history for B_avg (Average Buffer of encountered nodes)
    private double currentWindowBufferSum;
    private int currentWindowBufferCount;
    private double bAvg; // Moving average of buffer availability

    public DBRPEMRTRouter(Settings s) {
        // Initialize parent DBRPRouter
        super(s); 
        
        // Load EMRT-specific settings
        Settings ns = new Settings(DBRPEMRT_NS);
        // Default m_init for DBRP is 4 (from DBRPRouter.java)
        this.mInit = ns.getInt(M_INIT_S, 4); 
        this.alpha = ns.getDouble(ALPHA_S, 0.85);
        this.updateInterval = ns.getDouble(UPDATE_INTERVAL_S, 30.0);
        
        // Initialize EMRT state variables
        this.cwc = 0;
        this.ev = 0.0;
        this.lastUpdateTime = 0.0;
        this.currentWindowBufferSum = 0.0;
        this.currentWindowBufferCount = 0;
        this.bAvg = 0.5; // Initialize with assumed 50% availability
    }

    protected DBRPEMRTRouter(DBRPEMRTRouter r) {
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

    /**
     * Overridden update method.
     * Calls super.update() to run DBRP logic (aging, forwarding).
     * Adds EMRT periodic update logic (for EV and B_avg).
     */
    @Override
    public void update() {
        // Run parent's update (ages weights, tries forwarding)
        super.update();
        
        // --- EMRT Periodic Update ---
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
    }

    /**
     * Overridden changedConnection method.
     * Calls super.changedConnection() to run DBRP logic (update weights).
     * Adds EMRT state update logic (CWC, buffer stats).
     */
    @Override
    public void changedConnection(Connection con) {
        // Run parent's connection logic (updates encounterWeights)
        super.changedConnection(con);
        
        // --- EMRT On-Encounter Update ---
        if (con.isUp()) {
            // Increment CWC (Current Window Counter) for EMRT
            this.cwc++;
            
            // Collect buffer info from the other node for B_avg
            DTNHost other = con.getOtherNode(getHost());
            double freeBufferRatio = (double) other.getRouter().getFreeBufferSize() / other.getRouter().getBufferSize();
            
            this.currentWindowBufferSum += freeBufferRatio;
            this.currentWindowBufferCount++;
        }
    }

    /**
     * Overridden createNewMessage method.
     * Replaces fixed replica count with EMRT dynamic formula.
     */
    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        msg.setTtl(this.msgTtl);
        
        // --- EMRT FORMULA IMPLEMENTATION ---
        // Formula: Mi = m_init * (EVs + B_avg) / (TTL_i + Es)
        
        // Normalization:
        double bVal = this.bAvg * 100.0; // Buffer percentage (0-100)
        double ttlVal = msg.getTtl() / 12; // Scale TTL to Hours (60 min = 1.0 hour)
        double energyVal = 5.0; // Assumed energy percentage (0-100)
        
        // Calculate M_i
        double numerator = this.ev + bVal;
        double denominator = ttlVal + energyVal;
        
        if (denominator < 1.0) denominator = 1.0;
        
        double miDouble = this.mInit * (numerator / denominator);
        
        // Set the initial replicas
        int initialCopies = (int) Math.round(miDouble);
        if (initialCopies < 1) initialCopies = 1;
        
        // Use the SAME property key as the parent
        msg.addProperty(MSG_REPLICA_COUNT, initialCopies);

        addToMessages(msg, true);
        return true;
    }
    
    /**
     * We do NOT override tryForward. 
     * The parent DBRPRouter's tryForward logic is already correct
     * for handling the gradient-based forwarding and replica splitting.
     */

    @Override
    public DBRPEMRTRouter replicate() {
        return new DBRPEMRTRouter(this);
    }
}