package it.unitn.ds1;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import it.unitn.ds1.Replica.ReadRequest;
import it.unitn.ds1.Replica.WriteRequest;
import scala.concurrent.duration.Duration;

public class Client extends AbstractActor{
	private final int id; 
	private ActorRef replica;
	//build client actor
	public Client(int id, ActorRef replica) {
		    this.id = id;
		    this.replica=replica;
		  }
	
	static public Props props(int id, ActorRef replica) {
		    return Props.create(Client.class, () -> new Client(id, replica));
		  }
	
	//client-request
	  @Override
	  public void preStart() {
	    // Create a timer that will periodically send a message to the receiver actor
		Random rand = new Random();
		int x = rand.nextInt(2);
		
		
		if (x==0) {
			Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
					Duration.create(5, TimeUnit.SECONDS),               // when to start generating messages
					Duration.create(5, TimeUnit.SECONDS),               // how frequently generate them
					replica,          
					new WriteRequest("Write Request" + getSelf().path().name(), 8), // the message to send
					getContext().system().dispatcher(),                 // system dispatcher
					getSelf() );}                                         // source of the message (myself)
					
		else {
			Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
			        Duration.create(5, TimeUnit.SECONDS),               // when to start generating messages
			        Duration.create(5, TimeUnit.SECONDS),               // how frequently generate them
			        replica,          
			    
			        new ReadRequest("Write Request" + getSelf().path().name()), // the message to send
			        getContext().system().dispatcher()
			        ,                 // system dispatcher
			        getSelf()                                           // source of the message (myself)
			        );
		}
	  }
	  

	@Override
	public Receive createReceive() {
		// TODO Auto-generated method stub
		return receiveBuilder().build();
	}

}
