/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoapps.collections.AoCollections;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.web.HttpdServer;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.VirtualHost;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Manages web site logs.
 *
 * @author  AO Industries, Inc.
 */
class HttpdLogManager {

	private static final Logger logger = Logger.getLogger(HttpdLogManager.class.getName());

	/**
	 * The directory that contains the log rotation scripts.
	 */
	private static final String LOG_ROTATION_DIR_CENTOS_5 = HttpdServerManager.CONF_DIRECTORY + "/logrotate.sites";
	private static final String SERVER_LOG_ROTATION_DIR_CENTOS_5 = HttpdServerManager.CONF_DIRECTORY + "/logrotate.servers";

	/**
	 * Logrotate file prefix used for HttpdServer
	 */
	private static final String HTTPD_SERVER_PREFIX_OLD = "httpd";

	/**
	 * The /var/log directory.
	 */
	private static final PosixFile varLogDir = new PosixFile("/var/log");

	/**
	 * The directory that contains the per-apache-instance logs.
	 */
	private static final PosixFile serverLogDirOld = new PosixFile("/var/log/httpd");

	private static final Pattern HTTPD_NAME_REGEXP = Pattern.compile("^httpd@.+$");

	/**
	 * Responsible for control of all things in /logs and /etc/httpd/conf/logrotate.d
	 *
	 * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
	 */
	static void doRebuild(
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded,
		Set<PosixFile> restorecon
	) throws IOException, SQLException {
		// Used below
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		Server thisServer = AOServDaemon.getThisServer();

		// Rebuild /logs
		doRebuildLogs(thisServer, deleteFileList, serversNeedingReloaded);

		// Rebuild /etc/logrotate.d or /etc/httpd/conf/logrotate.(d|sites|servers) files
		doRebuildLogrotate(thisServer, deleteFileList, bout, restorecon);

		// Rebuild /var/log/httpd
		doRebuildVarLogHttpd(thisServer, deleteFileList, restorecon);
	}

