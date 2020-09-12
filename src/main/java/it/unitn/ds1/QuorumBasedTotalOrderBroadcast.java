package it.unitn.ds1;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import it.unitn.ds1.Client;
import it.unitn.ds1.Replica.JoinGroupMsg;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;



public class QuorumBasedTotalOrderBroadcast {
	  final private static int N_REPLICAS = 10; // number of listening actors
	  final private static int N_CLIENTS = 10;
	  
	  public static void main(String[] args) {
	    // Create the 'helloakka' actor system
	    final ActorSystem system = ActorSystem.create("quorumbasedsystem");

	    List<ActorRef> replicas = new ArrayList<>();
	    List<ActorRef> clients = new ArrayList<>();
	    
	    int id = 0;
	    Random rand = new Random();
	    // the first four peers will be participating in conversations
	    for(int i=0; i<N_REPLICAS; i++) {
	    replicas.add(system.actorOf(Replica.props(id++,2, false),    // actor class
	            "replica"+i     // the new actor name (unique within the system)
	            ));
	    }
	    
	    replicas = Collections.unmodifiableList(replicas);

	    // send the group member list to everyone in the group 
	    JoinGroupMsg join = new JoinGroupMsg(replicas);
	    for (ActorRef peer: replicas) {
	      peer.tell(join, null);
	    }
	    
	    
	    
		final ActorRef client = system.actorOf(
		          Client.props(1, replicas.get(2)),  // this one will start the topic "a"
		          "Client1"); 
		
		final ActorRef client2 = system.actorOf(
		          Client.props(2, replicas.get(2)),  // this one will start the topic "a" rand.nextInt(replicas.size()))
		          "Client2"); 
		
		final ActorRef client3 = system.actorOf(
		          Client.props(3, replicas.get(2)),  // this one will start the topic "a"
		          "Client3"); 
		
		
		  
	    System.out.println(">>> Press ENTER to exit <<<");
	    try {
	      System.in.read();
	    } 
	    catch (IOException ioe) {}
	    finally {
	      system.terminate();
	    }
	    
	  }
}
