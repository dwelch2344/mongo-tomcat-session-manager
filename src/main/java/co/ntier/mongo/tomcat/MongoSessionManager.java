package co.ntier.mongo.tomcat;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.catalina.Container;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.Valve;
import org.apache.catalina.session.StandardSession;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.ServerAddress;
import com.mongodb.WriteResult;

public class MongoSessionManager implements Manager, Lifecycle {
	
	
	private Logger log = Logger.getLogger( getClass().getName() );
	private final ServerAddress serverAddress;
	private final String databaseName, username;
	private final char[] password;
	
	private Mongo mongo;
	private DB db;
	private boolean slaveOk;

	private LifecycleState state = LifecycleState.NEW;
	private ThreadLocal<StandardSession> currentSession = new ThreadLocal<StandardSession>();
	private MongoSessionTrackerValve trackerValve;
	
	private MongoSerializer serializer;

	private String serializationStrategyClass = MongoSerializer.class.getName();

	private Container container;
	private int maxInactiveInterval = 60 * 30; // 30 minutes

	
	private int maxActive = 1000000;

	public MongoSessionManager(ServerAddress serverAddress, String databaseName, String username, String password) {
		super();
		this.serverAddress = serverAddress;
		this.databaseName = databaseName;
		this.username = username;
		this.password = password == null ? null : password.toCharArray();
	}
	
	// ****** Life-Cycle Methods ***************************
	
	@Override
	public void start() throws LifecycleException {
		boolean found = false;
		// find the valve and set this manager on it
		for (Valve valve : getContainer().getPipeline().getValves()) {
			if (valve instanceof MongoSessionTrackerValve) {
				trackerValve = (MongoSessionTrackerValve) valve;
				trackerValve.setMongoManager(this);
				log.info("Attached to Mongo Tracker Valve");
				found = true;
				break;
			}
		}
		if( !found ){
			log.warning( String.format("Did not find %s valve to attach MongoManager", MongoSessionTrackerValve.class.getSimpleName()) );
		}
		
		// 
		try {
			initSerializer();
		} catch (Exception e) {
			log.log(Level.SEVERE, "Unable to load serializer", e);
			throw new LifecycleException(e);
		}
		log.info("Will expire sessions after " + getMaxInactiveInterval()
				+ " seconds");
		
		initDbConnection();
	}

	@Override
	public void stop() throws LifecycleException {
		state = LifecycleState.STOPPING;
		mongo.close();
		state = LifecycleState.STOPPED;
	}
	
	@Override
	public Session createEmptySession() {
		MongoSession session = new MongoSession(this);
		session.setId(UUID.randomUUID().toString());
		session.setMaxInactiveInterval(maxInactiveInterval);
		session.setValid(true);
		session.setCreationTime(System.currentTimeMillis());
		session.setNew(true);
		currentSession.set(session);
		log.fine("Created new empty session " + session.getIdInternal());
		return session;
	}
	
	/**
	 * Periodically called by container to clear out old sessions.
	 */
	@Override
	public void backgroundProcess() {
		BasicDBObject query = new BasicDBObject();

		long olderThan = System.currentTimeMillis()
				- (getMaxInactiveInterval() * 1000);

		log.fine("Looking for sessions less than for expiry in Mongo : "
				+ olderThan);

		query.put("lastmodified", new BasicDBObject("$lt", olderThan));

		try {
			WriteResult result = getCollection().remove(query);
			log.fine("Expired sessions : " + result.getN());
		} catch (IOException e) {
			log.log(Level.SEVERE,
					"Error cleaning session in Mongo Session Store", e);
		}
	}
	
	@Override
	public void add(Session session) {
		try {
			save(session);
		} catch (IOException ex) {
			log.log(Level.SEVERE, "Error adding new session", ex);
		}
	}
	
	// ****** Simple Getters and Setters ****************************************

	@Override
	public Container getContainer() {
		return container;
	}

	@Override
	public void setContainer(Container container) {
		this.container = container;
	}

	@Override
	public boolean getDistributable() {
		return false;
	}

	@Override
	public void setDistributable(boolean b) {

	}

	@Override
	public String getInfo() {
		return "Mongo Session Manager";
	}

	@Override
	public int getMaxInactiveInterval() {
		return maxInactiveInterval;
	}

