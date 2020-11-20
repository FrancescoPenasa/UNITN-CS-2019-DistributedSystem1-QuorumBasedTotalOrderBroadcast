package it.unitn.ds1;

import java.io.Serializable;
import java.util.List;
import java.util.Random;
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
 */
public class Client extends AbstractActor{
	// === variables === //
	private final int id;
	protected List<ActorRef> replicas; // the list of replicas

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
		WriteRequest wr = new WriteRequest(v);
		getContext().system().scheduler().scheduleOnce(
				Duration.create(3, TimeUnit.SECONDS),
				replicas.get(ID),
				wr,
				getContext().system().dispatcher(),
				getSelf()
		);
		return wr;
	}
	private Serializable sendReadReq(int ID){
		ReadRequest rr = new ReadRequest();
		getContext().system().scheduler().scheduleOnce(
				Duration.create(3, TimeUnit.SECONDS),
				replicas.get(ID),
				rr,
				getContext().system().dispatcher(),
				getSelf()
		);
		return rr;
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
			System.out.println("sent update request to:" + ID + " from" + getSelf() + " with value " + v);
		}
		else { // read request
			req = sendReadReq(ID);
			System.out.println("sent read request to:" + ID + " from" + getSelf());
		}
		System.out.println(req);
	}

	/*
	init the msg exchange, periodically call WakeUpMsg
	 */
	@Override
	  public void preStart() {
		Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
			Duration.create(5, TimeUnit.SECONDS),               // when to start generating messages
			Duration.create(5, TimeUnit.SECONDS),               // how frequently generate them
			getSelf(),								// dst
			new WakeUpMsg("WakeUp" + getSelf().path().name()), // the message to send
			getContext().system().dispatcher(),                 // system dispatcher
			getSelf() );
	}



	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(WakeUpMsg.class, this::onWakeUpMsg)
				.build();
	}

}
