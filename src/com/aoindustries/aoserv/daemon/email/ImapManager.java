package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2008-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailSpamAssassinIntegrationMode;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxAccountTable;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.NetProtocol;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.SQLUtility;
import com.aoindustries.util.StringUtility;
import com.sun.mail.iap.Argument;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.iap.Response;
import com.sun.mail.imap.ACL;
import com.sun.mail.imap.AppendUID;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.Rights;
import com.sun.mail.imap.protocol.IMAPProtocol;
import com.sun.mail.imap.protocol.IMAPResponse;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Header;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.ReadOnlyFolderException;
import javax.mail.Session;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Any IMAP/Cyrus-specific features are here.
 *
 * Test account 1:
 *     hostname: 192.168.1.12
 *     username: cyrus.test@suspendo.aoindustries.com
 *     password: Clusk48Kulp
 * Test account 2:
 *     hostname: 192.168.1.12
 *     username: cyrus.test2
 *     password: Eflay43Klar
 *
 * TODO: Once conversion done:
 *     0) Look for any /home/?/???/MoveToCyrus folders (www2.kc.aoindustries.com:smurphy is one)
 *     1) Set WUIMAP_CONVERSION_ENABLED to false
 *     2) Make sure all gone in /home/?/???/Mail and /home/?/???/.mailboxlist
 *     - Then after a while -
 *     3) rm -rf /opt/imap-2007d
 *     4) rm -rf /var/opt/imap-2007d
 *
 * TODO: Future
 *     Control the synchronous mode for ext2/ext3 automatically?
 *         file:///home/o/orion/temp/cyrus/cyrus-imapd-2.3.7/doc/install-configure.html
 *         cd /var/imap
 *         chattr +S user quota user/* quota/*
 *         chattr +S /var/spool/imap /var/spool/imap/*
 *         chattr +S /var/spool/mqueue
 *     sieve to replace procmail and allow more directly delivery
 *         sieveusehomedir
 *         sieveshell:
 *             sieveshell --authname=cyrus.test@suspendo.aoindustries.com 192.168.1.12
 *             /bin/su -s /bin/bash -c "/usr/bin/sieveshell 192.168.1.12" cyrus.test@suspendo.aoindustries.com
 *         sieve only listen on primary IP only (for chroot failover)
 *         procmail migration script here: http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/MboxCyrusMigration
 *     Run chk_cyrus from NOC?
 *     Backups:
 *           stop master, snapshot, start master
 *           Or, without snapshots, do ctl_mboxlist -d
 *               http://cyrusimap.web.cmu.edu/twiki/bin/view/Cyrus/Backup
 *           Also, don't back-up Junk folders?
 *     Add smmapd support
 *     Consider http://en.wikipedia.org/wiki/Application_Configuration_Access_Protocol or http://en.wikipedia.org/wiki/Application_Configuration_Access_Protocol
 *     Look for any "junk" flags for Cyrus folders - if exists can train off this instead of requiring move to/from Junk
 *
 * @author  AO Industries, Inc.
 */
final public class ImapManager extends BuilderThread {

    public static final boolean WUIMAP_CONVERSION_ENABLED = true;
    private static final int WUIMAP_CONVERSION_CONCURRENCY = 20;

    private static final Log log = LogFactory.getLog(ImapManager.class);

    public static final File mailSpool=new File("/var/spool/mail");
    
    private static final File imapSpool = new File("/var/spool/imap");
    private static final File imapVirtDomainSpool = new File(imapSpool, "domain");
    private static final File subsysLockFile = new File("/var/lock/subsys/cyrus-imapd");

    private static final UnixFile
        cyrusRcFile=new UnixFile("/etc/rc.d/rc3.d/S65cyrus-imapd"),
        cyrusConfFile = new UnixFile("/etc/cyrus.conf"),
        cyrusConfNewFile = new UnixFile("/etc/cyrus.conf.new"),
        imapdConfFile = new UnixFile("/etc/imapd.conf"),
        imapdConfNewFile = new UnixFile("/etc/imapd.conf.new"),
        pkiVostsDirectory = new UnixFile("/etc/pki/cyrus-imapd/vhosts")
    ;

    /**
     * Default symbolic links for protocol-ip-port-specific certificate files.
     */
    private static final String
        DEFAULT_CERT_SYMLINK = "../cyrus-imapd.pem",
        DEFAULT_KEY_SYMLINK  = "../cyrus-imapd.pem",
        DEFAULT_CA_SYMLINK   = "../../tls/certs/ca-bundle.crt"
    ;

    private static final UnixFile wuBackupDirectory = new UnixFile("/var/opt/imap-2007d/backup");

    /**
     * These directories may be in the imapSpool and will be ignored.
     */
    private static final Set<String> imapSpoolIgnoreDirectories = new HashSet<String>();
    static {
        imapSpoolIgnoreDirectories.add("domain");
        imapSpoolIgnoreDirectories.add("stage.");
    }

    private static ImapManager imapManager;

    private ImapManager() {
    }

    private static final Object _sessionLock = new Object();
    private static Session _session;

    /**
     * Gets the Session used for admin control.
     */
    private static Session getSession() throws IOException, SQLException {
        synchronized(_sessionLock) {
            if(_session==null) {
                // Create and cache new session
                Properties props = new Properties();
                props.put("mail.store.protocol", "imap");
                props.put("mail.transport.protocol", "smtp");
                props.put("mail.smtp.auth", "true");
                props.put("mail.from", "cyrus@"+AOServDaemon.getThisAOServer().getHostname());
                _session = Session.getInstance(props, null);
                //_session.setDebug(true);
            }
            return _session;
        }
    }

    private static final Object _storeLock = new Object();
    private static IMAPStore _store;

    /**
     * Gets the IP address that should be used for admin access to the server.
     * It will first try to use the Primary IP address on the machine.  If that
     * doesn't have an IMAP server on port 143, then it will search all IP
     * addresses and use the first one with an IMAP server on port 143.
     * 
     * @return  The IP address or <code>null</code> if not an IMAP server.
     */
    private static String getImapServerIPAddress() throws IOException, SQLException {
        AOServer aoServer = AOServDaemon.getThisAOServer();
        AOServConnector conn = AOServDaemon.getConnector();
        Protocol imapProtocol = conn.protocols.get(Protocol.IMAP2);
        if(imapProtocol==null) throw new SQLException("Protocol not found: "+Protocol.IMAP2);
        int imapPort = imapProtocol.getPort(conn).getPort();
        List<NetBind> netBinds = aoServer.getServer().getNetBinds(imapProtocol);
        // Look for primary IP match
        String primaryIp = aoServer.getPrimaryIPAddress().getIPAddress();
        NetBind firstImap = null;
        for(NetBind nb : netBinds) {
            if(nb.getPort().getPort()==imapPort) {
                if(nb.getIPAddress().getIPAddress().equals(primaryIp)) return primaryIp;
                if(firstImap==null) firstImap = nb;
            }
        }
        return firstImap==null ? null : firstImap.getIPAddress().getIPAddress();
    }

    /**
     * Gets the IMAPStore for admin control or <code>null</code> if not an IMAP server.
     */
    private static IMAPStore getStore() throws IOException, SQLException, MessagingException {
        synchronized(_storeLock) {
            if(_store==null) {
                // Get things that may failed externally before allocating session and store
                String host = getImapServerIPAddress();
                if(host==null) return null;
                String user = LinuxAccount.CYRUS+"@default";
                String password = AOServDaemonConfiguration.getCyrusPassword();

                // Create and cache new store here
                IMAPStore newStore = (IMAPStore)getSession().getStore();
                newStore.connect(
                    host,
                    user,
                    password
                );
                _store = newStore;
            }
            return _store;
        }
    }

    /**
     * Closes IMAPStore.
     */
    private static void closeStore() {
        synchronized(_storeLock) {
            if(_store!=null) {
                try {
                    _store.close();
                } catch(MessagingException err) {
                    AOServDaemon.reportError(err, null);
                }
                _store = null;
            }
        }
    }

    /**
     * Gets access to the old IMAPStore for wu-imapd.
     */
    private static IMAPStore getOldUserStore(
        PrintWriter logOut,
        String username,
        String[] tempPassword,
        UnixFile passwordBackup,
        Stat tempStat
    ) throws IOException, SQLException, MessagingException {
        return getUserStore(
            logOut,
            AOServDaemon.getThisAOServer().getPrimaryIPAddress().getIPAddress(),
            8143,
            username,
            username,
            tempPassword,
            passwordBackup,
            tempStat
        );
    }

    /**
     * Gets access to the new IMAPStore for cyrus.
     */
    private static IMAPStore getNewUserStore(
        PrintWriter logOut,
        String username,
        String[] tempPassword,
        UnixFile passwordBackup,
        Stat tempStat
    ) throws IOException, SQLException, MessagingException {
        String host = getImapServerIPAddress();
        if(host==null) throw new IOException("Not an IMAP server");
        return getUserStore(
            logOut,
            host,
            143,
            username,
            username.indexOf('@')==-1 ? (username+"@default") : username,
            tempPassword,
            passwordBackup,
            tempStat
        );
    }

