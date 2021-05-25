package it.unitn.ds1;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import javafx.util.Pair;
import scala.concurrent.duration.Duration;
import scala.util.Random;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/*
Client requests.
    The client can issue read and write requests to any replica.
    Both types of request contain the ActorRef of the client.  The write request also contains the new proposed value v*.
    For read operations, the replica will reply immediately with its local value.
    For write operations, instead, the request will be forwarded to the coordinator.
 */

/*
Update protocol.
    To perform the update request, the coordinator initiates the protocol by broadcasting to all replicas the UPDATE
    message with the new value. Then, it waits for  their ACKs until a quorum Q of replicas is reached.
    In this implementation,|Q|=bN/2c+ 1, i.e., a majority of nodes.  Once enough ACKs are collected,
    the coordinator broadcasts the WRITEOK message.
    Each UPDATE is identified by a pairã€ˆe, iã€‰, whereeis the current epoch andi is a sequence number.
    The epoch is monotonically increasing;  a change of epoch corresponds to a change of coordinator.
    The sequence number resets to 0 for every new epoch, but identifies uniquely each UPDATE in an epoch.
    Upon a WRITEOK,replicas apply the update and change their local variable.
    They also keep the history of updates to ensure none of them is lost in the case of a coordinator crash (see â€œCoordinator electionâ€�).
 */

/*
Replica can answer a read from a client, and propose and update to the coordinator
Coordinator is determined by the value "coordinator", and it can receive a propose from a replica.


 */

class Replica extends AbstractActor {
	
	Logger logger;
    // === debug and crash === //
    static boolean DEBUG = true;
    static boolean CRASH_ON_UPDATE_SEND = false;
    static boolean CRASH_ON_UPDATE_RECEIVED = true;
    static boolean CRASH_ON_WRITEOKS_SEND = false;
    static boolean CRASH_ON_ELECTION = true;
    List<Cancellable> heartbeatTimers = new LinkedList<>();
    int ttl = 0; // turns before crash

	// ======================== //

    // timeouts
    final static int TIMEOUT_VOTE = 1500;
    final static int TIMEOUT_DECISION = 1000;
    final static int TIMEOUT_HEARTBEAT = 20000;
    final static int TIMEOUT_ELECTION = 2000;
    public enum Timeout {HEARTBEAT, DECISION, VOTE, ELECTION}

    // Heartbeat
    final static int HEARTBEAT = 5000;  // delay in heartbeats
    long lastHeartbeat = -1;

    // replica content
    protected int v = 0; // internal value
    protected int candidate = -1; // internal value
    protected boolean crashed = false;

    // replica ID
    protected int id; // replica ID
    protected static int coordinator; // coordinator ID
    protected List<ActorRef> replicas; // the list of replicas

    // Transaction ID
    private final Stack<Pair<Integer, Integer>> history = new Stack<>();
    private Pair<Integer, Integer> timeStamp = new Pair<>(0, 0);    // epoch and sequence number
    private Integer epoch = 0;
    private Integer sequence_number = 0;

    // 2pc
    private boolean deciding = false;
    public enum Vote {NO, YES}
    public enum Decision {ABORT, COMMIT}
    public Decision decision = null;
    private final List<ActorRef> yesVoters = new ArrayList<>();
    private boolean decisionResponseReceived = false;

    // Election
    private boolean electionMessageReceived = false;
    public static boolean electionStarted = false;
    public boolean messageACK = false;
    public Integer indexNextReplica = null;
    public List<Pair<Integer, Pair<Integer, Integer>>> mylastUpdates = new ArrayList<Pair<Integer, Pair<Integer, Integer>>>();
    public CoordinatorElectionMessage msg = null;
    public boolean lastUpdateReceived = true;
    private boolean electionMessageACKReceived = false;

    //Uniform agreement
    public static List<Integer> indexesOfReplicaWithoutUpdate = new LinkedList<Integer>();


