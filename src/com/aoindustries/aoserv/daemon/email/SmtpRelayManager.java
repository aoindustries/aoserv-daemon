package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailSmtpRelay;
import com.aoindustries.aoserv.client.EmailSmtpRelayType;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.StringUtility;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls access to the mail server, supports auto-expiring SMTP access.
 *
 * @author  AO Industries, Inc.
 */
public class SmtpRelayManager extends BuilderThread implements Runnable {

    private static final int REFRESH_PERIOD = 15*60*1000;

    /**
     * sendmail configs
     */
    private static final String ACCESS_FILENAME="/etc/mail/access";
    private static final String NEW_FILE="/etc/mail/access.new";

    /**
     * qmail configs
     */
    /*private static final String
        qmailFile="/etc/tcp.smtp",
        newQmailFile="/etc/tcp.smtp.new"
    ;*/

    private static SmtpRelayManager smtpRelayManager;

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();
        AOServer aoServer=AOServDaemon.getThisAOServer();
        Server server = aoServer.getServer();

        int osv=server.getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

        EmailSmtpRelayType allowRelay=connector.getEmailSmtpRelayTypes().get(EmailSmtpRelayType.ALLOW_RELAY);
        //boolean isQmail=server.isQmail();

        // The IP addresses that have been used
        Set<String> usedIPs=new HashSet<String>();

