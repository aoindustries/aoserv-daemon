/*
 * Copyright 2008-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdBind;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteAuthenticatedLocation;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.TechnologyVersion;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.InetAddress;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.logging.Level;

/**
 * Manages HttpdServer configurations and control.
 *
 * TODO: Build first Apache as "httpd" instead of "httpd1".
 *
 * @author  AO Industries, Inc.
 */
public class HttpdServerManager {

	private HttpdServerManager() {}

	/**
	 * The directory that all HTTPD configs are located in (/etc/httpd).
	 */
	static final String CONFIG_DIRECTORY = "/etc/httpd";

	/**
	 * The directory that HTTPD conf are located in (/etc/httpd/conf).
	 */
	static final String CONF_DIRECTORY = CONFIG_DIRECTORY + "/conf";

	/**
	 * The directory that individual host and bind configurations are in.
	 */
	static final String CONF_HOSTS = CONF_DIRECTORY+"/hosts";

	/**
	 * The init.d directory.
	 */
	private static final String INIT_DIRECTORY = "/etc/rc.d/init.d";

	/**
	 * Gets the workers#.properties file path.
	 */
	private static String getWorkersFile(HttpdServer hs) {
		return CONF_DIRECTORY+"/workers"+hs.getNumber()+".properties";
	}

	/**
	 * Gets the workers#.properties.new file path.
	 */
	private static String getWorkersNewFile(HttpdServer hs) {
		return getWorkersFile(hs)+".new";
	}

	/**
	 * Gets the httpd#.conf file path.
	 */
	private static String getHttpdConfFile(HttpdServer hs) {
		return CONF_DIRECTORY+"/httpd"+hs.getNumber()+".conf";
	}

	/**
	 * Gets the httpd#.conf.new file path.
	 */
	private static String getHttpdConfNewFile(HttpdServer hs) {
		return getHttpdConfFile(hs)+".new";
	}

	/**
	 * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
	 */
	static void doRebuild(
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded
	) throws IOException, SQLException {
		// Used below
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		AOServer aoServer = AOServDaemon.getThisAOServer();

		// Rebuild /etc/httpd/conf/hosts/ files
		doRebuildConfHosts(aoServer, bout, deleteFileList, serversNeedingReloaded);

		// Rebuild /etc/httpd/conf/ files
		doRebuildConf(aoServer, bout, serversNeedingReloaded);

		// Control the /etc/rc.d/init.d/httpd* files
		doRebuildInitScripts(aoServer, bout, deleteFileList, serversNeedingReloaded);

		// Other filesystem fixes related to logging
		fixFilesystem(deleteFileList);
	}

	/**
	 * Rebuilds the files in /etc/httpd/conf/hosts/
	 */
	private static void doRebuildConfHosts(
		AOServer thisAoServer,
		ByteArrayOutputStream bout,
		List<File> deleteFileList,
		Set<HttpdServer> serversNeedingReloaded
	) throws IOException, SQLException {
		// The config directory should only contain files referenced in the database
		String[] list=new File(CONF_HOSTS).list();
		Set<String> extraFiles = new HashSet<>(list.length*4/3+1);
		extraFiles.addAll(Arrays.asList(list));

		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();

		// Iterate through each site
		for(HttpdSite httpdSite : thisAoServer.getHttpdSites()) {
			// Some values used below
			final String siteName = httpdSite.getSiteName();
			final HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);
			final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
			final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();
			final int lsgGID = lsg.getGid().getId();
			final List<HttpdSiteBind> binds = httpdSite.getHttpdSiteBinds();

			// Remove from delete list
			extraFiles.remove(siteName);

			// The shared config part
			final UnixFile sharedFile = new UnixFile(CONF_HOSTS, siteName);
			if(!manager.httpdSite.isManual() || !sharedFile.getStat().exists()) {
				if(
					DaemonFileUtils.writeIfNeeded(
						buildHttpdSiteSharedFile(manager, bout),
						null,
						sharedFile,
						UnixFile.ROOT_UID,
						lsgGID,
						0640,
						uid_min,
						gid_min
					)
				) {
					// File changed, all servers that use this site need restarted
					for(HttpdSiteBind hsb : binds) serversNeedingReloaded.add(hsb.getHttpdBind().getHttpdServer());
				}
			}

			// Each of the binds
			for(HttpdSiteBind bind : binds) {
				// Some value used below
				final boolean isManual = bind.isManual();
				final boolean isDisabled = bind.isDisabled();
				final String predisableConfig = bind.getPredisableConfig();
				final HttpdBind httpdBind = bind.getHttpdBind();
				final NetBind nb = httpdBind.getNetBind();

				// Generate the filename
				final String bindFilename = siteName+"_"+nb.getIPAddress().getInetAddress()+"_"+nb.getPort().getPort();
				final UnixFile bindFile = new UnixFile(CONF_HOSTS, bindFilename);
				final boolean exists = bindFile.getStat().exists();

				// Remove from delete list
				extraFiles.remove(bindFilename);

				// Will only be verified when not exists, auto mode, disabled, or predisabled config need to be restored
				if(
					!exists                                 // Not exists
					|| !isManual                            // Auto mode
					|| isDisabled                           // Disabled
					|| predisableConfig!=null               // Predisabled config needs to be restored
				) {
					// Save manual config file for later restoration
					if(exists && isManual && isDisabled && predisableConfig==null) {
						bind.setPredisableConfig(FileUtils.readFileAsString(bindFile.getFile()));
					}

					// Restore/build the file
					byte[] newContent;
					if(isManual && !isDisabled && predisableConfig!=null) {
						// Restore manual config values
						newContent = predisableConfig.getBytes();
					} else {
						// Create auto config
						newContent = buildHttpdSiteBindFile(
							manager,
							bind,
							isDisabled ? HttpdSite.DISABLED : siteName,
							bout
						);
					}
					// Write only when missing or modified
					if(
						DaemonFileUtils.writeIfNeeded(
							newContent,
							null,
							bindFile,
							UnixFile.ROOT_UID,
							lsgGID,
							0640,
							uid_min,
							gid_min
					   )
					) {
						// Reload server if the file is modified
						serversNeedingReloaded.add(httpdBind.getHttpdServer());
					}
				}
			}
		}

