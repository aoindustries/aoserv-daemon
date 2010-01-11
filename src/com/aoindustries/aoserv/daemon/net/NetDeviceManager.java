package com.aoindustries.aoserv.daemon.net;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.IndexedSet;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.NetDeviceID;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.client.validator.MacAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.AOPool;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.ErrorPrinter;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the building of IP address configs and files.
 */
final public class NetDeviceManager extends BuilderThread {
    
    public static final UnixFile
        netScriptDirectory=new UnixFile("/etc/sysconfig/network-scripts"),
        networkScript=new UnixFile("/etc/sysconfig/network"),
        networkScriptNew=new UnixFile("/etc/sysconfig/network.new")
    ;
    
    private static NetDeviceManager netDeviceManager;

    private static final boolean WARN_ONLY = false;

    private static final String[] ipv4netmask = {
        "0.0.0.0",
        "128.0.0.0",
        "192.0.0.0",
        "224.0.0.0",
        "240.0.0.0",
        "248.0.0.0",
        "252.0.0.0",
        "254.0.0.0",
        "255.0.0.0",
        "255.128.0.0",
        "255.192.0.0",
        "255.224.0.0",
        "255.240.0.0",
        "255.248.0.0",
        "255.252.0.0",
        "255.254.0.0",
        "255.255.0.0",
        "255.255.128.0",
        "255.255.192.0",
        "255.255.224.0",
        "255.255.240.0",
        "255.255.248.0",
        "255.255.252.0",
        "255.255.254.0",
        "255.255.255.0",
        "255.255.255.128",
        "255.255.255.192",
        "255.255.255.224",
        "255.255.255.240",
        "255.255.255.248",
        "255.255.255.252",
        "255.255.255.254",
        "255.255.255.255"
    };
    public static String getNetMask(IPAddress ip) {
        if(ip.getIpAddress().isIPv4()) {
            return ipv4netmask[ip.getNetMask()];
        } else {
            throw new RuntimeException("TODO: Netmask configuration for IPv6 not yet determined.");
        }
    }

    private NetDeviceManager() {
    }
    
