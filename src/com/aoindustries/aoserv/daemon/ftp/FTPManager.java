/*
 * Copyright 2001-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.ftp;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.FTPGuestUser;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.NetProtocol;
import com.aoindustries.aoserv.client.NetTcpRedirect;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PrivateFTPServer;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdSiteManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the building of FTP configs and files.
 */
final public class FTPManager extends BuilderThread {

    private static final UnixFile
        newProFtpdConf=new UnixFile("/etc/proftpd.conf.new"),
        proFtpdConf=new UnixFile("/etc/proftpd.conf")
    ;

    private static final UnixFile
        vsFtpdConfNew = new UnixFile("/etc/vsftpd/vsftpd.conf.new"),
        vsFtpdConf = new UnixFile("/etc/vsftpd/vsftpd.conf"),
        vsFtpdVhostsirectory = new UnixFile("/etc/vsftpd/vhosts"),
        vsFtpdChrootList = new UnixFile("/etc/vsftpd/chroot_list"),
        vsFtpdChrootListNew = new UnixFile("/etc/vsftpd/chroot_list.new")
    ;

    /**
     * The directory that is used for site-independant FTP access.
     */
    private static final UnixFile sharedFtpDirectory = new UnixFile("/var/ftp/pub");

    private static FTPManager ftpManager;

