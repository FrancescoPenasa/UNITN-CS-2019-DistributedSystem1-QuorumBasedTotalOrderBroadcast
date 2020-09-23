package it.unitn.ds1;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import it.unitn.ds1.Coordinator.CoordinatorWriteRequest;




class Replica extends AbstractActor {
	protected int v;
	protected final int id; 
	protected List<ActorRef> replicas; // the list of replicas 
	
	//ACTOR REPLICA CONSTRUCTORS
	
	public Replica(int id, int v) {
		    this.id = id;
		    this.v = v;
		  }
	
	static public Props props(int id, int v, boolean coord) {
		    return Props.create(Replica.class, () -> new Replica(id, v));
		  }
	
	//MESSAGES CLASS
	 public static class JoinGroupMsg implements Serializable {
		    private final List<ActorRef> replicas; // list of group members
		    public JoinGroupMsg(List<ActorRef> group) {
		      this.replicas = Collections.unmodifiableList(group);
		    }
		  }
	 
	  public static class ReadRequest implements Serializable {
		    public final String msg;
		    public ReadRequest(String msg) {
		      this.msg = msg;
		    }
		}
	  
	  public static class WriteRequest implements Serializable {
		    public final String msg;
		    public final int value;
		    public WriteRequest(String msg, int value) {
		      this.msg = msg;
		      this.value = value;
		    }
		}
	  
	
	  
	  //METHODS ON MESSAGES
	  private void onReadRequest(ReadRequest req) {
		    System.out.println("[ Replica " + this.id + "] received: "  +req.msg);
		    System.out.println("[ Replica " + this.id + "] replied with value : "  +this.v);
		    
		  }
	
	  private void onWriteRequest(WriteRequest req) {
		  if(isCoordinator()) {
			  System.out.println("[ Coordinator ] received  : "  +req.msg);
		    System.out.println("[ [ Coordinator ]  write value : "  +req.value);
		    }else
		    System.out.println("[ Replica " + this.id + "] received  : "  +req.msg);
		   castToCoord();
		  }
	  
	  private void onJoinGroupMsg(JoinGroupMsg msg) {
		    this.replicas = msg.replicas;
		    System.out.printf("%s: joining a group of %d peers with ID %02d\n", 
		        getSelf().path().name(), this.replicas.size(), this.id);
		  }
	  
	  //OTHER METHODS
	 private boolean isCoordinator() {
		 if (this.id==-1) {
		 return true;}
		 return false;
	 }
	 
	 private void castToCoord() {
		 System.out.println("Send write request to coord => TO DO!");
	 }
	 
	@Override
	public Receive createReceive() {
		// TODO Auto-generated method stub
		return receiveBuilder()
				.match(ReadRequest.class, this::onReadRequest)
				.match(WriteRequest.class, this::onWriteRequest)
				.match(JoinGroupMsg.class, this::onJoinGroupMsg)
				.build();
	} 
}


//TO DO FOR THE QUORUM BASED 
class Coordinator extends Replica {
	
	private final Set<ActorRef> yesVoters = new HashSet<>();
	
	
	//COORDINATOR CONSTRUCTORS
	public Coordinator(int id, int v) {
		super(id, v);
		
	}
	
	static public Props props(int id, int v) {
	    return Props.create(Coordinator.class, () -> new Coordinator(id, v));
	  }
	
	public static class CoordinatorWriteRequest implements Serializable {
	    public final String msg;
	    public final int value;
	    public CoordinatorWriteRequest(String msg, int value) {
	      this.msg = msg;
	      this.value = value;
	    }

	}
	
	private void OnCoordinatorWriteRequest(CoordinatorWriteRequest req) {
	    System.out.println("[ Replica " + this.id + "] write value : "  +req.value);
	    
	  }
  
	
	@Override
	public Receive createReceive() {
		// TODO Auto-generated method stub
		return receiveBuilder()
				.match(CoordinatorWriteRequest.class, this::OnCoordinatorWriteRequest)
				.build();
	} 
	
	//COORDINATOR MESSAGES

	
	

	
}
