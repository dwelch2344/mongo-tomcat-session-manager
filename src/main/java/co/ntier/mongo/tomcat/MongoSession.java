package co.ntier.mongo.tomcat;

import org.apache.catalina.Manager;
import org.apache.catalina.session.StandardSession;

public class MongoSession extends StandardSession {
	
	private static final long serialVersionUID = 1L;
	private boolean isValid = true;

	public MongoSession(Manager manager) {
		super(manager);
	}

	@Override
	protected boolean isValidInternal() {
		return isValid;
	}

	@Override
	public boolean isValid() {
		return isValidInternal();
	}

	@Override
	public void setValid(boolean isValid) {
		this.isValid = isValid;
		if (!isValid) {
			String keys[] = keys();
			for (String key : keys) {
				removeAttributeInternal(key, false);
			}
			getManager().remove(this);

		}
	}

	@Override
	public void invalidate() {
		setValid(false);
	}

	@Override
	public void setId(String id) {
		this.id = id;
	}
}
