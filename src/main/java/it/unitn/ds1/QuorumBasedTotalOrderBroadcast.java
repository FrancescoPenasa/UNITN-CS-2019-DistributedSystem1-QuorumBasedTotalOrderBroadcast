package it.unitn.ds1;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import it.unitn.ds1.Replica.Crashed;
import it.unitn.ds1.Replica.JoinGroupMsg;
import it.unitn.ds1.Replica.WriteRequest;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;

// todo SISTEMARE UPDATE REQUEST CON LE CARATTERISTICHE 2PC.

public class QuorumBasedTotalOrderBroadcast {
	// === CONSTANTS === //
	  final private static int N_REPLICAS = 5;
	  final private static int N_CLIENTS = 3;


	  public static void main(String[] args) {

	  	// Create the actor system
		final ActorSystem system = ActorSystem.create("quorum_based_system");

		// Create replicas and put them in a list, replica 0 is the coordinator
		List<ActorRef> replicas = new ArrayList<>();
		int coordinatorID = 0;
		for(int id=0; id<N_REPLICAS; id++) {
			replicas.add(system.actorOf(Replica.props(id, coordinatorID),"replica"+id));
	  	}

		// Send the replicas members to all replicas
		replicas = Collections.unmodifiableList(replicas);
		JoinGroupMsg join = new JoinGroupMsg(replicas);
		for (ActorRef peer: replicas) {
		  peer.tell(join, null);
		}

		// Create clients and tell them the list of available replicas
		for(int id=0; id<N_CLIENTS; id++) {
			system.actorOf(Client.props(id, replicas),"client" + id);
		}

		 try {
		      System.out.println(">>> Press ENTER to crash <<<");
		      System.in.read();
		      Crashed wr = new Crashed();
		      replicas.get(3).tell(wr, null);
		    } 
		    catch (IOException ignored) {}
		  
	    System.out.println(">>> Press ENTER to exit <<<");
	    try {
	      int input = System.in.read();
	    } 
	    catch (IOException ioe) {
			System.err.println("IOException error");
		}
	    finally {
	      system.terminate();
	    }
	    
	  }
}