    // === Constructor === //
    public Replica(int id, int coordinator, Logger logger) {
        this.id = id;
        Replica.coordinator = coordinator;
        this.logger= logger;
    }
    static public Props props(int id, int coordinator, Logger logger) {
        return Props.create(Replica.class, () -> new Replica(id, coordinator, logger));
    }
    // ==================== //


    // === Messages handlers === //

	// --- Join group --- //
	/*
	JoinGroupMsg manage to send the replicas members to all replicas.
	 */
    public static class JoinGroupMsg implements Serializable {
        private final List<ActorRef> replicas; // list of group members
        private final int coordinator;

        public JoinGroupMsg(List<ActorRef> group, int coordinatorID) {
            this.replicas = Collections.unmodifiableList(group);
            this.coordinator = coordinatorID;
        }
    }
    private void onJoinGroupMsg(JoinGroupMsg msg) {
        this.replicas = msg.replicas;
        coordinator = msg.coordinator;
        logger.info("joins in a group of " + this.replicas.size() + " peers");
        // start coordinator heartbeat
        if (isCoordinator()) {
            sendBeat();
        } else {
            lastHeartbeat = System.currentTimeMillis();
            setTimeout(Timeout.HEARTBEAT);
        }
        // start replicas heartbeat timeout
    }
    // ------------------ //

    // --- ReadRequest --- //
	/*
	ReadRequest and onReadRequest manage the read request from the client and send back the value.
	 */
    public static class ReadRequest implements Serializable {
        public ReadRequest() {
        }
    }

    private void onReadRequest(ReadRequest req) {
        if (crashed) {
            return;
        }

        // send back the value to the client
        getContext().system().scheduler().scheduleOnce(
                Duration.create(getMsgDelay(), TimeUnit.MILLISECONDS),
                getSender(),
                new Client.ReadResponse(v),
                getContext().system().dispatcher(),
                getSelf()
        );
        print("receives read req from " + getSender().path().name());
    }
    // ------------------- //

    // === WriteRequest === //
	/*
	WriteRequest manage update request from the client,
	if the receiving replica is the coordinator send UPDATE message with the new value to all other replicas
	if the receiving replica is not the coordinator send the UPDATE request to the coordinator
	(part of the update protocol)
	 */
    public static class WriteRequest implements Serializable {
        public final int value;

        public WriteRequest(int value) {
            this.value = value;
        }
    }

    private void onWriteRequest(WriteRequest req) {
        if (crashed) {
            return;
        }

        if (isCoordinator()) {
            while (deciding) {} // lock while updating with a previous value

            if (DEBUG && CRASH_ON_UPDATE_RECEIVED){
                crash();
                return;
            }

            // update sequence number
            deciding = true;
            updateTransactionID();
            resetPastDecision();

            print("receives write req from " + getSender().path().name());

            // start voteRequest for the new value
            this.candidate = req.value;
            multicast(new VoteRequest(req.value));

            if (DEBUG && CRASH_ON_UPDATE_SEND){
                if(ttl == 0) {
                    crash();
                    return;
                } else {
                    ttl--;
                }
            }

            setTimeout(Timeout.VOTE);

        } else {

            print("receives write req from " + getSender().path().name());
            print("sends write req to coordinator");
            WriteRequest update = new WriteRequest(req.value);
            replicas.get(coordinator).tell(update, self());
        }
    }

    private void resetPastDecision() {
        decision = null;
        yesVoters.clear();
    }

    private void updateTransactionID() {
        this.sequence_number = this.sequence_number + 1;
        this.timeStamp = new Pair<>(this.epoch, this.sequence_number);
    }

    private void updateEpoch() {
        this.epoch = this.epoch + 1;
        this.sequence_number = 0;
        this.timeStamp = new Pair<>(this.epoch, this.sequence_number);
        this.history.add(this.timeStamp);
    }
    // =============== //

    // === heartbeat msgs === //
    public static class ReceivingHeartbeat implements Serializable {
        public ReceivingHeartbeat() {
        }
    }

