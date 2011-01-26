package com.trendmicro.spn.common.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.Socket;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.io.StringWriter;
import java.io.Writer;
import java.io.PrintWriter;

import java.util.ArrayList;

public class Utils {
    
    public Utils() {
    }
    
    public static String getCurrentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        String pid = "-1";
        int idx = name.indexOf("@");
        if(idx >= 0)
            pid = name.substring(0, idx);
        return pid;
    }

    public static String getHostIP() {
        String ip = "0.0.0.0";
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        }
        catch(UnknownHostException e) {
        }
        return ip;
    }

    public static String [] splitIgnoreEmpty(String str, String sep) {
        ArrayList<String> fields = new ArrayList<String>();
        String[] vec = str.split(sep);
        for(String t : vec) {
            if(t != null && t.length() > 0)
                fields.add(t);
        }
        String [] ret = new String[fields.size()];
        fields.toArray(ret);
        return ret;
    }

    public static int getJavaProcessId(String className) {
        int pid = -1;
        // because JRE does not provide the "jps" utility, we parse the output of ps instead
        String[] cmd = { "/bin/sh", "-c", "ps -C java -O start_time | grep " + className };
        Process psProc = null;
        try {
            psProc = Runtime.getRuntime().exec(cmd);
            psProc.waitFor();
            // read the output of the executed command into buffer
            byte[] buf = new byte[10];
            psProc.getInputStream().read(buf);

            String strArray[] = new String(buf).split("\\s+");
            // parse the first field (pid)
            if(strArray[0].compareTo("") == 0)
                pid = Integer.valueOf(strArray[1]);
            else
                pid = Integer.valueOf(strArray[0]);
        }
        catch(Exception e) {
        }
        finally{
            try {
                psProc.getInputStream().close();
            }
            catch(IOException e) {
            }
        }
        return pid;
    }

    public static boolean checkSocketConnectable(String host_list) {
        String [] hosts = host_list.split(",");     // host_list = "ip1:port1,ip2:port2"
        for(String h: hosts) {
            String [] v = h.split(":");
            if(v.length == 2) {
                String host = v[0];
                String port = v[1];
                if(checkSocketConnectable(host, Integer.parseInt(port)))
                    return true;
            }
        }
        return false;
    }

    public static boolean checkSocketConnectable(String host, int port) {
        try {
            Socket mySocket = new Socket(host, port);
            mySocket.close();
        }
        catch (Exception e) {
            return false;
        }
        return true;
    }
    
    public static String convertStackTrace(Throwable e) {
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);
        e.printStackTrace(printWriter);
        return result.toString();
    }
    
    public static void justSleep(long milliSecond) {
    	try {
        	Thread.sleep(milliSecond);
    	}
    	catch(InterruptedException e) {
    	}
    }
}