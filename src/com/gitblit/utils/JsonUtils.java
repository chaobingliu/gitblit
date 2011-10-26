/*
 * Copyright 2011 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jgit.util.Base64;

import com.gitblit.GitBlitException.ForbiddenException;
import com.gitblit.GitBlitException.NotAllowedException;
import com.gitblit.GitBlitException.UnauthorizedException;
import com.gitblit.GitBlitException.UnknownRequestException;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

/**
 * Utility methods for json calls to a Gitblit server.
 * 
 * @author James Moger
 * 
 */
public class JsonUtils {

	public static final String CHARSET;

	public static final Type REPOSITORIES_TYPE = new TypeToken<Map<String, RepositoryModel>>() {
	}.getType();

	public static final Type USERS_TYPE = new TypeToken<Collection<UserModel>>() {
	}.getType();

	private static final SSLContext SSL_CONTEXT;

	private static final DummyHostnameVerifier HOSTNAME_VERIFIER;

	static {
		SSLContext context = null;
		try {
			context = SSLContext.getInstance("SSL");
			context.init(null, new TrustManager[] { new DummyTrustManager() }, new SecureRandom());
		} catch (Throwable t) {
			t.printStackTrace();
		}
		SSL_CONTEXT = context;
		HOSTNAME_VERIFIER = new DummyHostnameVerifier();
		CHARSET = "UTF-8";
	}

	/**
	 * Creates JSON from the specified object.
	 * 
	 * @param o
	 * @return json
	 */
	public static String toJsonString(Object o) {
		String json = gson().toJson(o);
		return json;
	}

	/**
	 * Convert a json string to an object of the specified type.
	 * 
	 * @param json
	 * @param clazz
	 * @return an object
	 */
	public static <X> X fromJsonString(String json, Class<X> clazz) {
		return gson().fromJson(json, clazz);
	}

	/**
	 * Convert a json string to an object of the specified type.
	 * 
	 * @param json
	 * @param clazz
	 * @return an object
	 */
	public static <X> X fromJsonString(String json, Type type) {
		return gson().fromJson(json, type);
	}

	/**
	 * Reads a gson object from the specified url.
	 * 
	 * @param url
	 * @param type
	 * @return the deserialized object
	 * @throws {@link IOException}
	 */
	public static <X> X retrieveJson(String url, Type type) throws IOException,
			UnauthorizedException {
		return retrieveJson(url, type, null, null);
	}

	/**
	 * Reads a gson object from the specified url.
	 * 
	 * @param url
	 * @param type
	 * @param username
	 * @param password
	 * @return the deserialized object
	 * @throws {@link IOException}
	 */
	public static <X> X retrieveJson(String url, Type type, String username, char[] password)
			throws IOException {
		String json = retrieveJsonString(url, username, password);
		if (StringUtils.isEmpty(json)) {
			return null;
		}
		return gson().fromJson(json, type);
	}

	/**
	 * Reads a gson object from the specified url.
	 * 
	 * @param url
	 * @param clazz
	 * @param username
	 * @param password
	 * @return the deserialized object
	 * @throws {@link IOException}
	 */
	public static <X> X retrieveJson(String url, Class<X> clazz, String username, char[] password)
			throws IOException {
		String json = retrieveJsonString(url, username, password);
		if (StringUtils.isEmpty(json)) {
			return null;
		}
		return gson().fromJson(json, clazz);
	}

	/**
	 * Retrieves a JSON message.
	 * 
	 * @param url
	 * @return the JSON message as a string
	 * @throws {@link IOException}
	 */
	public static String retrieveJsonString(String url, String username, char[] password)
			throws IOException {
		try {
			URL urlObject = new URL(url);
			URLConnection conn = urlObject.openConnection();
			conn.setRequestProperty("Accept-Charset", CHARSET);
			setAuthorization(conn, username, password);
			conn.setUseCaches(false);
			conn.setDoInput(true);
			if (conn instanceof HttpsURLConnection) {
				HttpsURLConnection secureConn = (HttpsURLConnection) conn;
				secureConn.setSSLSocketFactory(SSL_CONTEXT.getSocketFactory());
				secureConn.setHostnameVerifier(HOSTNAME_VERIFIER);
			}
			InputStream is = conn.getInputStream();
			BufferedReader reader = new BufferedReader(new InputStreamReader(is, CHARSET));
			StringBuilder json = new StringBuilder();
			char[] buffer = new char[4096];
			int len = 0;
			while ((len = reader.read(buffer)) > -1) {
				json.append(buffer, 0, len);
			}
			is.close();
			return json.toString();
		} catch (IOException e) {
			if (e.getMessage().indexOf("401") > -1) {
				// unauthorized
				throw new UnauthorizedException(url);
			} else if (e.getMessage().indexOf("403") > -1) {
				// requested url is forbidden by the requesting user
				throw new ForbiddenException(url);
			} else if (e.getMessage().indexOf("405") > -1) {
				// requested url is not allowed by the server
				throw new NotAllowedException(url);
			} else if (e.getMessage().indexOf("501") > -1) {
				// requested url is not recognized by the server
				throw new UnknownRequestException(url);
			}
			throw e;
		}
	}