    private void onReceivingHeartbeat(ReceivingHeartbeat msg) {
        if (crashed) {
            return;
        } else {

            lastHeartbeat = System.currentTimeMillis();
            setTimeout(Timeout.HEARTBEAT);
        }
    }
    // ========================== //


    // ============================================================================================================== //
    // ======================================== 2 phase commit ====================================================== //
    // ============================================================================================================== //

    // === VoteRequest === //
	/*
	All non-coordinator replicas will receive a VoteRequest and will answer with a VoteReponse
	 */
    public static class VoteRequest implements Serializable {
        public final int v;

        public VoteRequest(int v) {
            this.v = v;
        }
    }

    private void onVoteRequest(VoteRequest req) {
        if (crashed) {
            //if crashed do nothing
            return;
        } else {
            this.candidate = req.v;
            print("receives vote req from " + getSender().path().name());
            print("sends its vote response to coordinator");
            replicas.get(coordinator).tell(new VoteResponse(Vote.YES), getSelf());
            setTimeout(Timeout.VOTE);
            //setTimeout(Timeout.DECISION);
        }
    }

    // ==================== //

    // === VoteResponse === //
	/*
	The coordinator receive the replicas votes and decide whether to push COMMIT or ABORT
	 */

    public static class VoteResponse implements Serializable {
        public final Vote vote;

        public VoteResponse(Vote vote) {
            this.vote = vote;
        }
    }

    private void onVoteResponse(VoteResponse res) {
        if (crashed) {
            // if crashed do nothing
            return;
        }

        print("receives vote response from " + getSender().path().name());
        if (hasDecided()) {
            return;
        }

        Vote v = res.vote;
        if (v == Vote.YES) {
            yesVoters.add(getSender());

            // coordinator crashes and updated replica fill up the replicas without update
            // crasha mentre manda il decision response ad alcuni dei yesvoters. detti anche somevoters
            if (DEBUG && CRASH_ON_WRITEOKS_SEND && quorumReachedYes()) {
                fixDecision(Decision.COMMIT);
                List<ActorRef> someVoters = yesVoters;
                someVoters.remove(0);
                print("DEBUG send decision response to" + someVoters.toString());
                multicast(new DecisionResponse(decision, this.candidate, this.timeStamp), someVoters);
                crash();
            }

            if (quorumReachedYes()) {
                this.v = this.candidate;
                this.history.add(this.timeStamp);
                fixDecision(Decision.COMMIT);
                multicast(new DecisionResponse(decision, this.candidate, this.timeStamp));
                deciding = false;
            }

        } else {
            fixDecision(Decision.ABORT);
            multicast(new DecisionResponse(Decision.ABORT, this.candidate, this.timeStamp));
            deciding = false;
        }
    }
    // ======================= //


    // === DecisionResponse === //
	/*
	DecisionResponse manage to tell replica the decision the coordinator made
	 */
    public static class DecisionResponse implements Serializable {
        public final Decision decision;
        public final int new_v;
        public final Pair<Integer, Integer> timeStamp;

        public DecisionResponse(Decision d, int new_v, Pair timeStamp) {
            this.decision = d;
            this.new_v = new_v;
            this.timeStamp = timeStamp;
        }
    }

    private void onDecisionResponse(DecisionResponse res) {
    	decisionResponseReceived = true;
        if (res.decision == Decision.COMMIT) {
            this.timeStamp = res.timeStamp;
            this.epoch = timeStamp.getKey();
            this.sequence_number = timeStamp.getValue();
            this.history.add(this.timeStamp);
            this.v = res.new_v;
            print("update " + this.epoch + ":" + this.sequence_number + " " + this.v);
        }


        fixDecision(res.decision);
    }
    // ======================== //


    // === Timeouts === //
	/*
	Coordinator don't receive an ACK from a Replica after a VoteRequest, a replica has crashed.
	 */
    public static class VoteTimeout implements Serializable {
        public VoteTimeout() {
        }
    }

