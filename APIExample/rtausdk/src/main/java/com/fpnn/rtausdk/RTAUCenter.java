package com.fpnn.rtausdk;

import android.content.Context;

import java.util.HashMap;

public class RTAUCenter {
    static HashMap<String, RTAUClient> clients = new HashMap<>();
    public  static RTAUClient initRTAUClient(String rtvtEndpoint, long pid, RTAUPushProcessor pushProcessor, Context applicationContext){
        synchronized (clients){
            String findkey = String.valueOf(pid);
            if (clients.containsKey(findkey)){
                return clients.get(findkey);
            }
        }
        RTAUClient client = new RTAUClient( rtvtEndpoint,pid, pushProcessor,applicationContext);
        return client;
    }


    static void closeRTAU(long pid){
        synchronized (clients){
            String findkey = String.valueOf(pid) ;
            clients.remove(findkey);
        }
    }
}
