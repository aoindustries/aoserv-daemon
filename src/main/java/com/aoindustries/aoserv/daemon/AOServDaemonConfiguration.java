/*
 * Copyright 2001-2013, 2014, 2015, 2017, 2019, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

import com.aoindustries.exception.ConfigurationException;
import com.aoindustries.io.AOPool;
import com.aoindustries.lang.Strings;
import com.aoindustries.util.PropertiesUtils;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The configuration for all AOServ processes is stored in a properties file.
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemonConfiguration {

	private AOServDaemonConfiguration() {
	}

	private static Properties props;

	/**
	 * Gets a property value.
	 *
	 * @param  required  when {@code true} the value must be non-null and non-empty
	 */
	private static String getProperty(String name, String templateSkip, boolean required) throws ConfigurationException {
		try {
			String propName = "aoserv.daemon." + name;
			String value;
			synchronized(AOServDaemonConfiguration.class) {
				if(props == null) props = PropertiesUtils.loadFromResource(AOServDaemonConfiguration.class, "aoserv-daemon.properties");
				value = props.getProperty(propName);
			}
			if(value != null && templateSkip != null) value = value.replace(templateSkip, "");
			if(required && (value==null || value.isEmpty())) throw new ConfigurationException("Required property not found: " + propName);
			return value;
		} catch(IOException e) {
			throw new ConfigurationException(e);
		}
	}

	/**
	 * Gets a property value, value not required.
	 */
	private static String getProperty(String name, String templateSkip) throws ConfigurationException {
		return getProperty(name, templateSkip, false);
	}

	public static boolean isNested() throws ConfigurationException {
		return "true".equalsIgnoreCase(getProperty("nested", null));
	}

	public static String getMonitorEmailFullTo() throws ConfigurationException {
		return getProperty("monitor.email.full.to", null);
	}

	public static String getMonitorEmailFullFrom() throws ConfigurationException {
		return getProperty("monitor.email.full.from", "[AO_SERVER_HOSTNAME]");
	}

	public static String getMonitorEmailSummaryTo() throws ConfigurationException {
		return getProperty("monitor.email.summary.to", null);
	}

	public static String getMonitorEmailSummaryFrom() throws ConfigurationException {
		return getProperty("monitor.email.summary.from", "[AO_SERVER_HOSTNAME]");
	}

	public static String getMonitorSmtpServer() throws ConfigurationException {
		return getProperty("monitor.smtp.server", "[SMTP_SERVER]");
	}

	public static String getServerHostname() throws ConfigurationException {
		return getProperty("server.hostname", "[AO_SERVER_HOSTNAME]", true);
	}

	public static String getSSLKeystorePassword() throws ConfigurationException {
		return getProperty("ssl.keystore.password", "[KEYSTORE_PASSWORD]");
	}

	public static String getSSLKeystorePath() throws ConfigurationException {
		return getProperty("ssl.keystore.path", null);
	}

	public static String getPostgresPassword(com.aoindustries.aoserv.client.postgresql.Server.Name serverName) throws ConfigurationException {
		String password = getProperty("postgres." + serverName + ".password", "[POSTGRES_PASSWORD]");
		if(password == null || password.isEmpty()) password = getProperty("postgres.password", "[POSTGRES_PASSWORD]", true);
		return password;
	}

	public static int getPostgresConnections(com.aoindustries.aoserv.client.postgresql.Server.Name serverName) throws ConfigurationException {
		String connections = getProperty("postgres." + serverName + ".connections", null);
		if(connections == null || connections.isEmpty()) connections = getProperty("postgres.connections", null, true);
		return Integer.parseInt(connections);
	}

	public static long getPostgresMaxConnectionAge(com.aoindustries.aoserv.client.postgresql.Server.Name serverName) throws ConfigurationException {
		String max_connection_age = getProperty("postgres." + serverName + ".max_connection_age", null);
		if(max_connection_age == null || max_connection_age.isEmpty()) max_connection_age = getProperty("postgres.max_connection_age", null);
		return max_connection_age == null || max_connection_age.isEmpty() ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(max_connection_age);
	}

	public static String getMySqlDriver() throws ConfigurationException {
		return getProperty("mysql.driver", null, true);
	}

	public static String getMySqlUser(com.aoindustries.aoserv.client.mysql.Server.Name serverName) throws ConfigurationException {
		String user = getProperty("mysql." + serverName + ".user", null);
		if(user == null || user.isEmpty()) user = getProperty("mysql.user", null, true);
		return user;
	}

	public static String getMySqlPassword(com.aoindustries.aoserv.client.mysql.Server.Name serverName) throws ConfigurationException {
		String password = getProperty("mysql." + serverName + ".password", "[MYSQL_PASSWORD]");
		if(password == null || password.isEmpty()) password = getProperty("mysql.password", "[MYSQL_PASSWORD]", true);
		return password;
	}

	public static int getMySqlConnections(com.aoindustries.aoserv.client.mysql.Server.Name serverName) throws ConfigurationException {
		String connections = getProperty("mysql." + serverName + ".connections", null);
		if(connections == null || connections.isEmpty()) connections = getProperty("mysql.connections", null, true);
		return Integer.parseInt(connections);
	}

	public static long getMySqlMaxConnectionAge(com.aoindustries.aoserv.client.mysql.Server.Name serverName) throws ConfigurationException {
		String max_connection_age = getProperty("mysql." + serverName + ".max_connection_age", null);
		if(max_connection_age == null || max_connection_age.isEmpty()) max_connection_age = getProperty("mysql.max_connection_age", null);
		return max_connection_age == null || max_connection_age.isEmpty() ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(max_connection_age);
	}

	public static String getCyrusPassword() throws ConfigurationException {
		return getProperty("cyrus.password", "[CYRUS_PASSWORD]", true);
	}

	public static class NetworkMonitorConfiguration {

		public enum NetworkDirection {
			in,
			out
		}

		public enum CountDirection {
			src,
			dst
		}

		private final String name;
		private final String device;
		private final List<String> networkRanges;
		private final NetworkDirection inNetworkDirection;
		private final CountDirection inCountDirection;
		private final NetworkDirection outNetworkDirection;
		private final CountDirection outCountDirection;
		private final Long nullRouteFifoErrorRate;
		private final Long nullRouteFifoErrorRateMinPps;
		private final Long nullRoutePacketRate;
		private final Long nullRouteBitRate;

		NetworkMonitorConfiguration(
			String name,
			String device,
			List<String> networkRanges,
			NetworkDirection inNetworkDirection,
			CountDirection inCountDirection,
			NetworkDirection outNetworkDirection,
			CountDirection outCountDirection,
			Long nullRouteFifoErrorRate,
			Long nullRouteFifoErrorRateMinPps,
			Long nullRoutePacketRate,
			Long nullRouteBitRate
		) {
			this.name = name;
			this.device = device;
			this.networkRanges = networkRanges;
			this.inNetworkDirection = inNetworkDirection;
			this.inCountDirection = inCountDirection;
			this.outNetworkDirection = outNetworkDirection;
			this.outCountDirection = outCountDirection;
			this.nullRouteFifoErrorRate = nullRouteFifoErrorRate;
			this.nullRouteFifoErrorRateMinPps = nullRouteFifoErrorRateMinPps;
			this.nullRoutePacketRate = nullRoutePacketRate;
			this.nullRouteBitRate = nullRouteBitRate;
		}

		public String getName() {
			return name;
		}

		public String getDevice() {
			return device;
		}

		public List<String> getNetworkRanges() {
			return networkRanges;
		}

		public NetworkDirection getInNetworkDirection() {
			return inNetworkDirection;
		}

		public CountDirection getInCountDirection() {
			return inCountDirection;
		}

		public NetworkDirection getOutNetworkDirection() {
			return outNetworkDirection;
		}

		public CountDirection getOutCountDirection() {
			return outCountDirection;
		}

		public Long getNullRouteFifoErrorRate() {
			return nullRouteFifoErrorRate;
		}

		public Long getNullRouteFifoErrorRateMinPps() {
			return nullRouteFifoErrorRateMinPps;
		}

		public Long getNullRoutePacketRate() {
			return nullRoutePacketRate;
		}

		public Long getNullRouteBitRate() {
			return nullRouteBitRate;
		}
	}

	/**
	 * Gets the set of network monitors that should be enabled on this server.
	 */
	public static Map<String, NetworkMonitorConfiguration> getNetworkMonitors() throws ConfigurationException {
		String networkNamesProp = getProperty("monitor.NetworkMonitor.networkNames", null);
		if(networkNamesProp == null || networkNamesProp.isEmpty()) {
			return Collections.emptyMap();
		} else {
			List<String> networkNames = Strings.splitCommaSpace(networkNamesProp);
			Map<String,NetworkMonitorConfiguration> networkMonitors = new LinkedHashMap<>(networkNames.size()*4/3+1);
			for(String name : networkNames) {
				String nullRouteFifoErrorRate = getProperty("monitor.NetworkMonitor.network." + name + ".nullRoute.fifoErrorRate", null);
				String nullRouteFifoErrorRateMinPps = getProperty("monitor.NetworkMonitor.network." + name + ".nullRoute.fifoErrorRateMinPps", null);
				String nullRoutePacketRate = getProperty("monitor.NetworkMonitor.network." + name + ".nullRoute.packetRate", null);
				String nullRouteBitRate = getProperty("monitor.NetworkMonitor.network." + name + ".nullRoute.bitRate", null);
				if(
					networkMonitors.put(
						name,
						new NetworkMonitorConfiguration(
							name,
							getProperty("monitor.NetworkMonitor.network." + name + ".device", null, true),
							Collections.unmodifiableList(Strings.splitCommaSpace(getProperty("monitor.NetworkMonitor.network." + name + ".networkRanges", null, true))),
							NetworkMonitorConfiguration.NetworkDirection.valueOf(getProperty("monitor.NetworkMonitor.network." + name + ".in.networkDirection", null, true)),
							NetworkMonitorConfiguration.CountDirection.valueOf(getProperty("monitor.NetworkMonitor.network." + name + ".in.countDirection", null, true)),
							NetworkMonitorConfiguration.NetworkDirection.valueOf(getProperty("monitor.NetworkMonitor.network." + name + ".out.networkDirection", null, true)),
							NetworkMonitorConfiguration.CountDirection.valueOf(getProperty("monitor.NetworkMonitor.network." + name + ".out.countDirection", null, true)),
							nullRouteFifoErrorRate == null || nullRouteFifoErrorRate.isEmpty() ? null : Long.valueOf(nullRouteFifoErrorRate),
							nullRouteFifoErrorRateMinPps == null || nullRouteFifoErrorRateMinPps.isEmpty() ? null : Long.valueOf(nullRouteFifoErrorRateMinPps),
							nullRoutePacketRate == null || nullRoutePacketRate.isEmpty() ? null : Long.valueOf(nullRoutePacketRate),
							nullRouteBitRate == null || nullRouteBitRate.isEmpty() ? null : Long.valueOf(nullRouteBitRate)
						)
					) != null
				) throw new ConfigurationException("Duplicate network name: " + name);
			}
			return Collections.unmodifiableMap(networkMonitors);
		}
	}

	public static boolean isPackageManagerUninstallEnabled() throws ConfigurationException {
		final String key = "unix.linux.PackageManager.uninstallEnabled";
		String value = getProperty(key, null);
		if(
			value == null
			|| value.isEmpty()
			|| "true".equalsIgnoreCase(value)
		) return true;
		if("false".equalsIgnoreCase(value)) return false;
		throw new ConfigurationException("Value in aoserv-daemon.properties must be either \"true\" or \"false\": " + key);
	}

	public static boolean isManagerEnabled(Class<?> clazz) throws ConfigurationException {
		final String stripPrefix = "com.aoindustries.aoserv.daemon.";
		String key = clazz.getName();
		if(key.startsWith(stripPrefix)) key = key.substring(stripPrefix.length());
		key += ".enabled";
		String value = getProperty(key, null, true);
		if("true".equalsIgnoreCase(value)) return true;
		if("false".equalsIgnoreCase(value)) return false;
		throw new ConfigurationException("Value in aoserv-daemon.properties must be either \"true\" or \"false\": " + key);
	}
}