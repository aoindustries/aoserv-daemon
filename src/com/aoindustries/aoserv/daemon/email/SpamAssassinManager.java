package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2005-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailSpamAssassinIntegrationMode;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.WrappedException;
import com.aoindustries.util.sort.AutoSort;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * The primary purpose of the manager is to decouple the IMAP server from the SpamAssassin training.
 * The training process is slow and makes the IMAP server hesitate in ways that interfere with the
 * mail client and user.
 *
 * The SpamAssassin manager looks for emails left in a specific directories by the IMAP server.
 * When these files are found, the SpamAssassin training command (sa-learn) is invoked
 * on these directories.  The directories start with either ham_ or spam_ depending on
 * the type of messages.  In order to maintain the expected behavior, the directories
 * are processed in chronological order.  That way if a user drags a message to the Junk
 * folder then back to the INBOX, it will not be considered spam.
 *
 * To help avoid any race conditions, only directories that are at least 1 minute old (or in the future by 1 or more minutes
 * to handle clock changes) are considered on each pass.  This gives the IMAP server at least one minute to write all of
 * its files.
 *
 * Multiple directories from one user are sent to sa-learn at once when possible for efficiency.
 *
 * @author  AO Industries, Inc.
 */
public class SpamAssassinManager extends BuilderThread implements Runnable {

    /**
     * The interval to sleep after each pass.
     */
    private static final long DELAY_INTERVAL=(long)60*1000;

    /**
     * The directory containing the spam and ham directories.
     */
    private static final UnixFile incomingDirectory=new UnixFile("/var/spool/aoserv/spamassassin");

    private static SpamAssassinManager spamAssassinManager;

    private static final String[] restartCommand = {"/etc/rc.d/init.d/spamassassin", "restart"};

    private static final UnixFile
        configUnixFile = new UnixFile("/etc/sysconfig/spamassassin"),
        configUnixFileNew = new UnixFile("/etc/sysconfig/spamassassin")
    ;

    private SpamAssassinManager() {
    }

