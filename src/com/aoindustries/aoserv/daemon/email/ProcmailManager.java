/*
 * Copyright 2000-2012 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailAttachmentBlock;
import com.aoindustries.aoserv.client.EmailAttachmentType;
import com.aoindustries.aoserv.client.EmailSpamAssassinIntegrationMode;
import com.aoindustries.aoserv.client.LinuxAccAddress;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Builds the .procmailrc configurations.
 *
 * @author  AO Industries, Inc.
 */
public final class ProcmailManager extends BuilderThread {

    /** Disable email attachment type blocks here. This should be moved to jilter, assuming we can make a faster implementation in Java. */
    public static final boolean EMAIL_ATTACHMENT_TYPES_ENABLED = false;

    /** The file that stores the procmail configuration */
    public static final String PROCMAILRC=".procmailrc";

    /** The old first 6 lines of an auto-generated procmailrc file */
    public static final String OLD_AUTO_PROCMAILRC="#\n"
                                                 + "# This .procmailrc file was automatically generated by AutoresponderManager. As\n"
                                                 + "# long as this comment remains in this file, any changes will be overwritten.\n"
                                                 + "# Removing this comment will allow manual changes to this file, but all\n"
                                                 + "# automated functionality will be disabled.\n"
                                                 + "#\n";

    /** The first 6 lines of an auto-generated procmailrc file */
    public static final String AUTO_PROCMAILRC="#\n"
                                             + "# This .procmailrc file was automatically generated by ProcmailManager. As\n"
                                             + "# long as this comment remains in this file, any changes will be overwritten.\n"
                                             + "# Removing this comment will allow manual changes to this file, but all\n"
                                             + "# automated functionality will be disabled.\n"
                                             + "#\n";

    private static ProcmailManager procmailManager;

    private static final UnixFile
        cyrusDeliverCentOs = new UnixFile("/usr/lib/cyrus-imapd/deliver"),
        cyrusDeliverRedHat = new UnixFile("/usr/lib64/cyrus-imapd/deliver")
    ;

