package routing;

import java.util.*;
import core.*;

public class EBREMRTRouter extends ActiveRouter {

    public static final String EBR_NS = "EBREMRT_TIMED";
    private static final String MAX_REPLICA_S = "maxReplicasBase";
    private static final String AGING_FACTOR_S = "agingFactor";
    private static final String WEIGHT_INC_S = "weightIncrement";
    private static final String WINDOW_INTERVAL_S = "windowInterval";  // Wi in milliseconds

    private static final String MSG_REPLICA_COUNT = EBR_NS + ".replicas";

    private int maxReplicasBase;
    private double alpha = 0.85;         // smoothing factor for EV update
    private double agingFactor;
    private double weightIncrement;

    private long windowInterval;         // Wi, window duration in ms
    private long nextUpdate;             // next scheduled update time (ms)

    private Map<DTNHost, Double> encounterValues;
    private Map<DTNHost, Integer> encounterWindowCount;  // CWC per host for current window

    private LinkedList<Double> bufferHistory;
    private double energyLevel;

    public EBREMRTRouter(Settings settings) {
        super(settings);
        Settings ns = new Settings(EBR_NS);
        maxReplicasBase = ns.getInt(MAX_REPLICA_S, 5);
        agingFactor = ns.getDouble(AGING_FACTOR_S, 0.98);
        weightIncrement = ns.getDouble(WEIGHT_INC_S, 0.1);

        // Load windowInterval as int then cast to long (THE ONE limitation)
        int wi = ns.getInt(WINDOW_INTERVAL_S, 60000); // default 60 sec
        windowInterval = (long) wi;

        encounterValues = new HashMap<>();
        encounterWindowCount = new HashMap<>();
        bufferHistory = new LinkedList<>();
        energyLevel = 100.0;

        nextUpdate = System.currentTimeMillis() + windowInterval;
    }

    protected EBREMRTRouter(EBREMRTRouter r) {
        super(r);
        this.maxReplicasBase = r.maxReplicasBase;
        this.alpha = r.alpha;
        this.agingFactor = r.agingFactor;
        this.weightIncrement = r.weightIncrement;
        this.windowInterval = r.windowInterval;
        this.nextUpdate = r.nextUpdate;
        this.encounterValues = new HashMap<>(r.encounterValues);
        this.encounterWindowCount = new HashMap<>(r.encounterWindowCount);
        this.bufferHistory = new LinkedList<>(r.bufferHistory);
        this.energyLevel = r.energyLevel;
    }

    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());
        msg.setTtl(this.msgTtl);

        int quota = calculateReplicaQuotaForMessage(msg);
        msg.addProperty(MSG_REPLICA_COUNT, quota);

        addToMessages(msg, true);
        return true;
    }

    protected int calculateReplicaQuotaForMessage(Message msg) {
        double EVs = encounterValues.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double Bi = bufferHistory.isEmpty() ? 1.0 : bufferHistory.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double TTLM = msg.getTtl() > 0 ? msg.getTtl() : 1.0;
        double Es = energyLevel > 0 ? energyLevel : 1.0;

        double quotaDouble = maxReplicasBase * (EVs + Bi) / (TTLM + Es);
        return Math.max(1, (int) Math.round(quotaDouble));
    }

    @Override
    public void update() {
        super.update();

        long currentTime = System.currentTimeMillis();
        if (currentTime >= nextUpdate) {
            updateEncounterValuesWindow();
            nextUpdate = currentTime + windowInterval;
        }

        ageEncounterValues();

        double bufferRatio = (double) getMessageCollection().size() / getBufferSize();
        if (bufferHistory.size() >= 5) bufferHistory.removeFirst();
        bufferHistory.add(bufferRatio);

        energyLevel = Math.max(0.0, energyLevel - 0.01);

        if (!canStartTransfer() || isTransferring()) return;

        Collection<Message> messages = new ArrayList<>(getMessageCollection());
        for (Message msg : messages) {
            for (Connection con : getConnections()) {
                DTNHost other = con.getOtherNode(getHost());
                tryForward(msg, con, other);
            }
        }
    }

    protected void updateEncounterValuesWindow() {
        for (Map.Entry<DTNHost, Integer> entry : encounterWindowCount.entrySet()) {
            DTNHost host = entry.getKey();
            int cwc = entry.getValue();
            double oldEV = encounterValues.getOrDefault(host, 0.0);
            double updatedEV = alpha * cwc + (1 - alpha) * oldEV;
            encounterValues.put(host, Math.min(1.0, updatedEV));
        }
        encounterWindowCount.clear();
    }

    protected void incrementEncounterWindowCount(DTNHost host) {
        encounterWindowCount.put(host, encounterWindowCount.getOrDefault(host, 0) + 1);
    }

    protected void ageEncounterValues() {
        Iterator<Map.Entry<DTNHost, Double>> iter = encounterValues.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<DTNHost, Double> e = iter.next();
            double aged = e.getValue() * agingFactor;
            if (aged < 0.001) iter.remove();
            else e.setValue(aged);
        }
    }

    @Override
    public int receiveMessage(Message msg, DTNHost from) {
        incrementEncounterWindowCount(from);
        return super.receiveMessage(msg, from);
    }

    protected void tryForward(Message msg, Connection con, DTNHost other) {
        if (isTransferring() || other == null) return;

        DTNHost dest = msg.getTo();
        if (other.equals(dest)) {
            startTransfer(msg, con);
            return;
        }

        int replicas = (int) msg.getProperty(MSG_REPLICA_COUNT);
        if (replicas <= 1) return;

        double myEV = encounterValues.getOrDefault(dest, 0.0);
        double otherEV = 0.0;

        if (other.getRouter() instanceof EBREMRTRouter) {
            EBREMRTRouter r = (EBREMRTRouter) other.getRouter();
            otherEV = r.encounterValues.getOrDefault(dest, 0.0);
        }

        if (otherEV > myEV) {
            int newReplicas = replicas / 2;
            msg.updateProperty(MSG_REPLICA_COUNT, replicas - newReplicas);

            Message copy = new Message(getHost(), dest, msg.getId(), msg.getSize());
            copy.setTtl(msg.getTtl());
            copy.addProperty(MSG_REPLICA_COUNT, newReplicas);
            copy.setAppID(msg.getAppID());
            copy.setResponseSize(msg.getResponseSize());

            addToMessages(copy, true);
            startTransfer(copy, con);
        }
    }

    @Override
    public EBREMRTRouter replicate() {
        return new EBREMRTRouter(this);
    }
}
