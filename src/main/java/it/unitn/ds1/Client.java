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

public class Client extends AbstractActor{
	private final int id;
	protected List<ActorRef> replicas; // the list of replicas

	// A message requesting the peer to start a discussion on his topic
	public static class StartMsg implements Serializable {}

	private void onStartMsg(StartMsg msg) {
		Random rand = new Random();
		boolean write = rand.nextBoolean();

		int replica_index = rand.nextInt(replicas.size()); // todo change bound value?
		if (write){ // update request
			Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
					Duration.create(5, TimeUnit.SECONDS),               // when to start generating messages
					Duration.create(5, TimeUnit.SECONDS),               // how frequently generate them
					replicas.get(replica_index),
					new WriteRequest("Write Request" + getSelf().path().name(), 8), // the message to send
					getContext().system().dispatcher(),                 // system dispatcher
					getSelf() );
		}
		else { // read request

			Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
					Duration.create(5, TimeUnit.SECONDS),               // when to start generating messages
					Duration.create(5, TimeUnit.SECONDS),               // how frequently generate them
					replicas.get(replica_index),
					new ReadRequest("Read Request" + getSelf().path().name()), // the message to send
					getContext().system().dispatcher(),                 // system dispatcher
					getSelf() );
		}
	}


	// To send the replicas list to the client
	public static class JoinGroupMsg implements Serializable {

		private final List<ActorRef> group; // list of group members
		public JoinGroupMsg(List<ActorRef> group) {
			this.group = Collections.unmodifiableList(group);
		}
	}

	private void onJoinGroupMsg(JoinGroupMsg msg) {
		System.out.println("onJoinGroupMsh");
		this.replicas = msg.group;
		System.out.printf("%s: joining a group of %d peers with ID %02d\n",
				getSelf().path().name(), this.replicas.size(), this.id);
	}


	static public Props props(int id, List<ActorRef> replicas) {
		return Props.create(Client.class, () -> new Client(id, replicas));
	}
	//build client actor
	public Client(int id, List<ActorRef> replicas) {
		this.id = id;
		this.replicas = replicas;
	}

	//client-request
	@Override
	  public void preStart() {


	  }
	  

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(JoinGroupMsg.class,    this::onJoinGroupMsg)
				.match(StartMsg.class,    this::onStartMsg)
				.build();
	}

}