    public void onVoteTimeout(VoteTimeout msg) {
        if (!hasDecided() && isCoordinator()) {
            print("timeout on vote request, replica crashed");
            List<ActorRef> new_replicas = null;
            setID(0);                    // set my ID to 0 because im the coordinator
            new_replicas.add(getSelf()); // position 0 the coordinator position
            new_replicas.addAll(yesVoters);
            multicast(new JoinGroupMsg(new_replicas, 0), new_replicas);
            fixDecision(Decision.ABORT);
            multicast(new DecisionResponse(Decision.ABORT, 0, this.timeStamp));
            setTimeout(Timeout.DECISION);
        }
    }

	/*
	Replica doesn't receive the decision from the coordinator, the coordinator might have crashed, ask the other replicas
	for the decision and start a new election
	 */

    public static class DecisionTimeout implements Serializable {
        public DecisionTimeout() {
        }
    }

    public void onDecisionTimeout(DecisionTimeout msg) {
    	if (decisionResponseReceived) {
    		decisionResponseReceived = false;
    		return;
    	}

        if (!hasDecided() && !isCoordinator() && !electionStarted) {
            print("timeout on decision response, coordinator crashed");

            
            // start election process
            if (!electionStarted) {
                print("I'm starting the election");
                electionStarted = true;
                String b = Boolean.toString(electionStarted);
                print("Valore election startes: "+b);
                getSelf().tell(new CoordinatorElectionMessage(id, timeStamp), getSelf());
                
            }else {
            	return;
            }
        }
    }

	/*
	Replica doesn't receive periodic HeartBeat from the coordinator, coordinator crashed, start a new Election
	 */

    public static class HeartbeatTimeout implements Serializable {
        public HeartbeatTimeout() {
        }
    }

    public void onHeartbeatTimeout(HeartbeatTimeout msg) {
        if (checkHeartbeat() && electionStarted == false) {
            print("timeout on heartbeat, coordinator crashed");
            try {
            	int n = (int)(Math.random()*100);
				Thread.sleep(n);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			};

            // start election process
            if (!electionStarted) {
                print("I'm starting the election");
                electionStarted = true;
                
                //getSelf().tell(new CoordinatorElectionMessage(id, timeStamp), getSelf());
                indexNextReplica = getID();
          
                
                indexNextReplica = (indexNextReplica + 1) % replicas.size();
                if (indexNextReplica == coordinator){
                    indexNextReplica = (indexNextReplica + 1) % replicas.size();
                   
                }
             
                ActorRef nextReplica = replicas.get(indexNextReplica);
                this.msg = new CoordinatorElectionMessage(id, timeStamp);
                nextReplica.tell(this.msg, getSelf());
              
                electionMessageReceived = true;
           
            }
         else {
            print("election in progress");
        }
        }
    }

	/*
	During the election a replica crashes and it's excluded from the election
	 */
    public static class ElectionTimeout implements Serializable {
        public ElectionTimeout() {
        }
    }

    public void onElectionTimeout(ElectionTimeout msg) {
        if (electionMessageACKReceived) {
        	return;
        }

        print("ack message not received, send election message to another replica");

        if (electionStarted) {
        	indexNextReplica = (indexNextReplica + 1) % replicas.size();
        	if (coordinator == indexNextReplica){
                indexNextReplica = (indexNextReplica + 1) % replicas.size();
            }
        	ActorRef nextReplica = replicas.get(indexNextReplica);
            nextReplica.tell(this.msg, getSelf());
        }
    }

    // ============= //

    // ============================================================================================================== //
    // ======================================== /2 phase commit ===================================================== //
    // ============================================================================================================== //

    // ============================================================================================================== //
    // ======================================== leader election ===================================================== //
    // ============================================================================================================== //

    public static class CoordinatorElectionMessage implements Serializable {
        public int idReplica;
        public final List<Pair<Integer, Pair<Integer, Integer>>> lastUpdates = new ArrayList<Pair<Integer, Pair<Integer, Integer>>>();

        public CoordinatorElectionMessage(int idReplica, Pair<Integer, Integer> lastUpdate) {
            lastUpdates.add(new Pair(idReplica, lastUpdate));
            this.idReplica = idReplica;
        }
    }