	@Override
	public void setMaxInactiveInterval(int i) {
		maxInactiveInterval = i;
	}

	@Override
	public int getSessionIdLength() {
		return 37;
	}

	@Override
	public void setSessionIdLength(int i) {

	}

	@Override
	public int getMaxActive() {
		return maxActive;
	}

	@Override
	public void setMaxActive(int i) {
		this.maxActive = i;
	}

	@Override
	public int getActiveSessions() {
		return 1000000;
	}

	public int getRejectedSessions() {
		return 0;
	}

	public void setSerializationStrategyClass(String strategy) {
		this.serializationStrategyClass = strategy;
	}

	public void setSlaveOk(boolean slaveOk) {
		this.slaveOk = slaveOk;
	}

	public boolean getSlaveOk() {
		return slaveOk;
	}

	public void setRejectedSessions(int i) {
	}

	@Override
	public int getSessionMaxAliveTime() {
		return maxInactiveInterval;
	}

	@Override
	public void setSessionMaxAliveTime(int i) {

	}

	@Override
	public int getSessionAverageAliveTime() {
		return 0;
	}

	@Override
	public void load() throws ClassNotFoundException, IOException {
	}

	@Override
	public void unload() throws IOException {
	}


	public void addLifecycleListener(LifecycleListener lifecycleListener) {
		throw new UnsupportedOperationException("Not implemented");
	}

	public LifecycleListener[] findLifecycleListeners() {
		throw new UnsupportedOperationException("Not implemented");
	}

	public void removeLifecycleListener(LifecycleListener lifecycleListener) {
		throw new UnsupportedOperationException("Not implemented");
	}

	@Override
	public void addPropertyChangeListener(
			PropertyChangeListener propertyChangeListener) {
		throw new UnsupportedOperationException("Not implemented");
	}

	@Override
	public void changeSessionId(Session session) {
		session.setId(UUID.randomUUID().toString());
	}

	
	@Override
	public org.apache.catalina.Session createSession(java.lang.String sessionId) {
		StandardSession session = (MongoSession) createEmptySession();

		log.fine("Created session with id " + session.getIdInternal() + " ( "
				+ sessionId + ")");
		if (sessionId != null) {
			session.setId(sessionId);
		}

		return session;
	}

