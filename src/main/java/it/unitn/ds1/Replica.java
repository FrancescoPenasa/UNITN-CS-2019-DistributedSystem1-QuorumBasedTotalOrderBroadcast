package it.unitn.ds1;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;

import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;


/*
For read operations, the
replica will reply immediately with its local value. For write operations, instead, the request will be forwarded
to the coordinator

Replica can answer a read from a client, and propose and update to the coordinator
Coordinator is determined by the value "coordinator", and it can receive a propose from a replica
 */

// replica ask to coordinator
// coordinator ask to replicas
// replicas answer
// coordinator confirsm
// replica finally get the answer

class Replica extends AbstractActor {
	// === debug === //
	final boolean DEBUG = true;
	final boolean REPLICA2_CRASH_ON_VOTE = true;
	final boolean COORDINATOR_CRASH_ON_HB = true;
	final boolean COORDINATOR_CRASH_ON_DECISION = true;
	int ttl = 3; // turns before crash

	// === const === //
	final static int VOTE_TIMEOUT = 1000;      // timeout for the votes, ms
	final static int DECISION_TIMEOUT = 2000;  // timeout for the decision, ms
	final static int HEARTBEAT_TIMEOUT = 10000;  // timeout for the heartbeat, ms

	final static int DELAY = 100;  // delay in msg communication
	final static int HEARTBEAT = 2500;  // delay in heartbeats
	long lastHeartbeat = -1;

	protected final int id; // replica ID
	protected int v = 0; // internal value
	protected int candidate = -1; // internal value
	protected int coordinator; // coordinator ID
	protected List<ActorRef> replicas; // the list of replicas
	protected boolean crashed = false;

	// 2pc
	public enum Vote {NO, YES}
	public enum Decision {ABORT, COMMIT}
	public Decision decision = null;

	private final List<ActorRef> yesVoters = new ArrayList<>();


