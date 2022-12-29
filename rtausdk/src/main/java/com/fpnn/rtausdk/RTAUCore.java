package com.fpnn.rtausdk;

import static com.fpnn.sdk.ErrorCode.FPNN_EC_CORE_INVALID_CONNECTION;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import com.fpnn.sdk.ClientEngine;
import com.fpnn.sdk.ConnectionWillCloseCallback;
import com.fpnn.sdk.ErrorCode;
import com.fpnn.sdk.ErrorRecorder;
import com.fpnn.sdk.FunctionalAnswerCallback;
import com.fpnn.sdk.TCPClient;
import com.fpnn.sdk.proto.Answer;
import com.fpnn.sdk.proto.Quest;

import com.fpnn.rtausdk.RTAUUserInterface.*;
import com.fpnn.rtausdk.RTAUStruct.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.HttpsURLConnection;

class RTAUCore extends BroadcastReceiver{

    //errorCode==0为成功 非0错误 错误信息详见errorMsg字段
    public enum ClientStatus {
        Closed,
        Connecting,
        Connected
    }

    public enum CloseType {
        ByUser,
        ByServer,
        Timeout,
        None
    }


    //for network change
    private int LAST_TYPE = NetUtils.NETWORK_NOTINIT;
    SharedPreferences addressSp;
    int globalQuestTimeoutSeconds = 30;
    int globalMaxThread = 8;
    String SDKVersion = "1.0.0";



    static class VoiceModel{
        long ts = 0;
        ByteArrayOutputStream voicedata = null;
        long voiceSeq = 0;
    }

    static class VideoModel{
        long ts;
        Bitmap bitmap ;
        long imageSeq = 0;

        public VideoModel(long ts, Bitmap bitmap) {
            this.ts = ts;
            this.bitmap = bitmap;
        }
    }
    String LogTAG = "rtau";
    int sendVoiceLength = 640;
    int sendImageInterval = 3; //每几秒发送一张图片
    byte[] readVoice = new byte[sendVoiceLength];
    Timer voiceTimer;
    Timer imageTimer;
    ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
    HashMap<String,VoiceModel> voiceDatas = new HashMap<>();
    HashMap<String, VideoModel> imageDatas = new HashMap<>();
    String LogTag = "rtaulog";


    @Override
    public void onReceive(Context context, Intent intent) {
        String b= ConnectivityManager.CONNECTIVITY_ACTION;
        String a= intent.getAction();
        if (a == b || (a != null && a.equals(b))) {
            int netWorkState = NetUtils.getNetWorkState(context);
            if (LAST_TYPE != netWorkState) {
                LAST_TYPE = netWorkState;
                onNetChange(netWorkState);
            }
        }
    }
    //for network change


    //-------------[ Fields ]--------------------------//
    private final Object interLocker =  new Object();
    private long pid;
    private String loginToken;
    private long loginTs = 0;
    private String rtauEndpoint;
    private Context context;
    ErrorRecorder errorRecorder = new ErrorRecorder();

    private ClientStatus rttGateStatus = ClientStatus.Closed;
    private CloseType closedCase = CloseType.None;
    private int lastNetType = NetUtils.NETWORK_NOTINIT;
    private AtomicBoolean isRelogin = new AtomicBoolean(false);
    private AtomicBoolean running = new AtomicBoolean(true);
    private AtomicBoolean initCheckThread = new AtomicBoolean(false);
    private Thread checkThread;
    private RTAUQuestProcessor processor;
//    private TCPClient dispatch;
    private TCPClient rtauGate;
    private AtomicLong connectionId = new AtomicLong(0);
    private AtomicBoolean noNetWorkNotify = new AtomicBoolean(false);
    private RTAUAnswer lastReloginAnswer = new RTAUAnswer();
    private RTAUPushProcessor serverPushProcessor;
    RTAUUtils rtvtUtils = new RTAUUtils();

    final int okRet = ErrorCode.FPNN_EC_OK.value();

    class RTAUQuestProcessor{

        void rtvtConnectClose() {
//            stopTimes();
            if (serverPushProcessor != null)
                serverPushProcessor.rtauConnectClose();
        }
    }

    void stopImageTimers(){
        if (imageTimer != null){
            imageTimer.cancel();
            imageTimer = null;
        }
    }

