package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2003-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.ServerFarm;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.email.jilter.JilterConfigurationWriter;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.jilter.config.JilterConfiguration;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.SortedArrayList;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Builds the sendmail.mc and sendmail.cf files as necessary.
 * 
 * @author  AO Industries, Inc.
 */
final public class SendmailCFManager extends BuilderThread {

    private static SendmailCFManager sendmailCFManager;

    private static final UnixFile
        sendmailMc=new UnixFile("/etc/mail/sendmail.mc"),
        sendmailMcNew=new UnixFile("/etc/mail/sendmail.mc.new"),
        sendmailCf=new UnixFile("/etc/mail/sendmail.cf"),
        sendmailCfNew=new UnixFile("/etc/mail/sendmail.cf.new"),
        submitMc=new UnixFile("/etc/mail/submit.mc"),
        submitMcNew=new UnixFile("/etc/mail/submit.mc.new"),
        submitCf=new UnixFile("/etc/mail/submit.cf"),
        submitCfNew=new UnixFile("/etc/mail/submit.cf.new")
    ;

    private SendmailCFManager() {
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        // Used on inner processing
        AOServConnector conn=AOServDaemon.getConnector();
        AOServer aoServer=AOServDaemon.getThisAOServer();
        Server server = aoServer.getServer();
        int osv=server.getOperatingSystemVersion().getPkey();
        ServerFarm serverFarm = server.getServerFarm();
        IPAddress primaryIpAddress = aoServer.getPrimaryIPAddress();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Stat tempStat = new Stat();
        
        synchronized(rebuildLock) {
            // Get the values used by different files once for internal consistency on dynamic data
            List<NetBind> smtpNetBinds = server.getNetBinds(conn.protocols.get(Protocol.SMTP));
            List<NetBind> smtpsNetBinds = server.getNetBinds(conn.protocols.get(Protocol.SMTPS));
            List<NetBind> submissionNetBinds = server.getNetBinds(conn.protocols.get(Protocol.SUBMISSION));

            // Build the new version of /etc/mail/sendmail.mc in RAM
            {
                ChainWriter out=new ChainWriter(bout);
                try {
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        out.print("divert(-1)\n"
                                + "dnl This is the macro config file used to generate the /etc/sendmail.cf\n"
                                + "dnl file. If you modify the file you will have to regenerate the\n"
                                + "dnl /etc/mail/sendmail.cf by running this macro config through the m4\n"
                                + "dnl preprocessor:\n"
                                + "dnl\n"
                                + "dnl        m4 /etc/mail/sendmail.mc > /etc/mail/sendmail.cf\n"
                                + "dnl\n"
                                + "dnl You will need to have the sendmail-cf package installed for this to\n"
                                + "dnl work.\n");
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print("divert(-1)dnl\n"
                                + "dnl #\n"
                                + "dnl # This is the sendmail macro config file for m4. If you make changes to\n"
                                + "dnl # /etc/mail/sendmail.mc, you will need to regenerate the\n"
                                + "dnl # /etc/mail/sendmail.cf file by confirming that the sendmail-cf package is\n"
                                + "dnl # installed and then performing a\n"
                                + "dnl #\n"
                                + "dnl #     make -C /etc/mail\n"
                                + "dnl #\n");
                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("divert(-1)dnl\n"
                                + "dnl #\n"
                                + "dnl # Generated by ").print(SendmailCFManager.class.getName()).print("\n"
                                + "dnl #\n");
                    } else {
                        throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                    out.print("include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
                            + "VERSIONID(`AO Industries, Inc.')dnl\n"   // AO added
                            + "define(`confDEF_USER_ID',``mail:mail'')dnl\n"
                            + "OSTYPE(`linux')dnl\n");
                    out.print("undefine(`UUCP_RELAY')dnl\n"
                            + "undefine(`BITNET_RELAY')dnl\n"
                            + "define(`confALIAS_WAIT', `30')dnl\n"
                            + "define(`confTO_CONNECT', `1m')dnl\n");
                    if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("define(`confTRY_NULL_MX_LIST', `True')dnl\n"
                                + "define(`confDONT_PROBE_INTERFACES', `True')dnl\n");
                    } else if(
                        osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                        || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                    ) {
                        out.print("define(`confTRY_NULL_MX_LIST',true)dnl\n"
                                + "define(`confDONT_PROBE_INTERFACES',true)dnl\n");
                    } else {
                        throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                    out.print("define(`PROCMAIL_MAILER_PATH',`/usr/bin/procmail')dnl\n");
                    if(
                        osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                        || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                    ) {
                        out.print("define(`ALIAS_FILE', `/etc/aliases')dnl\n"
                                + "define(`STATUS_FILE', `/var/log/mail/statistics')dnl\n"
                                + "define(`UUCP_MAILER_MAX', `2000000')dnl\n"
                                + "define(`confUSERDB_SPEC', `/etc/mail/userdb.db')dnl\n"
                                + "FEATURE(`smrsh',`/usr/sbin/smrsh')dnl\n");
                    }
                    out.print("dnl define delivery mode: interactive, background, or queued\n"
                            + "define(`confDELIVERY_MODE', `background')\n"
                            + "FEATURE(`mailertable',`hash -o /etc/mail/mailertable.db')dnl\n"
                            + "FEATURE(`virtuser_entire_domain')dnl\n"
                            + "FEATURE(`virtusertable',`hash -o /etc/mail/virtusertable.db')dnl\n"
                            + "FEATURE(redirect)dnl\n"
                            + "FEATURE(use_cw_file)dnl\n");
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        out.print("FEATURE(local_procmail)dnl\n");
                    } else if(
                        osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                        || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                    ) {
                        out.print("FEATURE(local_procmail,`',`procmail -t -Y -a $h -d $u')dnl\n");
                    } else {
                        throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                    if(
                        osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
                        || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                    ) {
                        out.print("FEATURE(`access_db',`hash -T<TMPF> /etc/mail/access.db')dnl\n");
                    } else if(
                        osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                    ) {
                        out.print("FEATURE(`access_db',`hash -T<TMPF> -o /etc/mail/access.db')dnl\n");
                    } else {
                        throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                    out.print("FEATURE(`delay_checks')dnl\n"
                            + "FEATURE(`blacklist_recipients')dnl\n"
                            + "dnl\n"
                            + "dnl Next lines are for SMTP Authentication\n"
                            + "define(`confAUTH_OPTIONS', `A y')dnl\n");
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        out.print("TRUST_AUTH_MECH(`GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
                                + "define(`confAUTH_MECHANISMS', `GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n");
                    } else if(
                        osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                        || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                    ) {
                        out.print("TRUST_AUTH_MECH(`EXTERNAL DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n"
                                + "define(`confAUTH_MECHANISMS', `EXTERNAL GSSAPI DIGEST-MD5 CRAM-MD5 LOGIN PLAIN')dnl\n");
                    } else {
                        throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                    out.print("dnl\n"
                            + "dnl STARTTLS configuration\n"
                            + "dnl extract from http://www.sendmail.org/~ca/email/starttls.html\n"
                            + "dnl\n"
                            + "define(`CERT_DIR', `/etc/ssl/sendmail')dnl\n"
                            + "define(`confCACERT_PATH', `CERT_DIR')dnl\n"
                            + "define(`confCACERT', `CERT_DIR/CAcert.pem')dnl\n"
                            + "define(`confSERVER_CERT', `CERT_DIR/MYcert.pem')dnl\n"
                            + "define(`confSERVER_KEY', `CERT_DIR/MYkey.pem')dnl\n"
                            + "define(`confCLIENT_CERT', `CERT_DIR/MYcert.pem')dnl\n"
                            + "define(`confCLIENT_KEY', `CERT_DIR/MYkey.pem')dnl\n"
                            + "dnl\n"
                            + "dnl Allow relatively high load averages\n"
                            + "define(`confQUEUE_LA', `50')dnl\n"
                            + "define(`confREFUSE_LA', `80')dnl\n"
                            + "dnl\n"
                            + "dnl Do not add the hostname to incorrectly formatted headers\n"
                            + "FEATURE(`nocanonify')dnl\n"
                            + "define(`confBIND_OPTS',`-DNSRCH -DEFNAMES')dnl\n"
                            + "dnl\n"
                            + "dnl Uncomment next lines to hide identity of mail server\n"
                            + "define(`confPRIVACY_FLAGS',`authwarnings,goaway,novrfy,noexpn,restrictqrun,restrictmailq,restrictexpand')dnl\n");
                    if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("dnl Do not advertize sendmail version.\n"
                                + "define(`confSMTP_LOGIN_MSG', `$j Sendmail; $b')dnl\n");
                    }
                    out.print("dnl\n"
                            + "dnl Additional features added AO Industries on 2005-04-22\n"
                            + "define(`confBAD_RCPT_THROTTLE',`10')dnl\n"
                            + "define(`confCONNECTION_RATE_THROTTLE',`100')dnl\n"
                            + "define(`confDELAY_LA',`40')dnl\n"
                            + "define(`confMAX_DAEMON_CHILDREN',`1000')dnl\n"
                            + "define(`confMAX_MESSAGE_SIZE',`100000000')dnl\n"
                            + "define(`confMAX_QUEUE_CHILDREN',`100')dnl\n"
                            + "define(`confMIN_FREE_BLOCKS',`65536')dnl\n"
                            + "define(`confNICE_QUEUE_RUN',`10')dnl\n"
                            + "define(`confPROCESS_TITLE_PREFIX',`").print(aoServer.getHostname()).print("')dnl\n"
                            + "dnl\n");
                    if(AOServDaemonConfiguration.isManagerEnabled(JilterConfigurationWriter.class)) {
                        out.print("dnl Enable Jilter\n"
                                + "dnl\n"
                                + "INPUT_MAIL_FILTER(`jilter',`S=inet:"+JilterConfiguration.MILTER_PORT+"@").print(primaryIpAddress.getIPAddress()).print(", F=R, T=S:60s;R:60s')\n"
                                + "dnl\n");
                    }
                    out.print("dnl Only listen to the IP addresses of this logical server\n"
                            + "dnl\n"
                            + "FEATURE(`no_default_msa')dnl\n");
                    List<String> finishedIPs=new SortedArrayList<String>();
                    for(NetBind nb : smtpNetBinds) {
                        IPAddress ia=nb.getIPAddress();
                        String ip=ia.getIPAddress();
                        if(
                            !ip.equals(IPAddress.LOOPBACK_IP)
                            && !finishedIPs.contains(ip)
                        ) {
                            out.print("DAEMON_OPTIONS(`Addr=").print(ip).print(", Family=inet, Port=").print(nb.getPort().getPort()).print(", Name=").print(ia.isWildcard()?aoServer.getHostname():ia.getHostname()).print("-MTA, Modifiers=");
                            if(ia.isWildcard()) out.print("h");
                            else out.print("bh");
                            out.print("')dnl\n"); // AO added
                            finishedIPs.add(ip);
                        }
                    }
                    finishedIPs.clear();
                    for(NetBind nb : smtpsNetBinds) {
                        IPAddress ia=nb.getIPAddress();
                        String ip=ia.getIPAddress();
                        if(
                            !ip.equals(IPAddress.LOOPBACK_IP)
                            && !finishedIPs.contains(ip)
                        ) {
                            out.print("DAEMON_OPTIONS(`Addr=").print(ip).print(", Family=inet, Port=").print(nb.getPort().getPort()).print(", Name=").print(ia.isWildcard()?aoServer.getHostname():ia.getHostname()).print("-TLSMSA, Modifiers=");
                            if(ia.isWildcard()) out.print("hs");
                            else out.print("bhs");
                            out.print("')dnl\n"); // AO added
                            finishedIPs.add(ip);
                        }
                    }
                    finishedIPs.clear();
                    for(NetBind nb : submissionNetBinds) {
                        IPAddress ia=nb.getIPAddress();
                        String ip=ia.getIPAddress();
                        if(
                            !ip.equals(IPAddress.LOOPBACK_IP)
                            && !finishedIPs.contains(ip)
                        ) {
                            out.print("DAEMON_OPTIONS(`Addr=").print(ip).print(", Family=inet, Port=").print(nb.getPort().getPort()).print(", Name=").print(ia.isWildcard()?aoServer.getHostname():ia.getHostname()).print("-MSA, Modifiers=");
                            if(ia.isWildcard()) out.print("Eh");
                            else out.print("Ebh");
                            out.print("')dnl\n"); // AO added
                            finishedIPs.add(ip);
                        }
                    }
                    out.print("dnl\n"
                            + "dnl Enable IDENT lookups\n"
                            // TO_IDENT set to 10s was causing normally 1 second email to become 30 second email on www.keepandshare.com
                            + "define(`confTO_IDENT',`0s')dnl\n");
                    if(serverFarm.useRestrictedSmtpPort()) {
                        out.print("MODIFY_MAILER_FLAGS(`SMTP',`+R')dnl\n"
                                + "MODIFY_MAILER_FLAGS(`ESMTP',`+R')dnl\n"
                                + "MODIFY_MAILER_FLAGS(`SMTP8',`+R')dnl\n"
                                + "MODIFY_MAILER_FLAGS(`DSMTP',`+R')dnl\n");
                    }
                    out.print("MAILER(smtp)dnl\n"
                            + "MAILER(procmail)dnl\n"
                            + "Dj").print(aoServer.getHostname()).print("\n" // AO added
                            + "\n"
                    );
                } finally {
                    out.close();
                }
                byte[] newBytes = bout.toByteArray();

                // Write the new file if it is different than the old
                if(!sendmailMc.getStat(tempStat).exists() || !sendmailMc.contentEquals(newBytes)) {
                    FileOutputStream fout = sendmailMcNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    try {
                        fout.write(newBytes);
                    } finally {
                        fout.close();
                    }
                    sendmailMcNew.renameTo(sendmailMc);
                }
            }

            // This is set to true when needed and a single reload will be performed after all config files are updated
            boolean needsReload = false;

            // Rebuild the /etc/sendmail.cf file if doesn't exist or modified time is before sendmail.mc
            Stat sendmailCfStat = sendmailCf.getStat();
            if(
                !sendmailCfStat.exists()
                || sendmailCfStat.getModifyTime()<sendmailMc.getStat(tempStat).getModifyTime()
            ) {
                // Build to RAM to compare
                String[] command = {"/usr/bin/m4", "/etc/mail/sendmail.mc"};
                byte[] cfNewBytes = AOServDaemon.execAndCaptureBytes(command);
                if(!sendmailCfStat.exists() || !sendmailCf.contentEquals(cfNewBytes)) {
                    FileOutputStream sendmailCfNewOut = sendmailCfNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    try {
                        sendmailCfNewOut.write(cfNewBytes);
                    } finally {
                        sendmailCfNewOut.close();
                    }
                    sendmailCfNew.renameTo(sendmailCf);
                    needsReload = true;
                }
            }
            
            // Build the new version of /etc/mail/submit.mc in RAM
            {
                bout.reset();
                ChainWriter out=new ChainWriter(bout);
                try {
                    // Submit will always be on the primary IP address
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        out.print("divert(-1)\n"
                                + "#\n"
                                + "# Generated by ").print(SendmailCFManager.class.getName()).print("\n"
                                + "#\n"
                                + "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
                                + "OSTYPE(`linux')dnl\n"
                                + "define(`confCF_VERSION', `Submit')dnl\n"
                                + "FEATURE(`msp', `[").print(primaryIpAddress.getIPAddress()).print("]')dnl\n"
                                + "define(`confRUN_AS_USER',`mail:mail')dnl\n"
                                + "define(`confTRUSTED_USER',`mail')dnl\n");
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print("divert(-1)\n"
                                + "#\n"
                                + "# Generated by ").print(SendmailCFManager.class.getName()).print("\n"
                                + "#\n"
                                + "divert(0)dnl\n"
                                + "include(`/usr/share/sendmail-cf/m4/cf.m4')\n"
                                + "VERSIONID(`linux setup for Red Hat Linux')dnl\n"
                                + "define(`confCF_VERSION', `Submit')dnl\n"
                                + "define(`__OSTYPE__',`')dnl dirty hack to keep proto.m4 from complaining\n"
                                + "define(`_USE_DECNET_SYNTAX_', `1')dnl support DECnet\n"
                                + "define(`confTIME_ZONE', `USE_TZ')dnl\n"
                                + "define(`confDONT_INIT_GROUPS', `True')dnl\n"
                                + "define(`confPID_FILE', `/var/run/sm-client.pid')dnl\n"
                                + "dnl define(`confDIRECT_SUBMISSION_MODIFIERS',`C')\n"
                                + "dnl FEATURE(`use_ct_file')dnl\n"
                                + "FEATURE(`msp', `[").print(primaryIpAddress.getIPAddress()).print("]')dnl\n"
                                + "define(`confPROCESS_TITLE_PREFIX',`").print(aoServer.getHostname()).print("')dnl\n");
                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("divert(-1)\n"
                                + "#\n"
                                + "# Generated by ").print(SendmailCFManager.class.getName()).print("\n"
                                + "#\n"
                                + "divert(0)dnl\n"
                                + "include(`/usr/share/sendmail-cf/m4/cf.m4')dnl\n"
                                + "VERSIONID(`linux setup')dnl\n"
                                + "define(`confCF_VERSION', `Submit')dnl\n"
                                + "define(`__OSTYPE__',`')dnl dirty hack to keep proto.m4 from complaining\n"
                                + "define(`_USE_DECNET_SYNTAX_', `1')dnl support DECnet\n"
                                + "define(`confTIME_ZONE', `USE_TZ')dnl\n"
                                + "define(`confDONT_INIT_GROUPS', `True')dnl\n"
                                + "define(`confPID_FILE', `/var/run/sm-client.pid')dnl\n"
                                + "dnl define(`confDIRECT_SUBMISSION_MODIFIERS',`C')dnl\n"
                                + "FEATURE(`use_ct_file')dnl\n"
                                + "FEATURE(`msp', `[").print(primaryIpAddress.getIPAddress()).print("]')dnl\n"
                                + "define(`confPROCESS_TITLE_PREFIX',`").print(aoServer.getHostname()).print("')dnl\n");
                    } else {
                        throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                    }
                } finally {
                    out.close();
                }
                byte[] newBytes = bout.toByteArray();

                // Write the new file if it is different than the old
                if(!submitMc.getStat(tempStat).exists() || !submitMc.contentEquals(newBytes)) {
                    FileOutputStream fout = submitMcNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    try {
                        fout.write(newBytes);
                    } finally {
                        fout.close();
                    }
                    submitMcNew.renameTo(submitMc);
                }
            }

            // Rebuild the /etc/submit.cf file if doesn't exist or modified time is before submit.mc
            Stat submitCfStat = submitCf.getStat();
            if(
                !submitCfStat.exists()
                || submitCfStat.getModifyTime()<submitMc.getStat(tempStat).getModifyTime()
            ) {
                // Build to RAM to compare
                String[] command = {"/usr/bin/m4", "/etc/mail/submit.mc"};
                byte[] cfNewBytes = AOServDaemon.execAndCaptureBytes(command);
                if(!submitCfStat.exists() || !submitCf.contentEquals(cfNewBytes)) {
                    FileOutputStream submitCfNewOut = submitCfNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    try {
                        submitCfNewOut.write(cfNewBytes);
                    } finally {
                        submitCfNewOut.close();
                    }
                    submitCfNew.renameTo(submitCf);
                    needsReload = true;
                }
            }

            // Reload if needed
            if(needsReload) reloadSendmail();
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
                && AOServDaemonConfiguration.isManagerEnabled(SendmailCFManager.class)
                && sendmailCFManager==null
            ) {
                System.out.print("Starting SendmailCFManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                sendmailCFManager=new SendmailCFManager();
                connector.ipAddresses.addTableListener(sendmailCFManager, 0);
                connector.netBinds.addTableListener(sendmailCFManager, 0);
                connector.aoServers.addTableListener(sendmailCFManager, 0);
                connector.serverFarms.addTableListener(sendmailCFManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild sendmail.cf";
    }
    
    private static final String[] reloadCommand={
        "/etc/rc.d/init.d/sendmail",
        "reload"
    };

    private static final Object sendmailLock=new Object();
    public static void reloadSendmail() throws IOException {
        synchronized(sendmailLock) {
            AOServDaemon.exec(reloadCommand);
        }
    }
}