	// === Constructor === //
	public Replica(int id, int coordinator) {
		this.id = id;
		this.coordinator = coordinator;
	}
	static public Props props(int id, int coordinator) {
		return Props.create(Replica.class, () -> new Replica(id, coordinator));
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
		this.coordinator = msg.coordinator;
		print("joining a group of " + this.replicas.size() + " peers with ID " + this.id);

		// start coordinator heartbeat
		if (isCoordinator()){
			sendBeat();
		} else {
			lastHeartbeat = System.currentTimeMillis();
			setTimeout(HEARTBEAT_TIMEOUT);
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
		if (DEBUG && REPLICA2_CRASH_ON_VOTE && this.id == 2){
			if(this.ttl-- == 0){
				crash();
				return;
			}
		}


		if (crashed) {
			// todo insert crash exception
		}

		// send back the value to the client
		getContext().system().scheduler().scheduleOnce(
				Duration.create(DELAY, TimeUnit.MILLISECONDS),
				getSender(),
				new Client.ReadResponse(v),
				getContext().system().dispatcher(),
				getSelf()
		);
		print("received ReadRequest from " + getSender().path().name() + " and replied with v = " + this.v);
	}
	// ------------------- //



	// === WriteRequest === //
	/*
	WriteRequest manage update request from the client,
	if the receiving replica is the coordinator update all the other replicas
	if the receiving replica is not the coordinator send the update req to the coordinator
	 */
	public static class WriteRequest implements Serializable {
		public final int value;
		public WriteRequest(int value) {
			this.value = value;
		}
	}
	private void onWriteRequest(WriteRequest req) {
		if (crashed) {
			// todo insert crash exception
		}
		if (isCoordinator()) {
			print("[Coordinator] received WriteRequest from " + getSender().path().name() + " v = " + req.value);

			// start voteRequest
			this.candidate = req.value;
			multicast(new VoteRequest());

			// todo timeout
			// setTimeout(VOTE_TIMEOUT);
		} else {
			print("received WriteRequest from " + getSender().path().name() + " v = " + req.value);
			print("sending WriteRequest to coordinator v = " + req.value);
			WriteRequest update = new WriteRequest(req.value);
			replicas.get(coordinator).tell(update, self());
		}
	}
	// =============== //

	// === heartbeat msgs === //
	public static class ReceivingHeartbeat implements Serializable {
		public ReceivingHeartbeat() {
		}
	}
	private void onReceivingHeartbeat(ReceivingHeartbeat msg) {
		if (crashed){
		}
		lastHeartbeat = System.currentTimeMillis();
		print("Heartbeat received, restart timeout at time : " + lastHeartbeat);
		setTimeout(HEARTBEAT_TIMEOUT);
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
		public VoteRequest() {
		}
	}
	private void onVoteRequest(VoteRequest req) {
		if (crashed){
			// todo insert crash exception
		}
		print("received VoteRequest from " + getSender().path().name());
		print("sending Vote YES to Coordinator ");

		replicas.get(this.coordinator).tell(new VoteResponse(Vote.YES), getSelf());

		// todo timeout
		setTimeout(VOTE_TIMEOUT);
	}
	// ==================== //

	// === VoteResponse === //
	/*
	The coordinator receive the replicas votes and decide wheter to push COMMIT or ABORT
	 */
	public static class VoteResponse implements Serializable {
		public final Vote vote;
		public VoteResponse(Vote vote) {
			this.vote = vote;
		}
	}
	private void onVoteResponse(VoteResponse res) {
		if (crashed) {
			// todo insert crash exception
		}

		print("[Coordinator] received VoteResponse from " + getSender().path().name() + " vote =" + res.vote);

		if (hasDecided()){
			return;
		}

		Vote v = res.vote;
		if (v == Vote.YES) {
			yesVoters.add(getSender());

			// commit requirement reached
			if (quorumReachedYes()) {
				this.v = this.candidate;
				fixDecision(Decision.COMMIT);
				multicast(new DecisionResponse(decision, this.candidate));
			}
		} else {
			fixDecision(Decision.ABORT);
			multicast(new DecisionResponse(Decision.ABORT, this.candidate));
		}
	}
	// ======================= //

	// === DecisionRequest === //
	/*
	DecisionRequest manage to tell the replicas that still don't know the decision if a decision has come
	 */
	public static class DecisionRequest implements Serializable {
		public DecisionRequest() {
		}
	}
	private void onDecisionRequest(DecisionRequest req) {
		if (hasDecided()){
			print("received DecisionRequest from " + getSender().path().name());
			print("answering DecisionRequest to " + getSender().path().name() + " decision = " + decision + " v = " + this.v);
			getSender().tell(new DecisionResponse(decision, this.v), getSelf());
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
		public DecisionResponse(Decision d, int new_v) {
			decision = d;
			this.new_v = new_v;
		}
	}
	private void onDecisionResponse(DecisionResponse res) {
		print("sending DecisionResponse to " + getSender().path().name() + " decision " + decision + " v = "+ res.new_v);
		if (res.decision==Decision.COMMIT) {
			this.v = res.new_v;
		}
		fixDecision(res.decision);
	}
	// ======================== //


	// === Timeout === //
	public static class Timeout implements Serializable {
		public Timeout() {
		}
	}
	public void onTimeout(Timeout msg) {

				// Heartbeat
				if (checkHeartbeat()){ // replica don't receive periodic HeartBeat
					print("Heartbeat not received, coordinator crashed");

					// todo start new Election

					setTimeout(HEARTBEAT_TIMEOUT);
				}

				if (!hasDecided() && isCoordinator()) { // coordinator dont receive VoteResponse
					print("Timeout on VoteRequest, replica crashed");
					List<ActorRef> new_replicas = yesVoters;
					new_replicas.add(getSelf());
					multicast(new JoinGroupMsg(new_replicas, getID()), new_replicas);
					fixDecision(Decision.ABORT);
					multicast(new DecisionResponse(Decision.ABORT, 0));
					setTimeout(DECISION_TIMEOUT);
				}
				if (!hasDecided() && !isCoordinator()) { // replica dont receive DecisionResponse
					print("Timeout on DecisionResponse, coordinator crashed");
					multicast(new DecisionRequest());

					// todo start new Election

					setTimeout(DECISION_TIMEOUT);
				}
	}
	// ============= //

	// ============================================================================================================== //
	// ======================================== /2 phase commit ===================================================== //
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
		getContext().become(crashed());
		print("CRASH!!!");
	}

	public Receive crashed() {
		return receiveBuilder()
				.matchAny(msg -> {})
				.build();
	}


	// ============================================================================================================== //
	// ======================================== /crash detection ==================================================== //
	// ============================================================================================================== //



	// === Methods === //
	private void multicast(Serializable m) {
		print("Multicasting");
		for (ActorRef p: replicas) {
			if (p.equals(getSelf())) { // so the coordinator will not send it to himself
			} else {
				print("Multicast sent to " + p.path().name());
				p.tell(m, getSelf());
			}
		}
	}
	private void multicast(Serializable m, List<ActorRef> new_replicas) {
		print("Multicasting");
		for (ActorRef p: new_replicas) {
			if (p.equals(getSelf())) { // so the coordinator will not send it to himself
			} else {
				print("Multicast sent to " + p.path().name());
				p.tell(m, getSelf());
			}
		}
	}

	private boolean checkHeartbeat(){
		return HEARTBEAT_TIMEOUT <= (System.currentTimeMillis() - lastHeartbeat);
	}

	private void sendBeat() {
		print("sending heartbeat");
		for (ActorRef p: replicas) {
			if (p.equals(getSelf())) { // so the coordinator will not send it to himself
			} else {
				Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
						Duration.create(HEARTBEAT, TimeUnit.MILLISECONDS),               // when to start generating messages
						Duration.create(HEARTBEAT, TimeUnit.MILLISECONDS),               // how frequently generate them
						p,								// dst
						new ReceivingHeartbeat(), // the message to send
						getContext().system().dispatcher(),                 // system dispatcher
						getSelf() );
			}
		}
	}