    void stopVoiceTimers(){
        if (voiceTimer != null){
            voiceTimer.cancel();
            voiceTimer = null;
        }
    }

    void startImageTimer(){
        imageTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (imageDatas){
                    for (String id: imageDatas.keySet()){
                        VideoModel videoModel = imageDatas.get(id);
                        if (videoModel.ts == 0 || videoModel.bitmap == null)
                            continue;
                        long sendseq = ++videoModel.imageSeq;
                        long sendts = videoModel.ts;

                        videoModel.bitmap.compress(Bitmap.CompressFormat.JPEG, 50, imageStream);
//                        File outfile = new File("/sdcard/Download/" + videoModel.imageSeq + ".png");
//                        try {
//                            FileOutputStream fileOutputStream = new FileOutputStream(outfile);
//                            fileOutputStream.write(imageStream.toByteArray());
//                        } catch (FileNotFoundException e) {
//                            e.printStackTrace();
//                        }
//                        catch (Exception ex){
//
//                        }


                        Quest quest = new Quest("image");
                        quest.param("streamId", id);
                        quest.param("seq", sendseq);
                        quest.param("data", imageStream.toByteArray());
                        quest.param("ts", sendts);
                        sendQuestEmptyCallback(new IRTAUEmptyCallback() {
                            @Override
                            public void onResult(RTAUAnswer answer) {
                                if(answer.errorCode != 0){
                                    Log.e(LogTAG, " send image data error " + answer.getErrInfo());
                                }
                                else {
                                    Log.e(LogTAG, " send image data ok ");
                                }
                            }
                        }, quest);
                    }
                }
            }
        },0,sendImageInterval * 1000);
    }

    void startVoiceTimer(){
        voiceTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                HashMap<String, VoiceModel> tmpmap;
                synchronized (voiceDatas) {
                    for (String id : voiceDatas.keySet()) {
                        VoiceModel voiceModel = voiceDatas.get(id);
                        if (voiceModel.ts == 0)
                            continue;
                        int size = voiceModel.voicedata.size();
                        if (voiceModel.voicedata.size() < sendVoiceLength)
                            continue;
                        else{
                            long sendseq = ++voiceModel.voiceSeq;
                            long ts = voiceModel.ts;
                            if (size == sendVoiceLength) {
                                System.arraycopy(voiceModel.voicedata.toByteArray(), 0, readVoice, 0, sendVoiceLength);
                                voiceModel.voicedata.reset();
                            } else {
                                System.arraycopy(voiceModel.voicedata.toByteArray(), 0, readVoice, 0, sendVoiceLength);
                                byte[] tmp = new byte[size - sendVoiceLength];

                                System.arraycopy(voiceModel.voicedata.toByteArray(), sendVoiceLength, tmp, 0, size - sendVoiceLength);
                                voiceModel.voicedata.reset();
                                try {
                                    voiceModel.voicedata.write(tmp);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                                System.arraycopy(voiceModel.voicedata.toByteArray(), 0, readVoice, 0, sendVoiceLength);
                            }
                            Quest quest = new Quest("audio");
                            quest.param("streamId", id);
                            quest.param("seq", sendseq);
                            quest.param("data", readVoice);
                            quest.param("ts", ts);
//                            Log.e(LogTAG, "send voicedata  streamId:" + id + " seq :" + sendseq + " ts:" +  ts);
                            sendQuestEmptyCallback(new RTAUUserInterface.IRTAUEmptyCallback() {
                                @Override
                                public void onResult(RTAUStruct.RTAUAnswer answer) {
                                    if (answer.errorCode != 0) {
                                        Log.e(LogTAG, "send voicedata error " + answer.getErrInfo());
                                    }
//                                    else
//                                        Log.e(LogTAG, "send voicedata  streamId:" + id + " seq :" + sendseq + " ts:" +  ts);
                                }
                            }, quest);
                        }
                    }
                }
            }
        },0,20);
    }


    void  internalReloginCompleted( final boolean successful, final int reloginCount){
        if (!successful){
            startImageTimer();
            stopVoiceTimers();
        }
        serverPushProcessor.reloginCompleted(successful, lastReloginAnswer, reloginCount);
    }

    void reloginEvent(int count){
        if (noNetWorkNotify.get()) {
            isRelogin.set(false);
            internalReloginCompleted(false, count);
            return;
        }
//        isRelogin.set(true);
        int num = count;
        if (serverPushProcessor.reloginWillStart(num)) {
            lastReloginAnswer = auth(loginToken, loginTs, false);
            if(lastReloginAnswer.errorCode == okRet) {
                isRelogin.set(false);
                internalReloginCompleted(true, num);
                return;
            }
            else {
                if (num >= serverPushProcessor.internalReloginMaxTimes){
                    isRelogin.set(false);
                    internalReloginCompleted(false, num);
                    return;
                }
                if (!isRelogin.get()) {
                    internalReloginCompleted(false, num);
                    return;
                }
                try {
                    Thread.sleep(2 * 1000);
                } catch (InterruptedException e) {
                    isRelogin.set(false);
                    internalReloginCompleted(false, num);
                    return;
                }
                reloginEvent(++num);
            }
        }
        else {
            isRelogin.set(false);
            internalReloginCompleted(false, --num);
        }
    }

    public void onNetChange(int netWorkState){
        if (lastNetType != NetUtils.NETWORK_NOTINIT) {
            switch (netWorkState) {
                case NetUtils.NETWORK_NONE:
                    noNetWorkNotify.set(true);
//                    Log.e("sdktest","no network");
//                    if (isRelogin.get()){
//                        isRelogin.set(false);
//                    }
                    break;
                case NetUtils.NETWORK_MOBILE:
                case NetUtils.NETWORK_WIFI:
//                    Log.e("sdktest","have network");

                    if (rtauGate == null)
                        return;
                    noNetWorkNotify.set(false);
                    if (lastNetType != netWorkState) {
                        if (isRelogin.get())
                            return;
                        close();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
//                        try {
//                            Thread.sleep(100);
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }
                        isRelogin.set(true);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                reloginEvent(1);
//                                if (getClientStatus() == ClientStatus.Connected){
//                                    Quest quest = new Quest("bye");
//                                    sendQuest(quest, new FunctionalAnswerCallback() {
//                                        @Override
//                                        public void onAnswer(Answer answer, int errorCode) {
//                                            close();
//                                            try {
//                                                Thread.sleep(200);
//                                            } catch (InterruptedException e) {
//                                                e.printStackTrace();
//                                            }
//                                            reloginEvent(1);
//                                        }
//                                    }, 5);
//                                }
//                                else {
////                                    voiceClose();
//                                    reloginEvent(1);
//                                }
                            }
                        }).start();
                    }
                    break;
            }
        }
        lastNetType = netWorkState;
    }

    public  void setServerPushProcessor(RTAUPushProcessor processor){
        this.serverPushProcessor = processor;
    }


    void RTAUInit(String rtvtendpoint, long pid, RTAUPushProcessor serverPushProcessor, Context appcontext) {

        rtvtUtils.errorRecorder = errorRecorder;
        this.rtauEndpoint = rtvtendpoint;

        this.pid = pid;
        isRelogin.set(false);
        processor = new RTAUQuestProcessor();

        this.serverPushProcessor = serverPushProcessor;

        context = appcontext;
        ClientEngine.setMaxThreadInTaskPool(globalMaxThread);

        try {
            //网络监听
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
            context.registerReceiver(this, intentFilter);

            addressSp = context.getSharedPreferences("Logindb",context.MODE_PRIVATE);
        }
        catch (Exception ex){
            ex.printStackTrace();
            errorRecorder.recordError("registerReceiver exception:" + ex.getMessage());
        }
    }

    long getPid() {
        return pid;
    }

    synchronized protected ClientStatus getClientStatus() {
        synchronized (interLocker) {
            return rttGateStatus;
        }
    }

    RTAUAnswer genRTAUAnswer(int errCode){
        return genRTAUAnswer(errCode,"");
    }

    RTAUAnswer genRTAUAnswer(int errCode,String msg)
    {
        RTAUAnswer tt = new RTAUAnswer();
        tt.errorCode = errCode;
        tt.errorMsg = msg;
        return tt;
    }

    private TCPClient getCoreClient() {
        synchronized (interLocker) {
            if (rttGateStatus == ClientStatus.Connected)
                return rtauGate;
            else
                return null;
        }
    }


    RTAUAnswer genRTAUAnswer(Answer answer) {
        if (answer == null)
            return new RTAUAnswer(ErrorCode.FPNN_EC_CORE_INVALID_CONNECTION.value(), "invalid connection");
        return new RTAUAnswer(answer.getErrorCode(),answer.getErrorMessage());
    }



    RTAUAnswer genRTAUAnswer(Answer answer, String msg) {
        if (answer == null)
            return new RTAUAnswer(ErrorCode.FPNN_EC_CORE_INVALID_CONNECTION.value(), "invalid connection");
        return new RTAUAnswer(answer.getErrorCode(),answer.getErrorMessage() + " " + msg);
    }


    RTAUAnswer genRTAUAnswer(Answer answer,int errcode) {
        if (answer == null && errcode !=0) {
            if (errcode == ErrorCode.FPNN_EC_CORE_TIMEOUT.value())
                return new RTAUAnswer(errcode, "FPNN_EC_CORE_TIMEOUT");
            else
                return new RTAUAnswer(errcode,"fpnn  error");
        }
        else
            return new RTAUAnswer(answer.getErrorCode(),answer.getErrorMessage());
    }

    void setCloseType(CloseType type)
    {
        closedCase = type;
    }

    void sayBye(final IRTAUEmptyCallback callback) {
        closedCase = CloseType.ByUser;
        final TCPClient client = getCoreClient();
        if (client == null) {
            close();
            return;
        }
        Quest quest = new Quest("bye");
        sendQuest(quest, new FunctionalAnswerCallback() {
            @Override
            public void onAnswer(Answer answer, int errorCode) {
                close();
                callback.onResult(genRTAUAnswer(answer,errorCode));
            }
        }, 5);
    }

    void realClose(){
        closedCase = CloseType.ByUser;
        try {
            if (context != null)
                context.unregisterReceiver(this);
        } catch (IllegalArgumentException e){
        }
        close();
    }


    void sendQuest(Quest quest, final FunctionalAnswerCallback callback) {
        sendQuest(quest, callback, globalQuestTimeoutSeconds);
    }

    Answer sendQuest(Quest quest) {
        return sendQuest(quest,globalQuestTimeoutSeconds);
    }

    Answer sendQuest(Quest quest, int timeout) {
        Answer answer = new Answer(new Quest(""));
        TCPClient client = getCoreClient();
        if (client == null) {
            answer.fillErrorInfo(ErrorCode.FPNN_EC_CORE_INVALID_CONNECTION.value(), "invalid connection");
        }else {
            try {
                answer = client.sendQuest(quest, timeout);
            } catch (Exception e) {
                if (errorRecorder != null)
                    errorRecorder.recordError(e);
                answer = new Answer(quest);
                answer.fillErrorInfo(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(), e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
        return answer;
    }

    void sendQuest(Quest quest, final FunctionalAnswerCallback callback, int timeout) {
        TCPClient client = getCoreClient();
        final Answer answer = new Answer(quest);
        if (client == null) {
            answer.fillErrorInfo(ErrorCode.FPNN_EC_CORE_INVALID_CONNECTION.value(),"invalid connection");
            callback.onAnswer(answer,answer.getErrorCode());//当前线程
            return;
        }
        if (timeout <= 0)
            timeout = globalQuestTimeoutSeconds;
        try {
            client.sendQuest(quest, callback, timeout);
        }
        catch (Exception e){
            answer.fillErrorInfo(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(),e.getMessage());
            callback.onAnswer(answer, answer.getErrorCode());
        }
    }

     byte[] shortArr2byteArr(short[] shortArr, int shortArrLen){
        byte[] byteArr = new byte[shortArrLen * 2];
        ByteBuffer.wrap(byteArr).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortArr);
        return byteArr;
    }


    void sendQuestEmptyCallback(final IRTAUEmptyCallback callback, Quest quest) {
        sendQuest(quest, new FunctionalAnswerCallback() {
            @Override
            public void onAnswer(Answer answer, int errorCode) {
                callback.onResult(genRTAUAnswer(answer,errorCode));
            }
        }, globalQuestTimeoutSeconds);
    }

    RTAUAnswer sendQuestEmptyResult(Quest quest){
        Answer ret =  sendQuest(quest);
        if (ret == null)
            return genRTAUAnswer(ErrorCode.FPNN_EC_CORE_INVALID_CONNECTION.value(),"invalid connection");
        return genRTAUAnswer(ret);
    }

    public void printLog(String msg){
//        Log.e("sdktest",msg);
        errorRecorder.recordError(msg);
    }

    boolean isAirplaneModeOn() {
        return Settings.System.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,0) != 0;
    }

    boolean isNetWorkConnected() {
        boolean isConnected = false;
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeInfo = cm.getActiveNetworkInfo();
            if (activeInfo != null && activeInfo.isAvailable() && activeInfo.isConnected())
                isConnected = true;
        }
        return isConnected;
    }


    //-------------[ Auth(Login) utilies functions ]--------------------------//
    private void ConfigRtmGateClient(final TCPClient client) {
        client.setQuestTimeout(globalQuestTimeoutSeconds);

        if (errorRecorder != null)
            client.setErrorRecorder(errorRecorder);

        client.setQuestProcessor(processor, "RTAUCore$RTAUQuestProcessor");

        client.setWillCloseCallback(new ConnectionWillCloseCallback() {
            @Override
            public void connectionWillClose(InetSocketAddress peerAddress, int _connectionId,boolean causedByError) {
//                printLog("closedCase " + closedCase + " getClientStatus() " + getClientStatus());
                if (connectionId.get() != 0 && connectionId.get() == _connectionId && closedCase != CloseType.ByUser && closedCase != CloseType.ByServer && getClientStatus() != ClientStatus.Connecting) {
                    close();

                    processor.rtvtConnectClose();

                    if (closedCase == CloseType.ByServer || isRelogin.get()) {
                        return;
                    }

                    if (isAirplaneModeOn()) {
                        return;
                    }

                    if(getClientStatus() == ClientStatus.Closed){
                        try {
                            Thread.sleep(2* 1000);//处理一些特殊情况
                            if (noNetWorkNotify.get()) {
                                return;
                            }
                            if (isRelogin.get() || getClientStatus() == ClientStatus.Connected) {
                                return;
                            }
                            isRelogin.set(true);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    reloginEvent(1);
                                }
                            }).start();
                        }
                        catch (Exception e){
                            printLog(" relogin error " + e.getMessage());
                        }
                    }
                }
            }
        });
    }

    public void httpRequest(final String url){
        new Thread(new Runnable() {
            @Override
            public void run() {
                int resultCode = -1;
                try {
                    URL sendurl = new URL(url);
                    HttpsURLConnection conn = (HttpsURLConnection) sendurl.openConnection();
                    conn.setConnectTimeout(15 * 1000);//超时时间
                    conn.setReadTimeout(15 * 1000);
                    conn.setDoInput(true);
                    conn.setUseCaches(false);
                    conn.connect();
                    resultCode = conn.getResponseCode();
                }catch (Exception ex){
                    Log.i("rtvtsdk","httprequest error " + resultCode);
                }
            }
        }).start();
    }


    private void test80(String ipaddres, final IRTAUEmptyCallback callback){
        String realhost = ipaddres;
        if (ipaddres.isEmpty()) {
            realhost = rtauEndpoint.split(":")[0];
            if (realhost == null || realhost.isEmpty()) {
                callback.onResult(genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value()));
                return;
            }
        }

        rtauGate = new TCPClient(realhost, 80);
        ConfigRtmGateClient(rtauGate);
        String deviceid = Build.BRAND + "-" + Build.MODEL;
        Quest qt = new Quest("auth");
        qt.param("pid", pid);
        qt.param("token", loginToken);
        qt.param("version", "Android-" + SDKVersion);
        qt.param("device", deviceid);

        Answer answer = null;
        try {
            answer = rtauGate.sendQuest(qt, globalQuestTimeoutSeconds);
//            answer = new Answer(qt);
//            answer.fillErrorCode(FPNN_EC_CORE_INVALID_CONNECTION.value());
            if (answer.getErrorCode() != ErrorCode.FPNN_EC_OK.value()){
                String url = "https://" + rtauEndpoint.split(":")[0] + "/service/tcp-13321-fail-tcp-80-fail" + pid + "-";
                httpRequest(url);
                callback.onResult(genRTAUAnswer(answer));
            }
            else{
                Quest quest = new Quest("adddebuglog");
                String msg = "rtau pid:" + pid  +  " link 80 port ok";
                quest.param("msg",msg);
                quest.param("attrs","");
                rtauGate.sendQuest(quest, new FunctionalAnswerCallback() {
                    @Override
                    public void onAnswer(Answer answer, int errorCode) {
                        Log.i("sdktest","hehehehe " + errorCode);
                    }
                });
                synchronized (interLocker) {
                    rttGateStatus = ClientStatus.Connected;
                }

                synchronized (addressSp){
                    SharedPreferences.Editor editor = addressSp.edit();
                    editor.putString("addressip",rtauGate.peerAddress.getAddress().getHostAddress());
                    editor.commit();
                }

//                checkRoutineInit();
                connectionId.set(rtauGate.getConnectionId());
                callback.onResult(genRTAUAnswer(ErrorCode.FPNN_EC_OK.value()));
            }

        } catch (Exception e) {
            e.printStackTrace();
            callback.onResult(genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_METHOD.value()));
        }
    }


    private RTAUAnswer test80(String ipaddres){
        String realhost = ipaddres;
        if (ipaddres.isEmpty()) {
            String linkEndpoint = rtauGate.endpoint();
            realhost = linkEndpoint.split(":")[0];
            if (realhost == null || realhost.isEmpty())
                return genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value());
        }

        rtauGate = new TCPClient(realhost, 80);
        ConfigRtmGateClient(rtauGate);
        Quest qt = new Quest("auth");
        qt.param("pid", pid);
        qt.param("token", loginToken);
        qt.param("version", "AndroidRTAU-" + SDKVersion);

        Answer answer = null;
        try {
            answer = rtauGate.sendQuest(qt, globalQuestTimeoutSeconds);
//            answer = new Answer(qt);
//            answer.fillErrorCode(ErrorCode.FPNN_EC_CORE_INVALID_CONNECTION.value());
            if (answer.getErrorCode() != ErrorCode.FPNN_EC_OK.value()){
                String url = "https://" + rtauEndpoint.split(":")[0] + "/service/tcp-13321-fail-tcp-80-fail" + pid + "-";
                httpRequest(url);
                return genRTAUAnswer(answer);
            }
            else {
                Quest quest = new Quest("adddebuglog");
                String msg = "rtau pid:" + pid +  "link 80 port ok";
                quest.param("msg",msg);
                quest.param("attrs","");
                rtauGate.sendQuest(quest, new FunctionalAnswerCallback() {
                    @Override
                    public void onAnswer(Answer answer, int errorCode) {

                    }
                });

                synchronized (interLocker) {
                    rttGateStatus = ClientStatus.Connected;
                }
//                checkRoutineInit();
                connectionId.set(rtauGate.getConnectionId());
                synchronized (addressSp){
                    SharedPreferences.Editor editor = addressSp.edit();
                    editor.putString("addressip",rtauGate.peerAddress.getAddress().getHostAddress());
                    editor.commit();
                }
                return genRTAUAnswer(ErrorCode.FPNN_EC_OK.value());
            }

        } catch (Exception e) {
            e.printStackTrace();
            return genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_METHOD.value());
        }
    }

    //------------voice add---------------//
    private RTAUAnswer auth(String token , long ts, boolean retry) {
        String sharedip = "";

        Quest qt = new Quest("auth");
        qt.param("pid", pid);
        qt.param("token", token);
        qt.param("ts", ts);
        qt.param("version", "AndroidRTAU-" + SDKVersion);

        try {
            Answer answer = rtauGate.sendQuest(qt, globalQuestTimeoutSeconds);
            if (answer  == null || answer.getErrorCode() != ErrorCode.FPNN_EC_OK.value()) {
                closeStatus();
                if (retry)
                    return test80(sharedip);
                if (answer != null && answer.getErrorMessage().indexOf("Connection open channel failed") != -1){
                    InetSocketAddress peeraddres = rtauGate.peerAddress;
                    if (peeraddres != null){
                        boolean isnetwork = isNetWorkConnected();
                        String hostname = rtauEndpoint.split(":")[0];
                        if (peeraddres.getHostString().equals(hostname) && isnetwork && addressSp != null){
                            synchronized (addressSp){
                                sharedip = addressSp.getString("addressip", "");
                            }
                            if (!sharedip.isEmpty()) {
                                rtauGate = new TCPClient(sharedip, peeraddres.getPort());
                                ConfigRtmGateClient(rtauGate);
                                return auth(token, ts,true);
                            }
                        }
                        if (!isnetwork)
                            return genRTAUAnswer(answer,"when send sync auth  failed:no network ");
                        else {
                            return genRTAUAnswer(answer, "when send sync auth  rtauGate parse endpoint " + peeraddres.getHostString());
                        }
                    }
                    else
                        return genRTAUAnswer(answer,"when send sync auth  parse address is null");
                }
                else if (answer != null && answer.getErrorCode() == FPNN_EC_CORE_INVALID_CONNECTION.value())
                {
                    return test80(sharedip);
//                    return genRTAUAnswer(answer,"when send sync auth ");
                }
                else
                    return genRTAUAnswer(answer,"when send sync auth ");

            }
            synchronized (interLocker) {
                rttGateStatus = ClientStatus.Connected;
            }
//            checkRoutineInit();
            connectionId.set(rtauGate.getConnectionId());
            synchronized (addressSp){
                SharedPreferences.Editor editor = addressSp.edit();
                editor.putString("addressip",rtauGate.peerAddress.getAddress().getHostAddress());
                editor.commit();
            }

            return genRTAUAnswer(answer);
        }
        catch (Exception  ex){
            closeStatus();
            return genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(),ex.getMessage());
        }
    }

    private void auth(final IRTAUEmptyCallback callback, final String token, final long ts, final boolean retry) {
        final Quest qt = new Quest("auth");
        qt.param("pid", pid);
        qt.param("token", token);
        qt.param("ts", ts);
        qt.param("version", "AndroidRTAU-" + SDKVersion);

        rtauGate.sendQuest(qt, new FunctionalAnswerCallback() {
            @SuppressLint("NewApi")
            @Override
            public void onAnswer(Answer answer, int errorCode) {
                    String sharedip = "";

                    if (answer == null || errorCode != ErrorCode.FPNN_EC_OK.value()) {
                        closeStatus();
                        if (retry) {
                            test80(sharedip, callback);
//                            callback.onResult(genRTAUAnswer(answer, "retry failed when send async auth "));
                            return;
                        }
                        if (answer!= null && answer.getErrorMessage().indexOf("Connection open channel failed") != -1){
                            InetSocketAddress peeraddres = rtauGate.peerAddress;
                            if (peeraddres != null){
                                boolean isnetwork = isNetWorkConnected();
                                if (rtauEndpoint.split(":") == null){
                                    callback.onResult(genRTAUAnswer( ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(), "when send async auth  failed: rtauEndpoint invalid " + rtauEndpoint));
                                    return;
                                }
                                String hostname = rtauEndpoint.split(":")[0];
                                if (peeraddres.getHostString().equals(hostname) && isnetwork && addressSp != null){
                                    synchronized (addressSp){
                                        sharedip = addressSp.getString("addressip", "");
                                    }
                                    rtauGate.peerAddress = new InetSocketAddress(sharedip, peeraddres.getPort());
                                    auth(callback, token, ts,true);
                                    return;
                                }
                                if (!isnetwork)
                                    callback.onResult(genRTAUAnswer( errorCode, "when send async auth   failed:no network "  + answer.getErrorMessage()));
                                else
                                    callback.onResult(genRTAUAnswer( errorCode, "when send async auth " + answer.getErrorMessage() + " parse address:" + peeraddres.getHostString()));
                            }
                            else
                                callback.onResult(genRTAUAnswer( errorCode, "when send async auth " + answer.getErrorMessage() + "peeraddres is null"));
                            return;
                        }
                        else
                        {
//                            test80(sharedip, callback);
                            callback.onResult(genRTAUAnswer( answer, "when send async auth " + answer.getErrorMessage()));
                            return;
                        }
                    } else {
                        synchronized (interLocker) {
                            rttGateStatus = ClientStatus.Connected;
                        }

                        synchronized (addressSp){
                            SharedPreferences.Editor editor = addressSp.edit();
                            editor.putString("addressip",rtauGate.peerAddress.getAddress().getHostAddress());
                            editor.commit();
                        }

//                        checkRoutineInit();
                        connectionId.set(rtauGate.getConnectionId());
                        callback.onResult(genRTAUAnswer(errorCode));
                    }
            }
        }, globalQuestTimeoutSeconds);
    }


    void login(final RTAUUserInterface.IRTAUEmptyCallback callback, final String token, long ts) {
        if (token ==null || token.isEmpty()){
            callback.onResult(genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(),"login failed token  is null or empty"));
            return;
        }

        String errDesc = "";
        if (rtauEndpoint == null || rtauEndpoint.isEmpty() || rtauEndpoint.lastIndexOf(':') == -1)
            errDesc = "login failed invalid rtauEndpoint:" + rtauEndpoint;
        if (pid <= 0)
            errDesc += "login failed pid is invalid:" + pid;
        if (serverPushProcessor == null)
            errDesc += "login failed RTAUMPushProcessor is null";

        if (!errDesc.equals("")) {
            callback.onResult(genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(), errDesc));
            return;
        }

            if (rttGateStatus == ClientStatus.Connected || rttGateStatus == ClientStatus.Connecting) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(genRTAUAnswer(ErrorCode.FPNN_EC_OK.value()));
                    }
                }).start();
                return;
            }
        synchronized (interLocker) {
            rttGateStatus = ClientStatus.Connecting;
        }