    private void onCoordinatorElectionMessage(CoordinatorElectionMessage msg) {
        print("receive coordinator election message from replica: " + msg.idReplica);
        if (DEBUG && CRASH_ON_ELECTION){
            if (getID() == 2){ // todo manage if the biggest one is crashed
                print("crashed during election: " + getID());
                crash();
                return;
            }
        }

        // second cycle im gonna check if i can be the coordinator else I will forward the msg
        // todo mettere tutta sta roba in una funzione o piu'
        if (electionMessageReceived) {
            //check If I can be the new coordinator else forward message
            int maxSqNb = 0;
            int countMax = 1;
            int indexMax = 0;

            //find the last update sequence number
            for (Pair<Integer, Pair<Integer, Integer>> lastUpdateSqNb : msg.lastUpdates) {
                if (maxSqNb < lastUpdateSqNb.getValue().getValue()) {
                    maxSqNb = lastUpdateSqNb.getValue().getValue();
                    indexMax = lastUpdateSqNb.getKey();//set the index of the replica with the last update
                }

                //check how many equal last update
                else if (maxSqNb == lastUpdateSqNb.getValue().getValue()) {
                    countMax = countMax + 1;
                }
            }

            //if there are more than one replica with the last update, find the highest id of replica
            int maxID = 0;
            if (countMax > 1) {

                for (Pair<Integer, Pair<Integer, Integer>> lastUpdateID : msg.lastUpdates) {

                    if (maxID <= lastUpdateID.getKey() && maxSqNb == lastUpdateID.getValue().getValue()) {
                        maxID = lastUpdateID.getKey();
                        indexMax = lastUpdateID.getKey();
                    }
                }
            }

            //Replica check if it corresponds to the index with the last update
            if (id == indexMax) {

                //Find replicas without last update
                for (Pair<Integer, Pair<Integer, Integer>> lastUpdate : msg.lastUpdates) {
                    if (maxSqNb != lastUpdate.getValue().getValue()) {
                        indexesOfReplicaWithoutUpdate.add(replicas.indexOf(replicas.get(lastUpdate.getKey())));
                    }
                }

                getSender().tell(new CoordinatorElectionMessageACK(), getSelf());
                print("is elected as new coordinator");

                print("replicas indexes without last update: " + indexesOfReplicaWithoutUpdate.toString());

                sendSync(id, this.v);
                sendBeat(); // todo remove send beat if the joingroupmessage has been added
                updateEpoch();
                
                return;
            }
        }

        // find the index of the next replica whom im supposed to forward
       
        	
        /*indexNextReplica = getID();
        print("INDEX NEXT REPLICA null" +indexNextReplica);
        
        indexNextReplica = (indexNextReplica + 1) % replicas.size();
        if (indexNextReplica == coordinator){
            indexNextReplica = (indexNextReplica + 1) % replicas.size();
            print("INDEX NEXT REPLICA coord" +indexNextReplica);
        }*/
        print("INDEX NEXT REPLICA norm" +indexNextReplica);
        //ActorRef nextReplica = replicas.get(indexNextReplica);

        //Update coordinator election message, first cycle
        if (!electionMessageReceived) {
        	indexNextReplica = getID();
            print("INDEX NEXT REPLICA null" +indexNextReplica);
            
            indexNextReplica = (indexNextReplica + 1) % replicas.size();
            if (indexNextReplica == coordinator){
                indexNextReplica = (indexNextReplica + 1) % replicas.size();
                print("INDEX NEXT REPLICA coord" +indexNextReplica);
            }
            ActorRef nextReplica = replicas.get(indexNextReplica);
            msg.idReplica = id;
            msg.lastUpdates.add(new Pair(id, timeStamp));
            mylastUpdates = msg.lastUpdates;
            this.msg = msg;
            nextReplica.tell(msg, getSelf());
            electionMessageReceived = true;
        } else {
        	ActorRef nextReplica = replicas.get(indexNextReplica);
        	msg.idReplica = id; // serve quest?
        	this.msg = msg;     // todo remove?
            nextReplica.tell(msg, getSelf());
        }

        //ACKnowle  the the message
        setTimeout(Timeout.ELECTION);
        getSender().tell(new CoordinatorElectionMessageACK(), getSelf());

    }


