/*
 * Copyright 2000-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Username;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.StringUtility;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

/**
 * @author  AO Industries, Inc.
 */
final public class EmailAddressManager extends BuilderThread {

    /**
     * Sendmail files.
     */
    private static final UnixFile
        newAliases = new UnixFile("/etc/aliases.new"),
        aliases=new UnixFile("/etc/aliases"),

        newUserTable=new UnixFile("/etc/mail/virtusertable.new"),
        userTable=new UnixFile("/etc/mail/virtusertable")
    ;

    private static EmailAddressManager emailAddressManager;

    private EmailAddressManager() {
    }

    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServer aoServer=AOServDaemon.getThisAOServer();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(rebuildLock) {
                List<EmailAddress> eas=aoServer.getEmailAddresses();

                // Each username may only be used once within the aliases file
                Set<String> usernamesUsed=new HashSet<String>();

                //
                // Write the new /etc/aliases file.
                //
                ByteArrayOutputStream aliasesBOut = new ByteArrayOutputStream();
                ByteArrayOutputStream usersBOut = new ByteArrayOutputStream();

                ChainWriter aliasesOut = new ChainWriter(aliasesBOut);
                try {
                    ChainWriter usersOut = new ChainWriter(usersBOut);
                    try {
                        aliasesOut.print(
                              "#\n"
                            + "#  Aliases in this file will NOT be expanded in the header from\n"
                            + "#  Mail, but WILL be visible over networks or from /bin/mail.\n"
                            + "#\n"
                            + "#       >>>>>>>>>>      The program \"newaliases\" must be run after\n"
                            + "#       >> NOTE >>      this file is updated for any changes to\n"
                            + "#       >>>>>>>>>>      show through to sendmail.\n"
                            + "#\n"
                            + "# Generated by "
                        );
                        aliasesOut.println(EmailAddressManager.class.getName());

                        // Write the system email aliases
                        for(SystemEmailAlias alias : aoServer.getSystemEmailAliases()) {
                            String address=alias.getAddress();
                            usernamesUsed.add(address);
                            aliasesOut.print(address).print(": ").println(alias.getDestination());
                        }

                        // Hide the Linux account usernames, so support@tantrix.com does not go to support@aoindustries.com
                        String ex_nouser;
                        if(
                            osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                            || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                        ) {
                            ex_nouser="/opt/aoserv-client/sbin/ex_nouser";
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        for(LinuxAccount la : aoServer.getSortedLinuxAccounts()) {
                            UserId username=la.getUserId();
                            if(!usernamesUsed.contains(username.toString())) {
                                if(username.toString().indexOf('@')==-1) {
                                    aliasesOut.print(username).print(": |").println(ex_nouser);
                                }
                                usernamesUsed.add(username.toString());
                            }
                        }

                        // Write the /etc/mail/virtusertable.new
                        String[] devNullUsername=new String[1];
                        Map<String,String> singleForwardingTies=new HashMap<String,String>();
                        Map<String,String> singleListTies=new HashMap<String,String>();
                        Map<String,String> singlePipeTies=new HashMap<String,String>();
                        Map<String,String> singleInboxTies=new HashMap<String,String>();

                        // Process the non-wildcard entries first
                        for(EmailAddress ea : eas) {
                            String address=ea.getAddress();
                            if(address.length()>0) {
                                writeEmailAddressConfigs(
                                    ea,
                                    usernamesUsed,
                                    devNullUsername,
                                    singleForwardingTies,
                                    singleListTies,
                                    singlePipeTies,
                                    singleInboxTies,
                                    aliasesOut,
                                    usersOut
                                );
                            }
                        }

                        // Send all other special email addresses if they have not been overridden.
                        for(EmailDomain ed : aoServer.getEmailDomains()) {
                            String domain=ed.getDomain();
                            if(ed.getEmailAddress("abuse")==null) usersOut.print("abuse@").print(domain).print("\tabuse\n");
                            if(ed.getEmailAddress("devnull")==null) usersOut.print("devnull@").print(domain).print("\tdevnull\n");
                            if(ed.getEmailAddress("mailer-daemon")==null) usersOut.print("mailer-daemon@").print(domain).print("\tmailer-daemon\n");
                            if(ed.getEmailAddress("postmaster")==null) usersOut.print("postmaster@").print(domain).print("\tpostmaster\n");
                        }

                        // Process the wildcard entries
                        for(EmailAddress ea : eas) {
                            String address=ea.getAddress();
                            if(address.length()==0) {
                                writeEmailAddressConfigs(
                                    ea,
                                    usernamesUsed,
                                    devNullUsername,
                                    singleForwardingTies,
                                    singleListTies,
                                    singlePipeTies,
                                    singleInboxTies,
                                    aliasesOut,
                                    usersOut
                                );
                            }
                        }

                        // Block all other email_domains that have not been explicitly configured as an email address.
                        // This had a dead.letter problem and was commented for a while
                        /*if(osv!=OperatingSystemVersion.XXXXXXXXXX && aoServer.getRestrictOutboundEmail()) {
                            for(EmailDomain ed : aoServer.getEmailDomains()) {
                                String domain=ed.getDomain();
                                if(ed.getEmailAddress("")==null) usersOut.print("@").print(domain).print("\terror:5.1.1:550 No such email address here\n");
                            }
                        }*/
                    } finally {
                        // Close the virtusertable
                        usersOut.close();
                    }
                } finally {
                    // Close the aliases file
                    aliasesOut.close();
                }
                byte[] usersNewBytes = usersBOut.toByteArray();
                byte[] aliasesNewBytes = aliasesBOut.toByteArray();

                // Only write to disk if changed, this will almost always be the case when
                // tie usernames are used for any reason, but this will help for servers with
                // simple configurations.
                Stat tempStat = new Stat();

                boolean needMakeMap;
                if(
                    !userTable.getStat(tempStat).exists()
                    || !userTable.contentEquals(usersNewBytes)
                ) {
                    FileOutputStream newOut = newUserTable.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    try {
                        newOut.write(usersNewBytes);
                    } finally {
                        newOut.close();
                    }
                    needMakeMap = true;
                } else {
                    needMakeMap = false;
                }

                boolean needNewAliases;
                if(
                    !aliases.getStat(tempStat).exists()
                    || !aliases.contentEquals(aliasesNewBytes)
                ) {
                    FileOutputStream newOut = newAliases.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true);
                    try {
                        newOut.write(aliasesNewBytes);
                    } finally {
                        newOut.close();
                    }
                    needNewAliases = true;
                } else {
                    needNewAliases = false;
                }

                // Move both files into place as close together as possible since they are a set
                if(needMakeMap) newUserTable.renameTo(userTable);
                if(needNewAliases) newAliases.renameTo(aliases);

                // Rebuild the hash map
                if(needMakeMap) makeMap();

                // Call newaliases
                if(needNewAliases) newAliases();
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(EmailAddressManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }
    
    private static void writeEmailAddressConfigs(
        EmailAddress ea,
        Set<String> usernamesUsed,
        String[] devNullUsername,
        Map<String,String> singleForwardingTies,
        Map<String,String> singleListTies,
        Map<String,String> singlePipeTies,
        Map<String,String> singleInboxTies,
        ChainWriter aliasesOut,
        ChainWriter usersOut
    ) throws IOException, SQLException {
        String address=ea.getAddress();
        String domain=ea.getDomain().getDomain();

        /*
         * The possible email deliveries:
         *
         * 1) /dev/null only
         * 2) One forwarding destination, BEA ignored (use singleForwardingTies)
         * 3) One email list, BEA ignored (use singleListTies)
         * 4) One pipe, BEA ignored (use singlePipeTies)
         * 5) One Inbox only, BEA ignored (use singleInboxTies)
         * 6) Multiple destinations, BEA ignored (list each)
         * 7) Nothing (ignore)
         */
        BlackholeEmailAddress bea=ea.getBlackholeEmailAddress();
        List<EmailForwarding> efs=ea.getEmailForwardings();
        // We need to not forward email for disabled accounts, but do we just reject it instead?
        // List<EmailForwarding> efs=ea.getEnabledEmailForwardings();
        List<EmailListAddress> elas=ea.getEnabledEmailListAddresses();
        List<EmailPipeAddress> epas=ea.getEnabledEmailPipeAddresses();
        List<LinuxAccAddress> laas=ea.getLinuxAccAddresses();

        String tieUsername;

        // 1) /dev/null only
        if(
            bea!=null
            && efs.isEmpty()
            && elas.isEmpty()
            && epas.isEmpty()
            && laas.isEmpty()
        ) {
            tieUsername=devNullUsername[0];
            if(tieUsername==null) {
                devNullUsername[0]=tieUsername=getTieUsername(usernamesUsed);
                aliasesOut.print(tieUsername).println(": /dev/null");
            }

        // 2) One forwarding destination, BEA ignored (use singleForwardingTies)
        } else if(
            efs.size()==1
            && elas.isEmpty()
            && epas.isEmpty()
            && laas.isEmpty()
        ) {
            String destination=efs.get(0).getDestination();
            tieUsername=singleForwardingTies.get(destination);
            if(tieUsername==null) {
                singleForwardingTies.put(destination, tieUsername=getTieUsername(usernamesUsed));
                aliasesOut.print(tieUsername).print(": ").println(destination);
            }

        // 3)  One email list, BEA ignored (use singleListTies)
        } else if(
            efs.isEmpty()
            && elas.size()==1
            && epas.isEmpty()
            && laas.isEmpty()
        ) {
            String path=elas.get(0).getEmailList().getPath();
            tieUsername=singleListTies.get(path);
            if(tieUsername==null) {
                singleListTies.put(path, tieUsername=getTieUsername(usernamesUsed));
                aliasesOut.print(tieUsername).print(": :include:").println(path);
            }

        // 4) One pipe, BEA ignored (use singlePipeTies)
        } else if(
            efs.isEmpty()
            && elas.isEmpty()
            && epas.size()==1
            && laas.isEmpty()
        ) {
            String path=epas.get(0).getEmailPipe().getPath();
            tieUsername=singlePipeTies.get(path);
            if(tieUsername==null) {
                singlePipeTies.put(path, tieUsername=getTieUsername(usernamesUsed));
                aliasesOut.print(tieUsername).print(": \"| ").print(path).println('"');
            }

        // 5) One Inbox only, BEA ignored (use singleInboxTies)
        } else if(
            efs.isEmpty()
            && elas.isEmpty()
            && epas.isEmpty()
            && laas.size()==1
        ) {
            LinuxServerAccount lsa=laas.get(0).getLinuxServerAccount();
            if(lsa!=null) {
                Username un=lsa.getLinuxAccount().getUsername();
                if(un!=null) {
                    String username=un.getUsername();
                    tieUsername=singleInboxTies.get(username);
                    if(tieUsername==null) {
                        singleInboxTies.put(username, tieUsername=getTieUsername(usernamesUsed));
                        aliasesOut.print(tieUsername).print(": \\").println(StringUtility.replace(username, '@', "\\@"));
                    }
                } else tieUsername=null;
            } else tieUsername=null;

        // 6) Multiple destinations, BEA ignored (list each)
        } else if(
            !efs.isEmpty()
            || !elas.isEmpty()
            || !epas.isEmpty()
            || !laas.isEmpty()
        ) {
            tieUsername=getTieUsername(usernamesUsed);
            aliasesOut.print(tieUsername).print(": ");
            boolean done=false;
            for(EmailForwarding ef : efs) {
                if(done) aliasesOut.print(",\n\t");
                else done=true;
                aliasesOut.print(ef.getDestination());
            }
            for(EmailListAddress ela : elas) {
                if(done) aliasesOut.print(",\n\t");
                else done=true;
                aliasesOut.print(":include:").print(ela.getEmailList().getPath());
            }
            for(EmailPipeAddress epa : epas) {
                if(done) aliasesOut.print(",\n\t");
                else done=true;
                aliasesOut.print("\"| ").print(epa.getEmailPipe().getPath()).print('"');
            }
            for(LinuxAccAddress laa : laas) {
                if(done) aliasesOut.print(",\n\t");
                else done=true;
                aliasesOut.print('\\').print(StringUtility.replace(laa.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername(),'@',"\\@"));
            }
            aliasesOut.println();

        // 7) Not used - ignore
        } else tieUsername=null;

        if(tieUsername!=null) usersOut.print(address).print('@').print(domain).print('\t').println(tieUsername);
    }

    private static final int TIE_USERNAME_DIGITS=12;
    private static final char[] tieChars={
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
        'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };
    private static String getTieUsername(Set<String> usernamesUsed) {
        Random random = AOServDaemon.getRandom();
        StringBuilder SB=new StringBuilder(4+TIE_USERNAME_DIGITS);
        SB.append("tmp_");
        while(true) {
            SB.setLength(4);
            for(int c=0;c<TIE_USERNAME_DIGITS;c++) SB.append(tieChars[random.nextInt(tieChars.length)]);
            String username=SB.toString();
            if(!usernamesUsed.contains(username)) {
                usernamesUsed.add(username);
                return username;
            }
        }
    }

    private static final Object makeMapLock=new Object();
    private static void makeMap() throws IOException, SQLException {
        synchronized(makeMapLock) {
            // Run the command
            String makemap;
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
            if(
                osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) makemap="/usr/sbin/makemap";
            else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            String[] cmd = { makemap, "hash", userTable.getPath() };
            Process P = Runtime.getRuntime().exec(cmd);
            try {
                // Pipe the file into the process
                InputStream in = new BufferedInputStream(new FileInputStream(userTable.getPath()));
                try {
                    OutputStream out = P.getOutputStream();
                    try {
                        int ch;
                        while ((ch = in.read()) != -1) out.write(ch);
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
            } finally {
                // Wait for the process to complete
                try {
                    int retCode = P.waitFor();
                    if(retCode!=0) throw new IOException("Non-zero return status: "+retCode);
                } catch (InterruptedException err) {
                    LogFactory.getLogger(EmailAddressManager.class).log(Level.WARNING, null, err);
                }
            }

            // Check for error exit code
            int exit = P.exitValue();
            if (exit != 0) throw new IOException("Non-zero exit status: " + exit);
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
                && AOServDaemonConfiguration.isManagerEnabled(EmailAddressManager.class)
                && emailAddressManager==null
            ) {
                System.out.print("Starting EmailAddressManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                emailAddressManager=new EmailAddressManager();
                connector.getEmailDomains().addTableListener(emailAddressManager, 0);
                connector.getBlackholeEmailAddresses().addTableListener(emailAddressManager, 0);
                connector.getEmailAddresses().addTableListener(emailAddressManager, 0);
                connector.getEmailForwardings().addTableListener(emailAddressManager, 0);
                connector.getEmailLists().addTableListener(emailAddressManager, 0);
                connector.getEmailListAddresses().addTableListener(emailAddressManager, 0);
                connector.getEmailPipes().addTableListener(emailAddressManager, 0);
                connector.getEmailPipeAddresses().addTableListener(emailAddressManager, 0);
                connector.getLinuxAccounts().getTable().addTableListener(emailAddressManager, 0);
                connector.getLinuxAccAddresses().addTableListener(emailAddressManager, 0);
                connector.getBusinesses().getTable().addTableListener(emailAddressManager, 0);
                connector.getSystemEmailAliases().addTableListener(emailAddressManager, 0);
                System.out.println("Done");
            }
        }
    }

    private static final Object newAliasesLock=new Object();
    private static final String[] newAliasesCommand={"/usr/bin/newaliases"};
    private static void newAliases() throws IOException {
        synchronized(newAliasesLock) {
            // Run the command
            AOServDaemon.exec(newAliasesCommand);
        }
    }

    @Override
    public String getProcessTimerDescription() {
        return "Rebuild Email Addresses";
    }
}