    public void run() {
        long lastStartTime=-1;
        while(true) {
            try {
                while(true) {
                    long delay;
                    if(lastStartTime==-1) delay=DELAY_INTERVAL;
                    else {
                        delay=lastStartTime+DELAY_INTERVAL-System.currentTimeMillis();
                        if(delay>DELAY_INTERVAL) delay=DELAY_INTERVAL;
                    }
                    if(delay>0) {
                        try {
                            Thread.sleep(DELAY_INTERVAL);
                        } catch(InterruptedException err) {
                            AOServDaemon.reportWarning(err, null);
                        }
                    }
                    lastStartTime=System.currentTimeMillis();
                    
                    // Process incoming messages
                    AOServer thisAOServer=AOServDaemon.getThisAOServer();
                    int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        processIncomingMessagesMandriva();
                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        processIncomingMessagesCentOs();
                    } else {
                        throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                }
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(Throwable T) {
                AOServDaemon.reportError(T, null);
                try {
                    Thread.sleep(60000);
                } catch(InterruptedException err) {
                    AOServDaemon.reportWarning(err, null);
                }
            }
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(SpamAssassinManager.class)
                && spamAssassinManager==null
            ) {
                System.out.print("Starting SpamAssassinManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                spamAssassinManager=new SpamAssassinManager();
                connector.linuxServerAccounts.addTableListener(spamAssassinManager, 0);
                connector.ipAddresses.addTableListener(spamAssassinManager, 0);
                new Thread(spamAssassinManager, "SpamAssassinManager").start();
                // Once per day, the razor logs will be trimmed to only include the last 1000 lines
                CronDaemon.addCronJob(new RazorLogTrimmer(), AOServDaemon.getErrorHandler());
                System.out.println("Done");
            }
        }
    }

    /**
     * The entire filename must be acceptable characters a-z, A-Z, 0-9, -, and _
     * This is, along with the character restrictions for usernames, are the key aspects
     * of the security of the following suexec call.
     */
    private static boolean isFilenameOk(String filename) {
        // Don't allow null
        if(filename==null) return false;
        
        // Don't allow empty string
        if(filename.length()==0) return false;

        for(int d=0;d<filename.length();d++) {
            char ch=filename.charAt(d);
            if(
                (ch<'a' || ch>'z')
                && (ch<'A' || ch>'Z')
                && (ch<'0' || ch>'9')
                && ch!='-'
                && ch!='_'
            ) {
                return false;
            }
        }
        return true;
    }

    private synchronized static void processIncomingMessagesMandriva() throws IOException, SQLException {
        String[] fileList=incomingDirectory.list();
        if(fileList!=null && fileList.length>0) {
            // Get the list of UnixFile's of all messages that are at least one minute old or one minute in the future
            List<UnixFile> readyList=new ArrayList<UnixFile>(fileList.length);
            for(int c=0;c<fileList.length;c++) {
                String filename=fileList[c];
                if(filename.startsWith("ham_") || filename.startsWith("spam_")) {
                    // Must be a directory
                    UnixFile uf=new UnixFile(incomingDirectory, filename, false);
                    Stat ufStat = uf.getStat();
                    if(ufStat.isDirectory()) {
                        long mtime=ufStat.getModifyTime();
                        if(mtime==-1) AOServDaemon.reportWarning(new IOException("getModify() returned -1"), new Object[] {"incomingDirectory="+incomingDirectory.getPath(), "filename="+filename});
                        else {
                            long currentTime=System.currentTimeMillis();
                            if(
                                (mtime-currentTime)>60000
                                || (currentTime-mtime)>60000
                            ) {
                                if(isFilenameOk(filename)) readyList.add(uf);
                                else AOServDaemon.reportWarning(new IOException("Invalid directory name"), new Object[] {"incomingDirectory="+incomingDirectory.getPath(), "filename="+filename});
                            }
                        }
                    } else AOServDaemon.reportWarning(new IOException("Not a directory"), new Object[] {"incomingDirectory="+incomingDirectory.getPath(), "filename="+filename});
                } else AOServDaemon.reportWarning(new IOException("Unexpected filename, should start with spam_ or ham_"), new Object[] {"incomingDirectory="+incomingDirectory.getPath(), "filename="+filename});
            }
            if(!readyList.isEmpty()) {
                // Sort the list by oldest time first
                AutoSort.sortStatic(
                    readyList,
                    new Comparator<UnixFile>() {
                        public int compare(UnixFile uf1, UnixFile uf2) {
                            try {
                                long mtime1=uf1.getStat().getModifyTime();
                                long mtime2=uf2.getStat().getModifyTime();
                                return
                                    mtime1<mtime2 ? -1
                                    : mtime1>mtime2 ? 1
                                    : 0
                                ;
                            } catch(IOException err) {
                                throw new WrappedException(
                                    err,
                                    new Object[] {
                                        "uf1="+uf1.getPath(),
                                        "uf2="+uf2.getPath()
                                    }
                                );
                            }
                        }
                    }
                );

                // Work through the list from oldest to newest, and for each user batching as many spam or ham directories together as possible
                AOServer aoServer=AOServDaemon.getThisAOServer();
                StringBuilder tempSB=new StringBuilder();
                List<UnixFile> thisPass=new ArrayList<UnixFile>();
                while(!readyList.isEmpty()) {
                    thisPass.clear();
                    UnixFile currentUF=readyList.get(0);
                    int currentUID=currentUF.getStat().getUID();
                    boolean currentIsHam=currentUF.getFile().getName().startsWith("ham_");
                    thisPass.add(currentUF);
                    readyList.remove(0);
                    for(int c=0;c<readyList.size();c++) {
                        UnixFile other=readyList.get(c);
                        // Only consider batching/terminating batching for same UID
                        if(other.getStat().getUID()==currentUID) {
                            boolean otherIsHam=other.getFile().getName().startsWith("ham_");
                            if(currentIsHam==otherIsHam) {
                                // If both spam or both ham, batch to one call and remove from later processing
                                thisPass.add(other);
                                readyList.remove(c);
                                c--;
                            } else {
                                // Mode for that user switched, termination batching loop
                                break;
                            }
                        }
                    }

                    // Find the account based on UID
                    LinuxServerAccount lsa=aoServer.getLinuxServerAccount(currentUID);
                    if(lsa==null) AOServDaemon.reportWarning(new SQLException("Unable to find LinuxServerAccount"), new Object[] {"aoServer="+aoServer.getHostname(), "currentUID="+currentUID});
                    else {
                        String username=lsa.getLinuxAccount().getUsername().getUsername();

                        // Only train SpamAssassin when integration mode not set to none
                        EmailSpamAssassinIntegrationMode integrationMode=lsa.getEmailSpamAssassinIntegrationMode();
                        if(!integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
                            // Call sa-learn for this pass
                            tempSB.setLength(0);
                            tempSB.append("/usr/bin/sa-learn ").append(currentIsHam?"--ham":"--spam").append(" --dir");
                            for(int c=0;c<thisPass.size();c++) {
                                UnixFile uf=thisPass.get(c);
                                tempSB.append(' ').append(uf.getPath());
                            }
                            AOServDaemon.suexec(
                                username,
                                tempSB.toString()
                            );
                        }

                        // Remove the files processed (or not processed based on integration mode) in this pass
                        for(int c=0;c<thisPass.size();c++) {
                            UnixFile uf=thisPass.get(c);
                            // Change the ownership and mode to make sure directory entries are not replaced during delete
                            uf.chown(UnixFile.ROOT_UID, UnixFile.ROOT_GID).setMode(0700);
                            String[] list=uf.list();
                            if(list!=null) {
                                // Delete all the immediate children, not recursing deeper
                                for(int d=0;d<list.length;d++) new UnixFile(uf, list[d], false).delete();
                            }
                            uf.delete();
                        }
                    }
                }
            }
        }
    }

    private synchronized static void processIncomingMessagesCentOs() throws IOException, SQLException {
        // Create the incomingDirectory if it doesn't exist
        Stat incomingStat = incomingDirectory.getStat();
        if(!incomingStat.exists()) {
            incomingDirectory.mkdir();
            incomingDirectory.getStat(incomingStat);
        }
        // Make sure mode 0755
        if(incomingStat.getMode()!=0755) {
            incomingDirectory.setMode(0755);
            incomingDirectory.getStat(incomingStat);
        }
        // Make sure user cyrus and group mail
        AOServer aoServer = AOServDaemon.getThisAOServer();
        LinuxServerAccount cyrus = aoServer.getLinuxServerAccount(LinuxAccount.CYRUS);
        if(cyrus==null) throw new SQLException("Unable to find LinuxServerAccount: "+LinuxAccount.CYRUS+" on "+aoServer);
        int cyrusUid = cyrus.getUID().getID();
        LinuxServerGroup mail = aoServer.getLinuxServerGroup(LinuxGroup.MAIL);
        if(mail==null) throw new SQLException("Unable to find LinuxServerGroup: "+LinuxAccount.MAIL+" on "+aoServer);
        int mailGid = mail.getGID().getID();
        if(incomingStat.getUID()!=cyrusUid || incomingStat.getGID()!=mailGid) {
            incomingDirectory.chown(cyrusUid, mailGid);
            incomingDirectory.getStat(incomingStat);
        }

        // Used on inner loop
        Stat userDirectoryUfStat = new Stat();
        List<File> deleteFileList=new ArrayList<File>();
        StringBuilder tempSB = new StringBuilder();
        List<UnixFile> thisPass = new ArrayList<UnixFile>(100);

        while(true) {
            // End loop if no subdirectories
            String[] incomingDirectoryList=incomingDirectory.list();
            if(incomingDirectoryList==null || incomingDirectoryList.length==0) break;

            // Find the username that has the oldest timestamp that is also at least one minute old or one minute in the future
            LinuxServerAccount oldestLsa = null;
            Map<UnixFile,Long> oldestReadyMap = null;
            long oldestTimestamp = -1;

            // The files will be backed-up before being deleted
            deleteFileList.clear();
            long currentTime = System.currentTimeMillis();

            for(String incomingDirectoryFilename : incomingDirectoryList) {
                UnixFile userDirectoryUf = new UnixFile(incomingDirectory, incomingDirectoryFilename, false);
                File userDirectoryFile = userDirectoryUf.getFile();

                // Each filename should be a username
                LinuxServerAccount lsa = aoServer.getLinuxServerAccount(incomingDirectoryFilename);
                if(lsa==null) {
                    // user not found, backup and then remove
                    AOServDaemon.reportWarning(new IOException("User not found, deleting"), new Object[] {"incomingDirectoryFilename="+incomingDirectoryFilename});
                    deleteFileList.add(userDirectoryFile);
                } else if(!lsa.getLinuxAccount().getType().isEmail()) {
                    // user not email type, backup and then remove
                    AOServDaemon.reportWarning(new IOException("User not email type, deleting"), new Object[] {"incomingDirectoryFilename="+incomingDirectoryFilename});
                    deleteFileList.add(userDirectoryFile);
                } else if(!lsa.getHome().startsWith("/home/")) {
                    // user doesn't have home directory in /home/, backup and then remove
                    AOServDaemon.reportWarning(new IOException("User home not in /home/, deleting"), new Object[] {"incomingDirectoryFilename="+incomingDirectoryFilename});
                    deleteFileList.add(userDirectoryFile);
                } else {
                    // Set permissions, should be 0770
                    userDirectoryUf.getStat(userDirectoryUfStat);
                    if(userDirectoryUfStat.getMode()!=0770) {
                        userDirectoryUf.setMode(0770);
                        userDirectoryUf.getStat(userDirectoryUfStat);
                    }
                    // Set ownership, should by username and group mail
                    int lsaUid = lsa.getUID().getID();
                    if(userDirectoryUfStat.getUID()!=lsaUid || userDirectoryUfStat.getGID()!=mailGid) {
                        userDirectoryUf.chown(lsaUid, mailGid);
                        userDirectoryUf.getStat(userDirectoryUfStat);
                    }
                    // Check each filename, searching if this lsa has the oldest timestamp (older or newer than one minute)
                    String[] userDirectoryList = userDirectoryUf.list();
                    if(userDirectoryList!=null && userDirectoryList.length>0) {
                        Map<UnixFile,Long> readyMap = new HashMap<UnixFile,Long>(userDirectoryList.length*4/3+1);
                        for(String userFilename : userDirectoryList) {
                            UnixFile userUf=new UnixFile(userDirectoryUf, userFilename, false);
                            File userFile = userUf.getFile();
                            if(userFilename.startsWith("ham_") || userFilename.startsWith("spam_")) {
                                // Must be a regular file
                                Stat userUfStat = userUf.getStat();
                                if(userUfStat.isRegularFile()) {
                                    int pos1 = userFilename.indexOf('_');
                                    if(pos1==-1) throw new AssertionError("pos1==-1"); // This should not happen because of check against ham_ or spam_ above.
                                    int pos2 = userFilename.indexOf('_', pos1+1);
                                    if(pos2!=-1) {
                                        try {
                                            long timestamp = Long.parseLong(userFilename.substring(pos1+1, pos2)) * 1000;
                                            if(
                                                (timestamp-currentTime)>60000
                                                || (currentTime-timestamp)>60000
                                            ) {
                                                if(isFilenameOk(userFilename)) {
                                                    readyMap.put(userUf, timestamp);
                                                    
                                                    // Is the oldest?
                                                    if(oldestLsa==null || timestamp < oldestTimestamp) {
                                                        oldestLsa = lsa;
                                                        oldestReadyMap = readyMap;
                                                        oldestTimestamp = timestamp;
                                                    }
                                                } else {
                                                    AOServDaemon.reportWarning(new IOException("Invalid character in filename, deleting"), new Object[] {"userDirectoryUf="+userDirectoryUf.getPath(), "userFilename="+userFilename});
                                                    deleteFileList.add(userFile);
                                                }
                                            }
                                        } catch(NumberFormatException err) {
                                            IOException ioErr = new IOException("Unable to find parse timestamp in filename, deleting");
                                            ioErr.initCause(err);
                                            AOServDaemon.reportWarning(ioErr, new Object[] {"userDirectoryUf="+userDirectoryUf.getPath(), "userFilename="+userFilename});
                                            deleteFileList.add(userFile);
                                        }
                                    } else {
                                        AOServDaemon.reportWarning(new IOException("Unable to find second underscore (_) in filename, deleting"), new Object[] {"userDirectoryUf="+userDirectoryUf.getPath(), "userFilename="+userFilename});
                                        deleteFileList.add(userFile);
                                    }
                                } else {
                                    AOServDaemon.reportWarning(new IOException("Not a regular file, deleting"), new Object[] {"userDirectoryUf="+userDirectoryUf.getPath(), "userFilename="+userFilename});
                                    deleteFileList.add(userFile);
                                }
                            } else {
                                AOServDaemon.reportWarning(new IOException("Unexpected filename, should start with \"spam_\" or \"ham_\", deleting"), new Object[] {"userDirectoryUf="+userDirectoryUf.getPath(), "userFilename="+userFilename});
                                deleteFileList.add(userFile);
                            }
                        }
                    }
                }
            }

            // Back up the files scheduled for removal.
            if(!deleteFileList.isEmpty()) {
                // Get the next backup filename
                File backupFile = BackupManager.getNextBackupFile();
                BackupManager.backupFiles(deleteFileList, backupFile);

                // Remove the files that have been backed up.
                for(File file : deleteFileList) new UnixFile(file).secureDeleteRecursive();
            }

            // Nothing to do, end loop to sleep
            if(oldestLsa==null) break;

            // Sort the list by oldest time first
            final Map<UnixFile,Long> readyMap = oldestReadyMap;
            List<UnixFile> readyList = new ArrayList<UnixFile>(oldestReadyMap.keySet());
            AutoSort.sortStatic(
                readyList,
                new Comparator<UnixFile>() {
                    public int compare(UnixFile uf1, UnixFile uf2) {
                        return readyMap.get(uf1).compareTo(readyMap.get(uf2));
                    }
                }
            );

            // Process the oldest file while batching as many spam or ham directories together as possible
            thisPass.clear();
            UnixFile firstUf = readyList.get(0);
            boolean firstIsHam = firstUf.getFile().getName().startsWith("ham_");
            thisPass.add(firstUf);
            for(int c=1;c<readyList.size();c++) {
                UnixFile other = readyList.get(c);
                boolean otherIsHam = other.getFile().getName().startsWith("ham_");
                if(firstIsHam == otherIsHam) {
                    // If both spam or both ham, batch to one call and remove from later processing
                    thisPass.add(other);
                    // Only train maximum 100 messages at a time
                    if(thisPass.size()>=100) break;
                } else {
                    // Mode for that user switched, termination batching loop
                    break;
                }
            }

            // Only train SpamAssassin when integration mode not set to none
            EmailSpamAssassinIntegrationMode integrationMode = oldestLsa.getEmailSpamAssassinIntegrationMode();
            if(!integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
                // Call sa-learn for this pass
                String username = oldestLsa.getLinuxAccount().getUsername().getUsername();
                tempSB.setLength(0);
                tempSB.append("/usr/bin/sa-learn");
                // The choice of 5 here is arbitrary, have not measured the performance of this versus other values.
                // This needs to balance the overhead of the additional exec versus the overhead of the sync.
                // This balance may consider that CPU is generally more plentiful than disk I/O.
                boolean isNoSync = thisPass.size() >= 5;
                if(isNoSync) tempSB.append(" --no-sync");
                tempSB.append(firstIsHam ? " --ham" : " --spam");
                for(UnixFile uf : thisPass) tempSB.append(' ').append(uf.getPath());
                String command = tempSB.toString();
                //System.err.println("DEBUG: "+SpamAssassinManager.class.getName()+": processIncomingMessagesCentOs: username="+username+" and command=\""+command+"\"");
                try {
                    AOServDaemon.suexec(
                        username,
                        command
                    );
                } finally {
                    if(isNoSync) {
                        String command2 = "/usr/bin/sa-learn --sync";
                        //System.err.println("DEBUG: "+SpamAssassinManager.class.getName()+": processIncomingMessagesCentOs: username="+username+" and command2=\""+command2+"\"");
                        AOServDaemon.suexec(
                            username,
                            command2
                        );
                    }
                }
            }

            // Remove the files processed (or not processed based on integration mode) in this pass
            for(UnixFile uf : thisPass) uf.delete();
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        AOServer aoServer=AOServDaemon.getThisAOServer();
        Server server = aoServer.getServer();

        int osv=server.getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

        final String primaryIP = aoServer.getPrimaryIPAddress().getIPAddress();

        /**
         * Build the /etc/sysconfig/..... file.
         */
        ByteArrayOutputStream bout=new ByteArrayOutputStream();
        {
            ChainWriter newOut=new ChainWriter(bout);
            try {
                // Build a new file in RAM
                newOut.print("#\n"
                           + "# Generated by ").print(SpamAssassinManager.class.getName()).print("\n"
                           + "#\n"
                           + "\n"
                           + "# Options to spamd\n"
                           + "SPAMDOPTIONS=\"-d -c -m25 -H -i ").print(primaryIP).print(" -A ");
                // Allow all IP addresses for this machine
                Set<String> usedIps = new HashSet<String>();
                List<IPAddress> ips = server.getIPAddresses();
                for(IPAddress ip : ips) {
                    if(!ip.isWildcard() && !ip.getNetDevice().getNetDeviceID().isLoopback()) {
                        String addr = ip.getIPAddress();
                        if(!usedIps.contains(addr)) {
                            if(!usedIps.isEmpty()) newOut.print(',');
                            newOut.print(addr);
                            usedIps.add(addr);
                        }
                    }
                }
                // Allow the primary IP of our current failover server
                /*
                AOServer failoverServer = server.getFailoverServer();
                if(failoverServer!=null) {
                    IPAddress foPrimaryIP = failoverServer.getPrimaryIPAddress();
                    if(foPrimaryIP==null) throw new SQLException("Unable to find Primary IP Address for failover server: "+failoverServer);
                    String addr = foPrimaryIP.getIPAddress();
                    if(!usedIps.contains(addr)) {
                        if(!usedIps.isEmpty()) newOut.print(',');
                        newOut.print(addr);
                        usedIps.add(addr);
                    }
                }*/
                newOut.print("\"\n"
                           + "\n"
                           + "# Run at nice level of 10\n"
                           + "NICELEVEL=\"+10\"\n");
            } finally {
                newOut.close();
            }
            byte[] newBytes=bout.toByteArray();
            // Compare to existing
            if(!configUnixFile.getStat().exists() || !configUnixFile.contentEquals(newBytes)) {
                // Replace when changed
                FileOutputStream out=new FileOutputStream(configUnixFileNew.getFile());
                try {
                    out.write(newBytes);
                } finally {
                    out.close();
                }
                configUnixFileNew.setMode(0644);
                configUnixFileNew.renameTo(configUnixFile);
                AOServDaemon.exec(restartCommand);
            }
        }

        /**
         * Build the spamassassin files per account.
         */
        List<LinuxServerAccount> lsas=aoServer.getLinuxServerAccounts();
        synchronized(rebuildLock) {
            for(int c=0;c<lsas.size();c++) {
                LinuxServerAccount lsa=lsas.get(c);
                // Only build spamassassin for accounts under /home/
                String homePath = lsa.getHome();
                if(lsa.getLinuxAccount().getType().isEmail() && homePath.startsWith("/home/")) {
                    EmailSpamAssassinIntegrationMode integrationMode=lsa.getEmailSpamAssassinIntegrationMode();
                    // Only write files when SpamAssassin is turned on
                    if(!integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.NONE)) {
                        UnixFile homeDir = new UnixFile(homePath);
                        UnixFile spamAssassinDir = new UnixFile(homeDir, ".spamassassin", false);
                        // Create the .spamassassin directory if it doesn't exist
                        if(!spamAssassinDir.getStat().exists()) {
                            spamAssassinDir.mkdir(
                                false,
                                0700,
                                lsa.getUID().getID(),
                                lsa.getPrimaryLinuxServerGroup().getGID().getID()
                            );
                        }
                        UnixFile userPrefs=new UnixFile(spamAssassinDir, "user_prefs", false);
                        // Build the new file in RAM
                        bout.reset();
                        ChainWriter newOut=new ChainWriter(bout);
                        try {
                            newOut.print("#\n"
                                       + "# Generated by ").print(SpamAssassinManager.class.getName()).print("\n"
                                       + "#\n"
                                       + "required_score ").print(lsa.getSpamAssassinRequiredScore()).print('\n');
                            if(integrationMode.getName().equals(EmailSpamAssassinIntegrationMode.POP3)) {
                                newOut.print("rewrite_header Subject *****SPAM*****\n");
                            }
                        } finally {
                            newOut.close();
                        }
                        byte[] newBytes=bout.toByteArray();

                        // Compare to existing
                        if(!userPrefs.getStat().exists() || !userPrefs.contentEquals(newBytes)) {
                            // Replace when changed
                            UnixFile userPrefsNew=new UnixFile(spamAssassinDir, "user_prefs.new", false);
                            FileOutputStream out=userPrefsNew.getSecureOutputStream(lsa.getUID().getID(), lsa.getPrimaryLinuxServerGroup().getGID().getID(), 0600, true);
                            try {
                                out.write(newBytes);
                            } finally {
                                out.close();
                            }
                            userPrefsNew.renameTo(userPrefs);
                        }
                    }
                }
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild SpamAssassin User Preferences";
    }

    @Override
    public long getProcessTimerMaximumTime() {
        return (long)30*60*1000;
    }
    
    public static class RazorLogTrimmer implements CronJob {
        
        private static final int NUM_LINES_RETAINED = 1000;

        public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
            return minute==5 && hour==1;
        }

        public int getCronJobScheduleMode() {
            return CRON_JOB_SCHEDULE_SKIP;
        }

        public String getCronJobName() {
            return "RazorLogTrimmer";
        }

        /**
         * Once a day, all of the razor-agent.log files are cleaned to only include the last 1000 lines.
         */
        public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
            try {
                Queue<String> queuedLines = new LinkedList<String>();
                for(LinuxServerAccount lsa : AOServDaemon.getThisAOServer().getLinuxServerAccounts()) {
                    // Only clean razor for accounts under /home/
                    String homePath = lsa.getHome();
                    if(lsa.getLinuxAccount().getType().isEmail() && homePath.startsWith("/home/")) {
                        UnixFile home = new UnixFile(homePath);
                        UnixFile dotRazor = new UnixFile(home, ".razor", false);
                        UnixFile razorAgentLog = new UnixFile(dotRazor, "razor-agent.log", false);
                        if(razorAgentLog.getStat().exists()) {
                            try {
                                boolean removed = false;
                                BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(razorAgentLog.getFile())));
                                try {
                                    queuedLines.clear();
                                    String line;
                                    while((line=in.readLine())!=null) {
                                        queuedLines.add(line);
                                        if(queuedLines.size()>NUM_LINES_RETAINED) {
                                            queuedLines.remove();
                                            removed=true;
                                        }
                                    }
                                } finally {
                                    in.close();
                                }
                                if(removed) {
                                    int uid = lsa.getUID().getID();
                                    int gid = lsa.getPrimaryLinuxServerGroup().getGID().getID();
                                    UnixFile tempFile = UnixFile.mktemp(razorAgentLog.getPath()+'.', false);
                                    try {
                                        PrintWriter out = new PrintWriter(new BufferedOutputStream(tempFile.getSecureOutputStream(uid, gid, 0644, true)));
                                        try {
                                            while(!queuedLines.isEmpty()) out.println(queuedLines.remove());
                                        } finally {
                                            out.close();
                                        }
                                        tempFile.renameTo(razorAgentLog);
                                    } finally {
                                        if(tempFile.getStat().exists()) tempFile.delete();
                                    }
                                }
                            } catch(IOException err) {
                                AOServDaemon.reportWarning(err, new Object[] {"lsa="+lsa});
                            }
                        }
                    }
                }
            } catch(RuntimeException err) {
                AOServDaemon.reportError(err, null);
            } catch(IOException err) {
                AOServDaemon.reportError(err, null);
            } catch(SQLException err) {
                AOServDaemon.reportError(err, null);
            }
        }

        public int getCronJobThreadPriority() {
            return Thread.MIN_PRIORITY;
        }
    }
}