	@Override
	public org.apache.catalina.Session[] findSessions() {
		try {
			List<Session> sessions = new ArrayList<Session>();
			for (String sessionId : keys()) {
				sessions.add(loadSession(sessionId));
			}
			return sessions.toArray(new Session[sessions.size()]);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
	}

	@Override
	public Session findSession(String id) throws IOException {
		return loadSession(id);
	}

	private DBCollection getCollection() throws IOException {
		return db.getCollection("sessions");
	}

	private String[] keys() throws IOException {

		BasicDBObject restrict = new BasicDBObject();
		restrict.put("_id", 1);

		DBCursor cursor = getCollection().find(new BasicDBObject(), restrict);

		List<String> ret = new ArrayList<String>();

		while (cursor.hasNext()) {
			ret.add(cursor.next().get("").toString());
		}

		return ret.toArray(new String[ret.size()]);
	}

	private Session loadSession(String id) throws IOException {

		if (id == null || id.length() == 0) {
			return createEmptySession();
		}

		StandardSession session = currentSession.get();

		if (session != null) {
			if (id.equals(session.getId())) {
				return session;
			} else {
				currentSession.remove();
			}
		}
		try {
			log.fine("Loading session " + id + " from Mongo");
			BasicDBObject query = new BasicDBObject();
			query.put("_id", id);

			DBObject dbsession = getCollection().findOne(query);

			if (dbsession == null) {
				log.fine("Session " + id + " not found in Mongo");
				StandardSession ret = (MongoSession) createEmptySession();
				ret.setId(id);
				currentSession.set(ret);
				return ret;
			}

			byte[] data = (byte[]) dbsession.get("data");

			session = (MongoSession) createEmptySession();
			session.setId(id);
			session.setManager(this);
			serializer.deserialize(data, session);

			session.setMaxInactiveInterval(-1);
			session.access();
			session.setValid(true);
			session.setNew(false);

			if (log.isLoggable(Level.FINE)) {
				log.fine("Session Contents [" + session.getId() + "]:");
				for (Object name : Collections
						.list(session.getAttributeNames())) {
					log.fine("  " + name);
				}
			}

			log.fine("Loaded session id " + id);
			currentSession.set(session);
			return session;
		} catch (IOException e) {
			log.severe(e.getMessage());
			throw e;
		} catch (ClassNotFoundException ex) {
			log.log(Level.SEVERE, "Unable to deserialize session ", ex);
			throw new IOException("Unable to deserializeInto session", ex);
		}
	}

	private void save(Session session) throws IOException {
		try {
			log.fine("Saving session " + session + " into Mongo");

			StandardSession standardsession = (MongoSession) session;

			if (log.isLoggable(Level.FINE)) {
				log.fine("Session Contents [" + session.getId() + "]:");
				for (Object name : Collections.list(standardsession
						.getAttributeNames())) {
					log.fine("  " + name);
				}
			}

			byte[] data = serializer.serialize(standardsession);

			BasicDBObject dbsession = new BasicDBObject();
			dbsession.put("_id", standardsession.getId());
			dbsession.put("data", data);
			dbsession.put("lastmodified", System.currentTimeMillis());

			BasicDBObject query = new BasicDBObject();
			query.put("_id", standardsession.getIdInternal());
			getCollection().update(query, dbsession, true, false);
			log.fine("Updated session with id " + session.getIdInternal());
		} catch (IOException e) {
			log.severe(e.getMessage());
			e.printStackTrace();
			throw e;
		} finally {
			currentSession.remove();
			log.fine("Session removed from ThreadLocal :"
					+ session.getIdInternal());
		}
	}

	@Override
	public void remove(Session session) {
		this.remove(session, true);
	}

	@Override
	public void removePropertyChangeListener(PropertyChangeListener propertyChangeListener) {
		throw new UnsupportedOperationException("Not implemented");
	}
	

	private void initDbConnection() throws LifecycleException {
		try {
			mongo = new Mongo(serverAddress);
			db = mongo.getDB(databaseName);
			if( username != null && password != null){
				db.authenticate(username, password);
			}
			if (slaveOk) {
				db.slaveOk();
			}
			getCollection().ensureIndex(new BasicDBObject("lastmodified", 1));
			
			int ttl = getMaxInactiveInterval() * 1000;
			String msg = String.format("Connected MongoManager to %s/%s (slave: %s; TTL: %s)", serverAddress, databaseName, slaveOk, ttl);
			log.info(msg);
			
		} catch (IOException e) {
			e.printStackTrace();
			throw new LifecycleException("Error Connecting to Mongo", e);
		}
	}

	private void initSerializer() throws ClassNotFoundException,
			IllegalAccessException, InstantiationException {
		log.info("Attempting to use serializer :" + serializationStrategyClass);
		serializer = (MongoSerializer) Class.forName(serializationStrategyClass).newInstance();

		Loader loader = null;

		if (container != null) {
			loader = container.getLoader();
		}
		ClassLoader classLoader = null;

		if (loader != null) {
			classLoader = loader.getClassLoader();
		}
		serializer.setClassLoader(classLoader);
	}

	@Override
	public void init() throws LifecycleException {}

	@Override
	public void destroy() throws LifecycleException {}

	@Override
	public LifecycleState getState() {
		return state;
	}

	@Override
	public String getStateName() {
		return state.toString();
	}

	@Override
	public long getSessionCounter() {
		return 10000000;
	}

	@Override
	public void setSessionCounter(long sessionCounter) {
	}

	@Override
	public long getExpiredSessions() {
		return 0;
	}

	@Override
	public void setExpiredSessions(long expiredSessions) {
	}

	@Override
	public int getSessionCreateRate() {
		return 0;
	}

	@Override
	public int getSessionExpireRate() {
		return 0;
	}

	@Override
	public void remove(Session session, boolean update) {
		log.fine("Removing session ID : " + session.getId());
		BasicDBObject query = new BasicDBObject();
		query.put("_id", session.getId());

		try {
			getCollection().remove(query);
		} catch (IOException e) {
			log.log(Level.SEVERE,
					"Error removing session in Mongo Session Store", e);
		} finally {
			currentSession.remove();
		}
	}

}
