package it.unitn.ds1;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import it.unitn.ds1.Replica.JoinGroupMsg;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;



public class QuorumBasedTotalOrderBroadcast {
	  final private static int N_REPLICAS = 5; // number of listening actors
	  final private static int N_CLIENTS = 3;
	  
	  public static void main(String[] args) {
		// Create the actor system
		final ActorSystem system = ActorSystem.create("quorum_based_system");

		// Create replicas and put them in a list, replica 0 is the coordinator
		List<ActorRef> replicas = new ArrayList<>();

		replicas.add(system.actorOf(Replica.props(0, 0),"replica"+"0"));
		for(int id=1; id<N_REPLICAS; id++) {
			replicas.add(system.actorOf(Replica.props(id, 0),"replica"+id));
	  	}

		// Send the replicas members to all replicas
		replicas = Collections.unmodifiableList(replicas);
		JoinGroupMsg join = new JoinGroupMsg(replicas);
		for (ActorRef peer: replicas) {
		  peer.tell(join, null);
		}


		// Create clients and tell them the list of available replicas
		List<ActorRef> clients = new ArrayList<>();
		for(int id=0; id<N_CLIENTS; id++) {
			clients.add(system.actorOf(Client.props(id, replicas),"client" + id));
		}

		
		  
	    System.out.println(">>> Press ENTER to exit <<<");
	    try {
	      int input = System.in.read();
	    } 
	    catch (IOException ioe) {
			System.err.println("error");
		}
	    finally {
	      system.terminate();
	    }
	    
	  }
}
