package it.unitn.ds1;

import java.io.Serializable;
import java.util.Collections;
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

	/*
	If true it means that the action is write
	If false the action is read
	 */
	private boolean getRandomAction(){
		Random rand = new Random();
		boolean write = rand.nextBoolean();
		return write;
	}

	private void sendWriteReq(int ID){
		Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
				Duration.create(5, TimeUnit.SECONDS),               // when to start generating messages
				Duration.create(5, TimeUnit.SECONDS),               // how frequently generate them
				replicas.get(ID),								// dst
				new WriteRequest("Write Request" + getSelf().path().name(), 8), // the message to send // TODO CHANGE MESSAGE
				getContext().system().dispatcher(),                 // system dispatcher
				getSelf() );
	}
	private void sendReadReq(int ID){
		Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
				Duration.create(5, TimeUnit.SECONDS),               // when to start generating messages
				Duration.create(5, TimeUnit.SECONDS),               // how frequently generate them
				replicas.get(ID),								// dst
				new ReadRequest("Read Request" + getSelf().path().name()), // the message to send
				getContext().system().dispatcher(),                 // system dispatcher
				getSelf() );
	}

	//client-request
	@Override
	  public void preStart() {
		System.out.println("onJoinGroupMsh");
		if (getRandomAction()){ // update request
			sendWriteReq(getRandomID());
		}
		else { // read request
			sendReadReq(getRandomID());
		}

	  }
	  

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.build();
	}

}
