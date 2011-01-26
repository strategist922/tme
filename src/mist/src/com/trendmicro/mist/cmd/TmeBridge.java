package com.trendmicro.mist.cmd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.File;
import java.io.PrintWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.net.ServerSocket;
import java.net.Socket;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.PropertyConfigurator;

import org.nocrala.tools.texttablefmt.Table;
import org.nocrala.tools.texttablefmt.CellStyle;
import org.nocrala.tools.texttablefmt.CellStyle.HorizontalAlign;

import com.google.protobuf.TextFormat;
import com.trendmicro.codi.CODIException;
import com.trendmicro.codi.ZKSessionManager;
import com.trendmicro.codi.ZNode;
import com.trendmicro.codi.lock.ZLock;
import com.trendmicro.codi.lock.Lock.LockType;
import com.trendmicro.mist.proto.BridgeTalk;
import com.trendmicro.mist.proto.GateTalk;
import com.trendmicro.mist.cmd.MistForwarder;
import com.trendmicro.mist.util.BrokerAddress;
import com.trendmicro.mist.util.ConnectionList;
import com.trendmicro.mist.util.Credential;
import com.trendmicro.mist.util.Exchange;
import com.trendmicro.mist.util.Packet;
import com.trendmicro.spn.common.util.Utils;
import com.trendmicro.mist.mfr.BrokerFarm;
import com.trendmicro.mist.console.CommandExecutable;
import com.trendmicro.mist.console.Console;
import com.trendmicro.mist.console.JNode;
import com.trendmicro.mist.console.StronglyConnectedGraph;
import com.trendmicro.mist.Daemon;
import com.trendmicro.mist.MistException;
import com.trendmicro.mist.ThreadInvoker;

public class TmeBridge implements Runnable {
    private StringWriter responseWriter;
    private static TmeBridge myApp;
    private static Log logger = LogFactory.getLog(TmeBridge.class);
    private ServerSocket server;
    private HashMap<String, CommandExecutable> commandTable = new HashMap<String, CommandExecutable>();
    private String BRIDGE_PATH = "/tme2/bridge/" + Daemon.propMIST.getProperty("bridge.name");
    private String ZK_SERVER = Daemon.propMIST.getProperty("mistd.zookeeper");
    private int ZK_TIMEOUT = Integer.valueOf(Daemon.propMIST.getProperty("mistd.zookeeper.timeout"));
    private boolean isMaster = false;
    private int retVal = 0;
    private ZLock bridgeLock = null;
    private ZNode masterChild = null;

    private ArrayList<ForwarderEntity> forwarderPool = new ArrayList<ForwarderEntity>();
    private ArrayList<BridgeTalk.BrokerInfo> brokerPool = new ArrayList<BridgeTalk.BrokerInfo>();
    private HashMap<String, JNode<String>> nodePool = new HashMap<String, JNode<String>>();

    private ThreadInvoker.OutputListener outputListener = new ThreadInvoker.OutputListener() {
        public void receiveOutput(String name, String msg) {
            logger.info(String.format("%s: %s", name, msg));
        }
    };

