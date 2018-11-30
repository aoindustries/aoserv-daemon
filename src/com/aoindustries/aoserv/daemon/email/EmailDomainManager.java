/*
 * Copyright 2000-2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.Domain;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
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

/**
 * @author  AO Industries, Inc.
 */
public final class EmailDomainManager extends BuilderThread {

	private static final UnixFile configFile = new UnixFile("/etc/mail/local-host-names");

	private static EmailDomainManager emailDomainManager;

	private EmailDomainManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			Server thisAoServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					// Grab the list of domains from the database
					List<Domain> domains = thisAoServer.getEmailDomains();

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
								UnixFile.ROOT_UID,
								UnixFile.ROOT_GID,
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
			LogFactory.getLogger(EmailDomainManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static final Object reloadLock = new Object();
	private static void reloadMTA() throws IOException, SQLException {
		synchronized(reloadLock) {
			OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
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
		Server thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
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
					conn.getEmailDomains().addTableListener(emailDomainManager, 0);
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