    public static class CoordinatorElectionMessageACK implements Serializable {
        public CoordinatorElectionMessageACK() {
        }
    }

    private void onCoordinatorElectionMessageACK(CoordinatorElectionMessageACK msg) {
    	electionMessageACKReceived = true;
        print("message to: " + getSender().path().name() + " acknowledged");
    }

    public static class SynchronizationMessage implements Serializable {
        int id;
        int value;

        public SynchronizationMessage(int id, int value) {
            this.id = id;
            this.value = value;
        }
    }

    private void onSynchronizationMessage(SynchronizationMessage msg) {
        electionMessageReceived = false;
       

        print("sets new coordinator as: " + getSender().path().name());

        //Replica checks if it is inside the list. If true it updates its value
        for (int i = 0; i < indexesOfReplicaWithoutUpdate.size(); i++) {
            if (replicas.indexOf(getSelf()) == indexesOfReplicaWithoutUpdate.get(i)) {
                this.v = msg.value;
                this.sequence_number = this.sequence_number + 1;
                print("update " + this.epoch + ":" + this.sequence_number + " " + this.v);
            }
        }

        //Replica set the new coordinator and update its epoch
        setID(0);
        coordinator = msg.id;

        updateEpoch();
        //todo add multicast(new JoinGroupMsg(new_replicas, 0), new_replicas);

        electionStarted = false;
    }

    void sendSync(int id, int value) {
        electionMessageReceived = false;
        multicast(new SynchronizationMessage(id, value));
    }


    // ============================================================================================================== //
    // ======================================== /leader election =================================================== //
    // ============================================================================================================== //


    // ============================================================================================================== //
    // ======================================== crash detection ===================================================== //
    // ============================================================================================================== //

    public static class Crashed implements Serializable {
        public Crashed() {
        }
    }

    private void onCrashed(Crashed req) {
        crash();
    }

    public void crash() {
        if (isCoordinator()) {
            for(Cancellable t : heartbeatTimers) {
                t.cancel();
            }
        }
        getContext().become(crashed());
        print("is crashed");
        
        crashed = true;
        CRASH_ON_UPDATE_SEND = false;
        CRASH_ON_UPDATE_RECEIVED = false;
        CRASH_ON_WRITEOKS_SEND = false;
    }

    public Receive crashed() {
        return receiveBuilder()
                .matchAny(msg -> {
                })
                .build();
    }

    // ============================================================================================================== //
    // ======================================== /crash detection ==================================================== //
    // ============================================================================================================== //


    // === Methods === //
    private int getMsgDelay(){
        Random r = new Random();
        return 5 + r.nextInt(95);
    }
    private void multicast(Serializable m) {
        for (ActorRef p : replicas) {
            if (p.equals(getSelf())) { // so the coordinator will not send it to himself
            } else {
                print("multicast sent to " + p.path().name());
                p.tell(m, getSelf());
            }
        }
    }

    private void multicast(Serializable m, List<ActorRef> new_replicas) {
        for (ActorRef p : new_replicas) {
            if (p.equals(getSelf())) { // so the coordinator will not send it to himself
            } else {
                print("multicast sent to " + p.path().name());
                p.tell(m, getSelf());
            }
        }
    }

    private boolean checkHeartbeat() {
        if (isCoordinator()) {
            return false;
        } else {
            return (TIMEOUT_HEARTBEAT <= (System.currentTimeMillis() - lastHeartbeat));
        }
    }

