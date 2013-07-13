/*
 * Copyright 2001-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.distro;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServTable;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.DistroFile;
import com.aoindustries.aoserv.client.DistroFileTable;
import com.aoindustries.aoserv.client.DistroFileType;
import com.aoindustries.aoserv.client.DistroReportType;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.SQLColumnValue;
import com.aoindustries.aoserv.client.SQLComparator;
import com.aoindustries.aoserv.client.SQLExpression;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.util.logging.ProcessTimer;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.md5.MD5;
import com.aoindustries.md5.MD5Utils;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verifies the server distribution.
 *
 * @author  AO Industries, Inc.
 */
final public class DistroManager implements Runnable {

    /**
     * The number of entries in a directory that will be reported as a warning.
     */
    private static final int DIRECTORY_LENGTH_WARNING=100000;

    /**
     * The time between distro verifications.
     */
    private static final int DISTRO_INTERVAL=2*24*60*60*1000;

    private static final int MAX_SLEEP_TIME=5*60*1000;

    private static Thread thread;

	private static final String EOL = System.getProperty("line.separator");

    public static void startDistro(boolean includeUser) {
        DistroManager.includeUser=includeUser;
		Thread t = thread;
        if(isSleeping && t!=null) {
            runNow=true;
            t.interrupt();
        }
    }

    private DistroManager() {
    }

    public static void start() throws IOException {
        if(AOServDaemonConfiguration.isManagerEnabled(DistroManager.class) && thread==null) {
            synchronized(System.out) {
                if(thread==null) {
                    System.out.print("Starting DistroManager: ");
                    thread=new Thread(new DistroManager());
                    thread.setDaemon(true);
                    thread.start();
                    System.out.println("Done");
                }
            }
        }
    }

    private static volatile boolean isSleeping=false;
    private static volatile boolean includeUser;
    private static volatile boolean runNow=false;

