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
 * Encounter-Based Routing (EBR).
 * * Based on: Hasan, S. et al. "Enhanced Message Replication Technique for DTN Routing Protocols", 
 * Sensors 2023, 23, 922.
 */
public class EBREMRTRouter extends EncounterBasedRouter {

    /** Namespace for settings */
    public static final String EBREMRT_NS = "EBREMRT";
    
    /** Parameter for initial base copies (m_init) - Default: 11 (from paper for EBR) */
    public static final String M_INIT_S = "m_init";
    
    /** Parameter for alpha (smoothing factor) - Default: 0.85 (from paper) */
    public static final String ALPHA_S = "alpha";
    
    /** Parameter for update interval in seconds - Default: 30s (from paper) */
    public static final String UPDATE_INTERVAL_S = "updateInterval";

    /** Message property key for the number of replicas */
    public static final String NUM_REPLICAS_KEY = "EBREMRT.replicas";

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

    public EBREMRTRouter(Settings s) {
        super(s);
        Settings ns = new Settings(EBREMRT_NS);
        this.mInit = ns.getInt(M_INIT_S, 11);
        this.alpha = ns.getDouble(ALPHA_S, 0.85);
        this.updateInterval = ns.getDouble(UPDATE_INTERVAL_S, 30.0);
        
        this.cwc = 0;
        this.ev = 0.0;
        this.lastUpdateTime = 0.0;
        this.currentWindowBufferSum = 0.0;
        this.currentWindowBufferCount = 0;
        this.bAvg = 0.5; // Initialize with assumed 50% availability
    }

