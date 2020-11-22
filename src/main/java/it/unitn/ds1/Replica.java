package it.unitn.ds1;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
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
	// === const === //
	final static int VOTE_TIMEOUT = 1000;      // timeout for the votes, ms
	final static int DECISION_TIMEOUT = 2000;  // timeout for the decision, ms


	protected final int id; // replica ID
	protected int v = 0; // internal value
	protected int candidate = -1; // internal value
	protected int coordinator; // coordinator ID
	protected List<ActorRef> replicas; // the list of replicas

	// 2pc
	public enum Vote {NO, YES}
	public enum Decision {ABORT, COMMIT}
	public Decision decision = null;

	private final Set<ActorRef> yesVoters = new HashSet<>();

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
	JoinGroupMsg and onJoinGroupMsg manage to send the replicas members to all replicas.
	 */
	public static class JoinGroupMsg implements Serializable {
		private final List<ActorRef> replicas; // list of group members
		public JoinGroupMsg(List<ActorRef> group) {
			this.replicas = Collections.unmodifiableList(group);
		}
	}
	private void onJoinGroupMsg(JoinGroupMsg msg) {
		this.replicas = msg.replicas;
		System.out.printf("%s: joining a group of %d peers with ID %02d\n",
				getSelf().path().name(), this.replicas.size(), this.id);
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
		// todo insert crash check
		getContext().system().scheduler().scheduleOnce(
				Duration.create(1, TimeUnit.SECONDS),
				getSender(),
				new Client.ReadResponse(v),
				getContext().system().dispatcher(),
				getSelf()
		);
		System.out.println("[ Replica " + this.id + "] received: "  + req);
		System.out.println("[ Replica " + this.id + "] replied with value : "  + this.v);
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
		if (isCoordinator()) {
			System.out.println("[ Coordinator ] received  : " + req);
			System.out.println("[ Coordinator ] write value : " + req.value);
			// start voterequest
			this.candidate = req.value;
			multicast(new VoteRequest());
		} else {
			System.out.println("[ Replica " + this.id + "] received  : " + req.value);
			System.out.println("[ Replica " + this.id + "] send to coordinator");
			WriteRequest update = new WriteRequest(req.value);
			replicas.get(coordinator).tell(update, self());
		}
	}
	// =============== //


	// ============================================================================================================== //
	// ======================================== 2 phase commit ====================================================== //
	// ============================================================================================================== //
// todo add quorum
	// === VoteRequest === //
	/*
	All non-coordinator replicas will receive a VoteRequest and will answer with a VoteReponse
	 */
	public static class VoteRequest implements Serializable {
		public VoteRequest() {
		}
	}
	private void onVoteRequest(VoteRequest req) {
		System.out.println("[ Replica " + this.id + "] received VoteRequest");

		replicas.get(this.coordinator).tell(new VoteResponse(Vote.YES), getSelf());
		setTimeout(DECISION_TIMEOUT);
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
		print("[Coordinator " + this.id + "] VoteResponse received");

		if (hasDecided()){
			return;
		}

		Vote v = res.vote;
		if (v == Vote.YES) {
			print("[Coordinator " + this.id + "] receive YES");
			yesVoters.add(getSender());
			if (quorumReachedYes()) {
				fixDecision(Decision.COMMIT);
				this.v = this.candidate;
				multicast(new DecisionResponse(decision, this.candidate));
			}
		} else {
			print("[Coordinator " + this.id + "] receive NO");
			fixDecision(Decision.ABORT);
			multicast(new DecisionResponse(Decision.ABORT, this.candidate));
		}
	}
	// ======================= //

	// === DecisionRequest === //
	/*
	DecisionRequest manage to tell the replicas that still don't know the decision if a decision has come
	 */
	public static class DecisionRequest implements Serializable {}
	private void onDecisionRequest(DecisionRequest req) {
		if (hasDecided()){
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
		this.v = res.new_v;
		fixDecision(res.decision);
	}
	// ======================== //


	// === Timeout === //
	public static class Timeout implements Serializable {
		public Timeout() {
		}
	}
	public void onTimeout(Timeout msg) {
		if (isCoordinator()){
			if (!hasDecided()) {
				print("Timeout");

				// not decided in time means ABORT
				fixDecision(Decision.ABORT);
				multicast(new DecisionResponse(Decision.ABORT, 0));
			}
		} else {
			if (!hasDecided()) {
				print("Timeout. Asking around.");

				// ask other participants
				multicast(new DecisionRequest());

				// ask also the coordinator
				replicas.get(coordinator).tell(new DecisionRequest(), getSelf());
				setTimeout(DECISION_TIMEOUT);
			}
		}
	}
	// ============= //

	// ============================================================================================================== //
	// ======================================== /2 phase commit ===================================================== //
	// ============================================================================================================== //



	// === Methods === //
	private void multicast(Serializable m) {
		print("Multicasting");
		for (ActorRef p: replicas) {
			if (p.equals(getSelf())) { // so the coordinator will not send it to himself
			} else {
				print("Multicast sent to " + p);
				p.tell(m, getSelf());
			}
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

	// todo where the fuck the new value is passed?
	// fix the final decision of the current node
	void fixDecision(Decision d) {
		if (!hasDecided()) {
			this.decision = d;
			print("[Replica " + id + "] decided " + d);
		}
	}

	void print(String s) {
		System.out.format("%2d: %s\n", id, s);
	}
	String trimGetSelf(String s){
		return s.substring(30);
	}


	// =============== //
	// === Getter === //
	public boolean isCoordinator() {
		return this.coordinator == this.id;
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

				// only replicas
				.match(VoteRequest.class, this::onVoteRequest)
				.match(DecisionRequest.class, this::onDecisionRequest)

				// only coordinator
				.match(VoteResponse.class, this::onVoteResponse)
				.match(DecisionResponse.class, this::onDecisionResponse)

				.build();
	}
}
