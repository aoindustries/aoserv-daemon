/*
 * Copyright 2002-2013, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.cvsd;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.CvsRepository;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the building of CVS repositories, xinetd configs are handled by XinetdManager.
 *
 * @author  AO Industries, Inc.
 */
final public class CvsManager extends BuilderThread {

	public static final String CVS_DIRECTORY = "/var/cvs";

	private static CvsManager cvsManager;

	private CvsManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				List<CvsRepository> cvsRepositories = thisAoServer.getCvsRepositories();
				// Install RPM when at least one CVS repository is configured
				if(
					!cvsRepositories.isEmpty()
					&& (
						osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
						|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
					)
				) {
					// Install any required RPMs
					PackageManager.installPackage(PackageManager.PackageName.CVS);
				}

				// Get a list of all the directories in /var/cvs
				File cvsDir = new File(CVS_DIRECTORY);
				if(!cvsDir.exists()) new UnixFile(cvsDir).mkdir(false, 0755, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
				String[] list = cvsDir.list();
				int listLen = list.length;
				Set<String> existing = new HashSet<>(listLen);
				for(int c = 0; c < listLen; c++) existing.add(CVS_DIRECTORY + '/' + list[c]);

				// Add each directory that doesn't exist, fix permissions and ownerships, too
				// while removing existing directories from existing
				for(CvsRepository cvs : cvsRepositories) {
					UnixPath path = cvs.getPath();
					UnixFile cvsUF = new UnixFile(path.toString());
					LinuxServerAccount lsa = cvs.getLinuxServerAccount();
					{
						Stat cvsStat = cvsUF.getStat();
						long cvsMode = cvs.getMode();
						// Make the directory
						if(!cvsStat.exists()) {
							cvsUF.mkdir(true, cvsMode);
							cvsStat = cvsUF.getStat();
						}
						// Set the mode
						if(cvsStat.getMode() != cvsMode) {
							cvsUF.setMode(cvsMode);
							cvsStat = cvsUF.getStat();
						}
						// Set the owner and group
						int uid = lsa.getUid().getId();
						int gid = cvs.getLinuxServerGroup().getGid().getId();
						if(uid != cvsStat.getUid() || gid != cvsStat.getGid()) {
							cvsUF.chown(uid, gid);
							// Unused here, no need to re-stat: cvsStat = cvsUF.getStat();
						}
					}
					// Init if needed
					UnixFile cvsRootUF = new UnixFile(cvsUF, "CVSROOT", false);
					if(!cvsRootUF.getStat().exists()) {
						AOServDaemon.suexec(
							lsa.getLinuxAccount().getUsername().getUsername(),
							"/usr/bin/cvs -d " + path + " init",
							0
						);
					}
					// Remove from list
					existing.remove(path.toString());
				}

				/*
				 * Back up the files scheduled for removal.
				 */
				int svLen = existing.size();
				if(svLen > 0) {
					List<File> deleteFileList = new ArrayList<>(svLen);
					for(String deleteFilename : existing) deleteFileList.add(new File(deleteFilename));

					// Get the next backup filename
					File backupFile = BackupManager.getNextBackupFile();
					BackupManager.backupFiles(deleteFileList, backupFile);

					/*
					 * Remove the files that have been backed up.
					 */
					int uid_min = thisAoServer.getUidMin().getId();
					int gid_min = thisAoServer.getGidMin().getId();
					for(int c = 0; c < svLen; c++) {
						File file = deleteFileList.get(c);
						new UnixFile(file.getPath()).secureDeleteRecursive(uid_min, gid_min);
					}
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(CvsManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(CvsManager.class)
				&& cvsManager == null
			) {
				System.out.print("Starting CvsManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					cvsManager = new CvsManager();
					conn.getCvsRepositories().addTableListener(cvsManager, 0);
					conn.getLinuxServerAccounts().addTableListener(cvsManager, 0);
					conn.getLinuxServerGroups().addTableListener(cvsManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild CVS";
	}
}