    protected EBREMRTRouter(EBREMRTRouter r) {
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
        
        // Algorithm 1: Update EV periodically
        if (SimClock.getTime() - lastUpdateTime >= updateInterval) {
            // Update EV: EV = alpha * CWC + (1 - alpha) * EV
            this.ev = this.alpha * this.cwc + (1 - this.alpha) * this.ev;
            
            // Update B_avg (Average buffer of nodes encountered in this window)
            if (this.currentWindowBufferCount > 0) {
                double windowAvg = this.currentWindowBufferSum / this.currentWindowBufferCount;
                // Smooth update for B_avg as well
                this.bAvg = this.alpha * windowAvg + (1 - this.alpha) * this.bAvg;
            }
            
            // Reset counters for next window
            this.cwc = 0;
            this.currentWindowBufferSum = 0.0;
            this.currentWindowBufferCount = 0;
            this.lastUpdateTime = SimClock.getTime();
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
            // We calculate buffer availability ratio (0.0 to 1.0)
            // 1.0 means fully empty, 0.0 means fully full.
            // Paper says "buffer availability", so free space is the metric.
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
        
        // Normalization/Unit handling:
        // EV: Count of encounters (approx 0-50)
        // B_avg: Scaled to 0-100 (percentage) to match magnitude of EV
        // TTL: Scaled to Hours? (60 mins = 1 hr). If we use seconds, it dominates.
        //      Let's normalize to "Time Units" roughly matching Energy. 
        //      Using Ratio of initial TTL (0.0 - 1.0) * 100 for percentage.
        // Es: Energy Percentage (0-100)
        
        double bVal = this.bAvg * 100.0; // 0 to 100
        
        // Normalized TTL (percentage of max TTL remaining, initially 100)
        // Or simply raw TTL in minutes to match magnitude of Energy? 
        // Paper example uses TTL=8. 8 mins? 8 hours? 
        // We will use TTL in Minutes.
        double ttlVal = msg.getTtl() / 3600.0; // Scale TTL to Hours (60 min = 1.0 hour)        
        // Energy Level (assuming simulation uses 0-100 or similar scale)
        // ONE's energy model might differ. We assume current/max * 100.
        double energyVal = 5.0; // Default if no energy model
        // Check if energy model exists
        // (This requires casting or accessing a known energy model interface if strictly needed,
        //  but for standard ONE ActiveRouter, we might not have direct access. 
        //  We assume full energy if not available or implement basic check).
        
        // Calculate M_i
        double numerator = this.ev + bVal;
        double denominator = ttlVal + energyVal;
        
        // Avoid division by zero
        if (denominator < 1.0) denominator = 1.0;
        
        double miDouble = this.mInit * (numerator / denominator);
        
        // Clamp M_i to reasonable bounds (at least 1, at most mInit * 3)
        int initialCopies = (int) Math.round(miDouble);
        if (initialCopies < 1) initialCopies = 1;
        
        // Set the replicas property
        msg.addProperty(NUM_REPLICAS_KEY, initialCopies);
        msg.addProperty(EncounterBasedRouter.MSG_DELIVERY_PROB, 0.0); // Also set EBR prob

        addToMessages(msg, true);
        return true;
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        // When we receive a message, we must ensure it has the replica key
        // If it was created by a non-EMRT router, default to 1
        if (msg.getProperty(NUM_REPLICAS_KEY) == null) {
            msg.addProperty(NUM_REPLICAS_KEY, 1);
        }
        return msg;
    }

    /**
     * Try to transfer message to other node.
     * Combines EBR forwarding strategy (probabilistic) with EMRT copy limiting.
     */
    @Override
    protected void tryMessageToConnection(Message msg, Connection con) {
        Integer replicas = (Integer) msg.getProperty(NUM_REPLICAS_KEY);
        if (replicas == null) replicas = 1;

        // If we only have 1 copy, we are in "Wait" phase (Direct Delivery only)
        // unless we want to forward the single copy (forwarding vs replication).
        // EMRT usually implies "Spray" phase. 
        // If replicas > 1, we can spray (binary spray).
        
        DTNHost other = con.getOtherNode(getHost());
        DTNHost dest = msg.getTo();

        if (other == dest) {
            startTransfer(msg, con);
            return;
        }

        // EBR Logic: Check delivery probabilities
        // We assume EncounterBasedRouter has 'deliveryProbabilities' map
        // This calls getPredFor from parent EncounterBasedRouter
        double myProb = getPredFor(dest);
        double otherProb = 0.0;
        
        if (other.getRouter() instanceof EncounterBasedRouter) {
             EncounterBasedRouter otherRouter = (EncounterBasedRouter)other.getRouter();
             otherProb = otherRouter.getPredFor(dest);
        }

        if (replicas > 1) {
            // SPRAY PHASE: Replicate if neighbor is better or just to spread?
            // EMRT paper implies "Spray and Wait" style + EBR utility.
            // "When combined with EBR... directs replicas towards high density... controls replicas based on capability"
            // Binary Spray logic:
            int copiesToTransfer = replicas / 2;
            
            // Condition to forward: Neighbor has higher probability OR we just want to spread (Spray)?
            // Standard Spray and Wait sprays to *anyone*. 
            // EBR-EMRT implies spraying to *better* candidates?
            // Paper says: "directing replicas towards high-density areas".
            // We will use EBR check: forward if otherProb > myProb OR if we are just spreading early?
            // To stick to "EBR" nature, we prefer better nodes.
            
            if (otherProb > myProb) {
                // Modify our copy count
                msg.updateProperty(NUM_REPLICAS_KEY, replicas - copiesToTransfer);
                
                // Create copy for valid transfer
                Message copy = msg.replicate();
                copy.updateProperty(NUM_REPLICAS_KEY, copiesToTransfer);
                
                startTransfer(copy, con);
            }
        } 
        else {
            // WAIT PHASE: Single copy left. 
            // Only forward if other is Destination (handled above) or *significantly* better (Focus)?
            // Pure Spray and Wait does not forward the last copy.
            // EBR usually forwards single copy if prob is better.
            if (otherProb > myProb) {
                // Forward the single copy (handover)
                startTransfer(msg, con);
            }
        }
    }
    
    // REMOVED: private double getPredFor(DTNHost dest)
    // We now rely on the public getPredFor from EncounterBasedRouter

    @Override
    public EBREMRTRouter replicate() {
        return new EBREMRTRouter(this);
    }
}