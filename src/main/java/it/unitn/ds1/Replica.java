package it.unitn.ds1;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;


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
	protected final int id;
	protected final int value = 0;
	protected final int coordinator;
	protected List<ActorRef> replicas; // the list of replicas

	// Constructor
	public Replica(int id, int coordinator) {
		    this.id = id;
		    this.coordinator = coordinator;
		  }
	static public Props props(int id, int coordinator) {
		    return Props.create(Replica.class, () -> new Replica(id, coordinator));
		  }
	
	// Messages handlers
	 public static class JoinGroupMsg implements Serializable {
		    private final List<ActorRef> replicas; // list of group members
		    public JoinGroupMsg(List<ActorRef> group) {
		      this.replicas = Collections.unmodifiableList(group);
		    }
		  }

/* === READ === */
	public static class ReadRequest implements Serializable {
		public final String msg;
		public ReadRequest(String msg) {
			this.msg = msg;
		}
	}

	// Message methods
	private void onReadRequest(ReadRequest req) {
		System.out.println("[ Replica " + this.id + "] received: "  +req.msg);
		System.out.println("[ Replica " + this.id + "] replied with value : "  +this.value);

	}

/* === WRITE === */
	public static class WriteRequest implements Serializable {
		  public final String msg;
		  public final int value;
		  public WriteRequest(String msg, int value) {
		    	// todo ask coordinator
		      this.msg = msg;
		      this.value = value;
		    }

		}

	  private void onWriteRequest(WriteRequest req) {
		  if (isCoordinator()) {
			  System.out.println("[ Coordinator ] received  : " + req.msg);
			  System.out.println("[ [ Coordinator ]  write value : " + req.value);
			  // todo add the 2pc part for the coordinator update
		  } else {
			  System.out.println("[ Replica " + this.id + "] received  : " + req.msg);
			  askUpdateToCoordinator(req.value);
		  }
	  }

	  private void askUpdateToCoordinator (int value){
		UpdateToCoordinatorMsg update = new UpdateToCoordinatorMsg(value);
		replicas.get(coordinator).tell(update, self());
	  }


	public static class UpdateToCoordinatorMsg implements Serializable {
		private final int new_value; // list of group members
		public UpdateToCoordinatorMsg(int new_value) {
			this.new_value = new_value;
		}

	}
	private void onUpdateToCoordinatorMsg(UpdateToCoordinatorMsg new_value) {
		// todo ask at least three replicas for the update
		System.out.printf("%s: joining a group of %d peers with ID %02d\n",
				getSelf().path().name(), this.replicas.size(), this.id);
	}


	  private void onJoinGroupMsg(JoinGroupMsg msg) {
		    this.replicas = msg.replicas;
		    System.out.printf("%s: joining a group of %d peers with ID %02d\n", 
		        getSelf().path().name(), this.replicas.size(), this.id);
		  }
	  
	  // Getter
	 private boolean isCoordinator() {
		 return this.coordinator == this.id;
	 }

	 
	@Override
	public Receive createReceive() {
		return receiveBuilder()
				// both
				.match(JoinGroupMsg.class, this::onJoinGroupMsg)
				.match(ReadRequest.class, this::onReadRequest)
				.match(WriteRequest.class, this::onWriteRequest)

				// only replicas
				.match(UpdateToCoordinatorMsg.class, this::onUpdateToCoordinatorMsg)

				// only coordinator

				.build();
	} 
}