	private void updateReplicas(Serializable m){
		print("updating replicas");
		for (ActorRef p: replicas) {

			print("Multicast sent to " + p);
			p.tell(m, getSelf());

		}
	}

	void setTimeout(int time) {
		print("[ Replica " + id + "] Timeout Started");
		getContext().system().scheduler().scheduleOnce(
				Duration.create(time, TimeUnit.MILLISECONDS),
				getSelf(),
				new Timeout(), // the message to send
				getContext().system().dispatcher(), getSelf()
		);
	}

	boolean hasDecided() {
		return decision != null;
	}
	boolean quorumReachedYes() {
		print("Coordinator" + id + " check quorum");
		return yesVoters.size() >= (replicas.size() / 2 )+ 1;
	}


	// fix the final decision of the current node
	void fixDecision(Decision d) {
		if (!hasDecided()) {
			this.decision = d;
			print("decided " + d);
		}
	}

	void print(String s) {
		System.out.format("Replica %2d: %s\n", id, s);
	}



	// =============== //
	// === Getter === //
	public boolean isCoordinator() {
		return this.coordinator == this.id;
	}
	public int getID() {
		return this.id;
	}
	// ============= //


	@Override
	public Receive createReceive() {
		return receiveBuilder()
				// both
				.match(JoinGroupMsg.class, this::onJoinGroupMsg)
				.match(ReadRequest.class, this::onReadRequest)
				.match(WriteRequest.class, this::onWriteRequest)
				.match(Timeout.class, this::onTimeout)
				.match(Crashed.class, this::onCrashed)
				// only replicas
				.match(VoteRequest.class, this::onVoteRequest)
				.match(DecisionRequest.class, this::onDecisionRequest)

				.match(ReceivingHeartbeat.class, this::onReceivingHeartbeat)

				// only coordinator
				.match(VoteResponse.class, this::onVoteResponse)
				.match(DecisionResponse.class, this::onDecisionResponse)


				.build();
	}
}
