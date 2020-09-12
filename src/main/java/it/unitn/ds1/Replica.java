package it.unitn.ds1;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;




class Replica extends AbstractActor {
	private int v;
	private final int id; 
	private List<ActorRef> replicas; // the list of replicas (the multicast group)
	private boolean coordinator;
	
	public Replica(int id, int v, boolean coordinator) {
		    this.id = id;
		    this.v = v;
		    this.coordinator = coordinator;
		  }
	
	static public Props props(int id, int v, boolean coord) {
		    return Props.create(Replica.class, () -> new Replica(id, v, coord));
		  }
	
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
	  
	  private void onReadRequest(ReadRequest req) {
		    System.out.println("[ Replica " + this.id + "] received: "  +req.msg);
		    System.out.println("[ Replica " + this.id + "] replied with value : "  +this.v);
		    
		  }
	
	  private void onWriteRequest(WriteRequest req) {
		    if(isCoordinator()) {
		    this.v=req.value;
		    System.out.println("[ Replica " + this.id + "] write value : "  +this.v);
		  }}
	  
	  private void onJoinGroupMsg(JoinGroupMsg msg) {
		    this.replicas = msg.replicas;

		    // create the vector clock
		   
		    System.out.printf("%s: joining a group of %d peers with ID %02d\n", 
		        getSelf().path().name(), this.replicas.size(), this.id);
		  }
	  
	private boolean isCoordinator() {
		if (this.coordinator == true) {;
		return true;}
		return false;
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
