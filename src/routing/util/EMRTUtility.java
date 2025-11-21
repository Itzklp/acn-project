package routing.util;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced Message Replication Technique (EMRT) Utility
 * 
 * Implements the algorithm from the paper:
 * "Enhanced Message Replication Technique for DTN Routing Protocols"
 * by Hasan et al., Sensors 2023
 * 
 * This utility dynamically adjusts the number of message replicas based on:
 * - Encounter Value (EV): History of encounter rates
 * - Average Buffer (Bavg): Average buffer size of encountered nodes
 * - Time-to-Live (TTL): Remaining lifetime of the message
 * - Energy (Es): Available energy of the source node
 */
public class EMRTUtility {
    
    /** Namespace for EMRT settings */
    public static final String EMRT_NS = "EMRTUtility";
    
    /** Alpha parameter for EV calculation (default from paper: 0.85) */
    public static final String ALPHA = "alpha";
    
    /** Window interval for updating EV (default: 30 seconds) */
    public static final String UPDATE_INTERVAL = "updateInterval";
    
    /** Initial number of replicas (protocol-specific) */
    public static final String INIT_REPLICAS = "initReplicas";
    
    // Default values from the paper
    private static final double DEFAULT_ALPHA = 0.85;
    private static final double DEFAULT_UPDATE_INTERVAL = 30.0; // seconds
    
    // Instance variables
    private double alpha;
    private double updateInterval;
    private int minit; // Initial number of replicas
    
    // Dynamic state variables
    private double encounterValue; // EV
    private int currentWindowCounter; // CWC
    private double nextUpdateTime;
    
    // Track buffer sizes of encountered nodes
    private Map<DTNHost, Double> encounteredNodeBuffers;
    private double averageBuffer; // Bavg
    
    // Energy tracking
    private double currentEnergy;
    
    /**
     * Constructor with settings
     */
    public EMRTUtility(Settings s) {
        if (s.contains(ALPHA)) {
            this.alpha = s.getDouble(ALPHA);
        } else {
            this.alpha = DEFAULT_ALPHA;
        }
        
        if (s.contains(UPDATE_INTERVAL)) {
            this.updateInterval = s.getDouble(UPDATE_INTERVAL);
        } else {
            this.updateInterval = DEFAULT_UPDATE_INTERVAL;
        }
        
        if (s.contains(INIT_REPLICAS)) {
            this.minit = s.getInt(INIT_REPLICAS);
        } else {
            this.minit = 8; // Default for Spray and Wait
        }
        
        // Initialize state
        this.encounterValue = 0.0;
        this.currentWindowCounter = 0;
        this.nextUpdateTime = SimClock.getTime() + updateInterval;
        this.encounteredNodeBuffers = new HashMap<>();
        this.averageBuffer = 0.0;
        this.currentEnergy = 100.0; // Default: full energy
    }
    
    /**
     * Constructor with initial number of replicas
     */
    public EMRTUtility(int initialReplicas) {
        this.alpha = DEFAULT_ALPHA;
        this.updateInterval = DEFAULT_UPDATE_INTERVAL;
        this.minit = initialReplicas;
        
        // Initialize state
        this.encounterValue = 0.0;
        this.currentWindowCounter = 0;
        this.nextUpdateTime = SimClock.getTime() + updateInterval;
        this.encounteredNodeBuffers = new HashMap<>();
        this.averageBuffer = 0.0;
        this.currentEnergy = 100.0; // Default: full energy
    }
    
    /**
     * Copy constructor
     */
    public EMRTUtility(EMRTUtility other) {
        this.alpha = other.alpha;
        this.updateInterval = other.updateInterval;
        this.minit = other.minit;
        this.encounterValue = other.encounterValue;
        this.currentWindowCounter = other.currentWindowCounter;
        this.nextUpdateTime = other.nextUpdateTime;
        this.encounteredNodeBuffers = new HashMap<>(other.encounteredNodeBuffers);
        this.averageBuffer = other.averageBuffer;
        this.currentEnergy = other.currentEnergy;
    }
    
    /**
     * Update encounter value when a new connection is made
     * Implements lines 1-5 of Algorithm 1 from the paper
     */
    public void updateOnEncounter(DTNHost otherHost) {
        // Increment current window counter
        this.currentWindowCounter++;
        
        // Store buffer information of encountered node
        double otherBufferSize = getBufferOccupancy(otherHost);
        encounteredNodeBuffers.put(otherHost, otherBufferSize);
        
        // Update average buffer
        calculateAverageBuffer();
        
        // Check if we need to update EV
        double currentTime = SimClock.getTime();
        if (currentTime >= nextUpdateTime) {
            // Update EV using formula from paper: EV = α * CWC + (1-α) * EV
            encounterValue = alpha * currentWindowCounter + (1 - alpha) * encounterValue;
            
            // Reset CWC
            currentWindowCounter = 0;
            
            // Set next update time
            nextUpdateTime = currentTime + updateInterval;
        }
    }
    
