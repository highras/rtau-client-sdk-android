package com.fpnn.rtausdk;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.fpnn.sdk.ErrorCode;
import com.fpnn.sdk.FunctionalAnswerCallback;
import com.fpnn.sdk.proto.Answer;
import com.fpnn.sdk.proto.Quest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class RTAUClient extends RTAUCore {

    /**
     * 创建rtvtclient
     * @param endpoint
     * @param pid 项目id
     * @param applicationContext 应用的appContext
     */
    public static RTAUClient CreateClient(String endpoint, long pid, RTAUPushProcessor pushProcessor, Context applicationContext){
            return RTAUCenter.initRTAUClient(endpoint, pid, pushProcessor, applicationContext);
        }

    protected RTAUClient(String rtauEndpoint, long pid, RTAUPushProcessor serverPushProcessor, Context applicationContext) {
        RTAUInit(rtauEndpoint,pid, serverPushProcessor,applicationContext);
    }


    /**
     *rtvt登陆
     * @param token  验证token
     */
    public RTAUStruct.RTAUAnswer login(String token, long ts) {
        RTAUStruct.RTAUAnswer answer = super.login(token, ts);
        if (answer.errorCode == 0){
            if (voiceTimer == null){
                voiceTimer = new Timer();
                startVoiceTimer();
            }
            if (imageTimer ==null){
                imageTimer = new Timer();
                startImageTimer();
            }
        }
        return answer;
    }

    /**
     *rtvt登陆  async
     * @param token   验证token
     * @param callback  登陆结果回调

     */
    public void login(String token, long ts, RTAUUserInterface.IRTAUEmptyCallback callback) {
        super.login(new RTAUUserInterface.IRTAUEmptyCallback() {
            @Override
            public void onResult(RTAUStruct.RTAUAnswer answer) {
                if (answer.errorCode == 0){
                    if (voiceTimer == null){
                        voiceTimer = new Timer();
                        startVoiceTimer();
                    }
                    if (imageTimer ==null){
                        imageTimer = new Timer();
                        startImageTimer();
                    }
                }
                callback.onResult(answer);
            }
        }, token, ts);
    }

    /**
     *开启审核
     * @param streamId 审核流id,尽量保证唯一
     * @param attrs 为用户附加筛选用字段，最多可传5个
     * @param audioLang 音频语言(如果为视频 可为null)
     * @param audioStrategyId   音频审核策略ID(传null为默认策略)
     * @param imageStrategyId   图片审核策略ID(传null为默认策略)
     * @param callbackUrl   指定的回调地址，不传使用项目配置的回调地址
     * @param callback
     */
    public void startAudit(String streamId, List<String> attrs, String audioLang, String audioStrategyId,
                               String imageStrategyId, String callbackUrl,final RTAUUserInterface.IRTAUEmptyCallback callback){
        Quest quest = new Quest("startAudit");
        quest.param("streamId", streamId);
        quest.param("attrs", attrs);
        if (audioLang != null)
            quest.param("audioLang", audioLang);

        if (audioStrategyId!= null)
            quest.param("audioStrategyId", audioStrategyId);

        if (imageStrategyId!= null)
            quest.param("imageStrategyId", imageStrategyId);

        if (callbackUrl!= null)
            quest.param("callbackUrl", callbackUrl);

        sendQuest(quest, new FunctionalAnswerCallback() {
            @Override
            public void onAnswer(Answer answer, int errorCode) {
                if (errorCode == 0){
                    synchronized (voiceDatas){
                        VoiceModel tmpModel = new VoiceModel();
                        tmpModel.ts = 0;
                        tmpModel.voicedata = new ByteArrayOutputStream();
                        voiceDatas.put(streamId, tmpModel);
                    }
                    synchronized (imageDatas){
                        VideoModel videoModel = new VideoModel(0, null);
                        imageDatas.put(streamId, videoModel);
                    }
                }
                callback.onResult(genRTAUAnswer(answer, errorCode));
            }
        });
    }


    /**
     * 发送视频审核图像
     * @param streamId  审核流id(streamId与前面startAudit中的streamId对应)
     * @param imageData 视频帧数据
     * @param ts    发送时间戳(毫秒)
     */
    public void sendImageData(String streamId, Bitmap imageData, long ts){
        synchronized (imageDatas){
            VideoModel videoModel = imageDatas.get(streamId);
            if (videoModel != null){
                videoModel.ts = ts;
                videoModel.bitmap = imageData;
            }
            else {
                Log.e(LogTag,"streamId " + streamId + " not start");
            }
        }
    }

    /**
     * 发送语音片段 (音频数据为PCM数据，16000Hz，16bit，单声道)
     * @param streamId 审核流id(streamId与前面startAudit中的streamId对应)
     * @param voicedata 语音数据
     * @param voiceDataTs 音频帧发送时间戳(毫秒)
     */
    public void sendAudioData(String streamId, byte[] voicedata, long voiceDataTs) {
        synchronized (voiceDatas){
            try{
                VoiceModel voiceModel = voiceDatas.get(streamId);
                if (voiceModel != null){
                    voiceModel.ts = voiceDataTs;
                    voiceModel.voicedata.write(voicedata);
                }
                else{
                    Log.e(LogTag,"streamId " + streamId + " not start");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * 停止审核
     * @param streamId 审核的流id(streamId与前面startAudit中的streamId对应)
     */
    public void endAudit(String streamId){
        Quest quest = new Quest("endAudit");
        quest.param("streamId", streamId);
        sendQuestEmptyCallback(new RTAUUserInterface.IRTAUEmptyCallback() {
            @Override
            public void onResult(RTAUStruct.RTAUAnswer answer) {
                synchronized (voiceDatas){
                    voiceDatas.remove(streamId);
                    if(voiceDatas.isEmpty()){
                        stopVoiceTimers();
                    }
                }
                synchronized (imageDatas){
                    imageDatas.remove(streamId);
                    if (imageDatas.isEmpty()){
                        stopImageTimers();
                    }
                }
            }
        },quest);
    }



    /** 释放rtvtclient(释放资源,网络广播监听会持有RTAUClient对象 如果不调用RTAUClient对象会一直持有不释放)
     * 如再次使用 需要重新调用RTAUCenter.initRTAUClient
     */
    public void closeRTAU(){
        stopVoiceTimers();
        stopImageTimers();
        realClose();
        RTAUCenter.closeRTAU(getPid());
    }
}