    /**
     * Gets the IMAPStore for the provided user to the given IPAddress and port.
     */
    private static IMAPStore getUserStore(
        PrintWriter logOut,
        String host,
        int port,
        String username,
        String imapUsername,
        String[] tempPassword,
        UnixFile passwordBackup,
        Stat tempStat
    ) throws IOException, SQLException, MessagingException {
        // Reset the user password if needed
        String password = tempPassword[0];
        if(password==null) {
            // Backup the password
            if(!passwordBackup.getStat(tempStat).exists()) {
                log(logOut, LogLevel.DEBUG, username, "Backing-up password");
                String encryptedPassword = LinuxAccountManager.getEncryptedPassword(username);
                UnixFile tempFile = UnixFile.mktemp(passwordBackup.getPath()+".", false);
                PrintWriter out = new PrintWriter(new FileOutputStream(tempFile.getFile()));
                try {
                    out.println(encryptedPassword);
                } finally {
                    out.close();
                }
                tempFile.renameTo(passwordBackup);
            }
            // Change the password to a random value
            password = LinuxAccountTable.generatePassword();
            log(logOut, LogLevel.DEBUG, username, "Setting password to "+password);
            LinuxAccountManager.setPassword(username, password);
            tempPassword[0] = password;
        }

        // Create the session
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.port", Integer.toString(port));
        Session session = Session.getInstance(props, null);
        //session.setDebug(true);

        // Create new store
        IMAPStore store = (IMAPStore)session.getStore();
        store.connect(
            host,
            imapUsername,
            password
        );
        return store;
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException, MessagingException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            // Used inside synchronized block
            Stat tempStat = new Stat();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            AOServConnector conn = AOServDaemon.getConnector();
            Server server = thisAOServer.getServer();

            Protocol imapProtocol = conn.protocols.get(Protocol.IMAP2);
            if(imapProtocol==null) throw new SQLException("Unable to find Protocol: "+Protocol.IMAP2);
            List<NetBind> imapBinds = server.getNetBinds(imapProtocol);

            Protocol imapsProtocol = conn.protocols.get(Protocol.SIMAP);
            if(imapsProtocol==null) throw new SQLException("Unable to find Protocol: "+Protocol.SIMAP);
            List<NetBind> imapsBinds = server.getNetBinds(imapsProtocol);

            Protocol pop3Protocol = conn.protocols.get(Protocol.POP3);
            if(pop3Protocol==null) throw new SQLException("Unable to find Protocol: "+Protocol.POP3);
            List<NetBind> pop3Binds = server.getNetBinds(pop3Protocol);

            Protocol pop3sProtocol = conn.protocols.get(Protocol.SPOP3);
            if(pop3sProtocol==null) throw new SQLException("Unable to find Protocol: "+Protocol.SPOP3);
            List<NetBind> pop3sBinds = server.getNetBinds(pop3sProtocol);

            Protocol sieveProtocol = conn.protocols.get(Protocol.SIEVE);
            if(sieveProtocol==null) throw new SQLException("Unable to find Protocol: "+Protocol.SIEVE);
            List<NetBind> sieveBinds = server.getNetBinds(sieveProtocol);

            synchronized(rebuildLock) {
                // If there are no IMAP(S)/POP3(S) binds
                if(imapBinds.isEmpty() && imapsBinds.isEmpty() && pop3Binds.isEmpty() && pop3sBinds.isEmpty()) {
                    // Should not have any sieve binds
                    if(!sieveBinds.isEmpty()) throw new SQLException("Should not have sieve without any imap, imaps, pop3, or pop3s");

                    // Stop service if running
                    if(subsysLockFile.exists()) {
                        if(log.isDebugEnabled()) log.debug("Stopping cyrus-imapd service");
                        AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/cyrus-imapd", "stop"});
                        if(subsysLockFile.exists()) throw new IOException(subsysLockFile.getPath()+" still exists after service stop");
                    }

                    // chkconfig off if needed
                    if(cyrusRcFile.getStat(tempStat).exists()) {
                        if(log.isDebugEnabled()) log.debug("Disabling cyrus-imapd service");
                        AOServDaemon.exec(new String[] {"/sbin/chkconfig", "cyrus-imapd", "off"});
                        if(cyrusRcFile.getStat(tempStat).exists()) throw new IOException(cyrusRcFile.getPath()+" still exists after chkconfig off");
                    }

                    // Delete config files if exist
                    if(imapdConfNewFile.getStat(tempStat).exists()) {
                        if(log.isDebugEnabled()) log.debug("Deleting unnecessary config file: "+imapdConfNewFile.getPath());
                        imapdConfNewFile.delete();
                    }
                    if(imapdConfFile.getStat(tempStat).exists()) {
                        if(log.isDebugEnabled()) log.debug("Deleting unnecessary config file: "+imapdConfFile.getPath());
                        imapdConfFile.delete();
                    }
                    if(cyrusConfNewFile.getStat(tempStat).exists()) {
                        if(log.isDebugEnabled()) log.debug("Deleting unnecessary config file: "+cyrusConfNewFile.getPath());
                        cyrusConfNewFile.delete();
                    }
                    if(cyrusConfFile.getStat(tempStat).exists()) {
                        if(log.isDebugEnabled()) log.debug("Deleting unnecessary config file: "+cyrusConfFile.getPath());
                        cyrusConfFile.delete();
                    }
                } else {
                    // Require sieve
                    if(sieveBinds.isEmpty()) throw new SQLException("sieve is required with any of imap, imaps, pop3, and pop3s");

                    // Required IMAP at least once on any default port
                    int defaultImapPort = imapProtocol.getPort(conn).getPort();
                    boolean foundOnDefault = false;
                    for(NetBind nb : imapBinds) {
                        if(nb.getPort().getPort()==defaultImapPort) {
                            foundOnDefault = true;
                            break;
                        }
                    }
                    if(!foundOnDefault) throw new SQLException("imap is required on a default port with any of imap, imaps, pop3, and pop3s");

                    // The worst-case number of services, used to initialize storage to avoid rehash/resize
                    int maxServices =
                        imapBinds.size()
                        + imapsBinds.size()
                        + pop3Binds.size()
                        + pop3sBinds.size()
                        + sieveBinds.size()
                        + 1 // lmtpunix
                    ;
                    // All services that support TLS or SSL will be added here
                    Map<String,NetBind> tlsServices = new LinkedHashMap<String,NetBind>(maxServices*4/3+1);

                    boolean needsReload = false;
                    // Update /etc/cyrus.conf
                    {
                        bout.reset();
                        ChainWriter out = new ChainWriter(bout);
                        try {
                            out.print("#\n"
                                    + "# Automatically generated by ").print(ImapManager.class.getName()).print("\n"
                                    + "#\n"
                                    + "START {\n"
                                    //+ "  # do not delete this entry!\n"
                                    + "  recover cmd=\"ctl_cyrusdb -r\"\n"
                                    //+ "  # this is only necessary if using idled for IMAP IDLE\n"
                                    + "  idled cmd=\"idled\"\n"
                                    + "}\n"
                                    //+ "# UNIX sockets start with a slash and are put into /var/lib/imap/sockets\n"
                                    + "SERVICES {\n");
                                    //+ "  # add or remove based on preferences\n"
                            // imap
                            int counter = 1;
                            for(NetBind imapBind : imapBinds) {
                                if(!imapBind.getNetProtocol().getProtocol().equals(NetProtocol.TCP)) throw new SQLException("imap requires TCP protocol");
                                String serviceName = "imap"+(counter++);
                                out.print("  ").print(serviceName).print(" cmd=\"imapd\" listen=\"[").print(imapBind.getIPAddress().getIPAddress()).print("]:").print(imapBind.getPort().getPort()).print("\" proto=\"tcp4\" prefork=5\n");
                                tlsServices.put(serviceName, imapBind);
                            }
                            // imaps
                            counter = 1;
                            for(NetBind imapsBind : imapsBinds) {
                                if(!imapsBind.getNetProtocol().getProtocol().equals(NetProtocol.TCP)) throw new SQLException("imaps requires TCP protocol");
                                String serviceName = "imaps"+(counter++);
                                out.print("  ").print(serviceName).print(" cmd=\"imapd -s\" listen=\"[").print(imapsBind.getIPAddress().getIPAddress()).print("]:").print(imapsBind.getPort().getPort()).print("\" proto=\"tcp4\" prefork=1\n");
                                tlsServices.put(serviceName, imapsBind);
                            }
                            // pop3
                            counter = 1;
                            for(NetBind pop3Bind : pop3Binds) {
                                if(!pop3Bind.getNetProtocol().getProtocol().equals(NetProtocol.TCP)) throw new SQLException("pop3 requires TCP protocol");
                                String serviceName = "pop3"+(counter++);
                                out.print("  ").print(serviceName).print(" cmd=\"pop3d\" listen=\"[").print(pop3Bind.getIPAddress().getIPAddress()).print("]:").print(pop3Bind.getPort().getPort()).print("\" proto=\"tcp4\" prefork=3\n");
                                tlsServices.put(serviceName, pop3Bind);
                            }
                            // pop3s
                            counter = 1;
                            for(NetBind pop3sBind : pop3sBinds) {
                                if(!pop3sBind.getNetProtocol().getProtocol().equals(NetProtocol.TCP)) throw new SQLException("pop3s requires TCP protocol");
                                String serviceName = "pop3s"+(counter++);
                                out.print("  ").print(serviceName).print(" cmd=\"pop3d -s\" listen=\"[").print(pop3sBind.getIPAddress().getIPAddress()).print("]:").print(pop3sBind.getPort().getPort()).print("\" proto=\"tcp4\" prefork=1\n");
                                tlsServices.put(serviceName, pop3sBind);
                            }
                            // sieve
                            counter = 1;
                            for(NetBind sieveBind : sieveBinds) {
                                if(!sieveBind.getNetProtocol().getProtocol().equals(NetProtocol.TCP)) throw new SQLException("sieve requires TCP protocol");
                                out.print("  sieve").print(counter++).print(" cmd=\"timsieved\" listen=\"[").print(sieveBind.getIPAddress().getIPAddress()).print("]:").print(sieveBind.getPort().getPort()).print("\" proto=\"tcp4\" prefork=0\n");
                            }
                                    //+ "  # these are only necessary if receiving/exporting usenet via NNTP\n"
                                    //+ "#  nntp         cmd=\"nntpd\" listen=\"nntp\" prefork=3\n"
                                    //+ "#  nntps                cmd=\"nntpd -s\" listen=\"nntps\" prefork=1\n"
                                    //+ "  # at least one LMTP is required for delivery\n"
                                    //+ "#  lmtp         cmd=\"lmtpd\" listen=\"lmtp\" prefork=0\n"
                            out.print("  lmtpunix cmd=\"lmtpd\" listen=\"/var/lib/imap/socket/lmtp\" prefork=1\n"
                                    //+ "  # this is only necessary if using notifications\n"
                                    //+ "#  notify       cmd=\"notifyd\" listen=\"/var/lib/imap/socket/notify\" proto=\"udp\" prefork=1\n"
                                    + "}\n"
                                    + "EVENTS {\n"
                                    //+ "  # this is required\n"
                                    + "  checkpoint cmd=\"ctl_cyrusdb -c\" period=30\n"
                                    //+ "  # this is only necessary if using duplicate delivery suppression,\n"
                                    //+ "  # Sieve or NNTP\n"
                                    // -X 3 added to allow 3 day "unexpunge" capability
                                    + "  delprune cmd=\"cyr_expire -E 3 -X 3\" at=0400\n"
                                    //+ "  # this is only necessary if caching TLS sessions\n"
                                    + "  tlsprune cmd=\"tls_prune\" at=0400\n"
                                    + "}\n");
                        } finally {
                            out.close();
                        }
                        byte[] newBytes = bout.toByteArray();

                        // Only write when changed
                        if(!cyrusConfFile.getStat(tempStat).exists() || !cyrusConfFile.contentEquals(newBytes)) {
                            if(log.isDebugEnabled()) log.debug("Writing new config file: "+cyrusConfFile.getPath());
                            FileOutputStream newOut = new FileOutputStream(cyrusConfNewFile.getFile());
                            try {
                                newOut.write(newBytes);
                            } finally {
                                newOut.close();
                            }
                            cyrusConfNewFile.renameTo(cyrusConfFile);
                            needsReload = true;
                        }
                    }

                    // Any files in /etc/pki/cyrus-imapd/vhosts that are not in this list are subject to removal
                    Set<String> vhostsFiles = new HashSet<String>(3 * (maxServices*4/3+1)); // Three files per service

                    // Update /etc/imapd.conf
                    {
                        bout.reset();
                        ChainWriter out = new ChainWriter(bout);
                        try {
                            out.print("#\n"
                                    + "# Automatically generated by ").print(ImapManager.class.getName()).print("\n"
                                    + "#\n"
                                    + "configdirectory: /var/lib/imap\n"
                                    + "\n"
                                    + "# Default partition\n"
                                    + "defaultpartition: default\n"
                                    + "partition-default: /var/spool/imap\n"
                                    + "\n"
                                    + "# Authentication\n"
                                    + "username_tolower: no\n" // This is to be consistent with authenticated SMTP, which always has case-sensitive usernames.
                                    + "unix_group_enable: no\n"
                                    + "sasl_pwcheck_method: saslauthd\n"
                                    + "sasl_mech_list: PLAIN\n"
                                    + "sasl_minimum_layer: 0\n"
                                    + "allowplaintext: yes\n"
                                    + "allowplainwithouttls: yes\n"
                                    + "\n"
                                    + "# SSL/TLS\n"
                                    + "tls_cert_file: /etc/pki/cyrus-imapd/cyrus-imapd.pem\n"
                                    + "tls_key_file: /etc/pki/cyrus-imapd/cyrus-imapd.pem\n"
                                    + "tls_ca_file: /etc/pki/tls/certs/ca-bundle.crt\n");
                            // Make sure directory exists and has proper permissions
                            {
                                Stat pkiVostsDirectoryStat = pkiVostsDirectory.getStat();
                                if(!pkiVostsDirectoryStat.exists()) {
                                    if(log.isDebugEnabled()) log.debug("Creating vhosts directory: "+pkiVostsDirectory.getPath());
                                    pkiVostsDirectory.mkdir();
                                    pkiVostsDirectory.getStat(pkiVostsDirectoryStat);
                                }
                                if(pkiVostsDirectoryStat.getMode()!=0755) {
                                    if(log.isDebugEnabled()) log.debug("Setting vhosts directory permissions: "+pkiVostsDirectory.getPath());
                                    pkiVostsDirectory.setMode(0755);
                                    // Not needed because last use: pkiVostsDirectory.getStat(pkiVostsDirectoryStat);
                                }
                            }
                            // service-specific certificates
                            //     file:///home/o/orion/temp/cyrus/cyrus-imapd-2.3.7/doc/install-configure.html
                            //     value of "disabled" if the certificate file doesn't exist (or use server default)
                            //     openssl req -new -x509 -nodes -out cyrus-imapd.pem -keyout cyrus-imapd.pem -days 3650
                            for(Map.Entry<String,NetBind> entry : tlsServices.entrySet()) {
                                String serviceName = entry.getKey();
                                NetBind netBind = entry.getValue();
                                String ipAddress = netBind.getIPAddress().getIPAddress();
                                int port = netBind.getPort().getPort();
                                String protocol;
                                String appProtocol = netBind.getAppProtocol().getProtocol();
                                if(Protocol.IMAP2.equals(appProtocol)) protocol = "imap";
                                else if(Protocol.SIMAP.equals(appProtocol)) protocol = "imaps";
                                else if(Protocol.POP3.equals(appProtocol)) protocol = "pop3";
                                else if(Protocol.SPOP3.equals(appProtocol)) protocol = "pop3s";
                                else throw new SQLException("Unexpected Protocol: "+appProtocol);

                                // cert file
                                String certFilename = protocol+"_"+ipAddress+"_"+port+".cert";
                                UnixFile certFile = new UnixFile(pkiVostsDirectory, certFilename, false);
                                if(!certFile.getStat(tempStat).exists()) {
                                    if(log.isDebugEnabled()) log.debug("Creating default cert symlink: "+certFile.getPath()+"->"+DEFAULT_CERT_SYMLINK);
                                    certFile.symLink(DEFAULT_CERT_SYMLINK);
                                }
                                vhostsFiles.add(certFilename);
                                out.print(serviceName).print("_tls_cert_file: ").print(certFile.getPath()).print('\n');

                                // key file
                                String keyFilename = protocol+"_"+ipAddress+"_"+port+".key";
                                UnixFile keyFile = new UnixFile(pkiVostsDirectory, keyFilename, false);
                                if(!keyFile.getStat(tempStat).exists()) {
                                    if(log.isDebugEnabled()) log.debug("Creating default key symlink: "+keyFile.getPath()+"->"+DEFAULT_KEY_SYMLINK);
                                    keyFile.symLink(DEFAULT_KEY_SYMLINK);
                                }
                                vhostsFiles.add(keyFilename);
                                out.print(serviceName).print("_tls_key_file: ").print(keyFile.getPath()).print('\n');
                                
                                // ca file
                                String caFilename = protocol+"_"+ipAddress+"_"+port+".ca";
                                UnixFile caFile = new UnixFile(pkiVostsDirectory, caFilename, false);
                                if(!caFile.getStat(tempStat).exists()) {
                                    if(log.isDebugEnabled()) log.debug("Creating default ca symlink: "+caFile.getPath()+"->"+DEFAULT_CA_SYMLINK);
                                    caFile.symLink(DEFAULT_CA_SYMLINK);
                                }
                                vhostsFiles.add(caFilename);
                                out.print(serviceName).print("_tls_ca_file: ").print(caFile.getPath()).print('\n');
                            }
                            out.print("\n"
                                    + "# Performance\n"
                                    + "expunge_mode: delayed\n"
                                    + "hashimapspool: true\n"
                                    + "\n"
                                    + "# Outlook compatibility\n"
                                    + "flushseenstate: yes\n"
                                    + "\n"
                                    + "# WU IMAPD compatibility\n"
                                    + "altnamespace: yes\n"
                                    + "unixhierarchysep: yes\n"
                                    + "virtdomains: userid\n"
                                    + "defaultdomain: default\n"
                                    + "\n"
                                    + "# Security\n"
                                    + "imapidresponse: no\n"
                                    + "admins: cyrus\n"
                                    + "\n"
                                    + "# Allows users to read for sa-learn after hard linking to user readable directory\n"
                                    + "umask: 022\n"
                                    + "\n"
                                    + "# Proper hostname in chroot fail-over state\n"
                                    + "servername: ").print(thisAOServer.getHostname()).print("\n"
                                    + "\n"
                                    + "# Sieve\n"
                                    + "sievedir: /var/lib/imap/sieve\n"
                                    + "autosievefolders: Junk\n"
                                    + "sendmail: /usr/sbin/sendmail\n");
                        } finally {
                            out.close();
                        }
                        byte[] newBytes = bout.toByteArray();

                        // Only write when changed
                        if(!imapdConfFile.getStat(tempStat).exists() || !imapdConfFile.contentEquals(newBytes)) {
                            if(log.isDebugEnabled()) log.debug("Writing new config file: "+imapdConfFile.getPath());
                            FileOutputStream newOut = new FileOutputStream(imapdConfNewFile.getFile());
                            try {
                                newOut.write(newBytes);
                            } finally {
                                newOut.close();
                            }
                            imapdConfNewFile.renameTo(imapdConfFile);
                            needsReload = true;
                        }
                    }

                    // Remove default links in /etc/pki/cyrus-imapd/vhosts that are no longer used
                    String[] list = pkiVostsDirectory.list();
                    if(list!=null) {
                        for(String filename : list) {
                            if(!vhostsFiles.contains(filename)) {
                                UnixFile vhostsFile = new UnixFile(pkiVostsDirectory, filename, false);
                                vhostsFile.getStat(tempStat);
                                if(tempStat.isSymLink()) {
                                    String target = vhostsFile.readLink();
                                    if(
                                        target.equals(DEFAULT_CERT_SYMLINK)
                                        || target.equals(DEFAULT_KEY_SYMLINK)
                                        || target.equals(DEFAULT_CA_SYMLINK)
                                    ) {
                                        if(log.isDebugEnabled()) log.debug("Deleting default symlink: "+vhostsFile.getPath()+"->"+target);
                                        vhostsFile.delete();
                                    } else {
                                        // Warn here to help admin keep clean?
                                    }
                                } else {
                                    // Warn here to help admin keep clean?
                                }
                            }
                        }
                    }
                    
                    // chkconfig on if needed
                    if(!cyrusRcFile.getStat(tempStat).exists()) {
                        if(log.isDebugEnabled()) log.debug("Enabling cyrus-imapd service");
                        AOServDaemon.exec(new String[] {"/sbin/chkconfig", "cyrus-imapd", "on"});
                        if(!cyrusRcFile.getStat(tempStat).exists()) throw new IOException(cyrusRcFile.getPath()+" still does not exists after chkconfig on");
                    }

                    // Start service if not running
                    if(!subsysLockFile.exists()) {
                        if(log.isDebugEnabled()) log.debug("Starting cyrus-imapd service");
                        AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/cyrus-imapd", "start"});
                        if(!subsysLockFile.exists()) throw new IOException(subsysLockFile.getPath()+" still does not exists after service start");
                    } else {
                        if(needsReload) {
                            if(log.isDebugEnabled()) log.debug("Reloading cyrus-imapd service");
                            AOServDaemon.exec(new String[] {"/etc/rc.d/init.d/cyrus-imapd", "reload"});
                        }
                    }

                    rebuildUsers();
                }
            }
        }
    }

    /**
     * Gets a folder name for the provided user, domain, and folder.
     * 
     * @param  user    the username (without any @ sign)
     * @param  domain  null if no domain
     * @param  folder  the folder or null for INBOX
     */
    private static String getFolderName(String user, String domain, String folder) {
        StringBuilder sb = new StringBuilder();
        sb.append("user/").append(user);
        if(folder.length()>0) sb.append('/').append(folder);
        if(!domain.equals("default")) sb.append('@').append(domain);
        return sb.toString();
    }

    /**
     * Rebuild the ACL on a folder, both creating missing and removing extra rights.
     * 
     * http://www.faqs.org/rfcs/rfc2086.html
     * 
     * @param  folder  the IMAPFolder to verify the ACL on
     * @param  user    the username (without any @ sign)
     * @param  domain  "default" if no domain
     * @param  rights  the rights, one character to right
     */
    private static void rebuildAcl(IMAPFolder folder, String user, String domain, Rights rights) throws MessagingException {
        // Determine the username
        String username = domain.equals("default") ? user : (user+'@'+domain);

        ACL userAcl = null;
        for(ACL acl : folder.getACL()) {
            if(acl.getName().equals(username)) {
                userAcl = acl;
                break;
            }
        }
        if(userAcl==null) {
            ACL newAcl = new ACL(username, new Rights(rights));
            if(log.isDebugEnabled()) log.debug(folder.getFullName()+": Adding new ACL: "+rights.toString());
            folder.addACL(newAcl);
        } else {
            // Verify rights
            Rights aclRights = userAcl.getRights();
            
            // Look for missing
            if(!aclRights.contains(rights)) {
                // Build the set of rights that are missing
                Rights missingRights = new Rights();
                for(Rights.Right right : rights.getRights()) {
                    if(!aclRights.contains(right)) missingRights.add(right);
                }
                userAcl.setRights(missingRights);
                if(log.isDebugEnabled()) log.debug(folder.getFullName()+": Adding rights to ACL: "+userAcl.toString());
                folder.addRights(userAcl);
            }
            if(!rights.contains(aclRights)) {
                // Build the set of rights that are extra
                Rights extraRights = new Rights();
                for(Rights.Right right : aclRights.getRights()) {
                    if(!rights.contains(right)) extraRights.add(right);
                }
                userAcl.setRights(extraRights);
                if(log.isDebugEnabled()) log.debug(folder.getFullName()+": Removing rights from ACL: "+userAcl.toString());
                folder.removeRights(userAcl);
            }
        }
    }

    /**
     * Gets the Cyrus user part of a username.
     * 
     * @see  #getDomain
     */
    private static String getUser(String username) {
        int atPos = username.lastIndexOf('@');
        return (atPos==-1) ? username : username.substring(0, atPos);
    }
    
    /**
     * Gets the Cyrus domain part of a username or <code>null</code> for no domain.
     * 
     * @see  #getUser
     */
    private static String getDomain(String username) {
        int atPos = username.lastIndexOf('@');
        return (atPos==-1) ? "default" : username.substring(atPos+1);
    }

    /**
     * Adds user directories to the provided domain->user map.
     * The directories should be in the format ?/user/*
     * The ? should be equal to the first letter of *
     */
    private static void addUserDirectories(File directory, Set<String> ignoreList, String domain, Map<String,Set<String>> allUsers) throws IOException {
        String[] hashFilenames = directory.list();
        if(hashFilenames!=null) {
            Arrays.sort(hashFilenames);
            for(String hashFilename : hashFilenames) {
                if(ignoreList==null || !ignoreList.contains(hashFilename)) {
                    // hashFilename should only be one character
                    File hashDir = new File(directory, hashFilename);
                    if(hashFilename.length()!=1) throw new IOException("hashFilename should only be on character: "+hashDir.getPath());
                    // hashDir should only contain a "user" directory
                    String[] hashSubFilenames = hashDir.list();
                    if(hashSubFilenames!=null && hashSubFilenames.length>0) {
                        if(hashSubFilenames.length!=1) throw new IOException("hashSubFilenames should only contain one directory: "+hashDir);
                        String hashSubFilename = hashSubFilenames[0];
                        File userDir = new File(hashDir, hashSubFilename);
                        if(!hashSubFilename.equals("user")) throw new IOException("hashSubFilenames should only contain a \"user\" directory: "+userDir);
                        String[] userSubFilenames = userDir.list();
                        if(userSubFilenames!=null && userSubFilenames.length>0) {
                            Arrays.sort(userSubFilenames);
                            // Add the domain if needed
                            Set<String> domainUsers = allUsers.get(domain);
                            if(domainUsers==null) {
                                if(log.isTraceEnabled()) log.trace("addUserDirectories: domain: "+domain);
                                allUsers.put(domain, domainUsers = new HashSet<String>());
                            }
                            // Add the users
                            for(String user : userSubFilenames) {
                                if(!user.startsWith(hashFilename)) throw new IOException("user directory should start with "+hashFilename+": "+userDir.getPath()+"/"+user);
                                user = user.replace('^', '.');
                                if(log.isTraceEnabled()) log.trace("addUserDirectories: user: "+user);
                                if(!domainUsers.add(user)) throw new IOException("user already in domain: "+userDir.getPath()+"/"+user);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void convertImapDirectory(
        final PrintWriter logOut,
        final String username,
        final int junkRetention,
        final int trashRetention,
        final UnixFile directory,
        final UnixFile backupDirectory,
        final String folderPath,
        final String[] tempPassword,
        final UnixFile passwordBackup,
        final Stat tempStat
    ) throws IOException, SQLException, MessagingException {
        // Careful not a symbolic link
        if(!directory.getStat(tempStat).isDirectory()) throw new IOException("Not a directory: "+directory.getPath());

        // Create backup directory
        if(!backupDirectory.getStat(tempStat).exists()) {
            log(logOut, LogLevel.DEBUG, username, "Creating backup directory: "+backupDirectory.getPath());
            backupDirectory.mkdir(false, 0700);
        }

        // Convert each of the children
        String[] list = directory.list();
        if(list!=null) {
            Arrays.sort(list);
            for(String childName : list) {
                UnixFile childUf = new UnixFile(directory, childName, false);
                long mode = childUf.getStat(tempStat).getRawMode();
                boolean isDirectory = UnixFile.isDirectory(mode);
                boolean isFile = UnixFile.isRegularFile(mode);
                if(isDirectory && isFile) throw new IOException("Both directory and regular file: "+childUf.getPath());
                if(!isDirectory && !isFile) throw new IOException("Neither directory nor regular file: "+childUf.getPath());
                String folderName = folderPath.length()==0 ? childName : (folderPath+'/'+childName);

                // Get New Store
                IMAPStore newStore = getNewUserStore(logOut, username, tempPassword, passwordBackup, tempStat);
                try {
                    // Get New Folder
                    IMAPFolder newFolder = (IMAPFolder)newStore.getFolder(folderName);
                    try {
                        // Create the new folder if doesn't exist
                        if(!newFolder.exists()) {
                            log(logOut, LogLevel.DEBUG, username, "Creating mailbox: "+folderName);
                            if(!newFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                                throw new MessagingException("Unable to create folder: "+folderName);
                            }
                        }

                        // Subscribe to new folder if not yet subscribed
                        if(!newFolder.isSubscribed()) {
                            log(logOut, LogLevel.DEBUG, username, "Subscribing to mailbox: "+folderName);
                            newFolder.setSubscribed(true);
                        }
                    } finally {
                        if(newFolder.isOpen()) newFolder.close(false);
                    }
                } finally {
                    newStore.close();
                }

                // Recurse
                UnixFile childBackupUf = new UnixFile(backupDirectory, childName, false);
                if(isDirectory && !isFile) {
                    convertImapDirectory(logOut, username, junkRetention, trashRetention, childUf, childBackupUf, folderName, tempPassword, passwordBackup, tempStat);
                } else if(isFile && !isDirectory) {
                    convertImapFile(logOut, username, junkRetention, trashRetention, childUf, childBackupUf, folderName, tempPassword, passwordBackup, tempStat);
                } else {
                    throw new AssertionError("This should already have been caught by the isDirectory and isFile checks above");
                }
            }
        }

        // Directory should be empty, delete it or error if not empty
        list = directory.list();
        if(list!=null && list.length>0) {
            log(logOut, LogLevel.WARN, username, "Unable to delete non-empty directory \""+directory.getPath()+"\": Contains "+list.length+" items");
        } else {
            log(logOut, LogLevel.DEBUG, username, "Deleting empty directory: "+directory.getPath());
            directory.delete();
        }
    }

    /**
     * This should really be a toString on the Flags.Flag class.
     */
    private static String getFlagName(Flags.Flag flag) throws MessagingException {
        if(flag==Flags.Flag.ANSWERED) return "ANSWERED";
        if(flag==Flags.Flag.DELETED) return "DELETED";
        if(flag==Flags.Flag.DRAFT) return "DRAFT";
        if(flag==Flags.Flag.FLAGGED) return "FLAGGED";
        if(flag==Flags.Flag.RECENT) return "RECENT";
        if(flag==Flags.Flag.SEEN) return "SEEN";
        if(flag==Flags.Flag.USER) return "USER";
        throw new MessagingException("Unexpected flag: "+flag);
    }

    private static final Flags.Flag[] systemFlags = {
        Flags.Flag.ANSWERED,
        Flags.Flag.DELETED,
        Flags.Flag.DRAFT,
        Flags.Flag.FLAGGED,
        Flags.Flag.RECENT,
        Flags.Flag.SEEN,
        Flags.Flag.USER
    };

    private static final Object appendCounterLock = new Object();
    private static long appendCounterStart = -1;
    private static long appendCounter = 0;

    private static void incAppendCounter() {
        synchronized(appendCounterLock) {
            long currentTime = System.currentTimeMillis();
            if(appendCounterStart==-1) {
                appendCounterStart = currentTime;
                appendCounter = 0;
            } else {
                appendCounter++;
                long span = currentTime - appendCounterStart;
                if(span<0) {
                    if(log.isWarnEnabled()) log.warn("incAppendCounter: span<0: System time reset?");
                    appendCounterStart = currentTime;
                    appendCounter = 0;
                } else if(span>=60000) {
                    long milliMessagesPerSecond = appendCounter * 1000000 / span;
                    if(log.isInfoEnabled()) log.info("Copied "+SQLUtility.getMilliDecimal(milliMessagesPerSecond)+" messages per second");
                    appendCounterStart = currentTime;
                    appendCounter = 0;
                }
            }
        }
    }

    /**
     * Flags.equals is returning false when the objects are equal.  Perhaps there
     * is a problem with one Flags having null user flags while the other
     * has an empty array.  Don't really care - this is the fix.
     */
    private static boolean equals(Flags flags1, Flags flags2) {
        Flags.Flag[] systemFlags1 = flags1.getSystemFlags();
        Flags.Flag[] systemFlags2 = flags2.getSystemFlags();
        if(systemFlags1.length!=systemFlags2.length) return false;
        for(Flags.Flag flag : systemFlags1) if(!flags2.contains(flag)) return false;

        String[] userFlags1 = flags1.getUserFlags();
        String[] userFlags2 = flags2.getUserFlags();
        if(userFlags1.length!=userFlags2.length) return false;
        for(String flag : userFlags1) if(!flags2.contains(flag)) return false;
        
        return true;
    }

    private static void convertImapFile(
        final PrintWriter logOut,
        final String username,
        final int junkRetention,
        final int trashRetention,
        final UnixFile file,
        final UnixFile backupFile,
        final String folderName,
        final String[] tempPassword,
        final UnixFile passwordBackup,
        final Stat tempStat
    ) throws IOException, SQLException, MessagingException {
        // Careful not a symolic link
        if(!file.getStat().isRegularFile()) throw new IOException("Not a regular file: "+file.getPath());

        // Backup file
        if(!backupFile.getStat(tempStat).exists()) {
            log(logOut, LogLevel.DEBUG, username, "Backing-up \""+folderName+"\" to \""+backupFile.getPath()+"\"");
            UnixFile tempFile = UnixFile.mktemp(backupFile.getPath()+".", false);
            file.copyTo(tempFile, true);
            tempFile.chown(UnixFile.ROOT_UID, UnixFile.ROOT_GID).setMode(0600).renameTo(backupFile);
        }

        // Delete the file if it is not a mailbox or is empty
        String filename = file.getFile().getName();
        if(
            filename.startsWith(".")
            && (
                filename.endsWith(".index.ids")
                || filename.endsWith(".index")
                || filename.endsWith(".index.sorted")
            )
        ) {
            log(logOut, LogLevel.DEBUG, username, "Deleting non-mailbox file: "+file.getPath());
            file.delete();
        } else if(file.getStat().getSize()==0) {
            log(logOut, LogLevel.DEBUG, username, "Deleting empty mailbox file: "+file.getPath());
            file.delete();
        } else {
            // Get Old Store
            boolean deleteOldFolder;
            IMAPStore oldStore = getOldUserStore(logOut, username, tempPassword, passwordBackup, tempStat);
            try {
                // Get Old Folder
                IMAPFolder oldFolder = (IMAPFolder)oldStore.getFolder(folderName);
                try {
                    if(!oldFolder.exists()) throw new MessagingException(username+": Old folder doesn't exist: "+folderName);
                    oldFolder.open(Folder.READ_WRITE);
                    // Get New Store
                    IMAPStore newStore = getNewUserStore(logOut, username, tempPassword, passwordBackup, tempStat);
                    try {
                        // Get New Folder
                        IMAPFolder newFolder = (IMAPFolder)newStore.getFolder(folderName);
                        try {
                            // Should already exist
                            if(!newFolder.exists()) throw new MessagingException(username+": New folder doesn't exist: "+folderName);
                            newFolder.open(Folder.READ_WRITE);

                            // Subscribe to new folder if not yet subscribed
                            if(!newFolder.isSubscribed()) {
                                log(logOut, LogLevel.DEBUG, username, "Subscribing to mailbox: "+folderName);
                                newFolder.setSubscribed(true);
                            }

                            Message[] oldMessages = oldFolder.getMessages();
                            for(int c=0, len=oldMessages.length; c<len; c++) {
                                Message oldMessage = oldMessages[c];
                                // Make sure not already finished this message
                                if(oldMessage.isSet(Flags.Flag.DELETED)) {
                                    log(logOut, LogLevel.TRACE, username, "\""+folderName+"\": Skipping deleted message "+(c+1)+" of "+len+" ("+StringUtility.getApproximateSize(oldMessage.getSize())+")");
                                } else {
                                    long messageAge = (System.currentTimeMillis() - oldMessage.getReceivedDate().getTime()) / (24L*60*60*1000);
                                    if(
                                        junkRetention!=-1
                                        && "Junk".equals(folderName)
                                        && messageAge>junkRetention
                                    ) {
                                        log(logOut, LogLevel.TRACE, username, "\""+folderName+"\": Deleting old junk message ("+messageAge+">"+junkRetention+" days) "+(c+1)+" of "+len+" ("+StringUtility.getApproximateSize(oldMessage.getSize())+")");
                                        oldMessage.setFlag(Flags.Flag.DELETED, true);
                                    } else if(
                                        trashRetention!=-1
                                        && "Trash".equals(folderName)
                                        && messageAge>trashRetention
                                    ) {
                                        log(logOut, LogLevel.TRACE, username, "\""+folderName+"\": Deleting old trash message ("+messageAge+">"+trashRetention+" days) "+(c+1)+" of "+len+" ("+StringUtility.getApproximateSize(oldMessage.getSize())+")");
                                        oldMessage.setFlag(Flags.Flag.DELETED, true);
                                    } else {
                                        log(logOut, LogLevel.TRACE, username, "\""+folderName+"\": Copying message "+(c+1)+" of "+len+" ("+StringUtility.getApproximateSize(oldMessage.getSize())+")");
                                        try {
                                            Flags oldFlags = oldMessage.getFlags();

                                            // Copy to newFolder
                                            incAppendCounter();
                                            AppendUID[] newUids = newFolder.appendUIDMessages(new Message[] {oldMessage});
                                            if(newUids.length!=1) throw new MessagingException("newUids.length!=1: "+newUids.length);
                                            AppendUID newUid = newUids[0];
                                            if(newUid==null) throw new MessagingException("newUid is null");

                                            // Make sure the flags match
                                            long newUidNum = newUid.uid;
                                            Message newMessage = newFolder.getMessageByUID(newUidNum);
                                            if(newMessage==null) throw new MessagingException(username+": \""+folderName+"\": Unable to find new message by UID: "+newUidNum);
                                            Flags newFlags = newMessage.getFlags();

                                            // Remove the recent flag if added by append
                                            Flags effectiveOldFlags = new Flags(oldFlags);
                                            Flags effectiveNewFlags = new Flags(newFlags);
                                            for(Flags.Flag flag : systemFlags) {
                                                if(oldFlags.contains(flag)) {
                                                    if(!newFlags.contains(flag)) {
                                                        // New should have
                                                    }
                                                } else {
                                                    if(newFlags.contains(flag)) {
                                                        // New should not have
                                                        if(
                                                            // This is OK to ignore since it was added by append
                                                            flag==Flags.Flag.RECENT
                                                        ) {
                                                            // This failed: newMessage.setFlag(flag, false);
                                                            effectiveNewFlags.remove(flag);
                                                        } else if(
                                                            // This was set by append but needs to be unset
                                                            flag==Flags.Flag.SEEN
                                                        ) {
                                                            newMessage.setFlag(flag, false);
                                                            newFlags = newMessage.getFlags();
                                                            effectiveNewFlags.remove(flag);
                                                        }
                                                    }
                                                }
                                            }
                                            for(String flag : oldFlags.getUserFlags()) {
                                                if(!effectiveNewFlags.contains(flag)) {
                                                    // Ignore "$NotJunk" on oldFlags
                                                    //if(flag.equals("$NotJunk")) {
                                                    //    effectiveOldFlags.remove(flag);
                                                    //} else {
                                                        // Add the user flag
                                                        effectiveNewFlags.add(flag);
                                                        newMessage.setFlags(new Flags(flag), true);
                                                        newFlags = newMessage.getFlags();
                                                    //}
                                                }
                                            }

                                            if(!equals(effectiveOldFlags, effectiveNewFlags)) {
                                                for(Flags.Flag flag : effectiveOldFlags.getSystemFlags()) {
                                                    log(logOut, LogLevel.ERROR, username, "\""+folderName+"\": effectiveOldFlags: system: \""+getFlagName(flag)+'"');
                                                }
                                                for(String flag : effectiveOldFlags.getUserFlags()) {
                                                    log(logOut, LogLevel.ERROR, username, "\""+folderName+"\": effectiveOldFlags: user: \""+flag+'"');
                                                }
                                                for(Flags.Flag flag : effectiveNewFlags.getSystemFlags()) {
                                                    log(logOut, LogLevel.ERROR, username, "\""+folderName+"\": effectiveNewFlags: system: \""+getFlagName(flag)+'"');
                                                }
                                                for(String flag : effectiveNewFlags.getUserFlags()) {
                                                    log(logOut, LogLevel.ERROR, username, "\""+folderName+"\": effectiveNewFlags: user: \""+flag+'"');
                                                }
                                                throw new MessagingException(username+": \""+folderName+"\": effectiveOldFlags!=effectiveNewFlags: "+effectiveOldFlags+"!="+effectiveNewFlags);
                                            }

                                            // Flag as deleted on the old folder
                                            oldMessage.setFlag(Flags.Flag.DELETED, true);
                                        } catch(MessagingException err) {
                                            String message = err.getMessage();
                                            if(message!=null && message.endsWith(" NO Message contains invalid header")) {
                                                log(logOut, LogLevel.WARN, username, "\""+folderName+"\": Not able to copy message: "+message);
                                                Enumeration headers = oldMessage.getAllHeaders();
                                                while(headers.hasMoreElements()) {
                                                    Header header = (Header)headers.nextElement();
                                                    log(logOut, LogLevel.WARN, username, "\""+folderName+"\": \""+header.getName()+"\" = \""+header.getValue()+"\"");
                                                }
                                            } else throw err;
                                        }
                                    }
                                }
                            }
                        } finally {
                            if(newFolder.isOpen()) newFolder.close(false);
                        }
                    } finally {
                        newStore.close();
                    }
                    // Confirm that all messages in the old folder have delete flag
                    int notDeletedCount = 0;
                    Message[] oldMessages = oldFolder.getMessages();
                    for(Message oldMessage : oldMessages) {
                        if(!oldMessage.isSet(Flags.Flag.DELETED)) notDeletedCount++;
                    }
                    if(notDeletedCount>0) {
                        log(logOut, LogLevel.WARN, username, "Unable to delete mailbox \""+folderName+"\": "+notDeletedCount+" of "+oldMessages.length+" old messages not flagged as deleted");
                        deleteOldFolder = false;
                    } else {
                        deleteOldFolder = true;
                    }
                } finally {
                    // Make sure closed
                    if(oldFolder.isOpen()) oldFolder.close(true);
                }
                // Delete old folder if completely empty, error otherwise
                if(deleteOldFolder && !folderName.equals("INBOX")) {
                    log(logOut, LogLevel.DEBUG, username, "Deleting mailbox: "+folderName);
                    if(!oldFolder.delete(false)) throw new IOException(username+": Unable to delete mailbox: "+folderName);
                }
            } finally {
                oldStore.close();
            }
            if(deleteOldFolder && file.getStat(tempStat).exists()) {
                // If INBOX, need to remove file
                if(folderName.equals("INBOX")) {
                    log(logOut, LogLevel.DEBUG, username, "Deleting mailbox file: "+file.getPath());
                    file.delete();
                } else {
                    // Confirm file should is gone
                    throw new IOException(username+": File still exists: "+file.getPath());
                }
            }
        }
    }

    private enum LogLevel {
        TRACE,
        DEBUG,
        WARN,
        ERROR
    }

    /**
     * Logs a message as trace on commons-logging and on the per-user log.
     */
    private static void log(PrintWriter userLogOut, LogLevel logLevel, String username, String message) {
        switch(logLevel) {
            case TRACE :
                if(log.isTraceEnabled()) log.trace(username+" - "+message);
                break;
            case DEBUG :
                if(log.isDebugEnabled()) log.debug(username+" - "+message);
                break;
            case WARN :
                if(log.isWarnEnabled()) log.warn(username+" - "+message);
                break;
            case ERROR :
                if(log.isErrorEnabled()) log.error(username+" - "+message);
                break;
            default :
                throw new AssertionError("Unexpected value for logLevel: "+logLevel);
        }
        synchronized(userLogOut) {
            userLogOut.println("["+logLevel+"] "+System.currentTimeMillis()+" - "+message);
            userLogOut.flush();
        }
    }

    private static void rebuildUsers() throws IOException, SQLException, MessagingException {
        try {
            // Connect to the store (will be null when not an IMAP server)
            IMAPStore store = getStore();
            if(store==null) throw new SQLException("Not an IMAP server");
            // Verify all email users - only users who have a home under /home/ are considered
            List<LinuxServerAccount> lsas = AOServDaemon.getThisAOServer().getLinuxServerAccounts();
            Set<String> validEmailUsernames = new HashSet<String>(lsas.size()*4/3+1);
            // Conversions are done concurrently
            Map<LinuxServerAccount,Future<Object>> convertors = WUIMAP_CONVERSION_ENABLED ? new HashMap<LinuxServerAccount,Future<Object>>(lsas.size()*4/3+1) : null;
            ExecutorService executorService = WUIMAP_CONVERSION_ENABLED ? Executors.newFixedThreadPool(WUIMAP_CONVERSION_CONCURRENCY) : null;
            try {
                for(final LinuxServerAccount lsa : lsas) {
                    LinuxAccount la = lsa.getLinuxAccount();
                    final String homePath = lsa.getHome();
                    if(la.getType().isEmail() && homePath.startsWith("/home/")) {
                        // Split into user and domain
                        final String laUsername = la.getUsername().getUsername();
                        String user = getUser(laUsername);
                        String domain = getDomain(laUsername);
                        validEmailUsernames.add(laUsername);

                        // INBOX
                        String inboxFolderName = getFolderName(user, domain, "");
                        IMAPFolder inboxFolder = (IMAPFolder)store.getFolder(inboxFolderName);
                        try {
                            if(!inboxFolder.exists()) {
                                if(log.isDebugEnabled()) log.debug("Creating mailbox: "+inboxFolderName);
                                if(!inboxFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                                    throw new MessagingException("Unable to create folder: "+inboxFolder.getFullName());
                                }
                            }
                            rebuildAcl(inboxFolder, LinuxAccount.CYRUS, "default", new Rights("ackrx"));
                            rebuildAcl(inboxFolder, user, domain, new Rights("acdeiklprstwx"));
                        } finally {
                            if(inboxFolder.isOpen()) inboxFolder.close(false);
                            inboxFolder = null;
                        }

                        // Trash
                        String trashFolderName = getFolderName(user, domain, "Trash");
                        IMAPFolder trashFolder = (IMAPFolder)store.getFolder(trashFolderName);
                        try {
                            if(!trashFolder.exists()) {
                                if(log.isDebugEnabled()) log.debug("Creating mailbox: "+trashFolderName);
                                if(!trashFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                                    throw new MessagingException("Unable to create folder: "+trashFolder.getFullName());
                                }
                            }
                            rebuildAcl(trashFolder, LinuxAccount.CYRUS, "default", new Rights("ackrx"));
                            rebuildAcl(trashFolder, user, domain, new Rights("acdeiklprstwx"));

                            // Set/update expire annotation
                            String existingValue = getAnnotation(trashFolder, "/vendor/cmu/cyrus-imapd/expire", "value.shared");
                            int trashRetention = lsa.getTrashEmailRetention();
                            String expectedValue = trashRetention==-1 ? null : Integer.toString(trashRetention);
                            if(!StringUtility.equals(existingValue, expectedValue)) {
                                if(log.isDebugEnabled()) log.debug("Setting mailbox expiration: "+trashFolderName+": "+expectedValue);
                                setAnnotation(trashFolder, "/vendor/cmu/cyrus-imapd/expire", expectedValue, "text/plain");
                            }
                        } finally {
                            if(trashFolder.isOpen()) trashFolder.close(false);
                            trashFolder = null;
                        }

                        // Junk
                        String junkFolderName = getFolderName(user, domain, "Junk");
                        IMAPFolder junkFolder = (IMAPFolder)store.getFolder(junkFolderName);
                        try {
                            if(lsa.getEmailSpamAssassinIntegrationMode().getName().equals(EmailSpamAssassinIntegrationMode.IMAP)) {
                                // Junk folder required for IMAP mode
                                if(!junkFolder.exists()) {
                                    if(log.isDebugEnabled()) log.debug("Creating mailbox: "+junkFolderName);
                                    if(!junkFolder.create(Folder.HOLDS_FOLDERS | Folder.HOLDS_MESSAGES)) {
                                        throw new MessagingException("Unable to create folder: "+junkFolder.getFullName());
                                    }
                                }
                            }
                            if(junkFolder.exists()) {
                                rebuildAcl(junkFolder, LinuxAccount.CYRUS, "default", new Rights("ackrx"));
                                rebuildAcl(junkFolder, user, domain, new Rights("acdeiklprstwx"));

                                // Set/update expire annotation
                                String existingValue = getAnnotation(junkFolder, "/vendor/cmu/cyrus-imapd/expire", "value.shared");
                                int junkRetention = lsa.getJunkEmailRetention();
                                String expectedValue = junkRetention==-1 ? null : Integer.toString(junkRetention);
                                if(!StringUtility.equals(existingValue, expectedValue)) {
                                    if(log.isDebugEnabled()) log.debug("Setting mailbox expiration: "+junkFolderName+": "+expectedValue);
                                    setAnnotation(junkFolder, "/vendor/cmu/cyrus-imapd/expire", expectedValue, "text/plain");
                                }
                            }
                        } finally {
                            if(junkFolder.isOpen()) junkFolder.close(false);
                            junkFolder = null;
                        }

                        if(WUIMAP_CONVERSION_ENABLED) {
                            convertors.put(
                                lsa,
                                executorService.submit(
                                    new Callable<Object>() {
                                        public Object call() throws IOException, SQLException, MessagingException {
                                            Stat tempStat = new Stat();
                                            // Create the backup directory
                                            if(!wuBackupDirectory.getStat(tempStat).exists()) {
                                                if(log.isDebugEnabled()) log.debug("Creating directory: "+wuBackupDirectory.getPath());
                                                wuBackupDirectory.mkdir(true, 0700);
                                            }
                                            UnixFile userBackupDirectory = new UnixFile(wuBackupDirectory, laUsername, false);
                                            if(!userBackupDirectory.getStat(tempStat).exists()) {
                                                if(log.isDebugEnabled()) log.debug(laUsername+": Creating backup directory: "+userBackupDirectory.getPath());
                                                userBackupDirectory.mkdir(false, 0700);
                                            }
                                            
                                            // Per-user logs
                                            UnixFile logFile = new UnixFile(userBackupDirectory, "log", false);
                                            if(log.isTraceEnabled()) log.trace(laUsername+": Using logfile: "+logFile.getPath());
                                            PrintWriter logOut = new PrintWriter(new FileOutputStream(logFile.getFile(), true));
                                            try {
                                                if(logFile.getStat(tempStat).getMode()!=0600) logFile.setMode(0600);
                                                // Password backup is delayed until immediately before the password is reset.
                                                // This avoids unnecessary password resets.
                                                UnixFile passwordBackup = new UnixFile(userBackupDirectory, "passwd", false);

                                                // Backup the mailboxlist
                                                UnixFile homeDir = new UnixFile(homePath);
                                                UnixFile mailBoxListFile = new UnixFile(homeDir, ".mailboxlist", false);
                                                if(mailBoxListFile.getStat(tempStat).exists()) {
                                                    if(!tempStat.isRegularFile()) throw new IOException("Not a regular file: "+mailBoxListFile.getPath());
                                                    UnixFile mailBoxListBackup = new UnixFile(userBackupDirectory, "mailboxlist", false);
                                                    if(!mailBoxListBackup.getStat(tempStat).exists()) {
                                                        log(logOut, LogLevel.DEBUG, laUsername, "Backing-up mailboxlist");
                                                        UnixFile tempFile = UnixFile.mktemp(mailBoxListBackup.getPath()+".", false);
                                                        mailBoxListFile.copyTo(tempFile, true);
                                                        tempFile.chown(UnixFile.ROOT_UID, UnixFile.ROOT_GID).setMode(0600).renameTo(mailBoxListBackup);
                                                    }
                                                }

                                                // The password will be reset to a random value upon first use, subsequent
                                                // accesses will use the same password.
                                                String[] tempPassword = new String[1];
                                                int junkRetention = lsa.getJunkEmailRetention();
                                                int trashRetention = lsa.getTrashEmailRetention();
                                                // Convert old INBOX
                                                UnixFile inboxFile = new UnixFile(mailSpool, laUsername);
                                                if(inboxFile.getStat(tempStat).exists()) {
                                                    if(!tempStat.isRegularFile()) throw new IOException("Not a regular file: "+inboxFile.getPath());
                                                    convertImapFile(logOut, laUsername, junkRetention, trashRetention, inboxFile, new UnixFile(userBackupDirectory, "INBOX", false), "INBOX", tempPassword, passwordBackup, tempStat);
                                                }

                                                // Convert old folders from UW software
                                                if(!"/home/a/acccorpapp".equals(homeDir.getPath())) {
                                                    UnixFile mailDir = new UnixFile(homeDir, "Mail", false);
                                                    if(mailDir.getStat(tempStat).exists()) {
                                                        if(!tempStat.isDirectory()) throw new IOException("Not a directory: "+mailDir.getPath());
                                                        convertImapDirectory(logOut, laUsername, junkRetention, trashRetention, mailDir, new UnixFile(userBackupDirectory, "Mail", false), "", tempPassword, passwordBackup, tempStat);
                                                    }
                                                }

                                                // Remove the mailboxlist file
                                                if(mailBoxListFile.getStat(tempStat).exists()) mailBoxListFile.delete();

                                                // Restore passwd, if needed
                                                if(passwordBackup.getStat(tempStat).exists()) {
                                                    String currentEncryptedPassword = LinuxAccountManager.getEncryptedPassword(laUsername);
                                                    String savedEncryptedPassword;
                                                    BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(passwordBackup.getFile())));
                                                    try {
                                                        savedEncryptedPassword = in.readLine();
                                                    } finally {
                                                        in.close();
                                                    }
                                                    if(savedEncryptedPassword==null) throw new IOException("Unable to load saved password");
                                                    if(!savedEncryptedPassword.equals(currentEncryptedPassword)) {
                                                        log(logOut, LogLevel.DEBUG, laUsername, "Restoring password");
                                                        LinuxAccountManager.setEncryptedPassword(laUsername, savedEncryptedPassword);
                                                        UnixFile passwordBackupOld = new UnixFile(userBackupDirectory, "passwd.old", false);
                                                        passwordBackup.renameTo(passwordBackupOld);
                                                    } else {
                                                        passwordBackup.delete();
                                                    }
                                                }
                                            } finally {
                                                logOut.close();
                                            }
                                            return null;
                                        }
                                    }
                                )
                            );
                        }
                    }
                }
                if(WUIMAP_CONVERSION_ENABLED) {
                    List<LinuxServerAccount> deleteMe = new ArrayList<LinuxServerAccount>();
                    while(!convertors.isEmpty()) {
                        deleteMe.clear();
                        for(Map.Entry<LinuxServerAccount,Future<Object>> entry : convertors.entrySet()) {
                            LinuxServerAccount lsa = entry.getKey();
                            Future<Object> future = entry.getValue();
                            // Wait for completion
                            try {
                                future.get(1, TimeUnit.SECONDS);
                                deleteMe.add(lsa);
                            } catch(InterruptedException err) {
                                AOServDaemon.reportWarning(err, new Object[] {"lsa="+lsa});
                                // Will retry on next loop
                            } catch(ExecutionException err) {
                                Object[] extraInfo;
                                Throwable cause = err.getCause();
                                if(cause!=null && (cause instanceof ReadOnlyFolderException)) {
                                    ReadOnlyFolderException rofe = (ReadOnlyFolderException)cause;
                                    extraInfo = new Object[] {
                                        "lsa="+lsa,
                                        "folder="+rofe.getFolder().getFullName()
                                    };
                                } else {
                                    extraInfo = new Object[] {"lsa="+lsa};
                                }
                                AOServDaemon.reportError(err, extraInfo);
                                deleteMe.add(lsa);
                            } catch(TimeoutException err) {
                                // This is OK, will just retry on next loop
                            }
                        }
                        for(LinuxServerAccount lsa : deleteMe) convertors.remove(lsa);
                    }
                }
            } finally {
                if(WUIMAP_CONVERSION_ENABLED) executorService.shutdown();
            }

            // Get the list of domains and users from the filesystem
            // (including the default).
            Map<String,Set<String>> allUsers = new HashMap<String,Set<String>>();

            // The default users are in /var/spool/imap/?/user/*
            addUserDirectories(imapSpool, imapSpoolIgnoreDirectories, "default", allUsers);

            // The virtdomains are in /var/spool/imap/domain/?/*
            String[] hashDirs = imapVirtDomainSpool.list();
            if(hashDirs!=null) {
                Arrays.sort(hashDirs);
                for(String hashDirName : hashDirs) {
                    File hashDir = new File(imapVirtDomainSpool, hashDirName);
                    String[] domainDirs = hashDir.list();
                    if(domainDirs!=null) {
                        Arrays.sort(domainDirs);
                        for(String domain : domainDirs) {
                            addUserDirectories(new File(hashDir, domain), null, domain, allUsers);
                        }
                    }
                }
            }

            for(String domain : allUsers.keySet()) {
                for(String user : allUsers.get(domain)) {
                    String lsaUsername;
                    if(domain.equals("default")) lsaUsername = user;
                    else lsaUsername = user+'@'+domain;
                    if(!validEmailUsernames.contains(lsaUsername)) {
                        String cyrusFolder = getFolderName(user, domain, "");

                        // Make sure the user folder exists
                        IMAPFolder userFolder = (IMAPFolder)store.getFolder(cyrusFolder);
                        try {
                            if(!userFolder.exists()) throw new MessagingException("Folder doesn't exist: "+cyrusFolder);
                            // TODO: Backup mailbox to /var/oldaccounts
                            rebuildAcl(userFolder, LinuxAccount.CYRUS, "default", new Rights("acdkrx")); // Adds the d permission
                            if(log.isDebugEnabled()) log.debug("Deleting mailbox: "+cyrusFolder);
                            if(!userFolder.delete(true)) throw new IOException("Unable to delete mailbox: "+cyrusFolder);
                        } finally {
                            if(userFolder.isOpen()) userFolder.close(false);
                        }
                    }
                }
            }
        } catch(RuntimeException err) {
            closeStore();
            throw err;
        } catch(IOException err) {
            closeStore();
            throw err;
        } catch(SQLException err) {
            closeStore();
            throw err;
        } catch(MessagingException err) {
            closeStore();
            throw err;
        }
    }

    /**
     * Remove after testing, or move to JUnit tests.
     */
    /*public static void main(String[] args) {
        for(int c=0;c<10;c++) benchmark();
        try {
            rebuildUsers();

            String[] folders = {"INBOX", "Junk", "No Existie"};
            long[] sizes = getImapFolderSizes("cyrus.test2", folders);
            for(int c=0;c<folders.length;c++) {
                System.out.println(folders[c]+": "+sizes[c]);
            }
            
            for(LinuxServerAccount lsa : AOServDaemon.getThisAOServer().getLinuxServerAccounts()) {
                LinuxAccount la = lsa.getLinuxAccount();
                if(la.getType().isEmail() && lsa.getHome().startsWith("/home/")) {
                    String username = la.getUsername().getUsername();
                    System.out.println(username+": "+getInboxSize(username));
                    DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.US);
                    System.out.println(username+": "+dateFormat.format(new Date(getInboxModified(username))));
                }
            }
        } catch(Exception err) {
            ErrorPrinter.printStackTraces(err);
        }
    }*/

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(ImapManager.class)
                && imapManager==null
            ) {
                System.out.print("Starting ImapManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                imapManager=new ImapManager();
                connector.aoServers.addTableListener(imapManager, 0);
                connector.ipAddresses.addTableListener(imapManager, 0);
                connector.linuxAccounts.addTableListener(imapManager, 0);
                connector.linuxServerAccounts.addTableListener(imapManager, 0);
                connector.netBinds.addTableListener(imapManager, 0);
                connector.servers.addTableListener(imapManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild IMAP and Cyrus configurations";
    }

    public static long[] getImapFolderSizes(String username, String[] folderNames) throws IOException, SQLException, MessagingException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        LinuxServerAccount lsa=thisAOServer.getLinuxServerAccount(username);
        if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+thisAOServer);
        long[] sizes=new long[folderNames.length];
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            for(int c=0;c<folderNames.length;c++) {
                String folderName = folderNames[c];
                if(folderName.indexOf("..") !=-1) sizes[c]=-1;
                else {
                    File folderFile;
                    if(folderName.equals("INBOX")) folderFile=new File(mailSpool, username);
                    else folderFile=new File(new File(lsa.getHome(), "Mail"), folderName);
                    if(folderFile.exists()) sizes[c]=folderFile.length();
                    else sizes[c]=-1;
                }
            }
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            String user = getUser(username);
            String domain = getDomain(username);
            for(int c=0;c<folderNames.length;c++) {
                String folderName = folderNames[c];
                if(folderName.indexOf("..") !=-1) sizes[c]=-1;
                else {
                    boolean isInbox = folderName.equals("INBOX");
                    sizes[c] = getCyrusFolderSize(user, isInbox ? "" : folderName, domain, !isInbox);
                }
            }
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
        return sizes;
    }

    public static void setImapFolderSubscribed(String username, String folderName, boolean subscribed) throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        LinuxServerAccount lsa=thisAOServer.getLinuxServerAccount(username);
        if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+thisAOServer);
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            UnixFile mailboxlist=new UnixFile(lsa.getHome(), ".mailboxlist");
            List<String> lines=new ArrayList<String>();
            boolean currentlySubscribed=false;
            if(mailboxlist.getStat().exists()) {
                BufferedReader in=new BufferedReader(new InputStreamReader(mailboxlist.getSecureInputStream()));
                try {
                    String line;
                    while((line=in.readLine())!=null) {
                        lines.add(line);
                        if(line.equals(folderName)) currentlySubscribed=true;
                    }
                } finally {
                    in.close();
                }
            }
            if(subscribed!=currentlySubscribed) {
                PrintWriter out=new PrintWriter(mailboxlist.getSecureOutputStream(lsa.getUID().getID(), lsa.getPrimaryLinuxServerGroup().getGID().getID(), 0644, true));
                try {
                    for(int c=0;c<lines.size();c++) {
                        String line=lines.get(c);
                        if(subscribed || !line.equals(folderName)) {
                            // Only print if the folder still exists
                            if(
                                line.equals("INBOX")
                                || line.equals("Drafts")
                                || line.equals("Trash")
                                || line.equals("Junk")
                            ) out.println(line);
                            else {
                                File folderFile=new File(new File(lsa.getHome(), "Mail"), line);
                                if(folderFile.exists()) out.println(line);
                            }
                        }
                    }
                    if(subscribed) out.println(folderName);
                } finally {
                    out.close();
                }
            }
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            throw new SQLException("Cyrus folders should be subscribed/unsubscribed from IMAP directly because subscribe list is stored per user basis.");
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }

    static class Annotation {
        private final String mailboxName;
        private final String entry;
        private final Map<String,String> attributes;
        
        Annotation(String mailboxName, String entry, Map<String,String> attributes) {
            this.mailboxName = mailboxName;
            this.entry = entry;
            this.attributes = attributes;
        }
        
        String getMailboxName() {
            return mailboxName;
        }
        
        String getEntry() {
            return entry;
        }
        
        String getAttribute(String attributeSpecifier) {
            return attributes.get(attributeSpecifier);
        }
    }

    /**
     * Gets all of the annotations for the provided folder, entry, and attribute.
     * 
     * This uses ANNOTATEMORE
     *     Current: http://vman.de/cyrus/draft-daboo-imap-annotatemore-07.html
     *     Newer:   http://vman.de/cyrus/draft-daboo-imap-annotatemore-10.html
     *
     * ad GETANNOTATION user/cyrus.test/Junk@suspendo.aoindustries.com "/vendor/cmu/cyrus-imapd/size" "value.shared"
     * ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/size" ("value.shared" "33650")
     * ad OK Completed
     * 
     * http://java.sun.com/products/javamail/javadocs/com/sun/mail/imap/IMAPFolder.html#doCommand(com.sun.mail.imap.IMAPFolder.ProtocolCommand)
     * 
     * https://glassfish.dev.java.net/javaee5/mail/
     * javamail@sun.com
     */
    @SuppressWarnings({"unchecked"})
    private static List<Annotation> getAnnotations(IMAPFolder folder, final String entry, final String attribute) throws MessagingException {
        final String mailboxName = folder.getFullName();
        List<Annotation> annotations = (List)folder.doCommand(
            new IMAPFolder.ProtocolCommand() {
                public Object doCommand(IMAPProtocol p) throws ProtocolException {
                    // Issue command
                    Argument args = new Argument();
                    args.writeString(mailboxName);
                    args.writeQString(entry);
                    args.writeQString(attribute);

                    Response[] r = p.command("GETANNOTATION", args);
                    Response response = r[r.length-1];

                    // Grab response
                    List<Annotation> annotations = new ArrayList<Annotation>(r.length-1);
                    if (response.isOK()) { // command succesful 
                        for (int i = 0, len = r.length; i < len; i++) {
                            if (r[i] instanceof IMAPResponse) {
                                IMAPResponse ir = (IMAPResponse)r[i];
                                if (ir.keyEquals("ANNOTATION")) {
                                    String mailboxName = ir.readAtomString();
                                    String entry = ir.readAtomString();
                                    String[] list = ir.readStringList();
                                    // Must be even number of elements in list
                                    if((list.length&1)!=0) throw new ProtocolException("Uneven number of elements in attribute list: "+list.length);
                                    Map<String,String> attributes = new HashMap<String,String>(list.length*2/3+1);
                                    for(int j=0; j<list.length; j+=2) {
                                        attributes.put(list[j], list[j+1]);
                                    }
                                    annotations.add(new Annotation(mailboxName, entry, attributes));
                                    // Mark as handled
                                    r[i] = null;
                                }
                            }
                        }
                    } else {
                        throw new ProtocolException("Response is not OK: "+response);
                    }

                    // dispatch remaining untagged responses
                    p.notifyResponseHandlers(r);
                    p.handleResult(response);

                    return annotations;
                }
            }
        );
        return annotations;
    }

    /**
     * Gets a single, specific annotation (specific in mailbox-name, entry-specifier, and attribute-specifier).
     * 
     * @return  the value if found or <code>null</code> if unavailable
     */
    private static String getAnnotation(IMAPFolder folder, String entry, String attribute) throws MessagingException {
        String folderName = folder.getFullName();
        List<Annotation> annotations = getAnnotations(folder, entry, attribute);
        for(Annotation annotation : annotations) {
            if(
                annotation.getMailboxName().equals(folderName)
                && annotation.getEntry().equals(entry)
            ) {
                // Look for the "value.shared" attribute
                String value = annotation.getAttribute(attribute);
                if(value!=null) return value;
            }
        }
        // Not found
        return null;
    }

    /**
     * Sets a single annotation.
     * 
     * @param  expectedValue  if <code>null</code>, annotation will be removed
     */
    private static void setAnnotation(IMAPFolder folder, final String entry, final String value, final String contentType) throws MessagingException {
        final String mailboxName = folder.getFullName();
        final String newValue;
        final String newContentType;
        if(value==null) {
            newValue = "NIL";
            newContentType = "NIL";
        } else {
            newValue = value;
            newContentType = contentType;
        }
        folder.doCommand(
            new IMAPFolder.ProtocolCommand() {
                public Object doCommand(IMAPProtocol p) throws ProtocolException {
                    // Issue command
                    Argument list = new Argument();
                    list.writeQString("value.shared");
                    list.writeQString(newValue);
                    list.writeQString("content-type.shared");
                    list.writeQString(newContentType);

                    Argument args = new Argument();
                    args.writeString(mailboxName);
                    args.writeQString(entry);
                    args.writeArgument(list);

                    Response[] r = p.command("SETANNOTATION", args);
                    Response response = r[r.length-1];

                    // Grab response
                    if (!response.isOK()) {
                        throw new ProtocolException("Response is not OK: "+response);
                    }

                    // dispatch remaining untagged responses
                    p.notifyResponseHandlers(r);
                    p.handleResult(response);

                    return null;
                }
            }
        );
    }

    private static long getCyrusFolderSize(String username, String folder, boolean notFoundOK) throws IOException, SQLException, MessagingException {
        return getCyrusFolderSize(getUser(username), folder, getDomain(username), notFoundOK);
    }

    /**
     * @param notFoundOK if <code>true</code> will return <code>-1</code> if annotation not found, MessagingException otherwise
     */
    private static long getCyrusFolderSize(String user, String folder, String domain, boolean notFoundOK) throws IOException, SQLException, MessagingException {
        try {
            // Connect to the store (will be null when not an IMAP server)
            IMAPStore store = getStore();
            if(store==null) {
                if(!notFoundOK) throw new MessagingException("Not an IMAP server");
                return -1;
            }
            String folderName = getFolderName(user, domain, folder);
            IMAPFolder mailbox = (IMAPFolder)store.getFolder(folderName);
            try {
                String value = getAnnotation(mailbox, "/vendor/cmu/cyrus-imapd/size", "value.shared");
                if(value!=null) return Long.parseLong(value);
                if(!notFoundOK) throw new MessagingException(folderName+": \"/vendor/cmu/cyrus-imapd/size\" \"value.shared\" annotation not found");
                return -1;
            } finally {
                if(mailbox.isOpen()) mailbox.close(false);
            }
        } catch(RuntimeException err) {
            closeStore();
            throw err;
        } catch(IOException err) {
            closeStore();
            throw err;
        } catch(SQLException err) {
            closeStore();
            throw err;
        } catch(MessagingException err) {
            closeStore();
            throw err;
        }
    }

    public static long getInboxSize(String username) throws IOException, SQLException, MessagingException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            return new File(mailSpool, username).length();
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            return getCyrusFolderSize(username, "", false);
            /*
ad GETANNOTATION user/cyrus.test/Junk@suspendo.aoindustries.com "*" "value.shared"
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/expire" ("value.shared" "31")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/condstore" ("value.shared" "false")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/lastpop" ("value.shared" " ")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/lastupdate" ("value.shared" " 7-Dec-2008 20:36:25 -0600")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/size" ("value.shared" "33650")
* ANNOTATION "user/cyrus.test/Junk@suspendo.aoindustries.com" "/vendor/cmu/cyrus-imapd/partition" ("value.shared" "default")
ad OK Completed
*/
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }

    public static long getInboxModified(String username) throws IOException, SQLException, MessagingException, ParseException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
            return new File(mailSpool, username).lastModified();
        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
            try {
                // Connect to the store
                IMAPStore store = getStore();
                if(store==null) {
                    // Not an IMAP server, consistent with File.lastModified() above
                    return 0L;
                }
                String user = getUser(username);
                String domain = getDomain(username);
                String inboxFolderName = getFolderName(user, domain, "");
                IMAPFolder inboxFolder = (IMAPFolder)store.getFolder(inboxFolderName);
                try {
                    String value = getAnnotation(inboxFolder, "/vendor/cmu/cyrus-imapd/lastupdate", "value.shared");
                    if(value==null) throw new MessagingException("username="+username+": \"/vendor/cmu/cyrus-imapd/lastupdate\" \"value.shared\" annotation not found");

                    // Parse values
                    // 8-Dec-2008 00:24:30 -0600
                    value = value.trim();
                    // Day
                    int hyphen1 = value.indexOf('-');
                    if(hyphen1==-1) throw new ParseException("Can't find first -", 0);
                    int day = Integer.parseInt(value.substring(0, hyphen1));
                    // Mon
                    int hyphen2 = value.indexOf('-', hyphen1+1);
                    if(hyphen2==-1) throw new ParseException("Can't find second -", hyphen1+1);
                    String monthString = value.substring(hyphen1+1, hyphen2);
                    int month;
                    if("Jan".equals(monthString)) month = Calendar.JANUARY;
                    else if("Feb".equals(monthString)) month = Calendar.FEBRUARY;
                    else if("Mar".equals(monthString)) month = Calendar.MARCH;
                    else if("Apr".equals(monthString)) month = Calendar.APRIL;
                    else if("May".equals(monthString)) month = Calendar.MAY;
                    else if("Jun".equals(monthString)) month = Calendar.JUNE;
                    else if("Jul".equals(monthString)) month = Calendar.JULY;
                    else if("Aug".equals(monthString)) month = Calendar.AUGUST;
                    else if("Sep".equals(monthString)) month = Calendar.SEPTEMBER;
                    else if("Oct".equals(monthString)) month = Calendar.OCTOBER;
                    else if("Nov".equals(monthString)) month = Calendar.NOVEMBER;
                    else if("Dec".equals(monthString)) month = Calendar.DECEMBER;
                    else throw new ParseException("Unexpected month: "+monthString, hyphen1+1);
                    // Year
                    int space1 = value.indexOf(' ', hyphen2+1);
                    if(space1==-1) throw new ParseException("Can't find first space", hyphen2+1);
                    int year = Integer.parseInt(value.substring(hyphen2+1, space1));
                    // Hour
                    int colon1 = value.indexOf(':', space1+1);
                    if(colon1==-1) throw new ParseException("Can't find first colon", space1+1);
                    int hour = Integer.parseInt(value.substring(space1+1, colon1));
                    // Minute
                    int colon2 = value.indexOf(':', colon1+1);
                    if(colon2==-1) throw new ParseException("Can't find second colon", colon1+1);
                    int minute = Integer.parseInt(value.substring(colon1+1, colon2));
                    // Second
                    int space2 = value.indexOf(' ', colon2+1);
                    if(space2==-1) throw new ParseException("Can't find second space", colon2+1);
                    int second = Integer.parseInt(value.substring(colon2+1, space2));
                    // time zone
                    int zoneHours = Integer.parseInt(value.substring(space2+1, value.length()-2));
                    int zoneMinutes = Integer.parseInt(value.substring(value.length()-2));
                    if(zoneHours<0) zoneMinutes = -zoneMinutes;

                    // Convert to correct time
                    Calendar cal = Calendar.getInstance(Locale.US);
                    cal.set(Calendar.ZONE_OFFSET, zoneHours*60*60*1000 + zoneMinutes*60*1000);
                    cal.set(Calendar.YEAR, year);
                    cal.set(Calendar.MONTH, month);
                    cal.set(Calendar.DAY_OF_MONTH, day);
                    cal.set(Calendar.HOUR_OF_DAY, hour);
                    cal.set(Calendar.MINUTE, minute);
                    cal.set(Calendar.SECOND, second);
                    cal.set(Calendar.MILLISECOND, 0);
                    return cal.getTimeInMillis();
                } finally {
                    if(inboxFolder.isOpen()) inboxFolder.close(false);
                }
            } catch(RuntimeException err) {
                closeStore();
                throw err;
            } catch(IOException err) {
                closeStore();
                throw err;
            } catch(SQLException err) {
                closeStore();
                throw err;
            } catch(MessagingException err) {
                closeStore();
                throw err;
            }
        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
    }
}