    /**
     * Calculate the dynamic number of replicas for a message
     * Implements line 7 of Algorithm 1 from the paper
     * 
     * Formula: Mi = minit × ⌊(EVs + Bavg) / (TTLi + Es)⌋
     * 
     * @param msg The message to calculate replicas for
     * @return The calculated number of replicas
     */
    public int calculateReplicas(Message msg) {
        // Get TTL in normalized form (0-1 scale, where 1 is full TTL)
        double ttl = getNormalizedTTL(msg);
        
        // Get normalized energy (0-1 scale, where 1 is full energy)
        double energy = getNormalizedEnergy();
        
        // Get normalized encounter value
        double evNormalized = getNormalizedEV();
        
        // Get normalized average buffer
        double bavgNormalized = getNormalizedBuffer();
        
        // Apply the formula from the paper
        // Note: We need to normalize to prevent extreme values
        double numerator = evNormalized + bavgNormalized;
        double denominator = ttl + energy;
        
        // Prevent division by zero
        if (denominator < 0.01) {
            denominator = 0.01;
        }
        
        // Calculate replicas
        double ratio = numerator / denominator;
        int replicas = (int) Math.floor(minit * ratio);
        
        // Ensure at least 1 replica and not more than minit * 3 (reasonable bound)
        replicas = Math.max(1, Math.min(replicas, minit * 3));
        
        return replicas;
    }
    
    /**
     * Calculate average buffer occupancy of encountered nodes
     */
    private void calculateAverageBuffer() {
        if (encounteredNodeBuffers.isEmpty()) {
            averageBuffer = 1.0; // Assume nodes have free buffer
            return;
        }
        
        double sum = 0.0;
        for (Double bufferOccupancy : encounteredNodeBuffers.values()) {
            sum += bufferOccupancy;
        }
        averageBuffer = sum / encounteredNodeBuffers.size();
    }
    
    /**
     * Get buffer occupancy of a node (0 = full, 1 = empty)
     */
    private double getBufferOccupancy(DTNHost host) {
        double freeBuffer = host.getRouter().getFreeBufferSize();
        double totalBuffer = host.getRouter().getBufferSize();
        
        if (totalBuffer == 0) {
            return 0.0;
        }
        
        return freeBuffer / totalBuffer;
    }
    
    /**
     * Get normalized TTL (0-1 scale, where higher is more time remaining)
     * Inverse: Low TTL should increase replicas
     */
    private double getNormalizedTTL(Message msg) {
        int remainingTTL = msg.getTtl();
        
        // Normalize to 0-1 scale
        // Assuming max TTL of 300 minutes (from paper: 60 min, but we'll use a reasonable max)
        double maxTTL = 300.0 * 60.0; // in seconds
        double normalizedTTL = remainingTTL / maxTTL;
        
        // Invert so that low TTL gives high value (more replicas needed)
        return 1.0 - Math.min(1.0, normalizedTTL);
    }
    
    /**
     * Get normalized energy (0-1 scale)
     * Inverse: Low energy should increase replicas
     */
    private double getNormalizedEnergy() {
        // Invert so that low energy gives high value (more replicas needed)
        return 1.0 - (currentEnergy / 100.0);
    }
    
    /**
     * Get normalized encounter value (0-1 scale)
     */
    private double getNormalizedEV() {
        // Normalize EV to 0-1 scale
        // Assuming max reasonable EV of 50 encounters per window
        double maxEV = 50.0;
        return Math.min(1.0, encounterValue / maxEV);
    }
    
    /**
     * Get normalized average buffer (0-1 scale)
     * High value means more free buffer available
     */
    private double getNormalizedBuffer() {
        return averageBuffer;
    }
    
    // Getters and setters
    
    public double getEncounterValue() {
        return encounterValue;
    }
    
    public double getAverageBuffer() {
        return averageBuffer;
    }
    
    public void setEnergy(double energy) {
        this.currentEnergy = Math.max(0, Math.min(100, energy));
    }
    
    public double getEnergy() {
        return currentEnergy;
    }
    
    public int getInitReplicas() {
        return minit;
    }
    
    public void setInitReplicas(int replicas) {
        this.minit = replicas;
    }
    
    /**
     * Reset state (useful for testing)
     */
    public void reset() {
        this.encounterValue = 0.0;
        this.currentWindowCounter = 0;
        this.nextUpdateTime = SimClock.getTime() + updateInterval;
        this.encounteredNodeBuffers.clear();
        this.averageBuffer = 0.0;
        this.currentEnergy = 100.0;
    }
}