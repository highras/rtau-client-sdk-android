### android-rtau-sdk 使用文档
- [版本支持](#版本支持)
- [使用说明](#使用说明)
- [使用示例](#使用示例)
- [测试案例](#DEMO)

### 版本支持
- 最低支持Android版本为 (api19)

### 使用说明
- RTAU实时音视频审核需要的权限
  ~~~
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.INTERNET"/>
    ~~~

- 默认支持自动重连(请继承RTVTPushProcessor类的reloginWillStart和reloginCompleted方法)                                                                                                
- 传入的pcm音频需要16000采样率 单声道
- login的token验证可以在客户端计算也可以在自己的业务服务器计算传到客户端 具体的计算方式请参见文档


### 使用示例
 ~~~
    public class RTAUExampleQuestProcessor extends RTAUPushProcessor {
        ....//重写自己需要处理的业务接口
    }
    
    RTAUClient client = RTAUClient.CreateClient("161.189.171.91:15001", 1111111, new RTAUExampleQuestProcessor(), getContext().getApplicationContext());

    RTAUStruct.RTAUAnswer answer = client.login(loginToken, ts);
        client.startAudit(streamid, new ArrayList<String>() {{
                    add("1");
                    add("2");
                }}, "zh-CN", null, null, null,
                new RTAUUserInterface.IRTAUEmptyCallback() {
                    @Override
                    public void onResult(RTAUStruct.RTAUAnswer answer) {
                        Log.i("sdktest","rtau startAudit " + answer.getErrInfo());
                    }
                });
                    
    client.sendAudioData(streamid,origin, ts);
~~~

## 接口说明
~~~
  /**
     * 创建rtauclient
     * @param endpoint
     * @param pid 项目id
     * @param applicationContext 应用的appContext
     */
    public static RTAUClient CreateClient(String endpoint, long pid, RTAUPushProcessor pushProcessor, Context applicationContext)

    /**
     *rtau登陆
     * @param token  验证token
     */
    public RTAUStruct.RTAUAnswer login(String token, long ts) 


    /**
     *rtau登陆  async
     * @param token   验证token
     * @param callback  登陆结果回调
     */
    public void login(String token, long ts, RTAUUserInterface.IRTAUEmptyCallback callback) 


    /**
     *开启审核
     * @param streamId 审核流id,尽量保证唯一
     * @param attrs 为用户附加筛选用字段，最多可传5个
     * @param audioLang 音频语言(如果为视频 可为null)具体支持的语言请参考https://docs.ilivedata.com/audiocheck/techdoc/language/
     * @param audioStrategyId   音频审核策略ID(传null为默认策略)
     * @param imageStrategyId   图片审核策略ID(传null为默认策略)
     * @param callbackUrl   指定的回调地址，不传使用项目配置的回调地址
     * @param callback
     */
    public void startAudit(String streamId, List<String> attrs, String audioLang, String audioStrategyId,String imageStrategyId, String callbackUrl,final RTAUUserInterface.IRTAUEmptyCallback callback)

    /**
     * 发送视频审核图像
     * @param streamId  审核流id(streamId与前面startAudit中的streamId对应)
     * @param imageData 视频帧数据
     * @param ts    发送时间戳(毫秒)
     */
    public void sendImageData(String streamId, Bitmap imageData, long ts)

    /**
     * 发送语音片段 (音频数据为PCM数据，16000Hz，16bit，单声道)
     * @param streamId 审核流id(streamId与前面startAudit中的streamId对应)
     * @param voicedata 语音数据
     * @param voiceDataTs 音频帧发送时间戳(毫秒)
     */
    public void sendAudioData(String streamId, byte[] voicedata, long voiceDataTs)


    /**
     * 停止审核
     * @param streamId 审核的流id(streamId与前面startAudit中的streamId对应)
     */
    public void endAudit(String streamId)


    /** 释放rtauclient(释放资源,网络广播监听会持有RTAUClient对象 如果不调用RTAUClient对象会一直持有不释放)
     * 如再次使用 需要重新调用RTAUCenter.initRTAUClient
     */
    public void closeRTAU()
~~~
### DEMO
- 示例demo基于声网 请替换为自己声网的appid和频道access_token 
音频演示为原始音频数据 视频演示为原始视频数据