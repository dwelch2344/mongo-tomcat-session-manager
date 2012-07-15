package co.ntier.mongo.tomcat;

import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.logging.Logger;

public class MongoSessionTrackerValve extends ValveBase {
	
	private Logger log = Logger.getLogger( getClass().getName() );
	private MongoSessionManager manager;

	public void setMongoManager(MongoSessionManager manager) {
		this.manager = manager;
	}

	@Override
	public void invoke(Request request, Response response) throws IOException,
			ServletException {
		try {
			getNext().invoke(request, response);
		} finally {
			storeSession(request, response);
		}
	}

	private void storeSession(Request request, Response response)
			throws IOException {
		final Session session = request.getSessionInternal(false);

		if (session == null) {
			return;
		}
		
		if (session.isValid()) {
			log.fine( String.format("Request with session completed, saving session %s", session.getId()) );
			if(session.getSession() == null){
				log.fine("No HTTP Session present, Not saving " + session.getId());
			}else{
				log.fine("HTTP Session present, saving " + session.getId());
				manager.add(session);
			}
		} else {
			log.fine("HTTP Session has been invalidated, removing :" + session.getId());
			manager.remove(session);
		}
	}
}
