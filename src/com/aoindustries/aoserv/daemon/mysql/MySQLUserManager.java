/*
 * Copyright 2002-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.IndexedSet;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.MySQLUser;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.command.SetMySQLUserPredisablePasswordCommand;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.client.validator.MySQLUserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.sql.AOConnectionPool;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls the MySQL Users.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLUserManager extends BuilderThread {

    private MySQLUserManager() {
    }

    private static final Object rebuildLock=new Object();
    @Override
    protected boolean doRebuild() {
        try {
            //AOServConnector connector = AOServDaemon.getConnector();
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
                && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized (rebuildLock) {
                for(MySQLServer mysqlServer : thisAOServer.getMysqlServers()) {
                    final String version=mysqlServer.getVersion().getVersion();
                    // Get the list of all users that should exist.  By getting the list and reusing it we have a snapshot of the configuration.
                    IndexedSet<MySQLUser> users = mysqlServer.getMysqlUsers();

                    boolean modified = false;

                    // Get the connection to work through
                    AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
                    Connection conn = pool.getConnection();
                    try {
                        // Get the list of all existing users
                        Set<String> existing = new HashSet<String>();
                        Statement stmt = conn.createStatement();
                        try {
                            ResultSet results = stmt.executeQuery("select host, user from user");
                            try {
                                while (results.next()) existing.add(results.getString(1) + '|' + results.getString(2));
                            } finally {
                                results.close();
                            }
                        } finally {
                            stmt.close();
                        }

                        // Update existing users to proper values
                        String updateSQL;
                        if(version.startsWith(MySQLServer.VERSION_4_0_PREFIX)) {
                            updateSQL="update user set\n"
                                    + "  Select_priv=?,\n"
                                    + "  Insert_priv=?,\n"
                                    + "  Update_priv=?,\n"
                                    + "  Delete_priv=?,\n"
                                    + "  Create_priv=?,\n"
                                    + "  Drop_priv=?,\n"
                                    + "  Reload_priv=?,\n"
                                    + "  Shutdown_priv=?,\n"
                                    + "  Process_priv=?,\n"
                                    + "  File_priv=?,\n"
                                    + "  Grant_priv=?,\n"
                                    + "  References_priv=?,\n"
                                    + "  Index_priv=?,\n"
                                    + "  Alter_priv=?,\n"
                                    + "  Show_db_priv=?,\n"
                                    + "  Super_priv=?,\n"
                                    + "  Create_tmp_table_priv=?,\n"
                                    + "  Lock_tables_priv=?,\n"
                                    + "  Execute_priv=?,\n"
                                    + "  Repl_slave_priv=?,\n"
                                    + "  Repl_client_priv=?,\n"
                                    + "  max_questions=?,\n"
                                    + "  max_updates=?,\n"
                                    + "  max_connections=?\n"
                                    + "where\n"
                                    + "  Host=?\n"
                                    + "  and User=?\n"
                                    + "  and (\n"
                                    + "    Select_priv!=?\n"
                                    + "    or Insert_priv!=?\n"
                                    + "    or Update_priv!=?\n"
                                    + "    or Delete_priv!=?\n"
                                    + "    or Create_priv!=?\n"
                                    + "    or Drop_priv!=?\n"
                                    + "    or Reload_priv!=?\n"
                                    + "    or Shutdown_priv!=?\n"
                                    + "    or Process_priv!=?\n"
                                    + "    or File_priv!=?\n"
                                    + "    or Grant_priv!=?\n"
                                    + "    or References_priv!=?\n"
                                    + "    or Index_priv!=?\n"
                                    + "    or Alter_priv!=?\n"
                                    + "    or Show_db_priv!=?\n"
                                    + "    or Super_priv!=?\n"
                                    + "    or Create_tmp_table_priv!=?\n"
                                    + "    or Lock_tables_priv!=?\n"
                                    + "    or Execute_priv!=?\n"
                                    + "    or Repl_slave_priv!=?\n"
                                    + "    or Repl_client_priv!=?\n"
                                    + "    or max_questions!=?\n"
                                    + "    or max_updates!=?\n"
                                    + "    or max_connections!=?\n"
                                    + "  )";
                        } else if(version.startsWith(MySQLServer.VERSION_4_1_PREFIX)) {
                            updateSQL="update user set\n"
                                    + "  Select_priv=?,\n"
                                    + "  Insert_priv=?,\n"
                                    + "  Update_priv=?,\n"
                                    + "  Delete_priv=?,\n"
                                    + "  Create_priv=?,\n"
                                    + "  Drop_priv=?,\n"
                                    + "  Reload_priv=?,\n"
                                    + "  Shutdown_priv=?,\n"
                                    + "  Process_priv=?,\n"
                                    + "  File_priv=?,\n"
                                    + "  Grant_priv=?,\n"
                                    + "  References_priv=?,\n"
                                    + "  Index_priv=?,\n"
                                    + "  Alter_priv=?,\n"
                                    + "  Show_db_priv=?,\n"
                                    + "  Super_priv=?,\n"
                                    + "  Create_tmp_table_priv=?,\n"
                                    + "  Lock_tables_priv=?,\n"
                                    + "  Execute_priv=?,\n"
                                    + "  Repl_slave_priv=?,\n"
                                    + "  Repl_client_priv=?,\n"
                                    + "  max_questions=?,\n"
                                    + "  max_updates=?,\n"
                                    + "  max_connections=?\n"
                                    + "where\n"
                                    + "  Host=?\n"
                                    + "  and User=?\n"
                                    + "  and (\n"
                                    + "    Select_priv!=?\n"
                                    + "    or Insert_priv!=?\n"
                                    + "    or Update_priv!=?\n"
                                    + "    or Delete_priv!=?\n"
                                    + "    or Create_priv!=?\n"
                                    + "    or Drop_priv!=?\n"
                                    + "    or Reload_priv!=?\n"
                                    + "    or Shutdown_priv!=?\n"
                                    + "    or Process_priv!=?\n"
                                    + "    or File_priv!=?\n"
                                    + "    or Grant_priv!=?\n"
                                    + "    or References_priv!=?\n"
                                    + "    or Index_priv!=?\n"
                                    + "    or Alter_priv!=?\n"
                                    + "    or Show_db_priv!=?\n"
                                    + "    or Super_priv!=?\n"
                                    + "    or Create_tmp_table_priv!=?\n"
                                    + "    or Lock_tables_priv!=?\n"
                                    + "    or Execute_priv!=?\n"
                                    + "    or Repl_slave_priv!=?\n"
                                    + "    or Repl_client_priv!=?\n"
                                    + "    or max_questions!=?\n"
                                    + "    or max_updates!=?\n"
                                    + "    or max_connections!=?\n"
                                    + "  )";
                        } else if(version.startsWith(MySQLServer.VERSION_5_0_PREFIX)) {
                            updateSQL="update user set\n"
                                    + "  Select_priv=?,\n"
                                    + "  Insert_priv=?,\n"
                                    + "  Update_priv=?,\n"
                                    + "  Delete_priv=?,\n"
                                    + "  Create_priv=?,\n"
                                    + "  Drop_priv=?,\n"
                                    + "  Reload_priv=?,\n"
                                    + "  Shutdown_priv=?,\n"
                                    + "  Process_priv=?,\n"
                                    + "  File_priv=?,\n"
                                    + "  Grant_priv=?,\n"
                                    + "  References_priv=?,\n"
                                    + "  Index_priv=?,\n"
                                    + "  Alter_priv=?,\n"
                                    + "  Show_db_priv=?,\n"
                                    + "  Super_priv=?,\n"
                                    + "  Create_tmp_table_priv=?,\n"
                                    + "  Lock_tables_priv=?,\n"
                                    + "  Execute_priv=?,\n"
                                    + "  Repl_slave_priv=?,\n"
                                    + "  Repl_client_priv=?,\n"
                                    + "  Create_view_priv=?,\n"
                                    + "  Show_view_priv=?,\n"
                                    + "  Create_routine_priv=?,\n"
                                    + "  Alter_routine_priv=?,\n"
                                    + "  Create_user_priv=?,\n"
                                    + "  max_questions=?,\n"
                                    + "  max_updates=?,\n"
                                    + "  max_connections=?,\n"
                                    + "  max_user_connections=?\n"
                                    + "where\n"
                                    + "  Host=?\n"
                                    + "  and User=?\n"
                                    + "  and (\n"
                                    + "    Select_priv!=?\n"
                                    + "    or Insert_priv!=?\n"
                                    + "    or Update_priv!=?\n"
                                    + "    or Delete_priv!=?\n"
                                    + "    or Create_priv!=?\n"
                                    + "    or Drop_priv!=?\n"
                                    + "    or Reload_priv!=?\n"
                                    + "    or Shutdown_priv!=?\n"
                                    + "    or Process_priv!=?\n"
                                    + "    or File_priv!=?\n"
                                    + "    or Grant_priv!=?\n"
                                    + "    or References_priv!=?\n"
                                    + "    or Index_priv!=?\n"
                                    + "    or Alter_priv!=?\n"
                                    + "    or Show_db_priv!=?\n"
                                    + "    or Super_priv!=?\n"
                                    + "    or Create_tmp_table_priv!=?\n"
                                    + "    or Lock_tables_priv!=?\n"
                                    + "    or Execute_priv!=?\n"
                                    + "    or Repl_slave_priv!=?\n"
                                    + "    or Repl_client_priv!=?\n"
                                    + "    or Create_view_priv!=?\n"
                                    + "    or Show_view_priv!=?\n"
                                    + "    or Create_routine_priv!=?\n"
                                    + "    or Alter_routine_priv!=?\n"
                                    + "    or Create_user_priv!=?\n"
                                    + "    or max_questions!=?\n"
                                    + "    or max_updates!=?\n"
                                    + "    or max_connections!=?\n"
                                    + "    or max_user_connections!=?\n"
                                    + "  )";
                        } else if(version.startsWith(MySQLServer.VERSION_5_1_PREFIX)) {
                            updateSQL="update user set\n"
                                    + "  Select_priv=?,\n"
                                    + "  Insert_priv=?,\n"
                                    + "  Update_priv=?,\n"
                                    + "  Delete_priv=?,\n"
                                    + "  Create_priv=?,\n"
                                    + "  Drop_priv=?,\n"
                                    + "  Reload_priv=?,\n"
                                    + "  Shutdown_priv=?,\n"
                                    + "  Process_priv=?,\n"
                                    + "  File_priv=?,\n"
                                    + "  Grant_priv=?,\n"
                                    + "  References_priv=?,\n"
                                    + "  Index_priv=?,\n"
                                    + "  Alter_priv=?,\n"
                                    + "  Show_db_priv=?,\n"
                                    + "  Super_priv=?,\n"
                                    + "  Create_tmp_table_priv=?,\n"
                                    + "  Lock_tables_priv=?,\n"
                                    + "  Execute_priv=?,\n"
                                    + "  Repl_slave_priv=?,\n"
                                    + "  Repl_client_priv=?,\n"
                                    + "  Create_view_priv=?,\n"
                                    + "  Show_view_priv=?,\n"
                                    + "  Create_routine_priv=?,\n"
                                    + "  Alter_routine_priv=?,\n"
                                    + "  Create_user_priv=?,\n"
                                    + "  Event_priv=?,\n"
                                    + "  Trigger_priv=?,\n"
                                    + "  max_questions=?,\n"
                                    + "  max_updates=?,\n"
                                    + "  max_connections=?,\n"
                                    + "  max_user_connections=?\n"
                                    + "where\n"
                                    + "  Host=?\n"
                                    + "  and User=?\n"
                                    + "  and (\n"
                                    + "    Select_priv!=?\n"
                                    + "    or Insert_priv!=?\n"
                                    + "    or Update_priv!=?\n"
                                    + "    or Delete_priv!=?\n"
                                    + "    or Create_priv!=?\n"
                                    + "    or Drop_priv!=?\n"
                                    + "    or Reload_priv!=?\n"
                                    + "    or Shutdown_priv!=?\n"
                                    + "    or Process_priv!=?\n"
                                    + "    or File_priv!=?\n"
                                    + "    or Grant_priv!=?\n"
                                    + "    or References_priv!=?\n"
                                    + "    or Index_priv!=?\n"
                                    + "    or Alter_priv!=?\n"
                                    + "    or Show_db_priv!=?\n"
                                    + "    or Super_priv!=?\n"
                                    + "    or Create_tmp_table_priv!=?\n"
                                    + "    or Lock_tables_priv!=?\n"
                                    + "    or Execute_priv!=?\n"
                                    + "    or Repl_slave_priv!=?\n"
                                    + "    or Repl_client_priv!=?\n"
                                    + "    or Create_view_priv!=?\n"
                                    + "    or Show_view_priv!=?\n"
                                    + "    or Create_routine_priv!=?\n"
                                    + "    or Alter_routine_priv!=?\n"
                                    + "    or Create_user_priv!=?\n"
                                    + "    or Event_priv!=?\n"
                                    + "    or Trigger_priv!=?\n"
                                    + "    or max_questions!=?\n"
                                    + "    or max_updates!=?\n"
                                    + "    or max_connections!=?\n"
                                    + "    or max_user_connections!=?\n"
                                    + "  )";
                        } else throw new SQLException("Unsupported MySQL version: "+version);

                        PreparedStatement pstmt = conn.prepareStatement(updateSQL);
                        try {
                            for(MySQLUser mu : users) {
                                InetAddress host=mu.getHost();
                                MySQLUserId username=mu.getUserId();
                                String key=(host==null ? "" : host.toString())+'|'+username.toString();
                                if(existing.contains(key)) {
                                    int pos=1;
                                    // set
                                    pstmt.setString(pos++, mu.getSelectPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getInsertPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getUpdatePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getDeletePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getCreatePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getDropPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReloadPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getShutdownPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getProcessPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getFilePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getGrantPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReferencePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getIndexPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getAlterPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getShowDbPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getSuperPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getCreateTmpTablePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getLockTablesPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getExecutePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReplSlavePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReplClientPriv()?"Y":"N");
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                    ) {
                                        pstmt.setString(pos++, mu.getCreateViewPriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getShowViewPriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getCreateRoutinePriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getAlterRoutinePriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getCreateUserPriv()?"Y":"N");
                                        if(version.startsWith(MySQLServer.VERSION_5_1_PREFIX)) {
                                            pstmt.setString(pos++, mu.getEventPriv()?"Y":"N");
                                            pstmt.setString(pos++, mu.getTriggerPriv()?"Y":"N");
                                        }
                                    }
                                    pstmt.setInt(pos++, mu.getMaxQuestions());
                                    pstmt.setInt(pos++, mu.getMaxUpdates());
                                    pstmt.setInt(pos++, mu.getMaxConnections());
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                    ) pstmt.setInt(pos++, mu.getMaxUserConnections());
                                    // where
                                    pstmt.setString(pos++, host==null ? "" : host.toString());
                                    pstmt.setString(pos++, username.toString());
                                    pstmt.setString(pos++, mu.getSelectPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getInsertPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getUpdatePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getDeletePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getCreatePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getDropPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReloadPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getShutdownPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getProcessPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getFilePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getGrantPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReferencePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getIndexPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getAlterPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getShowDbPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getSuperPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getCreateTmpTablePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getLockTablesPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getExecutePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReplSlavePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReplClientPriv()?"Y":"N");
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                    ) {
                                        pstmt.setString(pos++, mu.getCreateViewPriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getShowViewPriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getCreateRoutinePriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getAlterRoutinePriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getCreateUserPriv()?"Y":"N");
                                        if(version.startsWith(MySQLServer.VERSION_5_1_PREFIX)) {
                                            pstmt.setString(pos++, mu.getEventPriv()?"Y":"N");
                                            pstmt.setString(pos++, mu.getTriggerPriv()?"Y":"N");
                                        }
                                    }
                                    pstmt.setInt(pos++, mu.getMaxQuestions());
                                    pstmt.setInt(pos++, mu.getMaxUpdates());
                                    pstmt.setInt(pos++, mu.getMaxConnections());
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                    ) pstmt.setInt(pos++, mu.getMaxUserConnections());
                                    int updateCount=pstmt.executeUpdate();
                                    if(updateCount>0) modified = true;
                                }
                            }
                        } finally {
                            pstmt.close();
                        }

                        // Add the users that do not exist and should
                        String insertSQL;
                        if(version.startsWith(MySQLServer.VERSION_4_0_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?)";
                        else if(version.startsWith(MySQLServer.VERSION_4_1_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?)";
                        else if(version.startsWith(MySQLServer.VERSION_5_0_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?,?)";
                        else if(version.startsWith(MySQLServer.VERSION_5_1_PREFIX)) insertSQL="insert into user values(?,?,'"+MySQLUser.NO_PASSWORD_DB_VALUE+"',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'','','','',?,?,?,?)";
                        else throw new SQLException("Unsupported MySQL version: "+version);

                        pstmt = conn.prepareStatement(insertSQL);
                        try {
                            for(MySQLUser mu : users) {
                                InetAddress host=mu.getHost();
                                MySQLUserId username=mu.getUserId();
                                String key=(host==null ? "" : host.toString())+'|'+username.toString();
                                if (!existing.remove(key)) {
                                    // Add the user
                                    int pos=1;
                                    pstmt.setString(pos++, host==null ? "" : host.toString());
                                    pstmt.setString(pos++, username.toString());
                                    pstmt.setString(pos++, mu.getSelectPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getInsertPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getUpdatePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getDeletePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getCreatePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getDropPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReloadPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getShutdownPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getProcessPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getFilePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getGrantPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReferencePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getIndexPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getAlterPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getShowDbPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getSuperPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getCreateTmpTablePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getLockTablesPriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getExecutePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReplSlavePriv()?"Y":"N");
                                    pstmt.setString(pos++, mu.getReplClientPriv()?"Y":"N");
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                    ) {
                                        pstmt.setString(pos++, mu.getCreateViewPriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getShowViewPriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getCreateRoutinePriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getAlterRoutinePriv()?"Y":"N");
                                        pstmt.setString(pos++, mu.getCreateUserPriv()?"Y":"N");
                                        if(version.startsWith(MySQLServer.VERSION_5_1_PREFIX)) {
                                            pstmt.setString(pos++, mu.getEventPriv()?"Y":"N");
                                            pstmt.setString(pos++, mu.getTriggerPriv()?"Y":"N");
                                        }
                                    }
                                    pstmt.setInt(pos++, mu.getMaxQuestions());
                                    pstmt.setInt(pos++, mu.getMaxUpdates());
                                    pstmt.setInt(pos++, mu.getMaxConnections());
                                    if(
                                        version.startsWith(MySQLServer.VERSION_5_0_PREFIX)
                                        || version.startsWith(MySQLServer.VERSION_5_1_PREFIX)
                                    ) pstmt.setInt(pos++, mu.getMaxUserConnections());
                                    pstmt.executeUpdate();

                                    modified = true;
                                }
                            }
                        } finally {
                            pstmt.close();
                        }

                        // Remove the extra users
                        if (!existing.isEmpty()) {
                            pstmt = conn.prepareStatement("delete from user where host=? and user=?");
                            try {
                                for (String key : existing) {
                                    // Remove the extra host entry
                                    int pos=key.indexOf('|');
                                    String host=key.substring(0, pos);
                                    MySQLUserId user = MySQLUserId.valueOf(key.substring(pos+1));
                                    if(user.equals(MySQLUser.ROOT)) {
                                        LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, new SQLException("Refusing to remove the "+MySQLUser.ROOT+" user for host "+host+", please remove manually."));
                                    } else {
                                        pstmt.setString(1, host);
                                        pstmt.setString(2, user.toString());
                                        pstmt.executeUpdate();

                                        modified = true;
                                    }
                                }
                            } finally {
                                pstmt.close();
                            }
                        }
                    } finally {
                        pool.releaseConnection(conn);
                    }

                    // Disable and enable accounts
                    for(MySQLUser mu : users) {
                        String prePassword=mu.getPredisablePassword();
                        if(!mu.isDisabled()) {
                            if(prePassword!=null) {
                                setEncryptedPassword(mu, prePassword);
                                modified=true;
                                new SetMySQLUserPredisablePasswordCommand(mu, null).execute(AOServDaemon.getConnector());
                            }
                        } else {
                            if(prePassword==null) {
                                new SetMySQLUserPredisablePasswordCommand(mu, getEncryptedPassword(mu)).execute(AOServDaemon.getConnector());
                                setPassword(mu, null);
                                modified=true;
                            }
                        }
                    }
                    if (modified) MySQLServerManager.flushPrivileges(mysqlServer);
                }
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(MySQLUserManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }

    public static String getEncryptedPassword(MySQLUser mu) throws IOException, SQLException {
        MySQLServer mysqlServer = mu.getMysqlServer();
        AOConnectionPool pool=MySQLServerManager.getPool(mysqlServer);
        Connection conn=pool.getConnection(true);
        try {
            PreparedStatement pstmt=conn.prepareStatement("select password from user where user=?");
            try {
                pstmt.setString(1, mu.getUserId().toString());
                ResultSet result=pstmt.executeQuery();
                try {
                    if(result.next()) {
                        return result.getString(1);
                    } else throw new SQLException("No rows returned.");
                } finally {
                    result.close();
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+pstmt);
                throw err;
            } finally {
                pstmt.close();
            }
        } finally {
            pool.releaseConnection(conn);
        }
    }

    public static void setPassword(MySQLUser mu, String password) throws IOException, SQLException {
        MySQLServer mysqlServer = mu.getMysqlServer();
        // Get the connection to work through
        AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
        Connection conn = pool.getConnection();
        try {
            if(password==null) {
                // Disable the account
                PreparedStatement pstmt = conn.prepareStatement("update user set password='"+MySQLUser.NO_PASSWORD_DB_VALUE+"' where user=?");
                try {
                    pstmt.setString(1, mu.getUserId().toString());
                    pstmt.executeUpdate();
                } finally {
                    pstmt.close();
                }
            } else {
                // Reset the password
                PreparedStatement pstmt = conn.prepareStatement("update user set password=password(?) where user=?");
                try {
                    pstmt.setString(1, password);
                    pstmt.setString(2, mu.getUserId().toString());
                    pstmt.executeUpdate();
                } finally {
                    pstmt.close();
                }
            }
        } finally {
            pool.releaseConnection(conn);
        }
        MySQLServerManager.flushPrivileges(mysqlServer);
    }

    public static void setEncryptedPassword(MySQLUser mu, String password) throws IOException, SQLException {
        // Get the connection to work through
        MySQLServer mysqlServer = mu.getMysqlServer();
        AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
        Connection conn = pool.getConnection();
        try {
            if(password==null) {
                // Disable the account
                PreparedStatement pstmt = conn.prepareStatement("update user set password='"+MySQLUser.NO_PASSWORD_DB_VALUE+"' where user=?");
                try {
                    pstmt.setString(1, mu.getUserId().toString());
                    pstmt.executeUpdate();
                } finally {
                    pstmt.close();
                }
            } else {
                // Reset the password
                PreparedStatement pstmt = conn.prepareStatement("update user set password=? where user=?");
                try {
                    pstmt.setString(1, password);
                    pstmt.setString(2, mu.getUserId().toString());
                    pstmt.executeUpdate();
                } finally {
                    pstmt.close();
                }
            }
        } finally {
            pool.releaseConnection(conn);
        }
        MySQLServerManager.flushPrivileges(mysqlServer);
    }

    private static MySQLUserManager mysqlUserManager;
    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(MySQLUserManager.class)
                && mysqlUserManager==null
            ) {
                System.out.print("Starting MySQLUserManager: ");
                AOServConnector conn=AOServDaemon.getConnector();
                mysqlUserManager=new MySQLUserManager();
                conn.getMysqlUsers().getTable().addTableListener(mysqlUserManager, 0);
                System.out.println("Done");
            }
        }
    }

    public static void waitForRebuild() {
        if(mysqlUserManager!=null) mysqlUserManager.waitForBuild();
    }

    @Override
    public String getProcessTimerDescription() {
        return "Rebuild MySQL Users";
    }
}