    private FTPManager() {
    }

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();
            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            synchronized(rebuildLock) {
                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    doRebuildProFtpd();
                } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                    doRebuildVsFtpd();
                } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

                doRebuildSharedFtpDirectory();
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(FTPManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    /**
     * Rebuilds a ProFTPD installation.
     */
    private static void doRebuildProFtpd() throws IOException, SQLException {
        AOServConnector conn=AOServDaemon.getConnector();
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            final Stat tempStat = new Stat();
            // Rebuild the /etc/proftpd.conf file
            int bindCount=0;
            ChainWriter out=new ChainWriter(new BufferedOutputStream(newProFtpdConf.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true)));
            try {
                out.print("#\n"
                        + "# Automatically generated by ").print(FTPManager.class.getName()).print("\n"
                        + "#\n"
                        // Overall server settings
                        + "ServerName \"ProFTPD Server\"\n"
                        + "ServerIdent on \"ProFTPD Server [").print(thisAOServer.getHostname()).print("]\"\n"
                        + "ServerAdmin \"support@aoindustries.com\"\n"
                        + "ServerType standalone\n"
                        + "DefaultServer off\n"
                        + "AllowStoreRestart on\n"
                        + "Port 0\n"
                        + "SocketBindTight on\n"
                        + "PassivePorts 49152 50175\n"
                        + "Umask 002\n"
                        + "MaxInstances 100\n"
                        + "User "+LinuxAccount.NOBODY+"\n"
                        + "Group "+LinuxGroup.NOGROUP+"\n"
                        + "<Directory />\n"
                        + "  AllowOverwrite on\n"
                        + "</Directory>\n"
                        + "TimeoutIdle 7200\n"
                        + "TimeoutNoTransfer 7200\n"
                        + "TimesGMT on\n"
                        + "PersistentPasswd off\n"
                        + "SystemLog /var/log/proftpd/proftpd.log\n"
                        + "<Global>\n"
                        + "  DefaultRoot ~ "+LinuxGroup.PROFTPD_JAILED+"\n"
                        + "</Global>\n"
                );

                for(NetBind bind : thisAOServer.getServer().getNetBinds(conn.getProtocols().get(Protocol.FTP))) {
                    NetTcpRedirect redirect=bind.getNetTcpRedirect();
                    PrivateFTPServer privateServer=bind.getPrivateFTPServer();
                    if(redirect!=null) {
                        if(privateServer!=null) throw new SQLException("NetBind allocated as both NetTcpRedirect and PrivateFTPServer: "+bind.getPkey());
                    } else {
                        String netProtocol=bind.getNetProtocol().getProtocol();
                        if(!netProtocol.equals(NetProtocol.TCP)) throw new SQLException("ProFTPD may only be configured for TCP service:  (net_binds.pkey="+bind.getPkey()+").net_protocol="+netProtocol);

                        IPAddress ia=bind.getIPAddress();
                        bindCount++;
                        out.print("<VirtualHost ").print(ia.getInetAddress().toString()).print(">\n"
                                + "  Port ").print(bind.getPort().getPort()).print('\n');
                        if(privateServer!=null) out.print("  TransferLog \"").print(privateServer.getLogfile()).print("\"\n");
                        out.print("  ServerIdent on \"ProFTPD Server [").print(
                            privateServer!=null
                            ? privateServer.getHostname()
                            : (
                                ia.getInetAddress().isUnspecified()
                                ? thisAOServer.getHostname()
                                : ia.getHostname()
                            )
                        ).print("]\"\n"
                                + "  AllowOverwrite on\n");
                        if(privateServer!=null) out.print("  ServerAdmin \"").print(privateServer.getEmail()).print("\"\n");
                        if(privateServer==null || privateServer.allowAnonymous()) {
                            out.print("  <Anonymous \"").print(privateServer==null?"~ftp":privateServer.getLinuxServerAccount().getHome()).print("\">\n"
                                    + "    User ftp\n"
                                    + "    Group ftp\n"
                                    + "    AllowOverwrite off\n"
                                    + "    UserAlias anonymous ftp\n"
                                    + "    MaxClients 20\n"
                                    + "    RequireValidShell off\n"
                                    + "    AnonRequirePassword off\n"
                                    + "    DisplayLogin welcome.msg\n"
                                    + "    DisplayFirstChdir .message\n"
                                    + "    <Limit WRITE>\n"
                                    + "      DenyAll\n"
                                    + "    </Limit>\n"
                                    + "  </Anonymous>\n");
                        }
                        out.print("</VirtualHost>\n");
                    }
                }
            } finally {
                out.flush();
                out.close();
            }

            // Move into place if different than existing
            boolean configChanged;
            if(proFtpdConf.getStat(tempStat).exists() && newProFtpdConf.contentEquals(proFtpdConf)) {
                newProFtpdConf.delete();
                configChanged=false;
            } else {
                newProFtpdConf.renameTo(proFtpdConf);
                configChanged=true;
            }

            // Enable/disable rc.d entry and start/stop process if change needed, otherwise restart if config changed
            UnixFile rcFile=new UnixFile("/etc/rc.d/rc3.d/S85proftpd");
            if(bindCount==0) {
                // Turn off proftpd completely if not already off
                if(rcFile.getStat(tempStat).exists()) {
                    AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/proftpd", "stop"});
                    AOServDaemon.exec(new String[] {"/sbin/chkconfig", "--del", "proftpd"});
                }
            } else {
                // Turn on proftpd if not already on
                if(!rcFile.getStat(tempStat).exists()) {
                    AOServDaemon.exec(new String[] {"/sbin/chkconfig", "--add", "proftpd"});
                    AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/proftpd", "start"});
                } else if(configChanged) {
                    AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/proftpd", "reload"});
                }
            }
        } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
    }

    /**
     * Rebuilds a vsftpd installation.
     */
    private static void doRebuildVsFtpd() throws IOException, SQLException {
        AOServConnector conn=AOServDaemon.getConnector();
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            // Reused below
            final Stat tempStat = new Stat();
            final ByteArrayOutputStream bout = new ByteArrayOutputStream();

            // Jailed users
            {
                bout.reset();
                ChainWriter out = new ChainWriter(bout);
                try {
                    for(FTPGuestUser ftpGuestUser : thisAOServer.getFTPGuestUsers()) {
                        out.print(ftpGuestUser.getLinuxAccount().getUsername().getUsername()).print('\n');
                    }
                } finally {
                    out.close();
                }
                byte[] newBytes = bout.toByteArray();

                // Only write to filesystem if missing or changed
                if(!vsFtpdChrootList.getStat(tempStat).exists() || !vsFtpdChrootList.contentEquals(newBytes)) {
                    FileOutputStream newOut = vsFtpdChrootListNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                    try {
                        newOut.write(newBytes);
                    } finally {
                        newOut.close();
                    }
                    vsFtpdChrootListNew.renameTo(vsFtpdChrootList);
                }
            }

            // Write the default config file
            {
                bout.reset();
                ChainWriter out = new ChainWriter(bout);
                try {
                    out.print("# BOOLEAN OPTIONS\n"
                            + "anonymous_enable=YES\n"
                            + "async_abor_enable=YES\n"
                            + "chroot_list_enable=YES\n"
                            + "connect_from_port_20=YES\n"
                            + "dirmessage_enable=YES\n"
                            + "hide_ids=YES\n"
                            + "local_enable=YES\n"
                            + "ls_recurse_enable=NO\n"
                            + "text_userdb_names=NO\n"
                            + "userlist_enable=YES\n"
                            + "write_enable=YES\n"
                            + "xferlog_enable=YES\n"
                            + "xferlog_std_format=YES\n"
                            + "\n"
                            + "# NUMERIC OPTIONS\n"
                            + "accept_timeout=60\n"
                            + "anon_max_rate=125000\n"
                            + "connect_timeout=60\n"
                            + "data_connection_timeout=7200\n"
                            + "idle_session_timeout=7200\n"
                            + "local_umask=002\n"
                            + "pasv_max_port=50175\n"
                            + "pasv_min_port=49152\n"
                            + "\n"
                            + "# STRING OPTIONS\n"
                            + "chroot_list_file=/etc/vsftpd/chroot_list\n"
                            + "ftpd_banner=FTP Server [").print(thisAOServer.getHostname()).print("]\n"
                            + "pam_service_name=vsftpd\n");
                } finally {
                    out.close();
                }
                byte[] newBytes = bout.toByteArray();

                // Only write to filesystem if missing or changed
                if(!vsFtpdConf.getStat(tempStat).exists() || !vsFtpdConf.contentEquals(newBytes)) {
                    FileOutputStream newOut = vsFtpdConfNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                    try {
                        newOut.write(newBytes);
                    } finally {
                        newOut.close();
                    }
                    vsFtpdConfNew.renameTo(vsFtpdConf);
                }
            }

            // Specific net_binds
            {
                // Make the vhosts directory if it doesn't exist
                if(!vsFtpdVhostsirectory.getStat(tempStat).exists()) {
                    vsFtpdVhostsirectory.mkdir(false, 0700, UnixFile.ROOT_UID, UnixFile.ROOT_GID);
                }
                
                // Find all the FTP binds
                List<NetBind> binds = thisAOServer.getServer().getNetBinds(conn.getProtocols().get(Protocol.FTP));

                // Keep a list of the files that were verified
                Set<String> existing = new HashSet<String>(binds.size()*4/3+1);

                // Write each config file
                for(NetBind bind : binds) {
                    NetTcpRedirect redirect=bind.getNetTcpRedirect();
                    PrivateFTPServer privateServer=bind.getPrivateFTPServer();
                    if(redirect!=null) {
                        if(privateServer!=null) throw new SQLException("NetBind allocated as both NetTcpRedirect and PrivateFTPServer: "+bind.getPkey());
                    } else {
                        String netProtocol=bind.getNetProtocol().getProtocol();
                        if(!netProtocol.equals(NetProtocol.TCP)) throw new SQLException("vsftpd may only be configured for TCP service:  (net_binds.pkey="+bind.getPkey()+").net_protocol="+netProtocol);
                        IPAddress ia=bind.getIPAddress();

                        // Write to buffer
                        bout.reset();
                        ChainWriter out = new ChainWriter(bout);
                        try {
                            out.print("# BOOLEAN OPTIONS\n"
                                    + "anonymous_enable=").print(privateServer==null || privateServer.allowAnonymous() ? "YES" : "NO").print("\n"
                                    + "async_abor_enable=YES\n"
                                    + "chroot_list_enable=YES\n"
                                    + "connect_from_port_20=YES\n"
                                    + "dirmessage_enable=YES\n"
                                    + "hide_ids=").print(privateServer==null || privateServer.allowAnonymous() ? "YES" : "NO").print("\n"
                                    + "local_enable=YES\n"
                                    + "ls_recurse_enable=NO\n"
                                    + "text_userdb_names=").print(privateServer==null || privateServer.allowAnonymous() ? "NO" : "YES").print("\n"
                                    + "userlist_enable=YES\n"
                                    + "write_enable=YES\n"
                                    + "xferlog_enable=YES\n"
                                    + "xferlog_std_format=YES\n"
                                    + "\n"
                                    + "# NUMERIC OPTIONS\n"
                                    + "accept_timeout=60\n"
                                    + "anon_max_rate=125000\n"
                                    + "connect_timeout=60\n"
                                    + "data_connection_timeout=7200\n"
                                    + "idle_session_timeout=7200\n"
                                    + "local_umask=002\n"
                                    + "pasv_max_port=50175\n"
                                    + "pasv_min_port=49152\n"
                                    + "\n"
                                    + "# STRING OPTIONS\n"
                                    + "chroot_list_file=/etc/vsftpd/chroot_list\n");
                            if(privateServer!=null) {
                                out.print("ftp_username=").print(privateServer.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername()).print('\n');
                            }
                            out.print("ftpd_banner=FTP Server [").print(
                                privateServer!=null
                                ? privateServer.getHostname()
                                : (
                                    ia.getInetAddress().isUnspecified()
                                    ? thisAOServer.getHostname()
                                    : ia.getHostname()
                                )
                            ).print("]\n"
                                    + "pam_service_name=vsftpd\n");
                            if(privateServer!=null) {
                                out.print("xferlog_file=").print(privateServer.getLogfile()).print('\n');
                            }
                        } finally {
                            out.close();
                        }
                        byte[] newBytes = bout.toByteArray();

                        // Only write to filesystem if missing or changed
                        String filename = "vsftpd_"+bind.getIPAddress().getInetAddress().toString()+"_"+bind.getPort().getPort()+".conf";
                        if(!existing.add(filename)) throw new SQLException("Filename already used: "+filename);
                        UnixFile confFile = new UnixFile(vsFtpdVhostsirectory, filename, false);
                        if(!confFile.getStat(tempStat).exists() || !confFile.contentEquals(newBytes)) {
                            UnixFile newConfFile = new UnixFile(vsFtpdVhostsirectory, filename+".new", false);
                            FileOutputStream newOut = newConfFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                            try {
                                newOut.write(newBytes);
                            } finally {
                                newOut.close();
                            }
                            newConfFile.renameTo(confFile);
                        }
                    }
                }

                // Clean the vhosts directory
                for(String filename : vsFtpdVhostsirectory.list()) {
                    if(!existing.contains(filename)) {
                        new UnixFile(vsFtpdVhostsirectory, filename, false).delete();
                    }
                }
            }
        } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
    }

    /**
     * Rebuilds the contents of /var/cvs  Each site optinally gets its own
     * shared FTP space.
     */
    private static void doRebuildSharedFtpDirectory() throws IOException, SQLException {
        List<File> deleteFileList=new ArrayList<File>();

        String[] list = sharedFtpDirectory.list();
        Set<String> ftpDirectories = new HashSet<String>(list.length*4/3+1);
		ftpDirectories.addAll(Arrays.asList(list));

		for(HttpdSite httpdSite : AOServDaemon.getThisAOServer().getHttpdSites()) {
            HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);

            /*
             * Make the private FTP space, if needed.
             */
            if(manager.enableAnonymousFtp()) {
                String siteName = httpdSite.getSiteName();
                manager.configureFtpDirectory(new UnixFile(sharedFtpDirectory, siteName, false));
                ftpDirectories.remove(siteName);
            }
        }

        File sharedFtpDirectoryFile = sharedFtpDirectory.getFile();
        for(String filename : ftpDirectories) deleteFileList.add(new File(sharedFtpDirectoryFile, filename));

        // Back-up and delete the files scheduled for removal
        BackupManager.backupAndDeleteFiles(deleteFileList);
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5_DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5_DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(FTPManager.class)
                && ftpManager==null
            ) {
                System.out.print("Starting FTPManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                ftpManager=new FTPManager();
                conn.getFtpGuestUsers().addTableListener(ftpManager, 0);
                conn.getHttpdSites().addTableListener(ftpManager, 0);
                conn.getIpAddresses().addTableListener(ftpManager, 0);
                conn.getLinuxAccounts().addTableListener(ftpManager, 0);
                conn.getLinuxServerAccounts().addTableListener(ftpManager, 0);
                conn.getNetBinds().addTableListener(ftpManager, 0);
                conn.getPrivateFTPServers().addTableListener(ftpManager, 0);
                conn.getUsernames().addTableListener(ftpManager, 0);
                System.out.println("Done");
            }
        }
    }

    /**
     * Removes any file in the directory that is not listed in <code>files</code>.
     */
    public static void trimFiles(UnixFile dir, String[] files) throws IOException {
        String[] list=dir.list();
        if(list!=null) {
            final Stat tempStat = new Stat();
            int len=list.length;
            int flen=files.length;
            for(int c=0;c<len;c++) {
                String filename=list[c];
                boolean found=false;
                for(int d=0;d<flen;d++) {
                    if(filename.equals(files[d])) {
                        found=true;
                        break;
                    }
                }
                if(!found) {
                    UnixFile file=new UnixFile(dir, filename, false);
                    if(file.getStat(tempStat).exists()) file.delete();
                }
            }
        }
    }

    /**
     * Removes any file in the directory that is not listed in <code>files</code>.
     */
    public static void trimFiles(UnixFile dir, List<String> files) throws IOException {
        String[] SA=new String[files.size()];
        files.toArray(SA);
        trimFiles(dir, SA);
    }

    public String getProcessTimerDescription() {
        return "Rebuild FTP";
    }
}