        synchronized(rebuildLock) {
            UnixFile access, newFile;
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ChainWriter out=new ChainWriter(bout);
            try {
                /*if(isQmail) {
                    access=new UnixFile(qmailFile);
                    newFile=new UnixFile(newQmailFile);
                } else {*/
                    // Rebuild the new config file
                    out.print("# These entries were generated by ").print(SmtpRelayManager.class.getName()).print('\n');

                    access=new UnixFile(ACCESS_FILENAME);
                    newFile=new UnixFile(NEW_FILE);
                //}

                // Allow all of the local IP addresses
                for(NetDevice nd : server.getNetDevices()) {
                    for(IPAddress ia : nd.getIPAddresses()) {
                        String ip=ia.getIPAddress();
                        if(!usedIPs.contains(ip)) {
                            writeAccessLine(out, ip, allowRelay/*, isQmail*/);
                            usedIPs.add(ip);
                        }
                    }
                }

                // Deny first
                List<EmailSmtpRelay> relays = aoServer.getEmailSmtpRelays();
                for(EmailSmtpRelay ssr : relays) {
                    if(!ssr.isDisabled()) {
                        EmailSmtpRelayType esrt=ssr.getType();
                        String type=esrt.getName();
                        if(
                            type.equals(EmailSmtpRelayType.DENY)
                            || type.equals(EmailSmtpRelayType.DENY_SPAM)
                        ) {
                            long expiration=ssr.getExpiration();
                            if(expiration==EmailSmtpRelay.NO_EXPIRATION || expiration>System.currentTimeMillis()) {
                                String ip=ssr.getHost();
                                if(!usedIPs.contains(ip)) {
                                    writeAccessLine(out, ip, esrt/*, isQmail*/);
                                    usedIPs.add(ip);
                                }
                            }
                        }
                    }
                }

                // Allow last
                for(EmailSmtpRelay ssr : relays) {
                    if(!ssr.isDisabled()) {
                        EmailSmtpRelayType esrt=ssr.getType();
                        String type=esrt.getName();
                        if(
                            type.equals(EmailSmtpRelayType.ALLOW)
                            || type.equals(EmailSmtpRelayType.ALLOW_RELAY)
                        ) {
                            long expiration=ssr.getExpiration();
                            if(expiration==EmailSmtpRelay.NO_EXPIRATION || expiration>System.currentTimeMillis()) {
                                String ip=ssr.getHost();
                                if(!usedIPs.contains(ip)) {
                                    writeAccessLine(out, ip, esrt/*, isQmail*/);
                                    usedIPs.add(ip);
                                }
                            }
                        }
                    }
                }
            } finally {
                if(out!=null) {
                    out.close();
                }
            }
            byte[] newBytes = bout.toByteArray();

            if(!access.getStat().exists() || !access.contentEquals(newBytes)) {
                FileOutputStream newOut = newFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                try {
                    newOut.write(newBytes);
                } finally {
                    newOut.close();
                }
                newFile.renameTo(access);
                makeAccessMap();
            }
        }
    }
    private static void writeAccessLine(ChainWriter out, String host, EmailSmtpRelayType type/*, boolean isQmail*/) throws IOException, SQLException {
        /*if(isQmail) out.print(host).print(':').print(StringUtility.replace(type.getQmailConfig(), "%h", host)).print('\n');
        else*/ out.print("Connect:").print(host).print('\t').print(StringUtility.replace(type.getSendmailConfig(), "%h", host)).print('\n');
    }
    /**
     * Gets the number of dots in the String, returning a maximum of 3 even if there are more
     */
    /*
    private static int getDotCount(String S) {
        int count=0;
        int len=S.length();
        for(int c=0;c<len;c++) {
            if(S.charAt(c)=='.') {
                count++;
                if(count>=3) break;
            }
        }
        return count;
    }*/

    /*private static final String[] qmailctlCdbCommand={
        "/var/qmail/bin/qmailctl",
        "cdb"
    };*/

    private static final String[] mandrivaSendmailMakemapCommand={
        "/usr/aoserv/daemon/bin/make_sendmail_access_map"
    };

    private static final String[] centosSendmailMakemapCommand={
        "/opt/aoserv-daemon/bin/make_sendmail_access_map"
    };

    private static void makeAccessMap() throws IOException, SQLException {
        String[] command;
        /*if(AOServDaemon.getThisAOServer().isQmail()) command = qmailctlCdbCommand;
        else {*/
            int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) command = mandrivaSendmailMakemapCommand;
            else if(
                osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) command = centosSendmailMakemapCommand;
            else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
        //}
        AOServDaemon.exec(command);
    }

    private SmtpRelayManager() {
    }

    public void run() {
        long lastTime=Long.MIN_VALUE;
        while(true) {
            try {
                while(true) {
                    try {
                        Thread.sleep(REFRESH_PERIOD);
                    } catch(InterruptedException err) {
                        LogFactory.getLogger(SmtpRelayManager.class).log(Level.WARNING, null, err);
                    }
                    long time=System.currentTimeMillis();
                    boolean needRebuild=false;
                    for(EmailSmtpRelay relay : AOServDaemon.getThisAOServer().getEmailSmtpRelays()) {
                        long expires=relay.getExpiration();
                        if(
                            expires!=EmailSmtpRelay.NO_EXPIRATION
                            && expires>=lastTime
                            && expires<time
                        ) {
                            needRebuild=true;
                            break;
                        }
                    }
                    lastTime=time;
                    if(needRebuild) doRebuild();
                }
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(Throwable T) {
                LogFactory.getLogger(SmtpRelayManager.class).log(Level.SEVERE, null, T);
                try {
                    Thread.sleep(REFRESH_PERIOD);
                } catch(InterruptedException err) {
                    LogFactory.getLogger(SmtpRelayManager.class).log(Level.WARNING, null, err);
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
                && AOServDaemonConfiguration.isManagerEnabled(SmtpRelayManager.class)
                && smtpRelayManager==null
            ) {
                System.out.print("Starting SmtpRelayManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                smtpRelayManager=new SmtpRelayManager();
                connector.getEmailSmtpRelays().addTableListener(smtpRelayManager, 0);
                connector.getIpAddresses().addTableListener(smtpRelayManager, 0);
                connector.getNetDevices().addTableListener(smtpRelayManager, 0);
                new Thread(smtpRelayManager, "SmtpRelayManaged").start();
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild SMTP Relays";
    }

    @Override
    public long getProcessTimerMaximumTime() {
        return (long)30*60*1000;
    }
}