    private ThreadInvoker.OutputListener errorListener = new ThreadInvoker.OutputListener() {
        public void receiveOutput(String name, String msg) {
            logger.info(String.format("%s: %s", name, msg));
        }
    };

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                myApp.shutdown();
            }
        });
        logger.info("tme-bridge started");
    }

    private void shutdown() {
        for(ForwarderEntity fwd : forwarderPool)
            fwd.getForwarder().manualShutdown();
        saveConfig();

        if(masterChild != null) {
            try {
                masterChild.delete();
            }
            catch(CODIException e) {
            }
        }

        if(bridgeLock != null) {
            try {
                bridgeLock.release();
            }
            catch(CODIException e) {
            }
        }
        logger.info("tme-bridge shutdown");
    }

    private void setupEnvironment() {
        String namePidfile = "/var/run/tme/tme-bridge.pid";
        String nameLogfile = "/var/run/tme/tme-bridge.log";
        String pid = Utils.getCurrentPid();

        try {
            BufferedWriter pidfile = new BufferedWriter(new FileWriter(namePidfile));
            pidfile.write(pid);
            pidfile.newLine();
            pidfile.close();
            new File(namePidfile).deleteOnExit();

            Properties prop = new Properties();
            prop.load(new FileInputStream(System.getProperty("mistd.log.log4j", Daemon.nameLog4jConfig)));
            prop.setProperty("log4j.rootLogger", "OFF");
            prop.setProperty("log4j.logger.org.apache.zookeeper", "WARN, R");
            prop.setProperty("log4j.logger.com.trendmicro.mist", "INFO, R");
            prop.setProperty("log4j.appender.R.File", nameLogfile);
            PropertyConfigurator.configure(prop);
        }
        catch(IOException e) {
            logger.error(e.getMessage());
        }
    }

    private void clearResponseBuffer() {
        responseWriter = new StringWriter();
    }

    private void outputResponse(String message) {
        responseWriter.write(message + "\n");
    }

    private String getResponseBuffer() {
        return responseWriter.toString();
    }

    private int generateBrokerID() {
        HashMap<Integer, Boolean> used = new HashMap<Integer, Boolean>();
        int uid = 0;
        int max_id = -1;
        for(BridgeTalk.BrokerInfo b: brokerPool) {
            used.put(b.getId(), true);
            if(b.getId() > max_id)
                max_id = b.getId();
        }
        if(max_id != -1) {
            HashMap<Integer, Boolean> space = new HashMap<Integer, Boolean>();
            int i;
            for(i = 0; i <= max_id; i++)
                space.put(i, true);
            for(i = 0; i <= max_id; i++) {
                if(used.containsKey(i))
                    space.remove(i);
            }
            if(space.size() == 0)
                uid = max_id + 1;
            else {
                for(Integer id: space.keySet()) {
                    uid = id;
                    break;
                }
            }
        }
        return uid;
    }

    private int generateForwarderID() {
        HashMap<Integer, Boolean> used = new HashMap<Integer, Boolean>();
        int uid = 0;
        int max_id = -1;
        for(ForwarderEntity f: forwarderPool) {
            used.put(f.getForwarderInfo().getId(), true);
            if(f.getForwarderInfo().getId() > max_id)
                max_id = f.getForwarderInfo().getId();
        }
        if(max_id != -1) {
            HashMap<Integer, Boolean> space = new HashMap<Integer, Boolean>();
            int i;
            for(i = 0; i <= max_id; i++)
                space.put(i, true);
            for(i = 0; i <= max_id; i++) {
                if(used.containsKey(i))
                    space.remove(i);
            }
            if(space.size() == 0)
                uid = max_id + 1;
            else {
                for(Integer id: space.keySet()) {
                    uid = id;
                    break;
                }
            }
        }
        return uid;
    }

    private int findBrokerIndex(int broker_id) {
        int i;
        for(i = 0; i < brokerPool.size(); i++) {
            if(brokerPool.get(i).getId() == broker_id)
                return i;
        }
        return -1;
    }

    private int findForwarderIndex(int forwarder_id) {
        int i;
        for(i = 0; i < forwarderPool.size(); i++) {
            if(forwarderPool.get(i).getForwarderInfo().getId() == forwarder_id)
                return i;
        }
        return -1;
    }

    private void loadConfig() {
        BridgeTalk.BridgeConfig.Builder builder = BridgeTalk.BridgeConfig.newBuilder();
        try {
            TextFormat.merge(new String(new ZNode(BRIDGE_PATH).getContent()), builder);

            brokerPool.clear();
            forwarderPool.clear();
            BridgeTalk.BridgeConfig config = builder.build();
            int i;
            for(i = 0; i < config.getBrokersCount(); i++)
                addBroker(config.getBrokers(i));
            for(i = 0; i < config.getForwardersCount(); i++)
                addForwarder(config.getForwarders(i));

            logger.info(String.format("load %d broker(s), %d forwarder(s)", config.getBrokersCount(), config.getForwardersCount()));
        }
        catch(Exception e) {
            logger.error(e.getMessage());
        }
    }

    private void saveConfig() {
        BridgeTalk.BridgeConfig.Builder builder = BridgeTalk.BridgeConfig.newBuilder();
        int i;
        for(i = 0; i < brokerPool.size(); i++)
            builder.addBrokers(brokerPool.get(i));
        for(i = 0; i < forwarderPool.size(); i++)
            builder.addForwarders(forwarderPool.get(i).getForwarderInfo());
        BridgeTalk.BridgeConfig config = builder.build();

        try {
            new ZNode(BRIDGE_PATH).setContent(config.toString().getBytes());
            logger.info(String.format("saved %d broker(s), %d forwarder(s)", brokerPool.size(), forwarderPool.size()));
        }
        catch(Exception e) {
            logger.warn(String.format("fail to save: %s", e.getMessage()));
        }
    }

    public class ForwarderEntity {
        private BridgeTalk.ForwarderInfo fwdInfo;
        private MistForwarder mistForwarder;

        ////////////////////////////////////////////////////////////////////////////////

        public ForwarderEntity(BridgeTalk.ForwarderInfo finfo) {
            fwdInfo = finfo;
            mistForwarder = new MistForwarder();
            mistForwarder.setOutputListener(outputListener);
            mistForwarder.setErrorListener(errorListener);
        }

        public BridgeTalk.ForwarderInfo getForwarderInfo() {
            return fwdInfo;
        }

        public String getStatus() {
        	if(mistForwarder.getThread().isAlive() && mistForwarder.exitValue() == MistForwarder.RetVal.OK.ordinal())
        		return(mistForwarder.isEnabled() ? "ONLINE": "OFFLINE");
        	else
        		return "TERMINATED";
        }

        public MistForwarder getForwarder() {
            return mistForwarder;
        }

        public void start() {
            String sourceTarget = "";
            String destTarget = "";

            if(fwdInfo.getSrc().getBrokerId() == -1)
                sourceTarget = String.format("--from=%s", fwdInfo.getSrc().getExchange());
            else {
                int idx = findBrokerIndex(fwdInfo.getSrc().getBrokerId());
                BridgeTalk.BrokerInfo b = brokerPool.get(idx);
                ConnectionList connList = new ConnectionList();
                connList.set(b.getHost(), b.getPort());
                sourceTarget = String.format("--from=%s,%s,%s:%s,%s", b.getType(), connList.toString().replace(",", ";"), b.getUsername(), b.getPassword(), fwdInfo.getSrc().getExchange());
            }

            for(BridgeTalk.ForwarderInfo.Target t: fwdInfo.getDestList()) {
                if(t.getBrokerId() == -1)
                    destTarget += String.format(" --to=%s", t.getExchange());
                else {
                    int idx = findBrokerIndex(t.getBrokerId());
                    BridgeTalk.BrokerInfo b = brokerPool.get(idx);
                    ConnectionList connList = new ConnectionList();
                    connList.set(b.getHost(), b.getPort());
                    destTarget += String.format(" --to=%s,%s,%s:%s,%s", b.getType(), connList.toString().replace(",", ";"), b.getUsername(), b.getPassword(), t.getExchange());
                }
            }

            String cmd = String.format("%s %s", sourceTarget, destTarget);
            mistForwarder.disable();
            mistForwarder.invoke(cmd);
        }

        public void stop() {
            mistForwarder.manualShutdown();
            mistForwarder.waitForComplete();
        }

        public void addForwarderDest(int broker_id, String exchange) {
            BridgeTalk.ForwarderInfo.Builder builder = BridgeTalk.ForwarderInfo.newBuilder();
            builder.mergeFrom(fwdInfo);
            builder.addDest((BridgeTalk.ForwarderInfo.Target.newBuilder().setBrokerId(broker_id).setExchange(exchange).build()));
            fwdInfo = builder.build();
        }

        public void removeForwarderDest(int broker_id, String exchange) {
            BridgeTalk.ForwarderInfo.Builder builder = BridgeTalk.ForwarderInfo.newBuilder();
            builder.mergeFrom(fwdInfo);
            builder.clearDest();
            BridgeTalk.ForwarderInfo.Target find = BridgeTalk.ForwarderInfo.Target.newBuilder().setBrokerId(broker_id).setExchange(exchange).build();
            for(int i = 0; i < fwdInfo.getDestCount(); i++) {
                if(!fwdInfo.getDest(i).equals(find))
                    builder.addDest(fwdInfo.getDest(i));
            }
            fwdInfo = builder.build();
        }
    }

    private class ExitCommand implements CommandExecutable {
        public void execute(String [] argv) {
            outputResponse("EXIT");
        }
        public void help() {
            outputResponse("usage: exit");
            outputResponse("    exit from the program");
        }
        public String explain() {
            return "exit program";
        }
        public Options getOptions() {
            return null;
        }
    }

    private class HelpCommand implements CommandExecutable {
        public void execute(String [] argv) {
            if(argv.length == 1) {
                outputResponse("commands:");
                for(Map.Entry<String, CommandExecutable> e: commandTable.entrySet())
                    outputResponse(String.format(" %-20s %s", e.getKey(), e.getValue().explain()));
                return;
            }
            if(commandTable.containsKey(argv[1]))
                commandTable.get(argv[1]).help();
            else
                outputResponse(String.format("Don't know how to help on `%s'.", argv[1]));
        }
        public void help() {
            outputResponse("usage: help <command>");
            outputResponse("    list all commands or get help message for specific command");
        }
        public String explain() {
            return "getting help";
        }
        public Options getOptions() {
            return null;
        }
    }

    private class ForwarderCommand implements CommandExecutable {
        private Options opts = new Options();

        private String getTargetInfo(BridgeTalk.ForwarderInfo.Target t) {
            if(t.getBrokerId() == -1)
                return t.getExchange();
            else
                return String.format("%d,%s", t.getBrokerId(), t.getExchange());
        }
        
        private String getSessionInfo(int sessID) {
            String info = "";
            GateTalk.Request.Builder req_builder = GateTalk.Request.newBuilder();
            req_builder.setType(GateTalk.Request.Type.SESSION_INFO);
            req_builder.setArgument(String.valueOf(sessID));
            
            GateTalk.Command.Builder cmd_builder = GateTalk.Command.newBuilder();
            cmd_builder.addRequest(req_builder.build());
            try {
                GateTalk.Response res = MistSession.sendRequest(cmd_builder.build());
                info = res.getSuccess() ? res.getContext(): res.getException();
            }
            catch(MistException e) {
                outputResponse(e.getMessage());
            }
            return info;
        }

        private void list() {
            boolean showNote = false;
            if(forwarderPool.size() == 0)
                outputResponse("no forwarder(s)");
            else {
                Table tab = new Table(8);
                tab.addCell("ID");
                tab.addCell("Source");
                tab.addCell("Destination");
                tab.addCell("Delivery");
                tab.addCell("Drop");
                tab.addCell("Status");
                tab.addCell("Src. Broker");
                tab.addCell("Dest. Broker");
                
                for(ForwarderEntity f: forwarderPool) {               
                    MistForwarder fwd = f.getForwarder(); 
                    BridgeTalk.ForwarderInfo finfo = f.getForwarderInfo();
                    String src = getTargetInfo(finfo.getSrc());
                                       
                    for(int i = 0; i < finfo.getDestCount(); i++) {                    	                   	
                    	tab.addCell(String.valueOf(finfo.getId()));
                        tab.addCell(src);
                        tab.addCell(getTargetInfo(finfo.getDest(i)));
                        tab.addCell(String.valueOf(fwd.getMessageCnt(finfo.getDest(i).getExchange())), new CellStyle(HorizontalAlign.right));
                        tab.addCell(String.valueOf(fwd.getDropMessageCnt(finfo.getDest(i).getExchange())), new CellStyle(HorizontalAlign.right));
                        tab.addCell(f.getStatus());
                        
                        if(!f.getStatus().equals("TERMINATED")) {
                        	tab.addCell(getSessionInfo(fwd.getSourceSessionID()));
                            if(finfo.getDest(0).getBrokerId() != -1) 
                                tab.addCell(getSessionInfo(fwd.getDestinationSessionID()[0]));
                            else {
                                tab.addCell(getSessionInfo(fwd.getDestinationSessionID()[i]));
                            }                        
                        } else {
                        	tab.addCell("N/A");
                        	tab.addCell("N/A");
                        }
                    }                   
                    
                    if(f.getStatus().equals("TERMINATED"))
                        showNote = true;
                }
                outputResponse(tab.render());
                HashMap<Integer, Integer> brk_ids = new HashMap<Integer, Integer>();
                for(ForwarderEntity f: forwarderPool) {
                    BridgeTalk.ForwarderInfo finfo = f.getForwarderInfo();
                    if(finfo.getSrc().getBrokerId() != -1 && !brk_ids.containsKey(finfo.getSrc().getBrokerId()))
                        brk_ids.put(finfo.getSrc().getBrokerId(), finfo.getSrc().getBrokerId());
                    for(BridgeTalk.ForwarderInfo.Target t: finfo.getDestList()) {
                        if(t.getBrokerId() != -1 && !brk_ids.containsKey(t.getBrokerId()))
                            brk_ids.put(t.getBrokerId(), t.getBrokerId());
                    }
                }
                if(brk_ids.size() > 0) {
                    outputResponse("referenced brokers: ");
                    tab = new Table(3);
                    tab.addCell("ID");
                    tab.addCell("Type");
                    tab.addCell("Host");
                    for(Integer id: brk_ids.values()) {
                        int idx = findBrokerIndex(id);
                        BridgeTalk.BrokerInfo b = brokerPool.get(idx);
                        ConnectionList connList = new ConnectionList();
                        connList.set(b.getHost(), b.getPort());
                        tab.addCell(id.toString());
                        tab.addCell(b.getType());
                        tab.addCell(connList.toString().replace(",", ";"));
                    }
                    outputResponse(tab.render());
                }
                if(showNote)
                    outputResponse("(If a forwarder is terminated, you need to restart tme-bridge or create it again)");
            }
        }

        private List<String> parseTarget(String target) throws MistException {
            ArrayList<String> ret = new ArrayList<String>();
            if(target.contains(",")) {
                String [] v = target.split(",");
                if(v.length < 2)
                    throw new MistException(String.format("`%s' not valid", target));
                int broker_id = Integer.parseInt(v[0]);
                if(findBrokerIndex(broker_id) == -1)
                    throw new MistException(String.format("broker_id %d not valid", broker_id));
                ret.add(String.valueOf(broker_id));
                
                if(brokerPool.get(broker_id).getType().equals("openmq") && 
                		!Exchange.isValidExchange(new Exchange(v[1]).getName())) {
                	throw new MistException(String.format("exchange name %s not valid", v[1]));
                }
                
                if(v[1].contains(":"))
                	ret.add(v[1]);
                else
                	ret.add(String.format("queue:%s", v[1]));
            }
            else {
                ret.add("-1");
                if (Exchange.isValidExchange(new Exchange(target).getName())) {
                    if(target.contains(":"))
                        ret.add(target);
                    else
                        ret.add(String.format("queue:%s", target));                	
                } else {
                	throw new MistException(String.format("exchange name %s not valid", target));
                }
            }
            return ret;
        }

        private void add(String from, String to) {
            BridgeTalk.ForwarderInfo.Builder builder = BridgeTalk.ForwarderInfo.newBuilder();
            List<String> ret;
            try {
                ret = parseTarget(from);
                builder.setSrc(BridgeTalk.ForwarderInfo.Target.newBuilder().setBrokerId(Integer.parseInt(ret.get(0))).setExchange(ret.get(1)));
                String [] v = to.split(";");
                if(!checkSameBrokerRegion(v)) {
                    outputResponse("not all targets in the same broker region");
                    return;
                }
                for(String t: v) {
                    ret = parseTarget(t);
                    builder.addDest((BridgeTalk.ForwarderInfo.Target.newBuilder().setBrokerId(Integer.parseInt(ret.get(0))).setExchange(ret.get(1)).build()));
                }
                builder.setId(generateForwarderID());
                builder.setOnline(false);
                BridgeTalk.ForwarderInfo finfo = builder.build();
                if(addForwarder(finfo))
                    outputResponse(String.format("forwarder added with id = %d", finfo.getId()));
            }
            catch(Exception e) {
                outputResponse(e.getMessage());
            }
        }

        private boolean checkSameBrokerRegion(String [] target) {
            try {
                List<String> first = parseTarget(target[0]);
                for(String t: target) {
                    List<String> iter = parseTarget(t);
                    if(!iter.get(0).equals(first.get(0)))
                        return false;
                }
                return true;
            }
            catch(MistException e) {
                outputResponse(e.getMessage());
                return false;
            }
        }

        private void remove(int id) {
            int idx = findForwarderIndex(id);
            if(idx == -1) {
                outputResponse(String.format("forwarder_id %d not found", id));
                return;
            }
            removeForwarder(idx);
        }

        private void enable(int id) {
            int idx = findForwarderIndex(id);
            if(idx >= 0) {
                if(forwarderPool.get(idx).getStatus().equals("OFFLINE")) {
                    forwarderPool.get(idx).mistForwarder.enable();
                    BridgeTalk.ForwarderInfo newInfo = BridgeTalk.ForwarderInfo.newBuilder().mergeFrom(forwarderPool.get(idx).fwdInfo).clearOnline().setOnline(true).build();
                    forwarderPool.get(idx).fwdInfo = newInfo;
                    saveConfig();
                    outputResponse("enabling forwarder " + id + " done");
                }
                else {
                    outputResponse("forwarder " + id + " is already online or terminated");
                    retVal = 1;
                }
            }
            else
                outputResponse(String.format("forwarder id `%d' not found", id));
        }

        private void disable(int id) {
            int idx = findForwarderIndex(id);
            if(idx >= 0) {
                forwarderPool.get(idx).mistForwarder.disable();
                BridgeTalk.ForwarderInfo newInfo = BridgeTalk.ForwarderInfo.newBuilder().mergeFrom(forwarderPool.get(idx).fwdInfo).clearOnline().setOnline(false).build();
                forwarderPool.get(idx).fwdInfo = newInfo;
                saveConfig();
                outputResponse("disabling forwarder " + id + " done");
            }
            else
                outputResponse(String.format("forwarder id %d not found", id));
        }

        private String expandTarget(String target) throws MistException {
            try {
                List<String> ret = parseTarget(target);
                int broker_id = Integer.parseInt(ret.get(0));
                String flatTarget = ret.get(1);
                if(broker_id != -1) {
                    BridgeTalk.BrokerInfo b = brokerPool.get(findBrokerIndex(broker_id));
                    ConnectionList connList = new ConnectionList();
                    connList.set(b.getHost(), b.getPort());
                    flatTarget = String.format("%s,%s,%s:%s,%s", b.getType(), connList.toString().replace(",", ";"), b.getUsername(), b.getPassword(), ret.get(1));
                }
                return flatTarget;
            }
            catch(MistException e) {
                throw e;
            }
        }

        private void mount(String [] argv) {
            if(argv.length == 1) {
                outputResponse("missing `target'");
                return;
            }
            int id = Integer.parseInt(argv[0]);
            String target = argv[1];
            int idx = findForwarderIndex(id);
            try {
                if(idx == -1)
                    throw new MistException(String.format("forwarder id `%d' not found", id));
                ForwarderEntity fwd = forwarderPool.get(idx);
                List<String> ret = parseTarget(target);
                int broker_id = Integer.parseInt(ret.get(0));
                String ex_name = ret.get(1);
                if(broker_id != fwd.getForwarderInfo().getDest(0).getBrokerId()) {
                    outputResponse("target is not the same region with others in this forwarder");
                    return;  
                }
                String flatTarget = expandTarget(target);
                if(fwd.mistForwarder.mountDestination(flatTarget)) {
                    fwd.addForwarderDest(broker_id, ex_name);
                    saveConfig();
                    outputResponse(String.format("mount `%s' to forwarder id `%d'", target, id));
                }
            }
            catch(MistException e) {
                outputResponse(e.getMessage());
            }
        }

        private void unmount(String [] argv) {
            if(argv.length == 1) {
                outputResponse("missing `target'");
                return;
            }
            int id = Integer.parseInt(argv[0]);
            String target = argv[1];
            int idx = findForwarderIndex(id);
            try {
                if(idx == -1)
                    throw new MistException(String.format("forwarder id `%d' not found", id));
                ForwarderEntity fwd = forwarderPool.get(idx);
                String flatTarget = expandTarget(target);
                if(fwd.mistForwarder.unmountDestination(flatTarget)) {
                    List<String> ret = parseTarget(target);
                    fwd.removeForwarderDest(Integer.parseInt(ret.get(0)), ret.get(1));
                    saveConfig();
                    outputResponse(String.format("unmount `%s' from forwarder id `%d'", target, id));
                }
            }
            catch(MistException e) {
                outputResponse(e.getMessage());
            }
        }

        ////////////////////////////////////////////////////////////////////////////////

        public ForwarderCommand() {
            Option optHelp = new Option("h", "help", false, "display help message");
            Option optList = new Option("l", "list", false, "list all forwarders");
            Option optAdd = new Option("a", "add", false, "add a forwarder");
            Option optFrom = new Option("f", "from", true, "specify source target");
            optFrom.setArgName("target");
            Option optTo = new Option("t", "to", true, "specify destination target");
            optTo.setArgName("target[;target]");
            Option optRemove = new Option("r", "remove", true, "remove a forwarder");
            optRemove.setArgName("forwarder_id");
            Option optEnable = new Option("e", "enable", true, "enable a forwarder");
            optEnable.setArgName("forwarder_id");
            Option optDisable = new Option("d", "disable", true, "disable a forwarder");
            optDisable.setArgName("forwarder_id");
            Option optMount = new Option("m", "mount", true, "mount an extra destination target to a forwarder");
            optMount.setArgs(2);
            optMount.setArgName("forwarder_id target");
            Option optUnmount = new Option("u", "unmount", true, "unmount an destination target from a forwarder");
            optUnmount.setArgs(2);
            optUnmount.setArgName("forwarder_id target");

            opts.addOption(optAdd);
            opts.addOption(optRemove);
            opts.addOption(optFrom);
            opts.addOption(optTo);
            opts.addOption(optList);
            opts.addOption(optHelp);
            opts.addOption(optEnable);
            opts.addOption(optDisable);
            opts.addOption(optMount);
            opts.addOption(optUnmount);
        }

        public void execute(String [] argv) {
            try {
                CommandLine line = new GnuParser().parse(opts, argv);
                if(line.hasOption("list"))
                    list();
                else if(line.hasOption("add")) {
                    if(!line.hasOption("from"))
                        outputResponse("missing --from <target>");
                    if(!line.hasOption("to"))
                        outputResponse("missing --to <target>");
                    if(line.hasOption("from") && line.hasOption("to"))
                        add(line.getOptionValue("from"), line.getOptionValue("to"));
                }
                else if(line.hasOption("remove"))
                    remove(Integer.parseInt(line.getOptionValue("remove")));
                else if(line.hasOption("enable"))
                    enable(Integer.parseInt(line.getOptionValue("enable")));
                else if(line.hasOption("disable"))
                    disable(Integer.parseInt(line.getOptionValue("disable")));
                else if(line.hasOption("mount"))
                    mount(line.getOptionValues("mount"));
                else if(line.hasOption("unmount"))
                    unmount(line.getOptionValues("unmount"));
                else
                    help();
            }
            catch(NumberFormatException e) {
                outputResponse(String.format("%s, invalid number format", e.getMessage()));
            }
            catch(Exception e) {
                outputResponse(e.getMessage());
            }
        }

        public void help() {
            HelpFormatter formatter = new HelpFormatter();
            PrintWriter writer = new PrintWriter(responseWriter);
            formatter.printHelp(writer, 256, "forwarder", "", opts, 1, 3, "");
            outputResponse("notations: ");
            outputResponse(" `target' = {exchange|BROKER_ID,exchange}");
            outputResponse(" `exchange' = {queue|topic}:EXCHANGENAME");
        }

        public String explain() {
            return "manage forwarders";
        }

        public Options getOptions() {
            return opts;
        }
    }

    private class BrokerCommand implements CommandExecutable {
        private Options opts = new Options();

        private void list() {
            if(brokerPool.size() == 0)
                outputResponse("no broker(s)");
            else {
                Table tab = new Table(4);
                tab.addCell("ID");
                tab.addCell("Type");
                tab.addCell("Auth");
                tab.addCell("Host");
                for(BridgeTalk.BrokerInfo b: brokerPool) {
                    ConnectionList connList = new ConnectionList();
                    connList.set(b.getHost(), b.getPort());
                    tab.addCell(String.valueOf(b.getId()));
                    tab.addCell(String.valueOf(b.getType()));
                    tab.addCell(String.valueOf(b.getUsername() + ":" + b.getPassword()));
                    tab.addCell(String.valueOf(connList.toString().replace(",", ";")));
                }
                outputResponse(tab.render());
            }
        }

        private void add(String broker_config) {
            String [] v = broker_config.split(",");
            if(v.length < 3) {
                outputResponse(String.format("`%s' not valid", broker_config));
                return;
            }
            BridgeTalk.BrokerInfo.Builder builder = BridgeTalk.BrokerInfo.newBuilder();
            try {
                builder.setType(BrokerAddress.parseBrokerType(v[0]));
                ConnectionList connList = new ConnectionList(v[1].replace(";", ","));
                builder.setHost(connList.getHosts()).setPort(connList.getPorts());
                Credential auth = new Credential(v[2]);
                builder.setUsername(auth.getUser()).setPassword(auth.getPassword());
                builder.setId(generateBrokerID());
                BridgeTalk.BrokerInfo binfo = builder.build();
                if(addBroker(binfo))
                    outputResponse(String.format("broker added with id = %d", binfo.getId()));
            }
            catch(MistException e) {
                outputResponse(e.getMessage());
                return;
            }
        }

        private void remove(int id) {
            boolean using = false;
            for(ForwarderEntity f: forwarderPool) {
                BridgeTalk.ForwarderInfo finfo = f.getForwarderInfo();
                if(finfo.getSrc().getBrokerId() == id)
                    using = true;
                else {
                    for(BridgeTalk.ForwarderInfo.Target t: finfo.getDestList()) {
                        if(t.getBrokerId() == id) {
                            using = true;
                            break;
                        }
                    }
                }
                if(using)
                    break;
            }
            if(using)
                outputResponse(String.format("broker_id %d is still in use", id));
            else {
                int idx = findBrokerIndex(id);
                if(idx == -1)
                    outputResponse(String.format("broker_id %d not found", id));
                else {
                    removeBroker(idx);
                    outputResponse(String.format("broker_id %d removed", id));
                }
            }
        }

        ////////////////////////////////////////////////////////////////////////////////

        public BrokerCommand() {
            Option optHelp = new Option("h", "help", false, "display help message");
            Option optList = new Option("l", "list", false, "list all brokers and their states");
            Option optAdd = new Option("a", "add", true, "add a new broker");
            optAdd.setArgName("broker_config");
            Option optRemove = new Option("r", "remove", true, "remove a broker");
            optRemove.setArgName("broker_id");

            opts.addOption(optAdd);
            opts.addOption(optRemove);
            opts.addOption(optList);
            opts.addOption(optHelp);
        }

        public void execute(String [] argv) {
            try {
                CommandLine line = new GnuParser().parse(opts, argv);
                if(line.hasOption("list"))
                    list();
                else if(line.hasOption("add"))
                    add(line.getOptionValue("add"));
                else if(line.hasOption("remove"))
                    remove(Integer.parseInt(line.getOptionValue("remove")));
                else
                    help();
            }
            catch(NumberFormatException e) {
                outputResponse(String.format("%s, invalid number format", e.getMessage()));
            }
            catch(Exception e) {
                outputResponse(e.getMessage());
            }
        }

        public void help() {
            HelpFormatter formatter = new HelpFormatter();
            PrintWriter writer = new PrintWriter(responseWriter);
            formatter.printHelp(writer, 256, "broker", "", opts, 1, 3, "");
            outputResponse("notations: ");
            outputResponse(" `broker_config' = {activemq|openmq},host:port[;host:port],user:password");
        }

        public String explain() {
            return "manage brokers";
        }

        public Options getOptions() {
            return opts;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////

    public static final String DAEMON_HOST = "127.0.0.1";
    public static final int DAEMON_PORT = 7748;

    public TmeBridge() throws Exception {
        if(Utils.checkSocketConnectable(ZK_SERVER))
            new Thread(this).start();
        else
            throw new MistException(String.format("unable to connect zookeeper `%s'", ZK_SERVER));

        commandTable.put("broker", new BrokerCommand());
        commandTable.put("forwarder", new ForwarderCommand());
        commandTable.put("help", new HelpCommand());
        commandTable.put("exit", new ExitCommand());

        setupEnvironment();
        addShutdownHook();

        try {
            server = new ServerSocket(DAEMON_PORT);
        }
        catch(IOException e) {
            throw e;
        }
    }

    public void run() {
        ZKSessionManager.initialize(ZK_SERVER, ZK_TIMEOUT);
        try {
            ZKSessionManager.instance().waitConnected();
        }
        catch(InterruptedException e1) {
        }
        ZNode masterNode = new ZNode(BRIDGE_PATH + "/master");
        ZNode slaveNode = new ZNode(BRIDGE_PATH + "/master");
        try {
            if(!masterNode.exists()) {
                try {
                    masterNode.create(false, "".getBytes());
                }
                catch(CODIException.NodeExist e) {
                }
            }
            if(!slaveNode.exists()) {
                try {
                    slaveNode.create(false, "".getBytes());
                }
                catch(CODIException.NodeExist e) {
                }
            }
        }
        catch(Exception e) {
            logger.error(Utils.convertStackTrace(e));
        }

        ZNode slaveChild = new ZNode(BRIDGE_PATH + "/slave/" + Utils.getHostIP());
        try {
            slaveChild.create(true, "".getBytes());
        }
        catch(CODIException e) {
            logger.error(Utils.convertStackTrace(e));
        }

        bridgeLock = new ZLock(BRIDGE_PATH + "/lock");
        for(;;) {
            try {
                bridgeLock.acquire(LockType.WRITE_LOCK);
                break;
            }
            catch(CODIException e) {
            }
            catch(InterruptedException e) {
            }
        }
        isMaster = true;
        try {
            slaveChild.delete();
        }
        catch(CODIException e) {
        }
        masterChild = new ZNode(BRIDGE_PATH + "/master/" + Utils.getHostIP());
        try {
            masterChild.delete();
        }
        catch(CODIException e) {
        }
        try {
            masterChild.create(true, "".getBytes());
        }
        catch(CODIException e) {
            logger.error(Utils.convertStackTrace(e));
        }

        loadConfig();
    }

    public boolean equalBroker(BridgeTalk.BrokerInfo a, BridgeTalk.BrokerInfo b) {
        String s1 = String.format("%s:%s:%s:%s:%s", a.getType(), a.getHost(), a.getPort(), a.getUsername(), a.getPassword());
        String s2 = String.format("%s:%s:%s:%s:%s", b.getType(), b.getHost(), b.getPort(), b.getUsername(), b.getPassword());
        return s1.equals(s2);
    }

    public boolean addBroker(BridgeTalk.BrokerInfo broker) {
        ConnectionList connList = new ConnectionList();
        connList.set(broker.getHost(), broker.getPort());
        if(!BrokerFarm.authenticateBroker(broker.getType(), connList, broker.getUsername(), broker.getPassword())) {
            outputResponse(String.format("can not connect `%s' with account `%s'", connList.toString(), broker.getUsername()));
            return false;
        }

        for(BridgeTalk.BrokerInfo b: brokerPool) {
            if(equalBroker(b, broker)) {
                outputResponse(String.format("duplicate with broker_id %d", b.getId()));
                return false;
            }
        }
        brokerPool.add(broker);
        saveConfig();
        return true;
    }

    public void removeBroker(int idx) {
        if(idx >= 0 && idx < brokerPool.size()) {
            brokerPool.remove(idx);
            saveConfig();
        }
    }

    private JNode<String> getExchangeNode(String node) {
    	JNode<String> exNode = null;

    	if(nodePool.containsKey(node))
    		exNode = nodePool.get(node);
        else {
        	exNode = new JNode<String>(node);
        	nodePool.put(node, exNode);
        }

    	return exNode;
    }

    private boolean addExchangeNode(BridgeTalk.ForwarderInfo fwdInfo) {
        JNode<String> srcNode, dstNode;
        boolean isFailure = false;

        BridgeTalk.ForwarderInfo.Target src_target = fwdInfo.getSrc();
        String src = String.format("%d:%s", src_target.getBrokerId(), src_target.getExchange());
        srcNode = getExchangeNode(src);

        // First check whether any forwarder already exists.
        for(BridgeTalk.ForwarderInfo.Target t: fwdInfo.getDestList()) {
            String dst = String.format("%d:%s", t.getBrokerId(), t.getExchange());
            dstNode = getExchangeNode(dst);
            if(srcNode.search(dstNode) != null) {
                outputResponse(String.format("forwarder from %s to %s already exists!", src, dst));
                return false;
            }
        }

        // Second check whether loop is existed
        for(BridgeTalk.ForwarderInfo.Target t: fwdInfo.getDestList()) {
            String dst = String.format("%d:%s", t.getBrokerId(), t.getExchange());
            dstNode = nodePool.get(dst);
            srcNode.addChild(dstNode);
            // Check whether there is an infinite loop
            java.util.Iterator<JNode<String>> iter = nodePool.values().iterator();
            while(iter.hasNext()) {
                JNode<String> node = iter.next();
                StronglyConnectedGraph<String> ssg = new StronglyConnectedGraph<String>(node);
                if(ssg.isStronglyConnected()) {
                	outputResponse(String.format("create forwarder failed since it causes a loop!"));
                	isFailure = true;
                    break;
                }
            }
            if(isFailure) break;
        }

        // If it's failed, remove all
        if(isFailure) {
        	dstNode = null;
        	for(BridgeTalk.ForwarderInfo.Target t: fwdInfo.getDestList()) {
                String dst = String.format("%d:%s", t.getBrokerId(), t.getExchange());
                dstNode = nodePool.get(dst);
                if(dstNode != null)
                	srcNode.removeChild(dstNode);
        	}
        	return false;
        }

        return true;
    }

    private boolean removeExchangeNode(BridgeTalk.ForwarderInfo fwdInfo) {
        BridgeTalk.ForwarderInfo.Target src_target = fwdInfo.getSrc();
        String src = String.format("%d:%s", src_target.getBrokerId(), src_target.getExchange());
        JNode<String> srcNode = nodePool.get(src);

        if(srcNode == null)
            return false;

        for(BridgeTalk.ForwarderInfo.Target t: fwdInfo.getDestList()) {
            String dst = String.format("%d:%s", t.getBrokerId(), t.getExchange());
            JNode<String> dstNode = nodePool.get(dst);
            if(dstNode == null)
                continue;
            else
                srcNode.removeChild(dstNode);
        }
        return true;
    }

    public boolean addForwarder(BridgeTalk.ForwarderInfo fwdInfo) throws Exception {
        if(addExchangeNode(fwdInfo)) {
            ForwarderEntity fwdEntity = new ForwarderEntity(fwdInfo);
            forwarderPool.add(fwdEntity);
            saveConfig();
            fwdEntity.start();
            if(fwdInfo.getOnline())
                fwdEntity.mistForwarder.enable();

            return true;
        }
        else
            return false;
    }

    public void removeForwarder(int idx) {
        if(idx >= 0 && idx < forwarderPool.size()) {
            ForwarderEntity fwdEntity = forwarderPool.get(idx);
            if(removeExchangeNode(fwdEntity.getForwarderInfo())) {
                fwdEntity.stop();
                forwarderPool.remove(idx);
                saveConfig();
                outputResponse(String.format("forwarder_id %d removed", fwdEntity.getForwarderInfo().getId()));
            }
        }
    }

    public void startForwarder(int idx) {
        if(idx >= 0 && idx < forwarderPool.size()) {
            ForwarderEntity fwdEntity = forwarderPool.get(idx);
            if(!fwdEntity.getStatus().equals("start")) {
                fwdEntity.start();
                saveConfig();
            }
        }
    }

    public void stopForwarder(int idx) {
        if(idx >= 0 && idx < forwarderPool.size()) {
            ForwarderEntity fwdEntity = forwarderPool.get(idx);
            if(!fwdEntity.getStatus().equals("stop")) {
                fwdEntity.stop();
                saveConfig();
            }
        }
    }

    public void start() {
        while(true) {
            Socket clientSocket = null;
            BufferedInputStream in = null;
            BufferedOutputStream out = null;
            try {
                clientSocket = server.accept();
                in = new BufferedInputStream(clientSocket.getInputStream());
                out = new BufferedOutputStream(clientSocket.getOutputStream());

                Packet pack = new Packet();
                pack.read(in);
                BridgeTalk.Request request = BridgeTalk.Request.newBuilder().mergeFrom(pack.getPayload()).build();

                String line = request.getCommand();
                if(line.equals("COMPLETORS")) {
                    String [] cmds = Console.getAllCommands(commandTable);
                    String [] args = Console.getAllArguments(commandTable);
                    String answer = cmds[0];
                    for(int i = 0; i < cmds.length; i++)
                        answer += (":" + cmds[i]);
                    answer += ("," + args[0]);
                    for(int i = 0; i < args.length; i++)
                        answer += (":" + args[i]);
                    BridgeTalk.Response response = BridgeTalk.Response.newBuilder().setSuccess(true).setContext(answer).build();
                    pack.setPayload(response.toByteArray());
                    pack.write(out);
                }
                else {
                    StringTokenizer tok = new StringTokenizer(line);
                    if(tok.hasMoreElements()) {
                        String cmd = tok.nextToken();
                        clearResponseBuffer();
                        retVal = 0;
                        if(!isMaster) {
                            if(!cmd.equals("exit")) {
                                BridgeTalk.Response response = BridgeTalk.Response.newBuilder().setSuccess(true).setContext("in slave mode, no action\n").build();
                                pack.setPayload(response.toByteArray());
                                pack.write(out);
                                continue;
                            }
                        }
                        if(commandTable.containsKey(cmd))
                            commandTable.get(cmd).execute(line.split("\\s+"));
                        else {
                            outputResponse(String.format("Unknown command `%s'.", cmd));
                            retVal = 1;
                        }
                    }

                    BridgeTalk.Response response = BridgeTalk.Response.newBuilder().setSuccess(retVal == 0 ? true: false).setContext(getResponseBuffer()).build();
                    pack.setPayload(response.toByteArray());
                    pack.write(out);
                }
            }
            catch(Exception e) {
                logger.error(e.getMessage());
            }
            try {
                in.close();
                out.close();
                clientSocket.close();
            }
            catch(IOException e) {
                logger.error(e.getMessage());
            }
        }
    }

    public static void main(String argv[]) {
        try {
            myApp = new TmeBridge();
            myApp.start();
        }
        catch(Exception e) {
            logger.error(e.getMessage());
        }
    }
}