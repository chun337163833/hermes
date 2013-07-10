package nl.dittmer.hermes.actor;

import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import akka.actor.UntypedActor;


public class EchoActor extends UntypedActor {

	@Override
	public void onReceive(Object message) throws Exception {
		if(message instanceof TextWebSocketFrame) {
			TextWebSocketFrame frame = (TextWebSocketFrame) message;
			getSender().tell(frame.getText().toUpperCase(), null);
		}
	}
}
