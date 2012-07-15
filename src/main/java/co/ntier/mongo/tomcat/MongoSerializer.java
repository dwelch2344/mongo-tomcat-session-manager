package co.ntier.mongo.tomcat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import javax.servlet.http.HttpSession;

import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.CustomObjectInputStream;

public class MongoSerializer {
	private ClassLoader loader;

	public void setClassLoader(ClassLoader loader) {
		this.loader = loader;
	}

	public byte[] serialize(HttpSession session) throws IOException {

		StandardSession standardSession = (StandardSession) session;
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(
				new BufferedOutputStream(bos));
		oos.writeLong(standardSession.getCreationTime());
		standardSession.writeObjectData(oos);

		oos.close();

		return bos.toByteArray();
	}

	public HttpSession deserialize(byte[] data, HttpSession session)
			throws IOException, ClassNotFoundException {

		StandardSession standardSession = (StandardSession) session;

		BufferedInputStream bis = new BufferedInputStream(
				new ByteArrayInputStream(data));

		ObjectInputStream ois = new CustomObjectInputStream(bis, loader);
		standardSession.setCreationTime(ois.readLong());
		standardSession.readObjectData(ois);

		return session;
	}
}