		// Mark files for deletion
		for(String filename : extraFiles) deleteFileList.add(new File(CONF_HOSTS, filename));
	}

	/**
	 * Builds the contents for the shared part of a HttpdSite config.
	 */
	private static byte[] buildHttpdSiteSharedFile(HttpdSiteManager manager, ByteArrayOutputStream bout) throws IOException, SQLException {
		final HttpdSite httpdSite = manager.httpdSite;
		final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
		final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();

		// Build to a temporary buffer
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			out.print("    ServerAdmin ").print(httpdSite.getServerAdmin()).print("\n");

			// Enable CGI PHP option if the site supports CGI and PHP
			if(manager.enablePhp() && manager.enableCgi()) {
				out.print("\n"
						+ "    # Use CGI-based PHP when not using mod_php\n"
						+ "    <IfModule !sapi_apache2.c>\n"
						+ "        <IfModule !mod_php5.c>\n"
						+ "            Action php-script /cgi-bin/php\n"
						// Avoid *.php.txt going to PHP: http://php.net/manual/en/install.unix.apache2.php
						+ "            <FilesMatch \\.php$>\n"
						+ "                SetHandler php-script\n"
						+ "            </FilesMatch>\n"
						//+ "            AddHandler php-script .php\n"
						+ "        </IfModule>\n"
						+ "    </IfModule>\n");
			}

			// The CGI user info
			out.print("\n"
					+ "    # Use suexec when available\n"
					+ "    <IfModule mod_suexec.c>\n"
					+ "        SuexecUserGroup ").print(lsa.getLinuxAccount().getUsername()).print(' ').print(lsg.getLinuxGroup().getName()).print("\n"
					+ "    </IfModule>\n");

			// Protect against TRACE and TRACK
			if(manager.blockAllTraceAndTrackRequests()) {
				out.print("\n"
						+ "    # Protect dangerous request methods\n"
						+ "    <IfModule mod_rewrite.c>\n"
						+ "        RewriteEngine on\n"
						+ "        RewriteCond %{REQUEST_METHOD} ^(TRACE|TRACK)\n"
						+ "        RewriteRule .* - [F]\n"
						+ "    </IfModule>\n");
			}

			// Rejected URLs
			SortedSet<HttpdSiteManager.Location> rejectedLocations = manager.getRejectedLocations();
			if(!rejectedLocations.isEmpty()) {
				out.print("\n"
						+ "    # Rejected URL patterns\n");
				for(HttpdSiteManager.Location location : rejectedLocations) {
					if(location.isRegularExpression()) {
						out.print("    <LocationMatch \"").print(location.getLocation()).print("\">\n"
								+ "        Order deny,allow\n"
								+ "        Deny from All\n"
								+ "    </LocationMatch>\n"
						);
					} else {
						out.print("    <Location \"").print(location.getLocation()).print("\">\n"
								+ "        Order deny,allow\n"
								+ "        Deny from All\n"
								+ "    </Location>\n"
						);
					}
				}
			}

			// Rewrite rules
			SortedMap<String,String> permanentRewrites = manager.getPermanentRewriteRules();
			if(!permanentRewrites.isEmpty()) {
				// Write the standard restricted URL patterns
				out.print("\n"
						+ "    # Rewrite rules\n"
						+ "    <IfModule mod_rewrite.c>\n"
						+ "        RewriteEngine on\n");
				for(Map.Entry<String,String> entry : permanentRewrites.entrySet()) {
					out.print("        RewriteRule ").print(entry.getKey()).print(' ').print(entry.getValue()).print(" [L,R=permanent]\n");
				}
				out.print("    </IfModule>\n");
			}

			// Write the authenticated locations
			List<HttpdSiteAuthenticatedLocation> hsals = httpdSite.getHttpdSiteAuthenticatedLocations();
			if(!hsals.isEmpty()) {
				out.print("\n"
						+ "    # Authenticated Locations\n");
				for(HttpdSiteAuthenticatedLocation hsal : hsals) {
					out.print("    <").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(" \"").print(hsal.getPath()).print("\">\n");
					if(hsal.getAuthUserFile() != null || hsal.getAuthGroupFile() != null) out.print("        AuthType Basic\n");
					if(hsal.getAuthName().length()>0) out.print("        AuthName \"").print(hsal.getAuthName()).print("\"\n");
					if(hsal.getAuthUserFile() != null) out.print("        AuthUserFile \"").print(hsal.getAuthUserFile()).print("\"\n");
					if(hsal.getAuthGroupFile() != null) out.print("        AuthGroupFile \"").print(hsal.getAuthGroupFile()).print("\"\n");
					if(hsal.getRequire().length()>0) out.print("        require ").print(hsal.getRequire()).print('\n');
					out.print("    </").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(">\n");
				}
			}

			// Error if no root webapp found
			boolean foundRoot = false;
			SortedMap<String,HttpdSiteManager.WebAppSettings> webapps = manager.getWebapps();
			for(Map.Entry<String,HttpdSiteManager.WebAppSettings> entry : webapps.entrySet()) {
				String path = entry.getKey();
				HttpdSiteManager.WebAppSettings settings = entry.getValue();
				UnixPath docBase = settings.getDocBase();

				if(path.length()==0) {
					foundRoot = true;
					// DocumentRoot
					out.print("\n"
							+ "    # Set up the default webapp\n"
							+ "    DocumentRoot \"").print(docBase).print("\"\n"
							+ "    <Directory \"").print(docBase).print("\">\n"
							+ "        Allow from All\n"
							+ "        AllowOverride ").print(settings.getAllowOverride()).print("\n"
							+ "        Order allow,deny\n"
							+ "        Options ").print(settings.getOptions()).print("\n"
							+ "    </Directory>\n");
				} else {
					// Is webapp/alias
					out.print("\n"
							+ "    # Set up the ").print(path).print(" webapp\n"
							+ "    Alias \"").print(path).print("/\" \"").print(docBase).print("/\"\n"
							+ "    AliasMatch \"^").print(path).print("$\" \"").print(docBase).print("\"\n"
							+ "    <Directory \"").print(docBase).print("\">\n"
							+ "        Allow from All\n"
							+ "        AllowOverride ").print(settings.getAllowOverride()).print("\n"
							+ "        Order allow,deny\n"
							+ "        Options ").print(settings.getOptions()).print("\n"
							+ "    </Directory>\n");
				}
				if(settings.enableCgi()) {
					if(!manager.enableCgi()) throw new SQLException("Unable to enable webapp CGI when site has CGI disabled");
					out.print("    <Directory \"").print(docBase).print("/cgi-bin\">\n"
							+ "        <IfModule mod_ssl.c>\n"
							+ "            SSLOptions +StdEnvVars\n"
							+ "        </IfModule>\n"
							+ "        Options ExecCGI\n"
							+ "        SetHandler cgi-script\n"
							+ "    </Directory>\n");
					/*
					out.print("    ScriptAlias \"").print(path).print("/cgi-bin/\" \"").print(docBase).print("/cgi-bin/\"\n"
							+ "    <Directory \"").print(docBase).print("/cgi-bin\">\n"
							+ "        Options ExecCGI\n"
							+ "        <IfModule mod_ssl.c>\n"
							+ "            SSLOptions +StdEnvVars\n"
							+ "        </IfModule>\n"
							+ "        Allow from All\n"
							+ "        Order allow,deny\n"
							+ "    </Directory>\n");*/
				}
			}
			if(!foundRoot) throw new SQLException("No DocumentRoot found");

			// Write any JkMount and JkUnmount directives
			SortedSet<HttpdSiteManager.JkSetting> jkSettings = manager.getJkSettings();
			if(!jkSettings.isEmpty()) {
				out.print("\n"
						+ "    # Request patterns mapped through mod_jk\n"
						+ "    <IfModule mod_jk.c>\n");
				for(HttpdSiteManager.JkSetting setting : jkSettings) {
					out
						.print("        ")
						.print(setting.isMount() ? "JkMount" : "JkUnMount")
						.print(' ')
						.print(setting.getPath())
						.print(' ')
						.print(setting.getJkCode())
						.print('\n');
				}
				out.print("\n"
						+ "        # Remove jsessionid for non-jk requests\n"
						+ "        <IfModule mod_rewrite.c>\n"
						+ "            RewriteEngine On\n"
						+ "            RewriteRule ^(.*);jsessionid=.*$ $1\n"
						+ "        </IfModule>\n"
						+ "    </IfModule>\n");
			}
		}
		return bout.toByteArray();
	}

	/**
	 * Rebuilds the files in /etc/httpd/conf/
	 * <ul>
	 *   <li>/etc/httpd/conf/httpd#.conf</li>
	 *   <li>/etc/httpd/conf/workers#.properties</li>
	 * </ul>
	 */
	private static void doRebuildConf(AOServer thisAoServer, ByteArrayOutputStream bout, Set<HttpdServer> serversNeedingReloaded) throws IOException, SQLException {
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		// Rebuild per-server files
		for(HttpdServer hs : thisAoServer.getHttpdServers()) {
			List<HttpdSite> sites = hs.getHttpdSites();
			// Rebuild the httpd.conf file
			if(
				DaemonFileUtils.writeIfNeeded(
					buildHttpdConf(hs, sites, bout),
					new UnixFile(getHttpdConfNewFile(hs)),
					new UnixFile(getHttpdConfFile(hs)),
					UnixFile.ROOT_UID,
					UnixFile.ROOT_GID,
					0644,
					uid_min,
					gid_min
				)
			) {
				serversNeedingReloaded.add(hs);
			}

			// Rebuild the workers.properties file
			// Only include mod_jk when at least one site has jk settings
			boolean hasJkSettings = false;
			for(HttpdSite site : sites) {
				HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
				if(!manager.getJkSettings().isEmpty()) {
					hasJkSettings = true;
					break;
				}
			}
			UnixFile workersFile = new UnixFile(getWorkersFile(hs));
			if(hasJkSettings) {
				if(
					DaemonFileUtils.writeIfNeeded(
						buildWorkersFile(hs, bout),
						new UnixFile(getWorkersNewFile(hs)),
						workersFile,
						UnixFile.ROOT_UID,
						UnixFile.ROOT_GID,
						0644,
						uid_min,
						gid_min
					)
				) {
					serversNeedingReloaded.add(hs);
				}
			} else {
				// mod_jk not used: remove the unnecessary workers file
				if(workersFile.getStat().exists()) workersFile.delete();
			}
		}
	}

	/**
	 * Builds the httpd#.conf file for CentOS 5
	 */
	private static byte[] buildHttpdConfCentOs5(HttpdServer hs, List<HttpdSite> sites, ByteArrayOutputStream bout) throws IOException, SQLException {
		final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig!=HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) throw new AssertionError("This method is for CentOS 5 only");
		final int serverNum = hs.getNumber();
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			LinuxServerAccount lsa=hs.getLinuxServerAccount();
			boolean isEnabled=!lsa.isDisabled();
			// The version of PHP module to run
			TechnologyVersion phpVersion=hs.getModPhpVersion();
			out.print("ServerRoot \""+CONFIG_DIRECTORY+"\"\n"
					+ "Include conf/modules_conf/core\n"
					+ "PidFile /var/run/httpd").print(serverNum).print(".pid\n"
					+ "Timeout ").print(hs.getTimeOut()).print("\n"
					+ "CoreDumpDirectory /var/log/httpd/httpd").print(serverNum).print("\n"
					+ "LockFile /var/log/httpd/httpd").print(serverNum).print("/accept.lock\n"
					+ "\n"
					+ "Include conf/modules_conf/prefork\n"
					+ "Include conf/modules_conf/worker\n"
					+ "\n"
					+ "<IfModule prefork.c>\n"
					+ "    ListenBacklog 511\n"
					+ "    ServerLimit ").print(hs.getMaxConcurrency()).print("\n"
					+ "    MaxClients ").print(hs.getMaxConcurrency()).print("\n"
					+ "</IfModule>\n"
					+ "\n"
					+ "LoadModule auth_basic_module modules/mod_auth_basic.so\n"
					+ "#LoadModule auth_digest_module modules/mod_auth_digest.so\n"
					+ "LoadModule authn_file_module modules/mod_authn_file.so\n"
					+ "#LoadModule authn_alias_module modules/mod_authn_alias.so\n"
					+ "#LoadModule authn_anon_module modules/mod_authn_anon.so\n"
					+ "#LoadModule authn_dbm_module modules/mod_authn_dbm.so\n"
					+ "#LoadModule authn_default_module modules/mod_authn_default.so\n"
					+ "LoadModule authz_host_module modules/mod_authz_host.so\n"
					+ "LoadModule authz_user_module modules/mod_authz_user.so\n"
					+ "#LoadModule authz_owner_module modules/mod_authz_owner.so\n"
					+ "LoadModule authz_groupfile_module modules/mod_authz_groupfile.so\n"
					+ "#LoadModule authz_dbm_module modules/mod_authz_dbm.so\n"
					+ "#LoadModule authz_default_module modules/mod_authz_default.so\n"
					+ "#LoadModule ldap_module modules/mod_ldap.so\n"
					+ "#LoadModule authnz_ldap_module modules/mod_authnz_ldap.so\n");
			// Comment-out include module when no site has .shtml enabled
			boolean hasSsi = false;
			for(HttpdSite site : sites) {
				if(site.getEnableSsi()) {
					hasSsi = true;
					break;
				}
			}
			if(!hasSsi) out.print('#');
			out.print("LoadModule include_module modules/mod_include.so\n"
					+ "LoadModule log_config_module modules/mod_log_config.so\n"
					+ "#LoadModule logio_module modules/mod_logio.so\n"
					+ "LoadModule env_module modules/mod_env.so\n"
					+ "#LoadModule ext_filter_module modules/mod_ext_filter.so\n"
					+ "LoadModule mime_magic_module modules/mod_mime_magic.so\n"
					+ "LoadModule expires_module modules/mod_expires.so\n"
					+ "LoadModule deflate_module modules/mod_deflate.so\n"
					+ "LoadModule headers_module modules/mod_headers.so\n"
					+ "#LoadModule usertrack_module modules/mod_usertrack.so\n"
					+ "LoadModule setenvif_module modules/mod_setenvif.so\n"
					+ "LoadModule mime_module modules/mod_mime.so\n"
					+ "#LoadModule dav_module modules/mod_dav.so\n"
					+ "LoadModule status_module modules/mod_status.so\n");
			// Comment-out mod_autoindex when no sites used auto-indexes
			boolean hasIndexes = false;
			for(HttpdSite site : sites) {
				if(site.getEnableIndexes()) {
					hasIndexes = true;
					break;
				}
			}
			if(!hasIndexes) out.print('#');
			out.print("LoadModule autoindex_module modules/mod_autoindex.so\n"
					+ "#LoadModule info_module modules/mod_info.so\n"
					+ "#LoadModule dav_fs_module modules/mod_dav_fs.so\n"
					+ "#LoadModule vhost_alias_module modules/mod_vhost_alias.so\n"
					+ "LoadModule negotiation_module modules/mod_negotiation.so\n"
					+ "LoadModule dir_module modules/mod_dir.so\n"
					+ "LoadModule imagemap_module modules/mod_imagemap.so\n"
					+ "LoadModule actions_module modules/mod_actions.so\n"
					+ "#LoadModule speling_module modules/mod_speling.so\n"
					+ "#LoadModule userdir_module modules/mod_userdir.so\n"
					+ "LoadModule alias_module modules/mod_alias.so\n"
					+ "LoadModule rewrite_module modules/mod_rewrite.so\n"
					+ "LoadModule proxy_module modules/mod_proxy.so\n"
					+ "#LoadModule proxy_balancer_module modules/mod_proxy_balancer.so\n"
					+ "#LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n"
					+ "LoadModule proxy_http_module modules/mod_proxy_http.so\n"
					+ "#LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
					+ "#LoadModule cache_module modules/mod_cache.so\n");
			if(hs.useSuexec()) out.print("LoadModule suexec_module modules/mod_suexec.so\n");
			else out.print("#LoadModule suexec_module modules/mod_suexec.so\n");
			out.print("#LoadModule disk_cache_module modules/mod_disk_cache.so\n"
					+ "#LoadModule file_cache_module modules/mod_file_cache.so\n"
					+ "#LoadModule mem_cache_module modules/mod_mem_cache.so\n");
			// Comment-out cgi_module when no CGI enabled
			boolean hasCgi = false;
			for(HttpdSite site : sites) {
				HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
				if(manager.enableCgi()) {
					hasCgi = true;
					break;
				}
			}
			if(!hasCgi) out.print('#');
			out.print("LoadModule cgi_module modules/mod_cgi.so\n"
					+ "#LoadModule cern_meta_module modules/mod_cern_meta.so\n"
					+ "#LoadModule asis_module modules/mod_asis.so\n");
			// Only include mod_jk when at least one site has jk settings
			boolean hasJkSettings = false;
			for(HttpdSite site : sites) {
				HttpdSiteManager manager = HttpdSiteManager.getInstance(site);
				if(!manager.getJkSettings().isEmpty()) {
					hasJkSettings = true;
					break;
				}
			}
			if(hasJkSettings) {
				// TODO: CentOS 7: Install mod_jk RPM now
				out.print("LoadModule jk_module modules/mod_jk-1.2.27.so\n");
			}
			// Comment-out ssl module when has no ssl
			boolean hasSsl = false;
			HAS_SSL :
			for(HttpdSite site : sites) {
				for(HttpdSiteBind hsb : site.getHttpdSiteBinds(hs)) {
					if(hsb.getSSLCertFile() != null) {
						hasSsl = true;
						break HAS_SSL;
					}
				}
			}
			if(!hasSsl) out.print('#');
			out.print("LoadModule ssl_module modules/mod_ssl.so\n");
			if(isEnabled && phpVersion!=null) {
				String version = phpVersion.getVersion();
				String phpMinorVersion = getMinorPhpVersion(version);
				String phpMajorVersion = getMajorPhpVersion(version);
				out.print("\n"
						+ "# Enable mod_php\n"
						+ "LoadModule php").print(phpMajorVersion).print("_module /opt/php-").print(phpMinorVersion).print("-i686/lib/apache/").print(getPhpLib(phpVersion)).print("\n"
						+ "AddType application/x-httpd-php .php\n"
						+ "AddType application/x-httpd-php-source .phps\n");
			}
			out.print("\n"
					+ "Include conf/modules_conf/mod_ident\n"
					+ "Include conf/modules_conf/mod_log_config\n"
					+ "Include conf/modules_conf/mod_mime_magic\n"
					+ "Include conf/modules_conf/mod_setenvif\n"
					+ "Include conf/modules_conf/mod_proxy\n"
					+ "Include conf/modules_conf/mod_mime\n"
					+ "Include conf/modules_conf/mod_dav\n"
					+ "Include conf/modules_conf/mod_status\n");
			// Comment-out mod_autoindex when no sites used auto-indexes
			if(!hasIndexes) out.print('#');
			out.print("Include conf/modules_conf/mod_autoindex\n"
					+ "Include conf/modules_conf/mod_negotiation\n"
					+ "Include conf/modules_conf/mod_dir\n"
					+ "Include conf/modules_conf/mod_userdir\n");
			// Comment-out ssl module when has no ssl
			if(!hasSsl) out.print('#');
			out.print("Include conf/modules_conf/mod_ssl\n");
			// Only include mod_jk when at least one site has jk settings
			if(hasJkSettings) out.print("Include conf/modules_conf/mod_jk\n");
			out.print("\n"
					+ "ServerAdmin root@").print(hs.getAOServer().getHostname()).print("\n"
					+ "\n"
					+ "SSLSessionCache shmcb:/var/cache/httpd/mod_ssl/ssl_scache").print(serverNum).print("(512000)\n"
					+ "\n");
			// Use apache if the account is disabled
			if(isEnabled) {
				out.print("User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
						+ "Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print("\n");
			} else {
				out.print("User "+LinuxAccount.APACHE+"\n"
						+ "Group "+LinuxGroup.APACHE+"\n");
			}
			out.print("\n"
					+ "ServerName ").print(hs.getAOServer().getHostname()).print("\n"
					+ "\n"
					+ "ErrorLog /var/log/httpd/httpd").print(serverNum).print("/error_log\n"
					+ "CustomLog /var/log/httpd/httpd").print(serverNum).print("/access_log combined\n"
					+ "\n"
					+ "<IfModule mod_dav_fs.c>\n"
					+ "    DAVLockDB /var/lib/dav").print(serverNum).print("/lockdb\n"
					+ "</IfModule>\n"
					+ "\n");
			if(hasJkSettings) {
				out.print("<IfModule mod_jk.c>\n"
						+ "    JkWorkersFile /etc/httpd/conf/workers").print(serverNum).print(".properties\n"
						+ "    JkLogFile /var/log/httpd/httpd").print(serverNum).print("/mod_jk.log\n"
						+ "    JkShmFile /var/log/httpd/httpd").print(serverNum).print("/jk-runtime-status\n"
						+ "</IfModule>\n"
						+ "\n");
			}

			// List of binds
			for(HttpdBind hb : hs.getHttpdBinds()) {
				NetBind nb=hb.getNetBind();
				InetAddress ip=nb.getIPAddress().getInetAddress();
				int port=nb.getPort().getPort();
				out.print("Listen ").print(ip.toBracketedString()).print(':').print(port).print("\n"
						+ "NameVirtualHost ").print(ip.toBracketedString()).print(':').print(port).print('\n');
			}

			// The list of sites to include
			for(int d=0;d<2;d++) {
				boolean listFirst=d==0;
				out.print("\n");
				for(HttpdSite site : sites) {
					if(site.listFirst()==listFirst) {
						for(HttpdSiteBind bind : site.getHttpdSiteBinds(hs)) {
							NetBind nb=bind.getHttpdBind().getNetBind();
							InetAddress ipAddress=nb.getIPAddress().getInetAddress();
							int port=nb.getPort().getPort();
							out.print("Include conf/hosts/").print(site.getSiteName()).print('_').print(ipAddress).print('_').print(port).print('\n');
						}
					}
				}
			}
		}
		return bout.toByteArray();
	}

	/**
	 * Builds the httpd#.conf file contents for the provided HttpdServer.
	 */
	private static byte[] buildHttpdConf(HttpdServer hs, List<HttpdSite> sites, ByteArrayOutputStream bout) throws IOException, SQLException {
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		switch(osConfig) {
			case CENTOS_5_I686_AND_X86_64 : return buildHttpdConfCentOs5(hs, sites, bout);
			default                       : throw new AssertionError("Unexpected value for osConfig: "+osConfig);
		}
	}

	/**
	 * Builds the workers#.properties file contents for the provided HttpdServer.
	 */
	private static byte[] buildWorkersFile(HttpdServer hs, ByteArrayOutputStream bout) throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		List<HttpdWorker> workers=hs.getHttpdWorkers();
		int workerCount=workers.size();

		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			out.print("worker.list=");
			for(int d=0;d<workerCount;d++) {
				if(d>0) out.print(',');
				out.print(workers.get(d).getCode().getCode());
			}
			out.print('\n');
			for(int d=0;d<workerCount;d++) {
				HttpdWorker worker=workers.get(d);
				String code=worker.getCode().getCode();
				out.print("\n"
						+ "worker.").print(code).print(".type=").print(worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol()).print("\n"
						+ "worker.").print(code).print(".port=").print(worker.getNetBind().getPort().getPort()).print('\n');
			}
		}
		return bout.toByteArray();
	}

	/**
	 * Builds the contents of a HttpdSiteBind file.
	 */
	private static byte[] buildHttpdSiteBindFile(HttpdSiteManager manager, HttpdSiteBind bind, String siteInclude, ByteArrayOutputStream bout) throws IOException, SQLException {
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		HttpdBind httpdBind = bind.getHttpdBind();
		NetBind netBind = httpdBind.getNetBind();
		int port = netBind.getPort().getPort();
		InetAddress ipAddress = netBind.getIPAddress().getInetAddress();
		HttpdSiteURL primaryHSU = bind.getPrimaryHttpdSiteURL();
		String primaryHostname = primaryHSU.getHostname().toString();

		// TODO: Robots NOINDEX, NOFOLLOW on test URL, when it is not the primary?
		// TODO: Canonical URL header for non-primary, non-test: https://support.google.com/webmasters/answer/139066
		bout.reset();
		try (ChainWriter out = new ChainWriter(bout)) {
			out.print("<VirtualHost ").print(ipAddress.toBracketedString()).print(':').print(port).print(">\n"
					+ "    ServerName ").print(primaryHostname).print('\n'
			);
			List<HttpdSiteURL> altURLs=bind.getAltHttpdSiteURLs();
			if(!altURLs.isEmpty()) {
				out.print("    ServerAlias");
				for(HttpdSiteURL altURL : altURLs) {
					out.print(' ').print(altURL.getHostname().toString());
				}
				out.print('\n');
			}
			out.print("\n"
					+ "    CustomLog ").print(bind.getAccessLog()).print(" combined\n"
					+ "    ErrorLog ").print(bind.getErrorLog()).print("\n"
					+ "\n");
			UnixPath sslCert=bind.getSSLCertFile();
			if(sslCert!=null) {
				String sslCertStr = sslCert.toString();
				// If a .ca file exists with the same name as the certificate, use it instead of the OS default
				String sslCa = osConfig.getOpensslDefaultCaFile().toString();
				if(sslCertStr.endsWith(".cert")) {
					String possibleCa = sslCertStr.substring(0, sslCertStr.length()-5) + ".ca";
					if(new File(possibleCa).exists()) sslCa = possibleCa;
				}

				out.print("    <IfModule mod_ssl.c>\n"
						+ "        SSLCertificateFile ").print(sslCert).print("\n"
						+ "        SSLCertificateKeyFile ").print(bind.getSSLCertKeyFile()).print("\n"
						+ "        SSLCACertificateFile ").print(sslCa).print("\n");
				boolean enableCgi = manager.enableCgi();
				boolean enableSsi = manager.httpdSite.getEnableSsi();
				if(enableCgi && enableSsi) {
					out.print("        <Files ~ \"\\.(cgi|shtml)$\">\n"
							+ "            SSLOptions +StdEnvVars\n"
							+ "        </Files>\n");
				} else if(enableCgi) {
					out.print("        <Files ~ \"\\.cgi$\">\n"
							+ "            SSLOptions +StdEnvVars\n"
							+ "        </Files>\n");
				} else if(enableSsi) {
					out.print("        <Files ~ \"\\.shtml$\">\n"
							+ "            SSLOptions +StdEnvVars\n"
							+ "        </Files>\n");
				}
				out.print("        SSLEngine On\n"
						+ "    </IfModule>\n"
						+ "\n"
				);
			}
			if(bind.getRedirectToPrimaryHostname()) {
				out.print("    # Redirect requests that are not to either the IP address or the primary hostname to the primary hostname\n"
						+ "    RewriteEngine on\n"
						+ "    RewriteCond %{HTTP_HOST} !=").print(primaryHostname).print(" [NC]\n"
						+ "    RewriteCond %{HTTP_HOST} !=").print(ipAddress).print("\n"
						+ "    RewriteRule ^(.*)$ ").print(primaryHSU.getURLNoSlash()).print("$1 [L,R=permanent]\n"
						+ "    \n");
			}
			out.print("    Include conf/hosts/").print(siteInclude).print("\n"
					+ "\n"
					+ "</VirtualHost>\n");
		}
		return bout.toByteArray();
	}

	/**
	 * Reloads the configs for all provided <code>HttpdServer</code>s.
	 */
	public static void reloadConfigs(Set<HttpdServer> serversNeedingReloaded) throws IOException {
		for(HttpdServer hs : serversNeedingReloaded) reloadConfigs(hs);
	}

	private static final Object processControlLock = new Object();
	private static void reloadConfigs(HttpdServer hs) throws IOException {
		synchronized(processControlLock) {
			AOServDaemon.exec(
				"/etc/rc.d/init.d/httpd"+hs.getNumber(),
				"reload" // Should this be restart for SSL changes?
			);
		}
	}

	/**
	 * Calls all Apache instances with the provided command.
	 */
	private static void controlApache(String command) throws IOException, SQLException {
		synchronized(processControlLock) {
			for(HttpdServer hs : AOServDaemon.getThisAOServer().getHttpdServers()) {
				AOServDaemon.exec(
					"/etc/rc.d/init.d/httpd"+hs.getNumber(),
					command
				);
			}
		}
	}

	/**
	 * Restarts all Apache instances.
	 */
	public static void restartApache() throws IOException, SQLException {
		controlApache("restart");
	}

	/**
	 * Starts all Apache instances.
	 */
	public static void startApache() throws IOException, SQLException {
		controlApache("start");
	}

	/**
	 * Stops all Apache instances.
	 */
	public static void stopApache() throws IOException, SQLException {
		controlApache("stop");
	}

	/**
	 * Gets the shared library name for the given version of PHP.
	 */
	private static String getPhpLib(TechnologyVersion phpVersion) {
		String version=phpVersion.getVersion();
		if(version.equals("4") || version.startsWith("4.")) return "libphp4.so";
		if(version.equals("5") || version.startsWith("5.")) return "libphp5.so";
		throw new RuntimeException("Unsupported PHP version: "+version);
	}

	/**
	 * Gets the major (first number only) form of a PHP version.
	 */
	private static String getMajorPhpVersion(String version) {
		int pos = version.indexOf('.');
		return pos == -1 ? version : version.substring(0, pos);
	}

	/**
	 * Gets the minor (first two numbers only) form of a PHP version.
	 */
	private static String getMinorPhpVersion(String version) {
		int pos = version.indexOf('.');
		if(pos == -1) return version;
		pos = version.indexOf('.', pos+1);
		return pos == -1 ? version : version.substring(0, pos);
	}

	private static final UnixFile[] centOsAlwaysDelete = {
		new UnixFile("/etc/httpd/conf/httpd1.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd2.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd3.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd4.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd5.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd6.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd7.conf.old"),
		new UnixFile("/etc/httpd/conf/httpd8.conf.old"),
		new UnixFile("/etc/httpd/conf/workers1.properties.old"),
		new UnixFile("/etc/httpd/conf/workers2.properties.old"),
		new UnixFile("/etc/httpd/conf/workers3.properties.old"),
		new UnixFile("/etc/httpd/conf/workers4.properties.old"),
		new UnixFile("/etc/httpd/conf/workers5.properties.old"),
		new UnixFile("/etc/httpd/conf/workers6.properties.old"),
		new UnixFile("/etc/httpd/conf/workers7.properties.old"),
		new UnixFile("/etc/httpd/conf/workers8.properties.old"),
		new UnixFile("/etc/rc.d/init.d/httpd"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd1"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd2"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd3"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd4"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd5"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd6"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd7"),
		new UnixFile("/opt/aoserv-daemon/init.d/httpd8")
	};

	/**
	 * Fixes any filesystem stuff related to Apache.
	 */
	private static void fixFilesystem(List<File> deleteFileList) throws IOException, SQLException {
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		if(osConfig==HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
			// Make sure these files don't exist.  They may be due to upgrades or a
			// result of RPM installs.
			for(UnixFile uf : centOsAlwaysDelete) {
				if(uf.getStat().exists()) deleteFileList.add(uf.getFile());
			}
		}
	}

	/**
	 * Rebuilds /etc/rc.d/init.d/httpd* init scripts.
	 */
	private static void doRebuildInitScripts(AOServer thisAoServer, ByteArrayOutputStream bout, List<File> deleteFileList, Set<HttpdServer> serversNeedingReloaded) throws IOException, SQLException {
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		List<HttpdServer> hss = thisAoServer.getHttpdServers();
		Set<String> dontDeleteFilenames = new HashSet<>(hss.size()*4/3+1);
		for(HttpdServer hs : hss) {
			int num = hs.getNumber();
			bout.reset();
			try (ChainWriter out = new ChainWriter(bout)) {
				out.print("#!/bin/bash\n"
						+ "#\n"
						+ "# httpd").print(num).print("        Startup script for the Apache HTTP Server ").print(num).print("\n"
						+ "#\n"
						+ "# chkconfig: 345 85 15\n"
						+ "# description: Apache is a World Wide Web server.  It is used to serve \\\n"
						+ "#              HTML files and CGI.\n"
						+ "# processname: httpd").print(num).print("\n"
						+ "# config: /etc/httpd/conf/httpd").print(num).print(".conf\n"
						+ "# pidfile: /var/run/httpd").print(num).print(".pid\n"
						+ "\n");
				// mod_php requires MySQL and PostgreSQL in the path
				TechnologyVersion modPhpVersion = hs.getModPhpVersion();
				if(modPhpVersion!=null) {
					PackageManager.PackageName requiredPackage;
					String version = modPhpVersion.getVersion();
					String minorVersion = getMinorPhpVersion(version);
					switch (minorVersion) {
						case "4.4":
							requiredPackage = null;
							out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
							out.print(". /opt/postgresql-7.3-i686/setenv.sh\n");
							out.print('\n');
							break;
						case "5.2":
							requiredPackage = PackageManager.PackageName.PHP_5_2;
							out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
							out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
							out.print('\n');
							break;
						case "5.3":
							requiredPackage = PackageManager.PackageName.PHP_5_3;
							out.print(". /opt/mysql-5.1-i686/setenv.sh\n");
							out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
							out.print('\n');
							break;
						case "5.4":
							requiredPackage = PackageManager.PackageName.PHP_5_4;
							out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
							out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
							out.print('\n');
							break;
						case "5.5":
							requiredPackage = PackageManager.PackageName.PHP_5_5;
							out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
							out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
							out.print('\n');
							break;
						case "5.6":
							requiredPackage = PackageManager.PackageName.PHP_5_6;
							out.print(". /opt/mysql-5.7-i686/setenv.sh\n");
							out.print(". /opt/postgresql-9.4-i686/setenv.sh\n");
							out.print('\n');
							break;
						default:
							throw new SQLException("Unexpected version for mod_php: "+version);
					}

					// Make sure required RPM is installed
					if(requiredPackage != null) PackageManager.installPackage(requiredPackage);
				}
				out.print("NUM=").print(num).print("\n"
						+ ". /opt/aoserv-daemon/init.d/httpd\n");
			}
			String filename = "httpd"+num;
			dontDeleteFilenames.add(filename);
			if(
				DaemonFileUtils.writeIfNeeded(
					bout.toByteArray(),
					null,
					new UnixFile(INIT_DIRECTORY+"/"+filename),
					UnixFile.ROOT_UID,
					UnixFile.ROOT_GID,
					0700,
					uid_min,
					gid_min
				)
			) {
				// Make start at boot
				AOServDaemon.exec(
					"/sbin/chkconfig",
					"--add",
					filename
				);
				AOServDaemon.exec(
					"/sbin/chkconfig",
					filename,
					"on"
				);
				// Make reload
				serversNeedingReloaded.add(hs);
			}
		}
		for(String filename : new File(INIT_DIRECTORY).list()) {
			if(filename.startsWith("httpd")) {
				String suffix = filename.substring(5);
				try {
					// Parse to make sure is a httpd# filename
					int num = Integer.parseInt(suffix);
					if(!dontDeleteFilenames.contains(filename)) {
						// chkconfig off
						AOServDaemon.exec(
							"/sbin/chkconfig",
							filename,
							"off"
						);
						// stop
						String fullPath = INIT_DIRECTORY+"/"+filename;
						AOServDaemon.exec(
							fullPath,
							"stop"
						);
						deleteFileList.add(new File(fullPath));
					}
				} catch(NumberFormatException err) {
					LogFactory.getLogger(HttpdServerManager.class).log(Level.WARNING, null, err);
				}
			}
		}
	}
}