	/**
	 * Rebuilds the directories under /logs or /var/log/httpd-sites
	 */
	private static void doRebuildLogs(
		Server thisServer,
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded
	) throws IOException, SQLException {
		// Values used below
		final int logfileUID;
		{
			final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
			final UserServer awstatsLSA = thisServer.getLinuxServerAccount(User.AWSTATS);
			// awstats user is required when RPM is installed
			PackageManager.PackageName awstatsPackageName = osConfig.getAwstatsPackageName();
			if(
				awstatsPackageName != null
				&& PackageManager.getInstalledPackage(awstatsPackageName) != null
				&& awstatsLSA == null
			) {
				throw new SQLException("Unable to find UserServer: " + User.AWSTATS);
			}
			if(awstatsLSA != null) {
				// Allow access to AWStats user, if it exists
				logfileUID = awstatsLSA.getUid().getId();
			} else {
				// Allow access to root otherwise
				logfileUID = PosixFile.ROOT_UID;
			}
		}

		// The log directories that exist but are not used will be removed
		PosixPath logDir = thisServer.getHost().getOperatingSystemVersion().getHttpdSiteLogsDirectory();
		if(logDir != null) {
			PosixFile logDirUF = new PosixFile(logDir.toString());
			// Create the logs directory if missing
			if(!logDirUF.getStat().exists()) {
				logDirUF.mkdir(true, 0755, PosixFile.ROOT_UID, PosixFile.ROOT_GID);
			}

			Set<String> logDirectories;
			{
				String[] list = logDirUF.list();
				logDirectories = AoCollections.newHashSet(list.length);
				for(String dirname : list) {
					if(
						!dirname.equals("lost+found")
						&& !dirname.equals("aquota.group")
						&& !dirname.equals("aquota.user")
					) {
						logDirectories.add(dirname);
					}
				}
			}

			for(Site httpdSite : thisServer.getHttpdSites()) {
				int lsgGID = httpdSite.getLinuxServerGroup().getGid().getId();

				// Create the /logs/<site_name> or /var/log/httpd-sites/<site_name> directory
				String siteName = httpdSite.getName();
				PosixFile logDirectory = new PosixFile(logDirUF, siteName, true);
				Stat logStat = logDirectory.getStat();
				if(!logStat.exists()) {
					logDirectory.mkdir();
					logStat = logDirectory.getStat();
				}
				if(logStat.getUid() != logfileUID || logStat.getGid() != lsgGID) logDirectory.chown(logfileUID, lsgGID);
				if(logStat.getMode() != 0750) logDirectory.setMode(0750);

				// Remove from list so it will not be deleted
				logDirectories.remove(siteName);

				// Make sure each log file referenced under HttpdSiteBinds exists
				List<VirtualHost> hsbs = httpdSite.getHttpdSiteBinds();
				for(VirtualHost hsb : hsbs) {
					// access_log
					PosixPath accessLog = hsb.getAccessLog();
					PosixFile accessLogFile = new PosixFile(accessLog.toString());
					Stat accessLogStat = accessLogFile.getStat();
					PosixFile accessLogParent = accessLogFile.getParent();
					if(!accessLogStat.exists()) {
						// Make sure the parent directory exists
						if(!accessLogParent.getStat().exists()) accessLogParent.mkdir(true, 0750, logfileUID, lsgGID);
						// Create the empty logfile
						new FileOutputStream(accessLogFile.getFile(), true).close();
						accessLogStat = accessLogFile.getStat();
						// Need to restart servers if log file created
						for(VirtualHost hsb2 : hsbs) {
							if(hsb2.getAccessLog().equals(accessLog)) {
								serversNeedingReloaded.add(hsb2.getHttpdBind().getHttpdServer());
							}
						}
					}
					if(accessLogStat.getMode() != 0640) accessLogFile.setMode(0640);
					if(accessLogStat.getUid() != logfileUID || accessLogStat.getGid() != lsgGID) accessLogFile.chown(logfileUID, lsgGID);
					// TODO: Verify ownership and permissions of rotated logs in same directory

					// error_log
					PosixPath errorLog = hsb.getErrorLog();
					PosixFile errorLogFile = new PosixFile(errorLog.toString());
					PosixFile errorLogParent = errorLogFile.getParent();
					Stat errorLogStat = errorLogFile.getStat();
					if(!errorLogStat.exists()) {
						// Make sure the parent directory exists
						if(!errorLogParent.getStat().exists()) errorLogParent.mkdir(true, 0750, logfileUID, lsgGID);
						// Create the empty logfile
						new FileOutputStream(errorLogFile.getFile(), true).close();
						errorLogStat = errorLogFile.getStat();
						// Need to restart servers if log file created
						for(VirtualHost hsb2 : hsbs) {
							if(hsb2.getErrorLog().equals(errorLog)) {
								serversNeedingReloaded.add(hsb2.getHttpdBind().getHttpdServer());
							}
						}
					}
					if(errorLogStat.getMode() != 0640) errorLogFile.setMode(0640);
					if(errorLogStat.getUid() != logfileUID || errorLogStat.getGid() != lsgGID) errorLogFile.chown(logfileUID, lsgGID);
					// TODO: Verify ownership and permissions of rotated logs in same directory
				}
			}

			for(String filename : logDirectories) {
				File logFile = new File(logDirUF.getFile(), filename);
				if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + logFile);
				deleteFileList.add(logFile);
			}
		}
	}

	/**
	 * Rebuilds the per-site logrotation files.
	 */
	private static void doRebuildLogrotate(
		Server thisServer,
		List<File> deleteFileList,
		ByteArrayOutputStream byteOut,
		Set<PosixFile> restorecon
	) throws IOException, SQLException {
		final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		final String siteLogRotationDir;
		final String serverLogRotationDir;
		switch(osConfig) {
			case CENTOS_5_I686_AND_X86_64 :
				siteLogRotationDir = LOG_ROTATION_DIR_CENTOS_5;
				serverLogRotationDir = SERVER_LOG_ROTATION_DIR_CENTOS_5;
				break;
			case CENTOS_7_X86_64 :
				// Nothing done for CentOS 7, we now use wildcard patterns in static /etc/logrotate.d/httpd-(n|sites) files.
				return;
			default :
				throw new AssertionError("Unexpected value for osConfig: "+osConfig);
		}

		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();

		// Create directory if missing
		DaemonFileUtils.mkdir(siteLogRotationDir, 0700, PosixFile.ROOT_UID, PosixFile.ROOT_GID);

		// The log rotations that exist but are not used will be removed
		Set<String> logRotationFiles = new HashSet<>(Arrays.asList(new File(siteLogRotationDir).list()));

		// Each log file will be only rotated at most once
		Set<PosixPath> completedPaths = AoCollections.newHashSet(logRotationFiles.size());

		// For each site, build/rebuild the logrotate.d file as necessary and create any necessary log files
		ChainWriter chainOut=new ChainWriter(byteOut);
		for(Site site : thisServer.getHttpdSites()) {
			// Write the new file to RAM first
			byteOut.reset();
			boolean wroteOne = false;
			for(VirtualHost bind : site.getHttpdSiteBinds()) {
				PosixPath access_log = bind.getAccessLog();
				// Each unique path is only rotated once
				if(completedPaths.add(access_log)) {
					// Add to the site log rotation
					if(wroteOne) chainOut.print(' ');
					else wroteOne = true;
					chainOut.print(access_log);
				}
				PosixPath error_log = bind.getErrorLog();
				if(completedPaths.add(error_log)) {
					// Add to the site log rotation
					if(wroteOne) chainOut.print(' ');
					else wroteOne = true;
					chainOut.print(error_log);
				}
			}
			// Do not write empty files, finish the file
			if(wroteOne) {
				chainOut.print(" {\n"
							 + "    missingok\n"
							 + "    daily\n"
							 + "    rotate 379\n"
							 + "}\n");
				chainOut.flush();

				// Write to disk if file missing or doesn't match
				DaemonFileUtils.atomicWrite(
					new PosixFile(siteLogRotationDir, site.getName()),
					byteOut.toByteArray(),
					0640,
					PosixFile.ROOT_UID,
					site.getLinuxServerGroup().getGid().getId(),
					null,
					restorecon
				);

				// Make sure the newly created or replaced log rotation file is not removed
				logRotationFiles.remove(site.getName());
			}
		}

		// Remove extra filenames
		for(String extraFilename : logRotationFiles) {
			File siteLogRotationFile = new File(siteLogRotationDir, extraFilename);
			if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + siteLogRotationFile);
			deleteFileList.add(siteLogRotationFile);
		}

		if(serverLogRotationDir != null) {
			// Create directory if missing
			DaemonFileUtils.mkdir(serverLogRotationDir, 0700, PosixFile.ROOT_UID, PosixFile.ROOT_GID);

			// The log rotations that exist but are not used will be removed
			logRotationFiles.clear();
			logRotationFiles.addAll(Arrays.asList(new File(serverLogRotationDir).list()));

			boolean isFirst = true;
			for(HttpdServer hs : thisServer.getHttpdServers()) {
				String name = hs.getName();
				int num = name==null ? 1 : Integer.parseInt(name);
				String filename = HTTPD_SERVER_PREFIX_OLD + num;
				logRotationFiles.remove(filename);

				// Build to RAM first
				byteOut.reset();
				try (ChainWriter out = new ChainWriter(byteOut)) {
					out.write("/var/log/httpd/httpd").print(num).print("/access_log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/error_log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/jserv.log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/rewrite.log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/mod_jk.log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n"
							+ "/var/log/httpd/httpd").print(num).print("/ssl_engine_log {\n"
							+ "    missingok\n"
							+ "    daily\n"
							+ "    rotate 379\n"
							+ "}\n");
					if(isFirst) {
						out.print("/var/log/httpd").print(num).print("/suexec.log {\n"
								+ "    missingok\n"
								+ "    daily\n"
								+ "    rotate 379\n"
								+ "}\n");
						isFirst = false;
					}
				}
				DaemonFileUtils.atomicWrite(
					new PosixFile(serverLogRotationDir+"/"+filename),
					byteOut.toByteArray(),
					0600,
					PosixFile.ROOT_UID,
					PosixFile.ROOT_GID,
					null,
					restorecon
				);
			}

			// Remove extra filenames
			for(String extraFilename : logRotationFiles) {
				File serverLogRotationFile = new File(serverLogRotationDir, extraFilename);
				if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + serverLogRotationFile);
				deleteFileList.add(serverLogRotationFile);
			}
		}
	}

	/**
	 * Rebuilds the /var/log/httpd# or /var/log/httpd[@&lt;name&gt;] directories
	 */
	private static void doRebuildVarLogHttpd(
		Server thisServer,
		List<File> deleteFileList,
		Set<PosixFile> restorecon
	) throws IOException, SQLException {
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {

			// Remove any symlink at /var/log/httpd
			Stat serverLogDirStat = serverLogDirOld.getStat();
			if(serverLogDirStat.exists() && serverLogDirStat.isSymLink()) serverLogDirOld.delete();

			// Create /var/log/httpd if missing
			DaemonFileUtils.mkdir(serverLogDirOld, 0700, PosixFile.ROOT_UID, PosixFile.ROOT_GID);

			// Create all /var/log/httpd/* directories
			List<HttpdServer> hss = thisServer.getHttpdServers();
			Set<String> keepFilenames = AoCollections.newHashSet(hss.size());
			for(HttpdServer hs : hss) {
				String name = hs.getName();
				int num = name==null ? 1 : Integer.parseInt(name);
				String dirname = "httpd" + num;
				keepFilenames.add(dirname);
				DaemonFileUtils.mkdir(new PosixFile(serverLogDirOld, dirname, false), 0700, PosixFile.ROOT_UID, PosixFile.ROOT_GID);
			}

			// Remove any extra
			for(String filename : serverLogDirOld.list()) {
				if(!keepFilenames.contains(filename)) {
					if(!filename.startsWith("suexec.log")) {
						File toDelete = new PosixFile(serverLogDirOld, filename, false).getFile();
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
						deleteFileList.add(toDelete);
					}
				}
			}
		} else if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_7_X86_64) {
			// Create all /var/log/httpd[@<name>] directories
			List<HttpdServer> hss = thisServer.getHttpdServers();
			Set<String> keepFilenames = AoCollections.newHashSet(hss.size());
			for(HttpdServer hs : hss) {
				String escapedName = hs.getSystemdEscapedName();
				String dirname = escapedName == null ? "httpd" : ("httpd@" + escapedName);
				keepFilenames.add(dirname);
				PosixFile varLogDirUF = new PosixFile(varLogDir, dirname, true);
				if(DaemonFileUtils.mkdir(varLogDirUF, 0700, PosixFile.ROOT_UID, PosixFile.ROOT_GID)) {
					restorecon.add(varLogDirUF);
				}
			}

			// Remove any extra /var/log/httpd@<name> directories.
			// Note: /var/log/httpd will always be left in-place because it is part of the stock httpd RPM.
			for(String filename : varLogDir.list()) {
				if(
					!keepFilenames.contains(filename)
					&& HTTPD_NAME_REGEXP.matcher(filename).matches()
				) {
					File toDelete = new PosixFile(varLogDir, filename, false).getFile();
					if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + toDelete);
					deleteFileList.add(toDelete);
				}
			}
		}
	}

	private HttpdLogManager() {
	}
}