	/**
	 * Sends a JSON message.
	 * 
	 * @param url
	 *            the url to write to
	 * @param json
	 *            the json message to send
	 * @return the http request result code
	 * @throws {@link IOException}
	 */
	public static int sendJsonString(String url, String json) throws IOException {
		return sendJsonString(url, json, null, null);
	}

	/**
	 * Sends a JSON message.
	 * 
	 * @param url
	 *            the url to write to
	 * @param json
	 *            the json message to send
	 * @param username
	 * @param password
	 * @return the http request result code
	 * @throws {@link IOException}
	 */
	public static int sendJsonString(String url, String json, String username, char[] password)
			throws IOException {
		try {
			byte[] jsonBytes = json.getBytes(CHARSET);
			URL urlObject = new URL(url);
			URLConnection conn = urlObject.openConnection();
			conn.setRequestProperty("Content-Type", "text/plain;charset=" + CHARSET);
			conn.setRequestProperty("Content-Length", "" + jsonBytes.length);
			setAuthorization(conn, username, password);
			conn.setUseCaches(false);
			conn.setDoOutput(true);
			if (conn instanceof HttpsURLConnection) {
				HttpsURLConnection secureConn = (HttpsURLConnection) conn;
				secureConn.setSSLSocketFactory(SSL_CONTEXT.getSocketFactory());
				secureConn.setHostnameVerifier(HOSTNAME_VERIFIER);
			}

			// write json body
			OutputStream os = conn.getOutputStream();
			os.write(jsonBytes);
			os.close();

			int status = ((HttpURLConnection) conn).getResponseCode();
			return status;
		} catch (IOException e) {
			if (e.getMessage().indexOf("401") > -1) {
				// unauthorized
				throw new UnauthorizedException(url);
			} else if (e.getMessage().indexOf("403") > -1) {
				// requested url is forbidden by the requesting user
				throw new ForbiddenException(url);
			} else if (e.getMessage().indexOf("405") > -1) {
				// requested url is not allowed by the server
				throw new NotAllowedException(url);
			} else if (e.getMessage().indexOf("501") > -1) {
				// requested url is not recognized by the server
				throw new UnknownRequestException(url);
			}
			throw e;
		}
	}

	private static void setAuthorization(URLConnection conn, String username, char[] password) {
		if (!StringUtils.isEmpty(username) && (password != null && password.length > 0)) {
			conn.setRequestProperty(
					"Authorization",
					"Basic "
							+ Base64.encodeBytes((username + ":" + new String(password)).getBytes()));
		}
	}

	// build custom gson instance with GMT date serializer/deserializer
	// http://code.google.com/p/google-gson/issues/detail?id=281
	private static Gson gson() {
		GsonBuilder builder = new GsonBuilder();
		builder.registerTypeAdapter(Date.class, new GmtDateTypeAdapter());
		builder.setPrettyPrinting();
		return builder.create();
	}

	private static class GmtDateTypeAdapter implements JsonSerializer<Date>, JsonDeserializer<Date> {
		private final DateFormat dateFormat;

		private GmtDateTypeAdapter() {
			dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		}

		@Override
		public synchronized JsonElement serialize(Date date, Type type,
				JsonSerializationContext jsonSerializationContext) {
			synchronized (dateFormat) {
				String dateFormatAsString = dateFormat.format(date);
				return new JsonPrimitive(dateFormatAsString);
			}
		}

		@Override
		public synchronized Date deserialize(JsonElement jsonElement, Type type,
				JsonDeserializationContext jsonDeserializationContext) {
			try {
				synchronized (dateFormat) {
					return dateFormat.parse(jsonElement.getAsString());
				}
			} catch (ParseException e) {
				throw new JsonSyntaxException(jsonElement.getAsString(), e);
			}
		}
	}

	/**
	 * DummyTrustManager trusts all certificates.
	 */
	private static class DummyTrustManager implements X509TrustManager {

		@Override
		public void checkClientTrusted(X509Certificate[] certs, String authType)
				throws CertificateException {
		}

		@Override
		public void checkServerTrusted(X509Certificate[] certs, String authType)
				throws CertificateException {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return null;
		}
	}

	/**
	 * Trusts all hostnames from a certificate, including self-signed certs.
	 */
	private static class DummyHostnameVerifier implements HostnameVerifier {
		@Override
		public boolean verify(String hostname, SSLSession session) {
			return true;
		}
	}
}