    private static final Object rebuildLock=new Object();
    protected boolean doRebuild() {
        try {
            AOServer thisAOServer=AOServDaemon.getThisAOServer();

            int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
            if(
                osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
            ) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

            // Used on inner loop
            final Stat tempStat = new Stat();
            final ByteArrayOutputStream bout = new ByteArrayOutputStream();

            synchronized(rebuildLock) {
                Set<NetDeviceID> restartDeviceIDs=new HashSet<NetDeviceID>();

                IndexedSet<NetDevice> devices=thisAOServer.getServer().getNetDevices();
                for(NetDevice device : devices) {
                    NetDeviceID deviceId=device.getNetDeviceID();
                    if(
                        // Don't build loopback
                        !deviceId.isLoopback()
                        // Don't build bonded device
                        && !deviceId.getName().startsWith("bond")
                    ) {
                        // Build the new primary IP script to RAM first
                        bout.reset();
                        ChainWriter out = new ChainWriter(bout);
                        try {
                            if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                                out.print("#\n"
                                + "# Automatically generated by ").print(NetDeviceManager.class.getName()).print("\n"
                                + "#\n"
                                + "DEVICE=").print(deviceId).print('\n');
                                IPAddress primaryIP=device.getPrimaryIPAddress();
                                if(primaryIP.isDhcp()) {
                                    out.print("BOOTPROTO=dhcp\n"
                                            + "ONBOOT=yes\n"
                                            + "NEEDHOSTNAME=no\n");
                                } else {
                                    InetAddress network=device.getNetwork();
                                    if(network==null) throw new AssertionError("(net_devices.pkey="+device.getPkey()+").network may not be null");
                                    InetAddress broadcast=device.getBroadcast();
                                    if(broadcast==null) throw new AssertionError("(net_devices.pkey="+device.getPkey()+").broadcast may not be null");
                                    out.print("BOOTPROTO=static\n"
                                            + "IPADDR=").print(primaryIP.getIpAddress()).print("\n"
                                            + "NETMASK=").print(getNetMask(primaryIP)).print("\n"
                                            + "NETWORK=").print(network).print("\n"
                                            + "BROADCAST=").print(broadcast).print("\n"
                                            + "ONBOOT=yes\n");
                                }
                                MacAddress macAddr=device.getMacAddress();
                                if(macAddr==null) throw new AssertionError("(net_devices.pkey="+device.getPkey()+").macaddr may not be null for CentOS 5");
                                out.print("HWADDR=").print(macAddr).print("\n"
                                        + "IPV6ADDR=\n"
                                        + "IPV6PREFIX=\n");
                            } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                        } finally {
                            out.close();
                        }
                        byte[] newBytes = bout.toByteArray();

                        UnixFile existing=new UnixFile(netScriptDirectory, "ifcfg-"+deviceId, false);
                        if(!existing.getStat(tempStat).exists() || !existing.contentEquals(newBytes)) {
                            if(WARN_ONLY) {
                                synchronized(System.err) {
                                    System.err.println("--------------------------------------------------------------------------------");
                                    System.err.println(existing.getPath()+" should contain the following:");
                                    System.err.write(newBytes);
                                    System.err.println("--------------------------------------------------------------------------------");
                                }
                            } else {
                                UnixFile newFile=new UnixFile(netScriptDirectory, "ifcfg-"+deviceId+".new", false);
                                FileOutputStream newOut=newFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                                try {
                                    newOut.write(newBytes);
                                } finally {
                                    newOut.close();
                                }
                                newFile.renameTo(existing);
                            }
                            if(!restartDeviceIDs.contains(deviceId)) restartDeviceIDs.add(deviceId);
                        } else {
                            if(WARN_ONLY) {
                                synchronized(System.err) {
                                    System.err.println("--------------------------------------------------------------------------------");
                                    System.err.println(existing.getPath()+" is OK");
                                    System.err.println("--------------------------------------------------------------------------------");
                                }
                            }
                        }

                        // Rebuild the alias configs for this server, including all child failed-over servers IPs
                        Set<String> cfgFilenames = new HashSet<String>();
                        String aliasBeginning="ifcfg-"+deviceId+":";
                        int num=0;
                        List<AOServer> children=new ArrayList<AOServer>(thisAOServer.getNestedAoServers());
                        Collections.sort(children);
                        for(int d=-1;d<children.size();d++) {
                            AOServer aoServer=d==-1?thisAOServer:children.get(d).getServer().getAoServer();
                            NetDevice curDevice=d==-1?device:aoServer.getServer().getNetDevice(device.getNetDeviceID().getName());
                            if(curDevice!=null) {
                                for(IPAddress ip : curDevice.getIpAddresses()) {
                                    if(d!=-1 || ip.isAlias()) {
                                        if(ip.isDhcp()) throw new AssertionError("DHCP IP aliases are not allowed for IPAddress #"+ip.getKey());

                                        String filename=aliasBeginning+(num++);
                                        cfgFilenames.add(filename);
                                        // Write to RAM first
                                        bout.reset();
                                        out = new ChainWriter(bout);
                                        try {
                                            out.print("#\n"
                                                    + "# Automatically generated by ").print(NetDeviceManager.class.getName()).print("\n"
                                                    + "#\n");
                                            if(
                                                osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                                            ) {
                                                out.print("IPADDR=").print(ip.getIpAddress()).print("\n"
                                                        + "NETMASK=").print(getNetMask(ip)).print("\n");
                                            } else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                                        } finally {
                                            out.close();
                                        }
                                        newBytes = bout.toByteArray();

                                        UnixFile cfgUF=new UnixFile(netScriptDirectory, filename, false);
                                        if(!cfgUF.getStat(tempStat).exists() || !cfgUF.contentEquals(newBytes)) {
                                            if(WARN_ONLY) {
                                                synchronized(System.err) {
                                                    System.err.println();
                                                    System.err.println("--------------------------------------------------------------------------------");
                                                    System.err.println(cfgUF.getPath()+" should contain the following:");
                                                    System.err.write(newBytes);
                                                    System.err.println("--------------------------------------------------------------------------------");
                                                }
                                            } else {
                                                UnixFile newCfgUF=new UnixFile(netScriptDirectory, filename+".new", false);
                                                FileOutputStream newOut=newCfgUF.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true);
                                                try {
                                                    newOut.write(newBytes);
                                                } finally {
                                                    newOut.close();
                                                }
                                                newCfgUF.renameTo(cfgUF);
                                            }
                                            if(!restartDeviceIDs.contains(deviceId)) restartDeviceIDs.add(deviceId);
                                        } else {
                                            if(WARN_ONLY) {
                                                synchronized(System.err) {
                                                    System.err.println();
                                                    System.err.println("--------------------------------------------------------------------------------");
                                                    System.err.println(cfgUF.getPath()+" is OK");
                                                    System.err.println("--------------------------------------------------------------------------------");
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Remove the extra alias configs
                        for(String filename : netScriptDirectory.list()) {
                            if(
                                !cfgFilenames.contains(filename)
                                && filename.startsWith(aliasBeginning)
                            ) {
                                UnixFile extra=new UnixFile(netScriptDirectory, filename, false);
                                if(WARN_ONLY) {
                                    synchronized(System.err) {
                                        System.err.println();
                                        System.err.println("--------------------------------------------------------------------------------");
                                        System.err.println(extra.getPath()+" should be deleted");
                                        System.err.println("--------------------------------------------------------------------------------");
                                    }
                                } else {
                                    extra.delete();
                                }
                                if(!restartDeviceIDs.contains(deviceId)) restartDeviceIDs.add(deviceId);
                            }
                        }
                    }
                }

                // Build to RAM first
                bout.reset();
                ChainWriter out = new ChainWriter(bout);
                try {
                    out.print("#\n"
                            + "# Automatically generated by ").print(NetDeviceManager.class.getName()).print("\n"
                            + "#\n"
                            + "HOSTNAME=").print(thisAOServer.getHostname()).print("\n"
                            + "NETWORKING=yes\n");
                    // There should no more than one network device with a gateway specified
                    List<NetDevice> gatewayDevices=new ArrayList<NetDevice>();
                    for(NetDevice device : devices) {
                        NetDeviceID deviceId=device.getNetDeviceID();
                        if(!deviceId.isLoopback()) {
                            if(device.getGateway()!=null) gatewayDevices.add(device);
                        }
                    }
                    if(gatewayDevices.size()>0) {
                        if(gatewayDevices.size()>1) throw new AssertionError("More than one gateway device found: "+gatewayDevices.size());
                        NetDevice gateway=gatewayDevices.get(0);
                        out.print("GATEWAY=").print(gateway.getGateway()).print('\n');
                    }
                    if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
                        out.print("NETWORKING_IPV6=yes\n");
                    }
                } finally {
                    out.close();
                }
                byte[] newBytes = bout.toByteArray();

                if(!networkScript.getStat(tempStat).exists() || !networkScript.contentEquals(newBytes)) {
                    if(WARN_ONLY) {
                        synchronized(System.err) {
                            System.err.println();
                            System.err.println("--------------------------------------------------------------------------------");
                            System.err.println(networkScript.getPath()+" should contain the following:");
                            System.err.write(newBytes);
                            System.err.println("--------------------------------------------------------------------------------");
                        }
                    } else {
                        FileOutputStream newOut=networkScriptNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0755, true);
                        try {
                            newOut.write(newBytes);
                        } finally {
                            newOut.close();
                        }
                        networkScriptNew.renameTo(networkScript);
                    }
                    // Restart all devices in this scenario
                    for(NetDevice device : devices) {
                        NetDeviceID deviceId=device.getNetDeviceID();
                        if(
                            // Don't build loopback
                            !deviceId.isLoopback()
                            // Don't build bonded device
                            && !deviceId.getName().startsWith("bond")
                            // Don't add twice
                            && !restartDeviceIDs.contains(deviceId)
                        ) {
                            restartDeviceIDs.add(deviceId);
                        }
                    }
                } else {
                    if(WARN_ONLY) {
                        synchronized(System.err) {
                            System.err.println();
                            System.err.println("--------------------------------------------------------------------------------");
                            System.err.println(networkScript.getPath()+" is OK");
                            System.err.println("--------------------------------------------------------------------------------");
                        }
                    }
                }

                // Do not restart devices when is nested or in failover, the parent server does this
                if(!AOServDaemonConfiguration.isNested() && thisAOServer.getFailoverServer()==null) {
                    try {
                        for(NetDeviceID deviceId : restartDeviceIDs) {
                            String deviceName = deviceId.getName();
                            if(WARN_ONLY) {
                                synchronized(System.err) {
                                    System.err.println();
                                    System.err.println("--------------------------------------------------------------------------------");
                                    System.err.println(deviceName+" should be restarted");
                                    System.err.println("--------------------------------------------------------------------------------");
                                }
                            } else {
                                // Restart the networking
                                Process P=Runtime.getRuntime().exec(new String[] {"/sbin/ifdown", deviceName});
                                int downCode;
                                try {
                                    P.getOutputStream().close();
                                    downCode=P.waitFor();
                                } catch(InterruptedException err) {
                                    downCode=-1;
                                }
                                P=Runtime.getRuntime().exec(new String[] {"/sbin/ifup", deviceName});
                                int upCode;
                                try {
                                    P.getOutputStream().close();
                                    upCode=P.waitFor();
                                } catch(InterruptedException err) {
                                    upCode=-1;
                                }
                                if(downCode!=0) throw new IOException("Error calling /sbin/ifdown "+deviceName+", retCode="+(downCode==-1?"Interrupted":Integer.toString(downCode)));
                                if(upCode!=0) throw new IOException("Error calling /sbin/ifup "+deviceName+", retCode="+(upCode==-1?"Interrupted":Integer.toString(upCode)));
                            }
                        }
                    } finally {
                        if(restartDeviceIDs.size()>0) {
                            String command;
                            if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) command = "/etc/opt/aoserv-daemon/route";
                            else throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
                            if(WARN_ONLY) {
                                synchronized(System.err) {
                                    System.err.println();
                                    System.err.println("--------------------------------------------------------------------------------");
                                    System.err.println(command+" start should be called");
                                    System.err.println("--------------------------------------------------------------------------------");
                                }
                            } else {
                                Process P=Runtime.getRuntime().exec(new String[] {command, "start"});
                                int routeCode;
                                try {
                                    P.getOutputStream().close();
                                    routeCode=P.waitFor();
                                } catch(InterruptedException err) {
                                    routeCode=-1;
                                }
                                if(routeCode!=0) throw new IOException("Error calling "+command+" start, retCode="+(routeCode==-1?"Interrupted":Integer.toString(routeCode)));
                            }
                        }
                    }
                }
            }
            return true;
        } catch(ThreadDeath TD) {
            throw TD;
        } catch(Throwable T) {
            LogFactory.getLogger(NetDeviceManager.class).log(Level.SEVERE, null, T);
            return false;
        }
    }
    
    public static void start() throws IOException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(NetDeviceManager.class)
                && netDeviceManager==null
            ) {
                System.out.print("Starting NetDeviceManager: ");
                AOServConnector<?,?> conn=AOServDaemon.getConnector();
                netDeviceManager=new NetDeviceManager();
                conn.getIpAddresses().getTable().addTableListener(netDeviceManager, 0);
                conn.getNetDevices().getTable().addTableListener(netDeviceManager, 0);
                System.out.println("Done");
            }
        }
    }
    
    public String getProcessTimerDescription() {
        return "Rebuild Net Devices";
    }

    public static String getNetDeviceBondingReport(NetDevice netDevice) throws IOException {
        File procFile;
        int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            osv==OperatingSystemVersion.CENTOS_5DOM0_I686
            || osv==OperatingSystemVersion.CENTOS_5DOM0_X86_64
        ) {
            // Xen adds a "p" to the name
            procFile = new File("/proc/net/bonding/p"+netDevice.getNetDeviceID().getName());
        } else {
            procFile = new File("/proc/net/bonding/"+netDevice.getNetDeviceID().getName());
        }
        String report;
        if(procFile.exists()) {
            StringBuilder SB=new StringBuilder();
            InputStream in=new BufferedInputStream(new FileInputStream(procFile));
            try {
                int ch;
                while((ch=in.read())!=-1) SB.append((char)ch);
            } finally {
                in.close();
            }
            report = SB.toString();
        } else report="";
        return report;
    }

    /**
     * Reads a file, trims the whitespace, and parses it as a long.
     */
    private static long readLongFile(File file, StringBuilder tempSB) throws IOException {
        tempSB.setLength(0);
        InputStream in=new BufferedInputStream(new FileInputStream(file));
        try {
            int ch;
            while((ch=in.read())!=-1) tempSB.append((char)ch);
        } finally {
            in.close();
        }
        return Long.parseLong(tempSB.toString().trim());
    }

    /**
     * 32-bit Linux systems have counters that are uint and wrap at 2^32.  When
     * combined with gigabit, they wrap in a little over 30 seconds at full throughput.
     * The monitoring only checks the counters every 5 minutes and thus fails or is
     * incorrect when the average bitrate exceeds about 100 Mbps.
     * The solution is to use a background thread to check every 5 seconds to detect
     * this wrap and increment our own counter.  If the amount of increase is too much
     * for gigabit, then consider it a lower-level counter reset and reset our counter
     * to match the current value.
     */
    private static final Object _netDeviceStatisticsLock = new Object();
    private static Thread _netDeviceStatisticsThread;
    private static Map<NetDevice,Long> _lastTime = new HashMap<NetDevice,Long>();
    private static Map<NetDevice,Long> _lastTxBytes = new HashMap<NetDevice,Long>();
    private static Map<NetDevice,Long> _lastRxBytes = new HashMap<NetDevice,Long>();
    private static Map<NetDevice,Long> _lastTxPackets = new HashMap<NetDevice,Long>();
    private static Map<NetDevice,Long> _lastRxPackets = new HashMap<NetDevice,Long>();
    private static Map<NetDevice,Long> _totalTxBytes = new HashMap<NetDevice,Long>();
    private static Map<NetDevice,Long> _totalRxBytes = new HashMap<NetDevice,Long>();
    private static Map<NetDevice,Long> _totalTxPackets = new HashMap<NetDevice,Long>();
    private static Map<NetDevice,Long> _totalRxPackets = new HashMap<NetDevice,Long>();

    private static final long MAX_GIGABIT_BIT_RATE = 2000000000L; // Allow twice gigabit speed before assuming counter reset
    private static final long MAX_GIGABIT_PACKET_RATE = MAX_GIGABIT_BIT_RATE / (64 * 8); // Smallest packet is 64 octets

    /**
     * Updates all of the counts to the current values.  This is called by
     * getNetDeviceStatisticsReport to get up-to-date values, and also
     * called by the 32-bit wraparound detection thread to catch wraparound.
     * 
     * All access to this method already synchronized on netDeviceStatisticsLock
     */
    private static void updateCounts(NetDevice netDevice, StringBuilder tempSB) throws IOException {
        // Determine the statsDirectory
        File statsDirectory;
        int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            (
                osv==OperatingSystemVersion.CENTOS_5DOM0_I686
                || osv==OperatingSystemVersion.CENTOS_5DOM0_X86_64
            ) && !netDevice.getNetDeviceID().getName().equals(NetDeviceID.LO)
        ) {
            // Xen adds a "p" to the name or any device (except lo or non-xen devices)
            statsDirectory = new File("/sys/class/net/p"+netDevice.getNetDeviceID().getName()+"/statistics");
            // If doesn't exist, it is not a Xen-managed device, use its unaltered name
            if(!statsDirectory.exists()) statsDirectory = new File("/sys/class/net/"+netDevice.getNetDeviceID().getName()+"/statistics");
        } else {
            statsDirectory = new File("/sys/class/net/"+netDevice.getNetDeviceID().getName()+"/statistics");
        }
        
        // Determine if on 64-bit system
        boolean is64;
        String osArch = System.getProperty("os.arch");
        if("amd64".equals(osArch)) is64 = true;
        else if("i386".equals(osArch)) is64 = false;
        else if("i586".equals(osArch)) is64 = false;
        else throw new IOException("Unexpected value for os.arch: "+osArch);

        // Read the current raw values
        final long currentTime;
        final long currentTxBytes;
        final long currentRxBytes;
        final long currentTxPackets;
        final long currentRxPackets;
        try {
            currentTime = System.currentTimeMillis();
            currentTxBytes = readLongFile(new File(statsDirectory, "tx_bytes"), tempSB);
            currentRxBytes = readLongFile(new File(statsDirectory, "rx_bytes"), tempSB);
            currentTxPackets = readLongFile(new File(statsDirectory, "tx_packets"), tempSB);
            currentRxPackets = readLongFile(new File(statsDirectory, "rx_packets"), tempSB);
        } catch(IOException err) {
            // If there is any IO exception, it may indicate the device has been completely shutdown
            // and will reset its counters in the process
            _lastTime.remove(netDevice);
            _lastTxBytes.remove(netDevice);
            _lastRxBytes.remove(netDevice);
            _lastTxPackets.remove(netDevice);
            _lastRxPackets.remove(netDevice);
            _totalTxBytes.remove(netDevice);
            _totalRxBytes.remove(netDevice);
            _totalTxPackets.remove(netDevice);
            _totalRxPackets.remove(netDevice);
            throw err;
        }

        // If on a 64-bit system, just copy the data directly
        if(is64) {
            _lastTime.put(netDevice, currentTime);
            _totalTxBytes.put(netDevice, currentTxBytes);
            _totalRxBytes.put(netDevice, currentRxBytes);
            _totalTxPackets.put(netDevice, currentTxPackets);
            _totalRxPackets.put(netDevice, currentRxPackets);
        } else {
            Long lastTime = _lastTime.get(netDevice);
            Long lastTxBytes = _lastTxBytes.get(netDevice);
            Long lastRxBytes = _lastRxBytes.get(netDevice);
            Long lastTxPackets = _lastTxPackets.get(netDevice);
            Long lastRxPackets = _lastRxPackets.get(netDevice);
            if(lastTime==null || lastTxBytes==null || lastRxBytes==null || lastTxPackets==null || lastRxPackets==null) {
                // If no previous value, initialize remove the totals to indicate device reset
                _lastTime.put(netDevice, currentTime);
                _lastTxBytes.put(netDevice, currentTxBytes);
                _lastRxBytes.put(netDevice, currentRxBytes);
                _lastTxPackets.put(netDevice, currentTxPackets);
                _lastRxPackets.put(netDevice, currentRxPackets);
                _totalTxBytes.remove(netDevice);
                _totalRxBytes.remove(netDevice);
                _totalTxPackets.remove(netDevice);
                _totalRxPackets.remove(netDevice);
            } else {
                // Look for wraparound on txBytes
                final Long totalTxBytesL = _totalTxBytes.get(netDevice);
                final long oldTotalTxBytes = totalTxBytesL==null ? 0 : totalTxBytesL;
                long newTotalTxBytes;
                if(currentTxBytes>=lastTxBytes) {
                    newTotalTxBytes = oldTotalTxBytes + currentTxBytes - lastTxBytes;
                } else {
                    //System.err.println("NetDeviceManager: tx_bytes wraparound detected on "+netDevice);
                    newTotalTxBytes = oldTotalTxBytes + currentTxBytes + 0x100000000L - lastTxBytes;
                }

                // Look for wraparound on rxBytes
                final Long totalRxBytesL = _totalRxBytes.get(netDevice);
                final long oldTotalRxBytes = totalRxBytesL==null ? 0 : totalRxBytesL;
                long newTotalRxBytes;
                if(currentRxBytes>=lastRxBytes) {
                    newTotalRxBytes = oldTotalRxBytes + currentRxBytes - lastRxBytes;
                } else {
                    //System.err.println("NetDeviceManager: rx_bytes wraparound detected on "+netDevice);
                    newTotalRxBytes = oldTotalRxBytes + currentRxBytes + 0x100000000L - lastRxBytes;
                }

                // Look for wraparound on txPackets
                final Long totalTxPacketsL = _totalTxPackets.get(netDevice);
                final long oldTotalTxPackets = totalTxPacketsL==null ? 0 : totalTxPacketsL;
                long newTotalTxPackets;
                if(currentTxPackets>=lastTxPackets) {
                    newTotalTxPackets = oldTotalTxPackets + currentTxPackets - lastTxPackets;
                } else {
                    //System.err.println("NetDeviceManager: tx_packets wraparound detected on "+netDevice);
                    newTotalTxPackets = oldTotalTxPackets + currentTxPackets + 0x100000000L - lastTxPackets;
                }

                // Look for wraparound on rxPackets
                final Long totalRxPacketsL = _totalRxPackets.get(netDevice);
                final long oldTotalRxPackets = totalRxPacketsL==null ? 0 : totalRxPacketsL;
                long newTotalRxPackets;
                if(currentRxPackets>=lastRxPackets) {
                    newTotalRxPackets = oldTotalRxPackets + currentRxPackets - lastRxPackets;
                } else {
                    //System.err.println("NetDeviceManager: rx_packets wraparound detected on "+netDevice);
                    newTotalRxPackets = oldTotalRxPackets + currentRxPackets + 0x100000000L - lastRxPackets;
                }

                // Look for any indication of device reset
                long timeDiff = currentTime - lastTime;
                if(timeDiff<1000) timeDiff = 1000; // Don't let math be thrown off by a very small divisor
                long currentTxBitRate = (newTotalTxBytes - oldTotalTxBytes) * 8 * 1000 / timeDiff;
                long currentRxBitRate = (newTotalRxBytes - oldTotalRxBytes) * 8 * 1000 / timeDiff;
                long currentTxPacketRate = (newTotalTxPackets - oldTotalTxPackets) * 8 * 1000 / timeDiff;
                long currentRxPacketRate = (newTotalRxPackets - oldTotalRxPackets) * 8 * 1000 / timeDiff;
                if(
                    currentTxBitRate>MAX_GIGABIT_BIT_RATE
                    || currentRxBitRate>MAX_GIGABIT_BIT_RATE
                    || currentTxPacketRate>MAX_GIGABIT_PACKET_RATE
                    || currentRxPacketRate>MAX_GIGABIT_PACKET_RATE
                ) {
                    // Counter reset, remove totals to indicate reset
                    //System.err.println("NetDeviceManager: Device reset detected on "+netDevice);
                    _totalTxBytes.remove(netDevice);
                    _totalRxBytes.remove(netDevice);
                    _totalTxPackets.remove(netDevice);
                    _totalRxPackets.remove(netDevice);
                } else {
                    _totalTxBytes.put(netDevice, newTotalTxBytes);
                    _totalRxBytes.put(netDevice, newTotalRxBytes);
                    _totalTxPackets.put(netDevice, newTotalTxPackets);
                    _totalRxPackets.put(netDevice, newTotalRxPackets);
                }

                // Store result and counts for next iteration
                _lastTime.put(netDevice, currentTime);
                _lastTxBytes.put(netDevice, currentTxBytes);
                _lastRxBytes.put(netDevice, currentRxBytes);
                _lastTxPackets.put(netDevice, currentTxPackets);
                _lastRxPackets.put(netDevice, currentRxPackets);
            }
        }

        // On 32-bit systems, start the thread to automatically update this every 5 seconds
        if(!is64 && _netDeviceStatisticsThread==null) {
            _netDeviceStatisticsThread = new Thread("netDeviceStatisticsThread") {
                @Override
                public void run() {
                    // Reuse these two objects to reduce heap allocation
                    final List<NetDevice> netDevices = new ArrayList<NetDevice>();
                    final StringBuilder tempSB = new StringBuilder();
                    while(true) {
                        try {
                            netDevices.clear();
                            synchronized(_netDeviceStatisticsLock) {
                                netDevices.addAll(_lastTime.keySet());
                                for(NetDevice netDevice : netDevices) {
                                    try {
                                        updateCounts(netDevice, tempSB);
                                    } catch(Exception err) {
                                        LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, err);
                                    }
                                }
                            }
                        } catch(ThreadDeath TD) {
                            throw TD;
                        } catch(Throwable T) {
                            LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, T);
                        }
                        try {
                            Thread.sleep(5000);
                        } catch(InterruptedException err) {
                            LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err);
                        }
                    }
                }
            };
            _netDeviceStatisticsThread.start();
        }
    }

    public static String getNetDeviceStatisticsReport(NetDevice netDevice) throws IOException {
        StringBuilder tempSB = new StringBuilder((20+1)*5); // ( max length of long = 20 + newline ) * 5 lines
        synchronized(_netDeviceStatisticsLock) {
            updateCounts(netDevice, tempSB);
            Long totalTxBytes = _totalTxBytes.get(netDevice);
            Long totalRxBytes = _totalRxBytes.get(netDevice);
            Long totalTxPackets = _totalTxPackets.get(netDevice);
            Long totalRxPackets = _totalRxPackets.get(netDevice);
            tempSB.setLength(0);
            tempSB
                // Add the current system time so the bit rate calculations are unaffected by network latency
                .append(_lastTime.get(netDevice)).append('\n')
                // Add the counts
                .append(totalTxBytes==null ? -1 : totalTxBytes.longValue()).append('\n')
                .append(totalRxBytes==null ? -1 : totalRxBytes.longValue()).append('\n')
                .append(totalTxPackets==null ? -1 : totalTxPackets.longValue()).append('\n')
                .append(totalRxPackets==null ? -1 : totalRxPackets.longValue()).append('\n')
            ;
        }
        return tempSB.toString();
    }

    private final static List<Integer> privilegedPorts = new ArrayList<Integer>();

    /**
     * Gets the next privileged source port in the range 1<=port<=1023.  Will never return
     * any port referenced in the NetBinds for this server.  Will return all ports before
     * cycling back through the ports.  The ports are returned in a random order.
     * The returned port may be in use, and the resulting exception must be caught and
     * the next port tried in that case.
     */
    public static int getNextPrivilegedPort() throws IOException {
        synchronized(privilegedPorts) {
            if(privilegedPorts.isEmpty()) {
                Set<NetBind> netBinds = AOServDaemon.getThisAOServer().getServer().getNetBinds();
                Set<Integer> netBindPorts = new HashSet<Integer>(netBinds.size()*4/3+1);
                for(NetBind netBind : netBinds) netBindPorts.add(netBind.getPort().getPort());
                for(Integer port=1; port<=1023; port++) {
                    if(!netBindPorts.contains(port)) privilegedPorts.add(port);
                }
            }
            int size = privilegedPorts.size();
            if(size==0) throw new AssertionError("privilegedPorts is empty");
            if(size==1) return privilegedPorts.remove(0);
            return privilegedPorts.remove(AOServDaemon.getRandom().nextInt(size));
        }
    }

    public static String checkSmtpBlacklist(String sourceIp, String connectIp) throws IOException {
        Charset charset = Charset.forName("US-ASCII");
        // Try 100 times maximum
        final int numAttempts = 100;
        for(int attempt=1; attempt<=numAttempts; attempt++) {
            int sourcePort = getNextPrivilegedPort();
            try {
                Socket socket=new Socket();
                try {
                    socket.setKeepAlive(true);
                    socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
                    //socket.setTcpNoDelay(true);
                    socket.setSoTimeout(60000);
                    socket.bind(new InetSocketAddress(sourceIp, sourcePort));
                    socket.connect(new InetSocketAddress(connectIp, 25), 60*1000);

                    PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), charset));
                    try {
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), charset));
                        try {
                            // Status line
                            String line = in.readLine();
                            if(line==null) throw new EOFException("End of file reading status");
                            out.println("QUIT");
                            out.flush();
                            return line;
                        } finally {
                            in.close();
                        }
                    } finally {
                        out.close();
                    }
                } finally {
                    socket.close();
                }
            } catch(IOException err) {
                // TODO: Catch specific exception for local port in use
                ErrorPrinter.printStackTraces(err);
                throw err;
            }
        }
        throw new IOException("Unable to find available privileged port after "+numAttempts+" attempts");
    }
}
