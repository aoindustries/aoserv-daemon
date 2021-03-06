/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.email;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.Domain;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author  AO Industries, Inc.
 */
public final class EmailDomainManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(EmailDomainManager.class.getName());

	private static final PosixFile configFile = new PosixFile("/etc/mail/local-host-names");

	private static EmailDomainManager emailDomainManager;

	private EmailDomainManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				Set<PosixFile> restorecon = new LinkedHashSet<>();
				try {
					// Grab the list of domains from the database
					List<Domain> domains = thisServer.getEmailDomains();

					// Install sendmail if needed
					// Sendmail is enabled/disabled in SendmailCFManager based on net_binds
					boolean sendmailInstalled;
					if(!domains.isEmpty()) {
						PackageManager.installPackage(PackageManager.PackageName.SENDMAIL);
						sendmailInstalled = true;
					} else {
						sendmailInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.SENDMAIL) != null;
					}
					if(sendmailInstalled) {
						// Create the new file
						byte[] newBytes;
						{
							ByteArrayOutputStream bout = new ByteArrayOutputStream();
							try (ChainWriter out = new ChainWriter(bout)) {
								out.print(
									"# local-host-names - include all aliases for your machine here.\n"
									+ "#\n"
									+ "# Generated by ").print(EmailDomainManager.class.getName()).print("\n");
								for(Domain domain : domains) out.println(domain.getDomain());
							}
							newBytes = bout.toByteArray();
						}
						// Write new file only when needed
						if(
							DaemonFileUtils.atomicWrite(
								configFile,
								newBytes,
								0644,
								PosixFile.ROOT_UID,
								PosixFile.ROOT_GID,
								null,
								restorecon
							)
						) {
							// SELinux before reload
							DaemonFileUtils.restorecon(restorecon);
							restorecon.clear();
							// Reload the MTA
							reloadMTA();
						}
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			logger.log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static final Object reloadLock = new Object();
	private static void reloadMTA() throws IOException, SQLException {
		synchronized(reloadLock) {
			OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
				PackageManager.installPackage(PackageManager.PackageName.PSMISC);
				AOServDaemon.exec("/usr/bin/killall", "-HUP", "sendmail");
			} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
				File pidFile = new File("/run/sendmail.pid");
				if(pidFile.exists()) {
					// Install package for /usr/bin/kill
					PackageManager.installPackage(PackageManager.PackageName.UTIL_LINUX);
					// Read PID from first line of sendmail.pid
					int pid;
					try(BufferedReader pidIn = new BufferedReader(new InputStreamReader(new FileInputStream(pidFile)))) {
						pid = Integer.parseInt(pidIn.readLine());
					}
					AOServDaemon.exec("/usr/bin/kill", "-HUP", Integer.toString(pid));
				}
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
	}

	public static void start() throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(EmailDomainManager.class)
				&& emailDomainManager == null
			) {
				System.out.print("Starting EmailDomainManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					emailDomainManager = new EmailDomainManager();
					conn.getEmail().getDomain().addTableListener(emailDomainManager, 0);
					PackageManager.addPackageListener(emailDomainManager);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Email Domains";
	}
}