    private ProcmailManager() {
    }

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServer aoServer=AOServDaemon.getThisAOServer();
            String primaryIP = aoServer.getPrimaryIPAddress().getIPAddress();
            LinuxServerGroup mailLsg = aoServer.getLinuxServerGroup(LinuxGroup.MAIL);
            if(mailLsg==null) throw new SQLException("Unable to find LinuxServerGroup: "+LinuxGroup.MAIL+" on "+aoServer.getHostname());
            int mailGid = mailLsg.getGid().getID();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                // Control the permissions of the deliver program, needs to be SUID to
                // Setting here because RPM updates will change permissions
                if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                    Stat deliverStat = cyrusDeliverCentOs.getStat();
                    if(deliverStat.getUid()!=UnixFile.ROOT_UID || deliverStat.getGid()!=mailGid) {
                        cyrusDeliverCentOs.chown(UnixFile.ROOT_UID, mailGid);
                        cyrusDeliverCentOs.getStat(deliverStat);
                    }
                    if(deliverStat.getMode()!=02755) {
                        cyrusDeliverCentOs.setMode(02755);
                    }
                } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    Stat deliverStat = cyrusDeliverRedHat.getStat();
                    if(deliverStat.getUid()!=UnixFile.ROOT_UID || deliverStat.getGid()!=mailGid) {
                        cyrusDeliverRedHat.chown(UnixFile.ROOT_UID, mailGid);
                        cyrusDeliverRedHat.getStat(deliverStat);
                    }
                    if(deliverStat.getMode()!=02755) {
                        cyrusDeliverRedHat.setMode(02755);
                    }
                }

                List<LinuxServerAccount> lsas=aoServer.getLinuxServerAccounts();
                for(LinuxServerAccount lsa : lsas) {
                    if(lsa.getLinuxAccount().getType().isEmail()) {
                        String home = lsa.getHome();
                        UnixFile procmailrc = new UnixFile(home, PROCMAILRC);

                        if(!isManual(lsa)) {
                            // Stat for use below
                            Stat procmailrcStat = procmailrc.getStat();
                            boolean isAutoresponderEnabled=lsa.isAutoresponderEnabled();
                            List<EmailAttachmentBlock> eabs;
                            if(EMAIL_ATTACHMENT_TYPES_ENABLED) eabs = lsa.getEmailAttachmentBlocks();
                            else eabs = Collections.emptyList();
                            String spamAssassinMode = lsa.getEmailSpamAssassinIntegrationMode().getName();
                            // Build the file in RAM, first
                            ByteArrayOutputStream bout=new ByteArrayOutputStream(4096);
                            ChainWriter out=new ChainWriter(bout);
                            try {
                                out.print(AUTO_PROCMAILRC
                                          + "\n"
                                          + "# Setup the environment\n"
                                          + "SHELL=/bin/bash\n");

                                          // TODO: Build the file after this in advance, look for the longest line, and set accordingly.
                                          //+ "LINEBUF=16384\n"
                                          // This was only set for the auto-reply and email attachment block stuff.

                                          // Default locking time is fine since not locking for spamassassin now: + "LOCKSLEEP=15\n");

                                LinuxAccount la = lsa.getLinuxAccount();
                                String username = la.getUsername().getUsername();
                                LinuxAccAddress laa = lsa.getAutoresponderFrom();
                                List<LinuxAccAddress> addresses = lsa.getLinuxAccAddresses();

                                // The same X-Loop is used for attachment filters and autoresponders
                                String xloopAddress=username+'@'+lsa.getAOServer().getHostname();

                                // Split the username in to user and domain (used by Cyrus)
                                String user, domain;
                                {
                                    int atPos = username.indexOf('@');
                                    if(atPos==-1) {
                                        user = username;
                                        domain = "default";
                                    } else {
                                        user = username.substring(0, atPos);
                                        domain = username.substring(atPos+1);
                                    }
                                }

                                // The default from address
                                String defaultFromAddress;
                                if(laa!=null) defaultFromAddress=laa.getEmailAddress().toString();
                                else {
                                    if(addresses.size()>=1) defaultFromAddress=addresses.get(0).getEmailAddress().toString();
                                    else defaultFromAddress=xloopAddress;
                                }

                                // Any X-Loop address is sent to the bit-bucket
                                out.print("\n"
                                        + "# Discard any looped emails\n"
                                        + ":0\n"
                                        + "* ^X-Loop: ").print(xloopAddress).print("\n"
                                        + "/dev/null\n");

                                if(!spamAssassinMode.equals(EmailSpamAssassinIntegrationMode.NONE)) {
                                    out.print("\n"
                                            + "# Only use spamassassin if size less than 1000000 bytes\n"
                                            + ":0\n"
                                            + "* < 1000000\n"
                                            + "{\n"
                                            + "  # Filter through spamassassin\n"
                                            // procmail locking sucks and is not necessary: + "  :0 fw: spamassassin.lock\n"
                                            + "  :0 fw\n"
                                            + "  | /usr/bin/spamc -d ").print(primaryIP).print(" --connect-retries=6 --retry-sleep=10 --headers -s 2000000\n"
                                            + "  \n"
                                            + "  # If spamassassin failed, return a temporary failure code to sendmail\n"
                                            + "  :0\n"
                                            + "  * !^X-Spam-Status: (Yes|No)\n"
                                            + "  {\n"
                                            + "    # Return EX_TEMPFAIL to have sendmail retry delivery\n"
                                            + "    EXITCODE=75\n"
                                            + "    HOST\n"
                                            + "  }\n");
                                    // Discard if configured to do so
                                    int saDiscardScore = lsa.getSpamAssassinDiscardScore();
                                    if(saDiscardScore>0) {
                                        out.print("\n"
                                                + "  # Discard spam with a score >= ").print(saDiscardScore).print("\n"
                                                + "  :0\n"
                                                + "  * ^X-Spam-Level: ");
                                        for(int c=0;c<saDiscardScore;c++) out.print("\\*");
                                        out.print("\n"
                                                + "  /dev/null\n");
                                    }
                                    out.print("}\n");
                                }
                                // First figure out if this message will be rejected due to attachment
                                if(!eabs.isEmpty()) {
                                    out.print("\n"
                                            + "# Determine if the message contains ANY blocked attachments\n"
                                            + ":0\n"
                                            + "*^Content-Type: (multipart/.*|application/octet-stream)\n"
                                            + "* HB ?? ^Content-(Type|Disposition): .*;.*($.*)?name=(\")?.*\\.");
                                    if(eabs.size()>=1) out.print('(');
                                    for(int d=0;d<eabs.size();d++) {
                                        if(d>0) out.print('|');
                                        out.print(eabs.get(d).getEmailAttachmentType().getExtension());
                                    }
                                    if(eabs.size()>=1) out.print(')');
                                    out.print("(\")?$\n"
                                            + "{\n"
                                    // Second, figure out the To address that was used, to be used as the From address
                                            + "  # Figure out the from address\n"
                                            + "  FROM=`/usr/bin/formail -xTo:`\n"
                                            + "\n"
                                            + "  # Figure out the subject\n"
                                            + "  SUBJECT=`/usr/bin/formail -xSubject:`\n");

                                    // Third, figure out the specific attachment that was rejected
                                    if(eabs.size()==1) {
                                        EmailAttachmentType eat=eabs.get(0).getEmailAttachmentType();
                                        out.print("\n"
                                                + "  # Only one extension, use these values\n"
                                                + "  EXTENSIONS=\" ").print(eat.getExtension()).print("\"\n");
                                    } else {
                                        out.print("\n"
                                                + "  # Build the list of disallowed extensions\n");
                                        for(int d=0;d<eabs.size();d++) {
                                            String extension=eabs.get(d).getEmailAttachmentType().getExtension();
                                            out.print("  :0\n"
                                                    + "  *^Content-Type: (multipart/.*|application/octet-stream)\n"
                                                    + "  * HB ?? ^Content-(Type|Disposition): .*;.*($.*)?name=(\")?.*\\.").print(extension).print("(\")?$\n"
                                                    + "  {\n"
                                                    + "    EXTENSIONS=\"$EXTENSIONS ").print(extension).print("\"\n"
                                                    + "  }\n");
                                        }
                                    }
                                    // Fourth, send the response
                                    // TODO: Should we send these??? Should we put them in an IMAP folder?  Should we set the subject differently?
                                    // Can we block these attachment types within sendmail at message acceptance time?
                                    // MILTER!!!
                                    out.print("\n"
                                            + "  # Send the response message:\n"
                                            + "  :0 h\n"
                                            + "  | ( \\\n"
                                            + "    /usr/bin/formail \\\n"
                                            + "      -r \\\n"
                                            + "      -i\"Subject: BLOCKED:$SUBJECT\" \\\n"
                                            + "      -i\"From: $FROM\" \\\n"
                                            + "      -A\"X-Loop: ").print(xloopAddress).print("\" ; \\\n"
                                            + "      -i\"Precedence: junk\" ; \\\n"
                                            + "      echo \"Your message has not been delivered because it contains at least one restricted\" ; \\\n"
                                            + "      echo \"attachment.\" ; \\\n"
                                            + "      echo \"\" ; \\\n"
                                            + "      echo \"Recipient...........:$FROM\" ; \\\n"
                                            + "      echo \"Subject.............:$SUBJECT\" ; \\\n"
                                            + "      echo \"Detected Extensions.:$EXTENSIONS\" ; \\\n"
                                            + "      echo \"\" ; \\\n"
                                            + "      echo \"If you did not send this message, the most likely cause is a virus on another\" ; \\\n"
                                            + "      echo \"computer.  The virus has used your email address as the from address.  As a\" ; \\\n"
                                            + "      echo \"result, this automated response has been sent to you.\" ; \\\n"
                                            + "      echo \"\" ; \\\n"
                                            + "      echo \"Please scan your computer for viruses using the latest virus definitions.  If\" ; \\\n"
                                            + "      echo \"you are unable to find any viruses then you were probably not the source of\" ; \\\n"
                                            + "      echo \"this email and you may disregard this message.\" ; \\\n"
                                            + "      echo \"\" ; \\\n"
                                            + "      echo \"Blocked Attachment Types Include:\" ; \\\n"
                                            + "      echo \"\" ; \\\n"
                                            + "      echo \"    Extension   Description\" ; \\\n");
                                    for(int d=0;d<eabs.size();d++) {
                                        EmailAttachmentType eat=eabs.get(d).getEmailAttachmentType();
                                        String extension=eat.getExtension();
                                        out.print("      echo \"    ").print(extension);
                                        for(int e=extension.length();e<11;e++) out.print(' ');
                                        out.print(' ').print(eat.getDescription());
                                        out.print("\" ");
                                        if(d<(eabs.size()-1)) out.print("; ");
                                        out.print("\\\n");
                                    }
                                    out.print("  ) | $SENDMAIL -oi -t -f\"$FROM\"\n"
                                            + "}\n");
                                }

                                // Write the autoresponder if configured
                                if(isAutoresponderEnabled) {
                                    // Figure out the autoresponder details
                                    String path=lsa.getAutoresponderPath();
                                    String subject=lsa.getAutoresponderSubject();
                                    out.print("\n"
                                            + "# Configure the autoresponder\n"
                                            + ":0 h c\n"
                                            + "* !^FROM_DAEMON\n"
                                            + "* !^From: .*MAILER-DAEMON.*\n");
                                    // This is already discarded above: + "* !^X-Loop: ").print(xloopAddress).print("\n");
                                    // Don't respond to spam
                                    if(!spamAssassinMode.equals(EmailSpamAssassinIntegrationMode.NONE)) {
                                        // This handles both large messages that aren't scanned and those that are scanned by using !Yes
                                        out.print("* !^X-Spam-Status: Yes\n");
                                    }
                                    out.print("| (/usr/bin/formail -r \\\n"
                                            + "    -I\"Precedence: junk\" \\\n"
                                            + "    -i\"From: ").print(defaultFromAddress).print("\" \\\n");
                                    // TODO: What if subject has shell characters?
                                    if(subject!=null) out.print("    -i\"Subject: ").print(subject).print("\" \\\n");
                                    out.print("    -A\"X-Loop: ").print(xloopAddress).print("\" ");
                                    if(path==null) out.print("\\\n");
                                    else {
                                        out.print("; \\\n"
                                                + "    /bin/cat ").print(path).print(" \\\n");
                                    }
                                    out.print(") | /usr/sbin/sendmail -oi -t -f\"").print(defaultFromAddress).print("\"\n");
                                }

                                if(lsa.useInbox()) {
                                    // Capture return-path header if needed
                                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                        // Nothing special needed
                                    } else if(
                                        osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                                        || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                                    ) {
                                        out.print("\n"
                                                + "# Capture the current Return-path to pass to deliver\n"
                                                + ":0 h\n"
                                                //+ "RETURN_PATH=| /usr/bin/formail -c -x Return-Path: | /bin/sed -e 's/^ *<//' -e 's/>$//'\n");
                                                + "RETURN_PATH=| /opt/aoserv-client/bin/returnpath\n");
                                    } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

                                    // Only move to Junk folder when the inbox is enabled and in IMAP mode
                                    if(spamAssassinMode.equals(EmailSpamAssassinIntegrationMode.IMAP)) {
                                        out.print("\n"
                                                + "# Place any flagged spam in the Junk folder\n");
                                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                            out.print(":0:\n"
                                                    + "* ^X-Spam-Status: Yes\n"
                                                    + "Mail/Junk\n");
                                        } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                            out.print(":0\n"
                                                    + "* ^X-Spam-Status: Yes\n"
                                                    + "{\n"
                                                    + "  :0 w\n"
                                                    + "  | /opt/aoserv-client/bin/skipfirstline | ").print(cyrusDeliverCentOs.getPath()).print(" -a \"").print(user).print('@').print(domain).print("\" -r \"$RETURN_PATH\" \"").print(user).print("/Junk@").print(domain).print("\"\n"
                                                    + "\n"
                                                    + "  # Delivery failed, return EX_TEMPFAIL to have sendmail retry delivery\n"
                                                    + "  EXITCODE=75\n"
                                                    + "  HOST\n"
                                                    + "}\n");
                                        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                            out.print(":0\n"
                                                    + "* ^X-Spam-Status: Yes\n"
                                                    + "{\n"
                                                    + "  :0 w\n"
                                                    + "  | /opt/aoserv-client/bin/skipfirstline | ").print(cyrusDeliverRedHat.getPath()).print(" -a \"").print(user).print('@').print(domain).print("\" -r \"$RETURN_PATH\" \"").print(user).print("/Junk@").print(domain).print("\"\n"
                                                    + "\n"
                                                    + "  # Delivery failed, return EX_TEMPFAIL to have sendmail retry delivery\n"
                                                    + "  EXITCODE=75\n"
                                                    + "  HOST\n"
                                                    + "}\n");
                                        } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                                    }

                                    // Deliver to INBOX
                                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                        // Nothing special needed
                                    } else if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                        out.print("\n"
                                                + ":0 w\n"
                                                //+ "| /usr/bin/formail -I\"From \" | /usr/lib/cyrus-imapd/deliver -a \"").print(user).print('@').print(domain).print("\" -r \"$RETURN_PATH\" \"").print(user).print('@').print(domain).print("\"\n");
                                                + "| /opt/aoserv-client/bin/skipfirstline | ").print(cyrusDeliverCentOs.getPath()).print(" -a \"").print(user).print('@').print(domain).print("\" -r \"$RETURN_PATH\" \"").print(user).print('@').print(domain).print("\"\n"
                                                + "\n"
                                                + "# Delivery failed, return EX_TEMPFAIL to have sendmail retry delivery\n"
                                                + "EXITCODE=75\n"
                                                + "HOST\n");
                                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                        out.print("\n"
                                                + ":0 w\n"
                                                //+ "| /usr/bin/formail -I\"From \" | /usr/lib64/cyrus-imapd/deliver -a \"").print(user).print('@').print(domain).print("\" -r \"$RETURN_PATH\" \"").print(user).print('@').print(domain).print("\"\n");
                                                + "| /opt/aoserv-client/bin/skipfirstline | ").print(cyrusDeliverRedHat.getPath()).print(" -a \"").print(user).print('@').print(domain).print("\" -r \"$RETURN_PATH\" \"").print(user).print('@').print(domain).print("\"\n"
                                                + "\n"
                                                + "# Delivery failed, return EX_TEMPFAIL to have sendmail retry delivery\n"
                                                + "EXITCODE=75\n"
                                                + "HOST\n");
                                    } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                                } else {
                                    // Discard the email if configured to not use the inbox or Junk folders
                                    out.print("\n"
                                              + "# Discard the message\n"
                                              + ":0\n"
                                              + "/dev/null\n");
                                }
                            } finally {
                                // Close the file and move it into place
                                out.close();
                            }

                            // Write to disk if different than the copy on disk
                            byte[] newBytes=bout.toByteArray();
                            if(!procmailrcStat.exists() || !procmailrc.contentEquals(newBytes)) {
                                // Create the new autoresponder config
                                UnixFile tempUF=UnixFile.mktemp(home+"/.procmailrc.", false);
                                FileOutputStream fout=tempUF.getSecureOutputStream(
                                    lsa.getUid().getID(),
                                    lsa.getPrimaryLinuxServerGroup().getGid().getID(),
                                    0600,
                                    true
                                );
                                try {
                                    fout.write(newBytes);
                                } finally {
                                    fout.close();
                                }
                                tempUF.renameTo(procmailrc);
                            }
                        }
                    }
                }
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(ProcmailManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }
    
    /**
     * Determines if an existing procmail file is manually maintained.
     *
     * @exception  SQLException if the account is not an email type
     */
    public static boolean isManual(LinuxServerAccount lsa) throws IOException, SQLException {
        // Must be an email type
        if(!lsa.getLinuxAccount().getType().isEmail()) throw new SQLException("Not an email inbox: "+lsa.toString());

        String home=lsa.getHome();
        // If the home directory is outside /home/, it is manually maintained (not maintained by this code)
        if(!home.startsWith("/home/")) return true;

        UnixFile procmailrc=new UnixFile(home, PROCMAILRC);
        Stat procmailrcStat = procmailrc.getStat();

        boolean isManual;
        if(
            procmailrcStat.exists()
            && procmailrcStat.isRegularFile()
        ) {
            int len1=AUTO_PROCMAILRC.length();
            StringBuilder SB=new StringBuilder(len1);
            int len2=OLD_AUTO_PROCMAILRC.length();
            StringBuilder oldSB=new StringBuilder(len2);
            int longest=len1>=len2?len1:len2;
            BufferedInputStream in=new BufferedInputStream(new FileInputStream(procmailrc.getFile()));
            try {
                int count=0;
                int ch;
                while(count<longest && (ch=in.read())!=-1) {
                    if(count<len1) SB.append((char)ch);
                    if(count<len2) oldSB.append((char)ch);
                    count++;
                }
            } finally {
                in.close();
            }
            isManual=!(SB.toString().equals(AUTO_PROCMAILRC) || oldSB.toString().equals(OLD_AUTO_PROCMAILRC));
        } else {
            isManual=false;
        }
        return isManual;
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
                && AOServDaemonConfiguration.isManagerEnabled(ProcmailManager.class)
                && procmailManager==null
            ) {
                System.out.print("Starting ProcmailManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                procmailManager=new ProcmailManager();
                if(EMAIL_ATTACHMENT_TYPES_ENABLED) connector.getEmailAttachmentBlocks().addTableListener(procmailManager, 0);
                connector.getIpAddresses().addTableListener(procmailManager, 0);
                connector.getLinuxServerAccounts().addTableListener(procmailManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild .procmailrc files";
    }
}