//        long ts = System.currentTimeMillis()/1000;
//        String realToken = ApiSecurityExample.genToken(pid, secretKey);
        this.loginToken = token;
        this.loginTs = ts;

        if (rtauGate != null) {
            rtauGate.close();
            auth(callback, token,ts, false);
        } else {
            try {
                rtauGate = TCPClient.create(rtauEndpoint);
            }
            catch (IllegalArgumentException ex){
                callback.onResult(genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(),"create rtvtgate error endpoint Illegal:" +ex.getMessage() + " :" +  rtauEndpoint ));
                return;
            }
            catch (Exception e){
                e.printStackTrace();
                String msg = "create rtvtgate error orginal error:" + e.getMessage() + " endpoint: " + rtauEndpoint;
                if (rtauGate != null)
                    msg = msg + " parse endpoint " + rtauGate.endpoint();
                callback.onResult(genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(),msg ));
                return;
            }

            closedCase = CloseType.None;
            ConfigRtmGateClient(rtauGate);
            auth(callback, token, ts, false);
        }
    }

    private  void closeStatus()
    {
        synchronized (interLocker) {
            rttGateStatus = ClientStatus.Closed;
        }
    }

    RTAUAnswer login(String token, long ts) {

        if (token == null || token.isEmpty())
            return genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(), "login failed secretKey  is null or empty");

        String errDesc = "";
        if (rtauEndpoint == null || rtauEndpoint.isEmpty() || rtauEndpoint.lastIndexOf(':') == -1)
            errDesc = " login failed invalid rtauEndpoint:" + rtauEndpoint;
        if (pid <= 0)
            errDesc += " login failed pid is invalid:" + pid;
        if (serverPushProcessor == null)
            errDesc += " login failed RTAUMPushProcessor is null";

        if (!errDesc.equals("")) {
            return genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(), errDesc);
        }

        synchronized (interLocker) {
            if (rttGateStatus == ClientStatus.Connected || rttGateStatus == ClientStatus.Connecting)
                return genRTAUAnswer(ErrorCode.FPNN_EC_OK.value());

            rttGateStatus = ClientStatus.Connecting;
        }

        this.loginToken = token;
        this.loginTs = ts;

        if (rtauGate != null) {
            rtauGate.close();
            return auth(token, ts,false);
        } else {
            try {
                rtauGate = TCPClient.create(rtauEndpoint);
            }
            catch (IllegalArgumentException ex){
                return genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(),"create rtvtgate error endpoint Illegal:" +ex.getMessage() + " :" +  rtauEndpoint );
            }
            catch (Exception e){
                String msg = "create rtvtgate error orginal error:" + e.getMessage() + " endpoint: " + rtauEndpoint;
                if (rtauGate != null)
                    msg = msg + " parse endpoint " + rtauGate.endpoint();
                return genRTAUAnswer(ErrorCode.FPNN_EC_CORE_UNKNOWN_ERROR.value(),msg );
            }

            closedCase = CloseType.None;
            ConfigRtmGateClient(rtauGate);
            return auth(token, ts, false);
        }
    }



    public void close() {
        if (isRelogin.get()) {
            return;
        }
        synchronized (interLocker) {
            initCheckThread.set(false);
            running.set(false);
            if (rttGateStatus == ClientStatus.Closed) {
                return;
            }
            rttGateStatus = ClientStatus.Closed;
        }
        if (rtauGate !=null)
            rtauGate.close();
    }
}