    private void sendBeat() {
        print("sends first heartbeat");
        for (ActorRef p : replicas) {
            if (p.equals(getSelf())) { // so the coordinator will not send it to himself
            } else {
                Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
                        Duration.create(HEARTBEAT, TimeUnit.MILLISECONDS),               // when to start generating messages
                        Duration.create(HEARTBEAT, TimeUnit.MILLISECONDS),               // how frequently generate them
                        p,                                // dst
                        new ReceivingHeartbeat(), // the message to send
                        getContext().system().dispatcher(),                 // system dispatcher
                        getSelf());
                heartbeatTimers.add(timer);
            }
        }
    }


    private void updateReplicas(Serializable m) {
        print("updating replicas");
        for (ActorRef p : replicas) {
            print("multicast sent to " + p.path().name());
            p.tell(m, getSelf());
        }
    }

    void setTimeout(Timeout t) {
        print(t.toString() + " timeout starts");
        if (t.equals(Timeout.HEARTBEAT)) {
            getContext().system().scheduler().scheduleOnce(
                    Duration.create(TIMEOUT_HEARTBEAT, TimeUnit.MILLISECONDS),
                    getSelf(),
                    new HeartbeatTimeout(),
                    getContext().system().dispatcher(), getSelf()
            );
        } else if (t.equals(Timeout.VOTE)) {
            getContext().system().scheduler().scheduleOnce(
                    Duration.create(TIMEOUT_VOTE, TimeUnit.MILLISECONDS),
                    getSelf(),
                    new VoteTimeout(),
                    getContext().system().dispatcher(), getSelf()
            );
            return;
        } else if (t.equals(Timeout.DECISION)) {
            getContext().system().scheduler().scheduleOnce(
                    Duration.create(TIMEOUT_DECISION, TimeUnit.MILLISECONDS),
                    getSelf(),
                    new DecisionTimeout(),
                    getContext().system().dispatcher(), getSelf()
            );
            return;
        } else if (t.equals(Timeout.ELECTION)) {
            getContext().system().scheduler().scheduleOnce(
                    Duration.create(TIMEOUT_ELECTION, TimeUnit.MILLISECONDS),
                    getSelf(),
                    new ElectionTimeout(),
                    getContext().system().dispatcher(), getSelf()
            );
        } else {
        }
    }

    boolean quorumReachedYes() {
        print("checks quorum");
        return yesVoters.size() >= (replicas.size() / 2) + 1;
    }

    boolean ackReceived() {
        return true;
    }

    // fix the final decision of the current node

    void fixDecision(Decision d) {
        if (!hasDecided()) {
            this.decision = d;

        }
    }

    private void print(String s) {
        logger.info("Replica "+this.id+" : "+ s);
    }

    public boolean hasDecided() {
        return decision != null;
    }

    public boolean isCoordinator() {
        return coordinator == this.id;
    }

    public int getID() {
        return this.id;
    }
    private void setID(int id) {
        this.id = id;
    }
    // ============= //


    @Override
    public Receive createReceive() {
        return receiveBuilder()
                // both
                .match(JoinGroupMsg.class, this::onJoinGroupMsg)
                .match(ReadRequest.class, this::onReadRequest)
                .match(WriteRequest.class, this::onWriteRequest)
                .match(Crashed.class, this::onCrashed)

                // only replicas
                .match(VoteRequest.class, this::onVoteRequest)
                .match(ReceivingHeartbeat.class, this::onReceivingHeartbeat)

                //election message
                .match(CoordinatorElectionMessage.class, this::onCoordinatorElectionMessage)
                .match(CoordinatorElectionMessageACK.class, this::onCoordinatorElectionMessageACK)
                .match(SynchronizationMessage.class, this::onSynchronizationMessage)

                // only coordinator
                .match(VoteResponse.class, this::onVoteResponse)
                .match(DecisionResponse.class, this::onDecisionResponse)

                // timeouts
                .match(ElectionTimeout.class, this::onElectionTimeout)
                .match(VoteTimeout.class, this::onVoteTimeout)
                .match(DecisionTimeout.class, this::onDecisionTimeout)
                .match(HeartbeatTimeout.class, this::onHeartbeatTimeout)
                .build();
    }
}