	@Override
    public void run() {
        while(true) {
            try {
                while(true) {
                    // Wait 10 minutes before checking again
                    includeUser=true;
                    isSleeping=true;
                    runNow=false;
                    try {
                        Thread.sleep(10L*60*1000);
                    } catch(InterruptedException err) {
                        // Normal from startDistro method
                    }
                    isSleeping=false;

                    // It is time to run if it is the backup hour and the backup has not been run for at least 12 hours
                    AOServer thisAOServer=AOServDaemon.getThisAOServer();
                    long distroStartTime=System.currentTimeMillis();
                    Timestamp lastDistroTime=thisAOServer.getLastDistroTime();
                    Logger logger = LogFactory.getLogger(DistroManager.class);
                    boolean isFiner = logger.isLoggable(Level.FINER);
                    if(isFiner) {
                        logger.finer("runNow="+runNow);
                        logger.finer("distroStartTime="+distroStartTime);
                        logger.finer("lastDistroTime="+lastDistroTime);
                    }
                    if(runNow || lastDistroTime==null || lastDistroTime.getTime()>distroStartTime || (distroStartTime-lastDistroTime.getTime())>=12L*60*60*1000) {
                        int distroHour=thisAOServer.getDistroHour();
                        Calendar cal=Calendar.getInstance();
                        cal.setTimeInMillis(distroStartTime);
                        int currentHour = cal.get(Calendar.HOUR_OF_DAY);
                        if(isFiner) {
                            logger.finer("distroHour="+distroHour);
                            logger.finer("currentHour="+currentHour);
                        }
                        if(runNow || currentHour==distroHour) {
                            ProcessTimer timer=new ProcessTimer(
                                LogFactory.getLogger(DistroManager.class),
                                AOServDaemon.getRandom(),
                                DistroManager.class.getName(),
                                "run",
                                "Distro verification taking too long",
                                "Distro Verification",
                                12*60*60*1000,
                                60*60*1000
                            );
                            try {
                                AOServDaemon.executorService.submit(timer);

                                AOServDaemon.getThisAOServer().setLastDistroTime(new Timestamp(distroStartTime));

								DistroReportStats stats = new DistroReportStats();
                                List<DistroReportFile> results = checkFilesystem(stats, null);

								// Report the results to the master server
								// TODO

								// Compile the counters for each of the different codes
								/*
								HashMap codes=new HashMap();
								for(int c=0;c<size;c++) {
									String code=results.get(c).substring(0,2);
									int[] count=(int[])codes.get(code);
									if(count==null) codes.put(code, count=new int[1]);
									count[0]++;
								}*/
                            } finally {
                                timer.finished();
                            }
                        }
                    }
                }
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(Throwable T) {
                LogFactory.getLogger(DistroManager.class).log(Level.SEVERE, null, T);
                try {
                    Thread.sleep(MAX_SLEEP_TIME);
                } catch(InterruptedException err) {
                    LogFactory.getLogger(DistroManager.class).log(Level.WARNING, null, err);
                }
            }
        }
    }

	private static class DistroReportStats {
		/**
		 * The time the scan started.
		 */
		private long startTime;

		/**
		 * The time the scan ended.
		 */
		private long endTime;

		/**
		 * Total number of objects checked.
		 */
		private long scanned;

		/**
		 * Total number of system objects checked.
		 */
		private long systemCount;

		/**
		 * Total number of user objects checked.
		 */
		private long userCount;

		/**
		 * Total number of non-recurse directories.
		 */
		private long noRecurseCount;

		/**
		 * Total number of files PRELINK verified.
		 * TODO: Get stats
		 */
		private long prelinkFiles;

		/**
		 * Total number of bytes PRELINK verified.
		 * TODO: Get stats
		 */
		private long prelinkBytes;

		/**
		 * Total number of files MD5 verified.
		 */
		private long md5Files;

		/**
		 * Total number of bytes MD5 hashed.
		 */
		private long md5Bytes;
	}

	private static class DistroReportFile {
		private final String type;
		private final String path;
		private final String actualValue;
		private final String expectedValue;
		private final String recommendedAction;
		private DistroReportFile(String type, String path, String actualValue, String expectedValue, String recommendedAction) {
			this.type = type;
			this.path = path;
			this.actualValue = actualValue;
			this.expectedValue = expectedValue;
			this.recommendedAction = recommendedAction;
		}
	}
	/**
	 * Writes a value, surrounding by quotes if it contains any spaces.
	 *
	 * @param value  the value to print, nothing is output when null
	 */
	private static void writeQuoteIfNeeded(String value, Appendable out) throws IOException {
		if(value!=null) {
			boolean hasSpace = value.indexOf(' ') != -1;
			if(hasSpace) out.append('\'');
			out.append(value);
			if(hasSpace) out.append('\'');
		}
	}

	/**
	 * Adds a report line, displaying to provided error printer if non-null.
	 */
	private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, String path, String actualValue, String expectedValue, String recommendedAction) throws IOException {
		DistroReportFile report = new DistroReportFile(type, path, actualValue, expectedValue, recommendedAction);
		results.add(report);
		if(verboseOut!=null) {
			if(recommendedAction!=null) {
				verboseOut.append(recommendedAction);
			} else {
				verboseOut.append(type);
				verboseOut.append(' ');
				writeQuoteIfNeeded(path, verboseOut);
			}
			if(actualValue!=null || expectedValue!=null) {
				verboseOut.append(" # ");
				writeQuoteIfNeeded(actualValue, verboseOut);
				if(expectedValue!=null) {
					verboseOut.append(" != ");
					writeQuoteIfNeeded(expectedValue, verboseOut);
				}
			}
			verboseOut.append(EOL);
			//verboseOut.flush();
		}
	}
	private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, String path, String actualValue, String expectedValue) throws IOException {
		addResult(results, verboseOut, type, path, actualValue, expectedValue, null);
	}
	private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, String path) throws IOException {
		addResult(results, verboseOut, type, path, null, null, null);
	}
	private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, UnixFile file, String actualValue, String expectedValue, String recommendedAction) throws IOException {
		addResult(results, verboseOut, type, file.getPath(), actualValue, expectedValue, recommendedAction);
	}
	private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, UnixFile file, String actualValue, String expectedValue) throws IOException {
		addResult(results, verboseOut, type, file.getPath(), actualValue, expectedValue, null);
	}
	private static void addResult(List<DistroReportFile> results, Appendable verboseOut, String type, UnixFile file) throws IOException {
		addResult(results, verboseOut, type, file.getPath(), null, null, null);
	}

	private static List<DistroReportFile> checkFilesystem(DistroReportStats stats, Appendable verboseOut) throws IOException, SQLException {
		stats.startTime = System.currentTimeMillis();
		try {
			// Build the list of files that should exist
			AOServConnector conn=AOServDaemon.getConnector();
			DistroFileTable distroFileTable=conn.getDistroFiles();
			// Getting this list provides a single, immutable, consistent snap-shot of the information
			List<DistroFile> distroFiles=distroFileTable.getRows();
			boolean[] foundFiles=new boolean[distroFiles.size()];

			// The comparator used for the searches
			SQLComparator pathComparator=new SQLComparator(
				conn,
				new SQLExpression[] {
					new SQLColumnValue(conn, distroFileTable.getTableSchema().getSchemaColumn(conn, DistroFile.COLUMN_PATH)),
					new SQLColumnValue(conn, distroFileTable.getTableSchema().getSchemaColumn(conn, DistroFile.COLUMN_OPERATING_SYSTEM_VERSION))
				},
				new boolean[] {AOServTable.ASCENDING, AOServTable.ASCENDING}
			);

			// Verify all the files, from the root to the lowest directory, accumulating the results in the results List
			List<DistroReportFile> results=new ArrayList<>();
			checkDistroFile(
				AOServDaemon.getThisAOServer(),
				Integer.valueOf(AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey()),
				distroFiles,
				foundFiles,
				pathComparator,
				new UnixFile("/"),
				results,
				stats,
				verboseOut
			);

			// Add entries for all the missing files
			String lastPath=null;
			int size=foundFiles.length;
			for(int c=0;c<size;c++) {
				if(!foundFiles[c]) {
					DistroFile distroFile=distroFiles.get(c);
					if(!distroFile.isOptional()) {
						String path=distroFile.getPath();
						if(lastPath==null || !path.startsWith(lastPath)) {
							addResult(results, verboseOut, DistroReportType.MISSING, path);
							lastPath=path;
						}
					}
				}
			}
			if(stats.scanned != (stats.systemCount + stats.userCount + stats.noRecurseCount)) throw new AssertionError();
	        return results;
		} finally {
			stats.endTime = System.currentTimeMillis();
		}
    }

	/**
     * BIG_DIRECTORY Big Directory
     * EX Extra
     * GROUP_MISMATCH Group Mismatch
     * HIDDEN Hidden "..." or " " directory
     * LENGTH Length
     * MD5_MISMATCH MD5
     * MISSING Missing
     * OWNER_MISMATCH Owner Mismatch
     * NO_OWNER No Owner
     * NO_GROUP No Group
     * PERMISSIONS Permissions
     * SETUID User SetUID
     * SYMLINK Symlink
     * TYPE Type
     */
    @SuppressWarnings({"unchecked"})
    private static void checkDistroFile(
        AOServer aoServer,
        Integer osVersionPKey,
        List<DistroFile> distroFiles,
        boolean[] foundFiles,
        SQLComparator pathComparator,
        UnixFile file,
        List<DistroReportFile> results,
		DistroReportStats stats,
        Appendable verboseOut
    ) throws IOException, SQLException {
		stats.scanned++;
		stats.systemCount++;
        // Check for ...
        String name=file.getFile().getName();
        if(isHidden(name)) {
			addResult(
				results,
				verboseOut,
				DistroReportType.HIDDEN,
				file
			);
        }
		// Find the matching DistroFile
		DistroFile distroFile;
		{
			// First look for exact match
			String filename=file.getPath();
			int index = Collections.binarySearch(distroFiles, new Object[] {filename, osVersionPKey}, pathComparator);
			if(index>=0) {
				distroFile = distroFiles.get(index);
				// Flag as found
				foundFiles[index] = true;
			} else {
				// Check for hostname substitution
				String hostname = aoServer.getHostname().toString();
				int pos = filename.indexOf(hostname);
				if(pos>=0) {
					filename = filename.substring(0, pos)+"$h"+filename.substring(pos+hostname.length());
					index = Collections.binarySearch(distroFiles, new Object[] {filename, osVersionPKey}, pathComparator);
					if(index>=0) {
						distroFile = distroFiles.get(index);
						// Flag as found
						foundFiles[index] = true;
					} else {
						distroFile = null;
					}
				} else {
					distroFile = null;
				}
			}
		}

        // Stat here for use below
        Stat fileStat = file.getStat();

        if(distroFile==null) {
            // Should not be here
            addResult(
				results,
				verboseOut,
				DistroReportType.EXTRA,
				file,
				null,
				null,
				"rm '" + file + "' # " + (fileStat.isDirectory() ? "-rf" : "-f")
			);
        } else {
			// Check owner
			int fileUID=fileStat.getUid();
			LinuxAccount la=distroFile.getLinuxAccount();
			LinuxServerAccount lsa=la.getLinuxServerAccount(aoServer);
			if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount for "+la+" on "+aoServer+", path="+file);
			int distroUID=lsa.getUid().getID();
			if(fileUID!=distroUID) {
				addResult(
					results,
					verboseOut,
					DistroReportType.OWNER_MISMATCH,
					file,
					Integer.toString(fileUID),
					Integer.toString(distroUID),
					"chown "+distroUID+" '"+file+'\''
				);
			}

			// Check group
			int fileGID=fileStat.getGid();
			LinuxGroup lg = distroFile.getLinuxGroup();
			LinuxServerGroup lsg = lg.getLinuxServerGroup(aoServer);
			if(lsg==null) throw new SQLException("Unable to find LinuxServerGroup for "+lg+" on "+aoServer+", path="+file);
			int distroGID=lsg.getGid().getID();
			if(fileGID!=distroGID) {
				addResult(
					results,
					verboseOut,
					DistroReportType.GROUP_MISMATCH,
					file,
					Integer.toString(fileGID),
					Integer.toString(distroGID),
					"chgrp "+distroGID+" '"+file+'\''
				);
			}

            // Type
            long fileMode=fileStat.getRawMode();
            long distroMode=distroFile.getMode();
            long fileType=fileMode&UnixFile.TYPE_MASK;
            long distroType=distroMode&UnixFile.TYPE_MASK;
            if(fileType!=distroType) {
				addResult(
					results,
					verboseOut,
					DistroReportType.TYPE,
					file,
					UnixFile.getModeString(fileType),
					UnixFile.getModeString(distroType)
				);
            } else {
				// Permissions
				long filePerms=fileMode&UnixFile.PERMISSION_MASK;
				long distroPerms=distroMode&UnixFile.PERMISSION_MASK;
				if(filePerms!=distroPerms) {
					addResult(
						results,
						verboseOut,
						DistroReportType.PERMISSIONS,
						file,
						Long.toOctalString(filePerms),
						Long.toOctalString(distroPerms),
						"chmod "+Long.toOctalString(distroPerms)+" '"+file+'\''
					);
				}
            }

            // Symlinks
            if(fileStat.isSymLink()) {
                String distroLink=distroFile.getSymlinkTarget();
                if(distroLink!=null) {
                    String fileLink=file.readLink();
                    // Allow multiple destinations separated by |
                    String[] dests=StringUtility.splitString(distroLink, '|');
                    boolean found=false;
                    for(int c=0;c<dests.length;c++) {
                        if(dests[c].equals(fileLink)) {
                            found=true;
                            break;
                        }
                    }
                    if(!found) {
						addResult(
							results,
							verboseOut,
							DistroReportType.SYMLINK,
							file,
							fileLink,
							distroLink,
							"rm -f '"+file+"'; ln -s '"+distroLink+"' '"+file+'\''
						);
                    }
                }
            } else {
                if(
                    !fileStat.isBlockDevice()
                    && !fileStat.isCharacterDevice()
                    && !fileStat.isFifo()
                    && !fileStat.isSocket()
                ) {
                    String type=distroFile.getType().getType();
                    if(!fileStat.isDirectory()) {
                        if(!type.equals(DistroFileType.CONFIG)) {
                            if(type.equals(DistroFileType.PRELINK)) {
								// TODO: Also check length here
                                // Prelink MD5
                                long startTime=System.currentTimeMillis();

                                String md5;
                                try {
                                    md5 = getPrelinkMD5(file);
                                } catch(IOException err) {
                                    LogFactory.getLogger(DistroManager.class).log(Level.INFO, "IOException getting MD5 from prelink, will now re-prelink and retry getting MD5", err);
                                    // Re-prelink
                                    AOServDaemon.exec(new String[] {"/usr/sbin/prelink", file.getPath()});
                                    // Retry
                                    md5 = getPrelinkMD5(file);
                                }
                                long file_md5_hi=MD5.getMD5Hi(md5);
                                long file_md5_lo=MD5.getMD5Lo(md5);
                                long distro_md5_hi=distroFile.getFileMD5Hi();
                                long distro_md5_lo=distroFile.getFileMD5Lo();
                                if(
                                    file_md5_hi!=distro_md5_hi
                                    || file_md5_lo!=distro_md5_lo
                                ) {
									addResult(
										results,
										verboseOut,
										DistroReportType.MD5,
										file,
										MD5.getMD5String(file_md5_hi, file_md5_lo),
										MD5.getMD5String(distro_md5_hi, distro_md5_lo)
									);
                                }

                                // Sleep for an amount of time equivilent to half the time it took to process this file
                                long timeSpan=(System.currentTimeMillis()-startTime)/2;
                                if(timeSpan<0) timeSpan=0;
                                else if(timeSpan>MAX_SLEEP_TIME) timeSpan=MAX_SLEEP_TIME;
                                if(timeSpan!=0) {
                                    try {
                                        Thread.sleep(timeSpan);
                                    } catch(InterruptedException err) {
                                        LogFactory.getLogger(DistroManager.class).log(Level.WARNING, null, err);
                                    }
                                }
                            } else if(type.equals(DistroFileType.SYSTEM)) {
                                // Length
                                long fileLen=file.getFile().length();
                                long distroLen=distroFile.getSize();
                                if(fileLen!=distroLen) {
									addResult(
										results,
										verboseOut,
										DistroReportType.LENGTH,
										file,
										Long.toString(fileLen),
										Long.toString(distroLen)
									);
                                } else {
                                    // MD5
									stats.md5Files++;
									stats.md5Bytes += fileLen;

									long startTime=System.currentTimeMillis();

                                    byte[] fileHash=MD5Utils.md5(file);
                                    long file_md5_hi=MD5.getMD5Hi(fileHash);
                                    long file_md5_lo=MD5.getMD5Lo(fileHash);
                                    long distro_md5_hi=distroFile.getFileMD5Hi();
                                    long distro_md5_lo=distroFile.getFileMD5Lo();
                                    if(
                                        file_md5_hi!=distro_md5_hi
                                        || file_md5_lo!=distro_md5_lo
                                    ) {
										addResult(
											results,
											verboseOut,
											DistroReportType.MD5,
											file,
											MD5.getMD5String(file_md5_hi, file_md5_lo),
											MD5.getMD5String(distro_md5_hi, distro_md5_lo)
										);
                                    }

                                    // Sleep for an amount of time equivilent to half the time it took to process this file
                                    long timeSpan=(System.currentTimeMillis()-startTime)/2;
                                    if(timeSpan<0) timeSpan=0;
                                    else if(timeSpan>MAX_SLEEP_TIME) timeSpan=MAX_SLEEP_TIME;
                                    if(timeSpan!=0) {
                                        try {
                                            Thread.sleep(timeSpan);
                                        } catch(InterruptedException err) {
                                            LogFactory.getLogger(DistroManager.class).log(Level.WARNING, null, err);
                                        }
                                    }
                                }
                            } else throw new RuntimeException("Unexpected value for type: "+type);
                        }
                    } else {
                        if(type.equals(DistroFileType.USER)) {
                            // Check as user directory
							stats.systemCount--;
                            if(includeUser) {
								stats.userCount++;
								checkUserDirectory(aoServer, file, results, stats, verboseOut);
							} else {
								stats.noRecurseCount++;
							}
                        } else {
                            if(type.equals(DistroFileType.NO_RECURSE)) {
								stats.systemCount--;
								stats.noRecurseCount++;
							} else {
                                // Recurse directory
                                String[] list=file.list();
                                if(list!=null) {
                                    Arrays.sort(list);
                                    int len=list.length;
                                    if(len>=DIRECTORY_LENGTH_WARNING) {
										addResult(
											results,
											verboseOut,
											DistroReportType.BIG_DIRECTORY,
											file,
											null,
											null,
											len+" >= "+DIRECTORY_LENGTH_WARNING
										);
                                    }
                                    for(int c=0;c<len;c++) {
                                        checkDistroFile(
                                            aoServer,
                                            osVersionPKey,
                                            distroFiles,
                                            foundFiles,
                                            pathComparator,
                                            new UnixFile(file, list[c], false),
                                            results,
											stats,
                                            verboseOut
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets the original filename MD5 using <code>/usr/sbin/prelink --verify --md5</code>
     */
    private static String getPrelinkMD5(UnixFile file) throws IOException {
        String[] command = {
            "/usr/sbin/prelink",
            "--verify",
            "--md5",
            file.getPath()
        };
        Process P = Runtime.getRuntime().exec(command);
        try {
            P.getOutputStream().close();
            BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()));
            try {
                String line = in.readLine();
                if(line.length()<32) throw new IOException("Line too short, must be at least 32 characters: "+line);
                return line.substring(0, 32);
            } finally {
                in.close();
            }
        } finally {
            try {
                int retCode = P.waitFor();
                if(retCode!=0) throw new IOException("Non-zero response from command: "+AOServDaemon.getCommandString(command));
            } catch(InterruptedException err) {
                IOException ioErr = new InterruptedIOException();
                ioErr.initCause(err);
                throw ioErr;
            }
        }
    }

    private static void checkUserDirectory(
        AOServer aoServer,
        UnixFile file,
        List<DistroReportFile> results,
		DistroReportStats stats,
        Appendable verboseOut
    ) throws IOException, SQLException {
        String[] list=file.list();
        if(list!=null) {
            Arrays.sort(list);
            int len=list.length;
            if(len>=DIRECTORY_LENGTH_WARNING) {
				addResult(
	                results,
					verboseOut,
					DistroReportType.BIG_DIRECTORY,
					file,
					null,
					null,
					len+" >= "+DIRECTORY_LENGTH_WARNING
				);
            }
            for(int c=0;c<len;c++) {
                try {
					stats.scanned++;
					stats.userCount++;
                    String name=list[c];
                    UnixFile uf=new UnixFile(file, name, false);
                    try {
                        // Check for ...
                        if(isHidden(name)) {
							addResult(
	                           results,
							   verboseOut,
							   DistroReportType.HIDDEN,
							   uf
						   );
                        }

                        // Stat here for use below
                        Stat ufStat = uf.getStat();

                        // Make sure is a valid user
                        int uid=ufStat.getUid();
                        if(aoServer.getLinuxServerAccount(uid)==null) {
							addResult(
								results,
								verboseOut,
								DistroReportType.NO_OWNER,
								uf,
								Integer.toString(uid),
								null
							);
                        }

                        // Make sure is a valid group
                        int gid=ufStat.getGid();
                        if(aoServer.getLinuxServerGroup(gid)==null) {
							addResult(
	                            results,
								verboseOut,
								DistroReportType.NO_GROUP,
								uf,
								Integer.toString(gid),
								null
							);
                        }

                        // Make sure not setUID or setGID
                        long fileMode=ufStat.getMode();
                        if(
                            (fileMode&(UnixFile.SET_UID|UnixFile.SET_GID))!=0
                            && (
                                uid<UnixFile.MINIMUM_USER_UID
                                || gid<UnixFile.MINIMUM_USER_GID
                            )
                        ) {
                            // Allow setUID for /etc/mail/majordomo/*/wrapper 4750 root.mail
                            boolean found=false;
                            String filename=uf.getPath();
                            if(
                                filename.length()>20
                                && filename.substring(0, 20).equals("/etc/mail/majordomo/")
                            ) {
                                int pos=filename.indexOf('/', 20);
                                if(pos!=-1) {
                                    String fname=filename.substring(pos+1);
                                    if(
                                        fname.equals("wrapper")
                                        && fileMode==04750
                                        && ufStat.getUid()==UnixFile.ROOT_UID
                                        && aoServer.getLinuxServerGroup(ufStat.getGid()).getLinuxGroup().getName().equals(LinuxGroup.MAIL)
                                    ) found=true;
                                }
                            }
                            if(!found) {
								addResult(
									results,
									verboseOut,
									DistroReportType.SETUID,
									uf,
									Long.toOctalString(fileMode),
									null
								);
                            }
                        }

                        // Make sure not world writable
                        //if((fileMode&UnixFile.OTHER_WRITE)==UnixFile.OTHER_WRITE) {
                        //    results.add("PERMISSIONS "+uf+" "+Integer.toOctalString(fileMode));
                        //    if(displayResults) {
                        //        System.out.println(results.get(results.size()-1));
                        //        System.out.flush();
                        //    }
                        //}

                        // Recurse
                        if(!ufStat.isSymLink() && ufStat.isDirectory()) {
							if(includeUser) {
	                            checkUserDirectory(aoServer, uf, results, stats, verboseOut);
							} else {
								stats.userCount--;
								stats.noRecurseCount++;
							}
                        }
                    } catch(RuntimeException err) {
                        LogFactory.getLogger(DistroManager.class).severe("RuntimeException while accessing: "+uf);
                        throw err;
                    }
                } catch(FileNotFoundException err) {
                    // File might be removed during the scan
                }
            }
        }
    }

	/**
	 * Checks if a filename is all spaces.
	 */
    private static boolean allSpace(String name) {
        int len=name.length();
        if(len==0) return false;
        for(int c=0;c<len;c++) {
            char ch=name.charAt(c);
            if(ch>' ') return false;
        }
        return true;
    }
    
	/**
	 * Determines if a filename possibly represents maliciously hidden content.
	 */
	private static boolean isHidden(String name) {
		return
            name.startsWith("...")
            || name.startsWith(".. ")
            || allSpace(name)
        ;
	}

	/**
     * Runs a scan immediately and displays the output to <code>System.out</code>.
     */
    public static void main(String[] args) {
		DistroReportStats stats = new DistroReportStats();
		PrintStream out = System.out;
        try {
			try {
				List<DistroReportFile> results = checkFilesystem(stats, out);
			} finally {
				out.flush();
			}
        } catch(IOException exc) {
            ErrorPrinter.printStackTraces(exc, System.err);
			System.err.flush();
        } catch(SQLException exc) {
            ErrorPrinter.printStackTraces(exc, System.err);
			System.err.flush();
        } finally {
			out.println("Time");
			out.print("  Start.....: "); out.println(new Date(stats.startTime).toString());
			out.print("  End.......: "); out.println(new Date(stats.endTime).toString());
			out.print("  Duration..: "); out.println(StringUtility.getDecimalTimeLengthString(stats.endTime -stats.startTime));
			out.println("Scan");
			out.print("  Total.....: "); out.println(stats.scanned);
			out.print("  System....: "); out.println(stats.systemCount);
			out.print("  User......: "); out.println(stats.userCount);
			out.print("  No Recurse: "); out.println(stats.noRecurseCount);
			out.println("Prelink");
			out.print("  Files.....: "); out.println(stats.prelinkFiles);
			out.print("  Bytes.....: "); out.print(stats.prelinkBytes); out.print(" ("); out.print(StringUtility.getApproximateSize(stats.prelinkBytes)); out.println(')');
			out.println("MD5");
			out.print("  Files.....: "); out.println(stats.md5Files);
			out.print("  Bytes.....: "); out.print(stats.md5Bytes); out.print(" ("); out.print(StringUtility.getApproximateSize(stats.md5Bytes)); out.println(')');
		}
    }
}