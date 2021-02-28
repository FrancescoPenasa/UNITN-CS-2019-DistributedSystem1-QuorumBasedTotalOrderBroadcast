package it.unitn.ds1;

import java.io.Serializable;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import it.unitn.ds1.Replica.ReadRequest;
import it.unitn.ds1.Replica.WriteRequest;
import scala.concurrent.duration.Duration;

/*
Client requests. The client can issue read and write requests to any replica. Both types of request contain
the ActorRef of the client. The write request also contains the new proposed value v*.

Specifications:
The client can issue read and write requests to any replica. Both types of request contain
the ActorRef of the client. The write request also contains the new proposed value v*. For read operations, the
replica will reply immediately with its local value. For write operations, instead, the request will be forwarded
to the coordinator.

 */
public class Client extends AbstractActor{

	final static int DELAY = 100;  // delay in msg communication

	// === variables === //
	private final int id;
	protected List<ActorRef> replicas; // the list of replicas
	// ================= //

	// === build client actor === //
	public Client(int id, List<ActorRef> replicas) {
		this.id = id;
		this.replicas = replicas;
	}
	static public Props props(int id, List<ActorRef> replicas) {
		return Props.create(Client.class, () -> new Client(id, replicas));
	}
	// ========================== //


	/*
	get a random id of a replica
	 */
	private int getRandomID(){
		Random rand = new Random();
		int ID = rand.nextInt(replicas.size());
		return ID;
	}

	private int getRandomValue(){
		Random rand = new Random();
		int v = rand.nextInt(50);
		return v;
	}

	/*
	If true it means that the action is write
	If false the action is read
	 */
	private boolean getRandomAction(){
		Random rand = new Random();
		boolean write = rand.nextBoolean();
		return write;
	}




	private Serializable sendWriteReq(int ID, int v){
		print("write req to " + ID);
		WriteRequest wr = new WriteRequest(v);
		getContext().system().scheduler().scheduleOnce(
				Duration.create(DELAY, TimeUnit.MILLISECONDS),
				replicas.get(ID),
				wr,
				getContext().system().dispatcher(),
				getSelf()
		);
		return wr;
	}

	// todo add timeout on read to send a new one
	private Serializable sendReadReq(int ID){
		print("read req to " + ID);
		ReadRequest rr = new ReadRequest();
		getContext().system().scheduler().scheduleOnce(
				Duration.create(DELAY, TimeUnit.MILLISECONDS),
				replicas.get(ID),
				rr,
				getContext().system().dispatcher(),
				getSelf()
		);
		return rr;
	}

	/*
		Read the value sent from the replica after a read request
	 */
	public static class ReadResponse implements Serializable{
		private final int v;
		public ReadResponse(int v){
			this.v = v;
		}
	}
	private void onReadResponse(ReadResponse rr){
		print("read done " + rr.v);
	}

	/*
	WakeUp decide to which replica and which type of msg to send.
	 */
	public class WakeUpMsg implements Serializable{
		private final String msg;
		public WakeUpMsg(String msg) {
			this.msg = msg;
		}
	}
	
	private	void onWakeUpMsg(WakeUpMsg msg){
		int ID = getRandomID();
		Serializable req = null;
		if (getRandomAction()){ // update request
			int v = getRandomValue();
			req = sendWriteReq(ID, v);
		
		}
		else { // read request
			req = sendReadReq(ID);
		
		}
	}

	/*
	init the msg exchange, periodically call WakeUpMsg
	 */
	@Override
	  public void preStart() {

		Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
			Duration.create(ThreadLocalRandom.current().nextInt(1000, 5000), TimeUnit.MILLISECONDS),               // when to start generating messages
			Duration.create(ThreadLocalRandom.current().nextInt(1000, 10000), TimeUnit.MILLISECONDS),               // how frequently generate them
			getSelf(),								// dst
			new WakeUpMsg("WakeUp" + getSelf().path().name()), // the message to send
			getContext().system().dispatcher(),                 // system dispatcher
			getSelf() );
	}



	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(WakeUpMsg.class, this::onWakeUpMsg)
				.match(ReadResponse.class, this::onReadResponse)
				.build();
	}

	void print(String s) {
		System.out.format("Client%2d: %s\n", id, s);
	}

}
