package net.nicovrc.dev.api;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.nicovrc.dev.data.*;
import okhttp3.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import xyz.n7mn.nico_proxy.*;
import xyz.n7mn.nico_proxy.data.RequestVideoData;
import xyz.n7mn.nico_proxy.data.ResultVideoData;
import xyz.n7mn.nico_proxy.data.TokenJSON;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConversionAPI {

    private static final String ver = "2.1.3";

    private final ProxyAPI proxyAPI;
    private final String SocketIP;
    private final ConcurrentHashMap<String, LogData> LogDataList = new ConcurrentHashMap<>();
    private final List<String> ServiceURLList = new ArrayList<>();

    public ConversionAPI(ProxyAPI proxyAPI){
        this.proxyAPI = proxyAPI;
        String temp = null;
        try {
            //
            OkHttpClient client = new OkHttpClient();
            Request ip = new Request.Builder()
                    .url("https://ipinfo.io/ip")
                    .build();
            Response response = client.newCall(ip).execute();
            if (response.body() != null){
                temp = response.body().string();
            }
            response.close();
        } catch (Exception e){
            temp = null;
        }
        SocketIP = temp;

        // 1hおきにログ出力をする
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                ForceLogDataWrite();
            }
        }, 0L, 3600000L);

        // 対応サービスリスト
        ServiceURLList.add("nicovideo.jp");
        ServiceURLList.add("nico.ms");
        ServiceURLList.add("bilibili.com");
        ServiceURLList.add("bilibili.tv");
        ServiceURLList.add("youtu.be");
        ServiceURLList.add("youtube.com");
        ServiceURLList.add("xvideos.com");
        ServiceURLList.add("tiktok.com");
        ServiceURLList.add("x.com");
        ServiceURLList.add("twitter.com");
        ServiceURLList.add("openrec.tv");
        ServiceURLList.add("pornhub.com");
        ServiceURLList.add("twitcasting.tv");
        ServiceURLList.add("tver.jp");
        ServiceURLList.add("gimy.ai");
    }

    /**
     * @param HTTPRequest 生のHTTPリクエストorUDPリクエスト
     * @param RequestURL 事前変換前のURL
     * @param TempRequestURL 事前変換後のURL(陣内システムがついていたら除去したあとのURL)
     * @param isTitleGet タイトル取得する場合はtrue
     * @return 処理結果のURL
     * @throws Exception エラーメッセージ(#getMessageで取得してエラー画面に表示する想定)
     */
    public String get(String HTTPRequest, String RequestURL, String TempRequestURL, boolean isTitleGet) throws Exception {
        //System.out.println("Debug : " + TempRequestURL);
        String result = null;

        final String request;

        if (HTTPRequest == null){
            request = new Gson().toJson(new UDPServerAccessLog(RequestURL, TempRequestURL, isTitleGet));
        } else {
            request = HTTPRequest;
        }
        String ErrorMessage = null;

        ShareService Service = getService(TempRequestURL);
        String ServiceName = null;

        if (Service != null){
            ServiceName = Service.getServiceName();
        }

        if (ServiceName == null){
            ServiceName = getServiceName(TempRequestURL);
        }
        //System.out.println("Debug1-1 : " + ServiceName);

        try {
            if (Service == null){
                return null;
            }
            if (ServiceName == null){
                return null;
            }
            //System.out.println("Debug1-2 : " + ServiceName);

            final List<ProxyData> list = proxyAPI.getMainProxyList();
            final List<ProxyData> list_jp = proxyAPI.getJPProxyList();
            int main_count = list.isEmpty() ? 0 : (list.size() > 1 ? new SecureRandom().nextInt(0, list.size() - 1) : 0);
            int jp_count = list_jp.isEmpty() ? 0 : (list_jp.size() > 1 ? new SecureRandom().nextInt(0, list_jp.size() - 1) : 0);
            //System.out.println("Debug1-3 : " + ServiceName);

            final xyz.n7mn.nico_proxy.data.ProxyData proxyData = list.isEmpty() ? null : new xyz.n7mn.nico_proxy.data.ProxyData(list.get(main_count).getIP(), list.get(main_count).getPort());
            final xyz.n7mn.nico_proxy.data.ProxyData proxyData_jp = list_jp.isEmpty() ? null : new xyz.n7mn.nico_proxy.data.ProxyData(list_jp.get(jp_count).getIP(), list_jp.get(jp_count).getPort());
            boolean isUseJPProxy = false;

            final OkHttpClient.Builder builder = new OkHttpClient.Builder();
            OkHttpClient client = proxyData == null ? new OkHttpClient() : builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(list.get(main_count).getIP(), list.get(main_count).getPort()))).build();

            Request img = new Request.Builder()
                    .url(TempRequestURL)
                    .build();
            Response response = client.newCall(img).execute();
            if (response.body() != null){
                String temp = response.body().string();
                //System.out.println(temp);
                isUseJPProxy = Pattern.compile("fail-message").matcher(temp).find();
            }
            response.close();
            //System.out.println(isUseJPProxy);
            //System.out.println(proxyData_jp.getProxyIP());

            if (isTitleGet){
                return Service.getTitle(new RequestVideoData(TempRequestURL, isUseJPProxy ? proxyData_jp : proxyData));
            }

            //System.out.println("Debug3 : "+TempRequestURL);
            if (!Pattern.compile("\\?v=").matcher(TempRequestURL).find()){
                TempRequestURL = TempRequestURL.split("\\?")[0];
            } else {
                Matcher matcher = Pattern.compile("\\?v=(.+)").matcher(TempRequestURL);
                if (matcher.find()){
                    TempRequestURL = "https://nico.ms/"+matcher.group(1);
                }
            }

            ResultVideoData video = null;
            //System.out.println("debug : " + TempRequestURL);
            //System.out.println("debug : " + ServiceName);
            if (ServiceName.equals("ニコニコ動画")){
                if (Pattern.compile("sm|nm").matcher(TempRequestURL).find()){
                    // 通常動画
                    video = Service.getVideo(new RequestVideoData(TempRequestURL, isUseJPProxy ? proxyData_jp : proxyData));
                } else if (Pattern.compile("so").matcher(TempRequestURL).find()){
                    // 公式動画 or 配信
                    try {
                        video = Service.getVideo(new RequestVideoData(TempRequestURL, isUseJPProxy ? proxyData_jp : proxyData));
                    } catch (Exception e){
                        if (e.getMessage().equals("www.nicovideo.jp Not Found")){
                            try {
                                video = Service.getLive(new RequestVideoData(TempRequestURL, isUseJPProxy ? proxyData_jp : proxyData));
                            } catch (Exception ex){
                                if (ex.getMessage().equals("live.nicovideo.jp No WebSocket Found")){
                                    throw new Exception("対応していない動画または配信です");
                                } else {
                                    throw e;
                                }
                            }
                        } else {
                            throw e;
                        }
                    }
                } else {
                    // 配信
                    try {
                        video = Service.getLive(new RequestVideoData(TempRequestURL, proxyData));
                    } catch (Exception e){
                        video = Service.getLive(new RequestVideoData(TempRequestURL, proxyData_jp));
                    }
                }

                if (Pattern.compile("dmc\\.nico").matcher(video.getVideoURL()).find()){
                    if (!video.isStream()) {
                        final String JsonData = video.getTokenJson();
                        String finalTempRequestURL = TempRequestURL;
                        new Thread(() -> {
                            try {
                                // ハートビート信号
                                Request request_html = new Request.Builder()
                                        .url(finalTempRequestURL)
                                        .build();
                                Response response1 = client.newCall(request_html).execute();
                                String HtmlText;
                                if (response1.body() != null) {
                                    HtmlText = response1.body().string();
                                } else {
                                    HtmlText = "";
                                }
                                response1.close();

                                Matcher matcher_video = Pattern.compile("<meta property=\"video:duration\" content=\"(\\d+)\">").matcher(HtmlText);

                                final long videoTime;
                                if (matcher_video.find()) {
                                    videoTime = Long.parseLong(matcher_video.group(1));
                                } else {
                                    videoTime = 3600L;
                                }

                                TokenJSON json = new Gson().fromJson(JsonData, TokenJSON.class);

                                Timer timer = new Timer();
                                int[] count = new int[]{0};
                                timer.scheduleAtFixedRate(new TimerTask() {
                                    @Override
                                    public void run() {
                                        if (count[0] > (videoTime / 40L)) {
                                            timer.cancel();
                                            return;
                                        }

                                        RequestBody body = RequestBody.create(json.getTokenValue(), MediaType.get("application/json; charset=utf-8"));
                                        Request request1 = new Request.Builder()
                                                .url(json.getTokenSendURL())
                                                .post(body)
                                                .build();
                                        try {
                                            Response response1 = client.newCall(request1).execute();
                                            //System.out.println(response.body().string());
                                            response1.close();
                                        } catch (IOException e) {
                                            // e.printStackTrace();
                                            count[0]++;
                                            return;
                                        }

                                        count[0]++;
                                    }
                                }, 0L, 40000L);
                            } catch (Exception e) {
                                //e.printStackTrace();
                            }
                        }).start();
                    }

                    final ResultVideoData finalVideo = video;
                    new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, finalVideo.getVideoURL(), null))).start();
                    System.gc();
                    return video.getVideoURL();
                }


                NicoVideoInputData nicoVideoInputData = new NicoVideoInputData();
                nicoVideoInputData.setVideoURL(video.getVideoURL());
                nicoVideoInputData.setAudioURL(video.getAudioURL());
                nicoVideoInputData.setCookie(video.getTokenJson());

                if (proxyData != null && !isUseJPProxy){
                    nicoVideoInputData.setProxy(proxyData.getProxyIP() + ":" + proxyData.getPort());
                }
                if (proxyData_jp != null && isUseJPProxy){
                    nicoVideoInputData.setProxy(proxyData_jp.getProxyIP() + ":" + proxyData_jp.getPort());
                }

                String jsonText = new Gson().toJson(nicoVideoInputData);
                String SystemIP = "";
                try {
                    YamlMapping mapping = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();
                    SystemIP = mapping.string("NicoVideoSystem");
                } catch (Exception e) {
                    //e.printStackTrace();
                }
                //System.out.println("Debug : "+SystemIP);

                Socket sock = new Socket(SystemIP, 25250);
                sock.setSoTimeout(4000);
                OutputStream outputStream = sock.getOutputStream();
                InputStream inputStream = sock.getInputStream();
                outputStream.write(jsonText.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                byte[] bytes = inputStream.readAllBytes();
                sock.close();

                new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, new String(bytes, StandardCharsets.UTF_8), null))).start();
                System.gc();
                return new String(bytes, StandardCharsets.UTF_8);
            }

            if (ServiceName.equals("bilibili.com") || ServiceName.equals("bilibili.tv")){
                video = Service.getVideo(new RequestVideoData(TempRequestURL, isUseJPProxy ? proxyData_jp : proxyData));

                String bilibiliSystem = "";
                try {
                    YamlMapping mapping = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();
                    bilibiliSystem = mapping.string("BiliBiliSystemIP");
                } catch (Exception e) {
                    //e.printStackTrace();
                }

                if (!bilibiliSystem.isEmpty()) {
                    BiliBiliInputData jsonText = new BiliBiliInputData();
                    jsonText.setSiteType(ServiceName.split("\\.")[1]);
                    jsonText.setVideoURL(video.getVideoURL());
                    jsonText.setAudioURL(video.getAudioURL());
                    if (proxyData != null && !isUseJPProxy){
                        jsonText.setProxy(proxyData.getProxyIP() + ":" + proxyData.getPort());
                    }
                    if (proxyData_jp != null && isUseJPProxy){
                        jsonText.setProxy(proxyData_jp.getProxyIP() + ":" + proxyData_jp.getPort());
                    }

                    if (jsonText.getSiteType().equals("com")) {
                        long duration = 0;
                        Request request_html = new Request.Builder()
                                .url(TempRequestURL)
                                .build();
                        Response response1 = client.newCall(request_html).execute();
                        if (response1.body() != null) {
                            Matcher matcher = Pattern.compile("\"dash\":\\{\"duration\":(\\d+)").matcher(response1.body().string());
                            if (matcher.find()) {
                                duration = Long.parseLong(matcher.group(1));
                            }
                        }
                        response1.close();
                        jsonText.setVideoDuration(duration);
                    }

                    String json = new Gson().toJson(jsonText);
                    Socket sock = new Socket(bilibiliSystem, 28279);
                    sock.setSoTimeout(4000);
                    OutputStream outputStream = sock.getOutputStream();
                    InputStream inputStream = sock.getInputStream();
                    outputStream.write(json.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    byte[] bytes = inputStream.readAllBytes();
                    final String url = new String(bytes, StandardCharsets.UTF_8);

                    new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, url, null))).start();
                    System.gc();
                    return url;
                }
            }

            // xvideos / TikTok / Twitter / Pornhub / Ameba / TVer
            if (ServiceName.equals("XVIDEOS.com") || ServiceName.equals("TikTok") || ServiceName.equals("Twitter") || ServiceName.equals("Pornhub") || ServiceName.equals("Abema") || ServiceName.equals("TVer") || ServiceName.equals("Gimy 劇迷")){
                if (ServiceName.equals("Abema") && Pattern.compile("https://abema\\.tv/now-on-air/(.+)").matcher(TempRequestURL).find()){
                    video = Service.getLive(new RequestVideoData(TempRequestURL, proxyData_jp));

                    ResultVideoData finalVideo1 = video;
                    new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, finalVideo1.getVideoURL(), null))).start();
                } else if (ServiceName.equals("TVer") && Pattern.compile("https://tver\\.jp/live/(.+)").matcher(TempRequestURL).find()) {
                    video = Service.getLive(new RequestVideoData(TempRequestURL, proxyData_jp));

                    ResultVideoData finalVideo1 = video;
                    new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, finalVideo1.getVideoURL(), null))).start();
                } else {
                    video = Service.getVideo(new RequestVideoData(TempRequestURL, proxyData));

                    ResultVideoData finalVideo1 = video;
                    new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, finalVideo1.getVideoURL(), null))).start();
                }



                if (ServiceName.equals("Twitter")) {
                    System.gc();
                    return video.getVideoURL().split("\\?")[0];
                } else {
                    System.gc();
                    return video.getVideoURL();
                }
            }

            if (ServiceName.equals("ツイキャス")){
                video = Service.getLive(new RequestVideoData(TempRequestURL, proxyData));

                if (video.isStream()) {
                    ResultVideoData finalVideo1 = video;
                    new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, finalVideo1.getVideoURL(), null))).start();

                    return video.getVideoURL();
                } else {
                    // アーカイブはReferer付きじゃないとアクセスできないので
                    Request twicast = new Request.Builder()
                            .url(video.getVideoURL())
                            .addHeader("Referer", "https://twitcasting.tv/")
                            .build();

                    Response response_twicast = client.newCall(twicast).execute();

                    if (response_twicast.code() == 200) {
                        if (response_twicast.body() != null) {

                            Matcher tempUrl = Pattern.compile("https://(.+)/tc.vod.v2").matcher(video.getVideoURL());

                            String baseUrl = "";
                            if (tempUrl.find()) {
                                baseUrl = "https://" + tempUrl.group(1);
                            }

                            String str = response_twicast.body().string();
                            for (String s : str.split("\n")) {
                                if (s.startsWith("#")) {
                                    continue;
                                }

                                String s1 = baseUrl + s;
                                new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, s1, null))).start();

                                return s1;
                            }
                        }
                    }
                    response_twicast.close();
                }
            }

            // OPENREC
            if (ServiceName.equals("Openrec")){
                try {
                    video = Service.getVideo(new RequestVideoData(TempRequestURL, isUseJPProxy ? proxyData_jp : proxyData));
                } catch (Exception e){
                    video = Service.getLive(new RequestVideoData(TempRequestURL, isUseJPProxy ? proxyData_jp : proxyData));
                }

                ResultVideoData finalVideo3 = video;
                new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, finalVideo3.getVideoURL().replaceAll("d3cfw2mckicdfw\\.cloudfront\\.net", "o.nicovrc.net"), null))).start();
                return video.getVideoURL().replaceAll("d3cfw2mckicdfw\\.cloudfront\\.net", "o.nicovrc.net");
            }

            if (ServiceName.equals("画像") || ServiceName.equals("動画")){
                video = Service.getVideo(new RequestVideoData(TempRequestURL, null));

                ResultVideoData finalVideo2 = video;
                new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, finalVideo2.getVideoURL(), null))).start();
                return video.getVideoURL();
            }

            // Youtube
            if (ServiceName.equals("Youtube")){
                new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, "https://yt.8uro.net/r?v="+RequestURL, null))).start();
                return "https://yt.8uro.net/r?v="+RequestURL;
            }

            new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, null, null))).start();


        } catch (Exception e){
            ErrorMessage = ServiceName + " : " + e.getMessage();
            //e.printStackTrace();


            final String finalErrorMessage = ErrorMessage;
            new Thread(() -> LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), request, SocketIP, RequestURL, null, finalErrorMessage))).start();
            throw new Exception(ErrorMessage);
        }


        System.gc();
        return result;
    }

    /**
     * @param RequestURL 事前処理前URL
     * @param TempRequestURL 事前処理後URL
     * @param isTitleGet タイトル取得する場合はtrue
     * @return 処理後のURL
     * @throws Exception エラーメッセージ
     */
    public String get(String RequestURL, String TempRequestURL, boolean isTitleGet) throws Exception{
        return get(null, RequestURL, TempRequestURL, isTitleGet);
    }

    /**
     * @return 処理プログラムのバージョン
     */
    public static String getVer(){
        return ver;
    }

    /**
     * @param data ログデータ
     */
    public void LogWrite(LogData data){
        //System.out.println(data.getRequestIP());
        LogDataList.put(data.getLogID(), data);
    }

    /**
     * @param URL 処理するURL
     * @return 対応する処理サービスのオブジェクト
     */

    private ShareService getService(String URL){

        Matcher matcher_NicoVideoURL = Pattern.compile("(nico\\.ms|nicovideo\\.jp)").matcher(URL);
        Matcher matcher_BilibiliComURL = Pattern.compile("bilibili\\.com").matcher(URL);
        Matcher matcher_BilibiliTvURL = Pattern.compile("bilibili\\.tv").matcher(URL);
        Matcher matcher_YoutubeURL = Pattern.compile("(youtu\\.be|youtube\\.com)").matcher(URL);
        Matcher matcher_XvideoURL = Pattern.compile("xvideos\\.com").matcher(URL);
        Matcher matcher_TikTokURL = Pattern.compile("tiktok\\.com").matcher(URL);
        Matcher matcher_TwitterURL = Pattern.compile("(x|twitter)\\.com/(.*)/status/(.*)").matcher(URL);
        Matcher matcher_OpenrecURL = Pattern.compile("openrec\\.tv").matcher(URL);
        Matcher matcher_PornhubURL = Pattern.compile("pornhub\\.com").matcher(URL);
        Matcher matcher_TwicastURL = Pattern.compile("twitcasting\\.tv").matcher(URL);
        Matcher matcher_AbemaURL = Pattern.compile("abema\\.tv").matcher(URL);
        Matcher matcher_TVerURL = Pattern.compile("tver\\.jp").matcher(URL);
        Matcher matcher_GimyURL = Pattern.compile("gimy\\.ai").matcher(URL);

        if (matcher_NicoVideoURL.find()){
            return new NicoNicoVideo();
        }

        if (matcher_BilibiliComURL.find()){
            return new BilibiliCom();
        }

        if (matcher_BilibiliTvURL.find()){
            return new BilibiliTv();
        }

        if (matcher_YoutubeURL.find()){
            return new Youtube();
        }

        if (matcher_XvideoURL.find()){
            return new Xvideos();
        }

        if (matcher_TikTokURL.find()){
            return new TikTok();
        }

        if (matcher_TwitterURL.find()){
            return new Twitter();
        }

        if (matcher_OpenrecURL.find()){
            return new OPENREC();
        }

        if (matcher_PornhubURL.find()){
            return new Pornhub();
        }

        if (matcher_TwicastURL.find()){
            try {
                final YamlMapping yamlMapping = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();
                return new Twicast(yamlMapping.string("ClientID"), yamlMapping.string("ClientSecret"));
            } catch (Exception e){
                return new Twicast("", "");
            }
        }

        if (matcher_AbemaURL.find()){
            return new Abema();
        }

        if (matcher_TVerURL.find()){
            return new TVer();
        }

        try {
            List<ProxyData> list = proxyAPI.getMainProxyList();
            int i = list.isEmpty() ? 0 : new SecureRandom().nextInt(0, list.size() - 1);

            final OkHttpClient client;
            if (list.isEmpty()){
                client = new OkHttpClient();
            } else {
                final OkHttpClient.Builder builder = new OkHttpClient.Builder();
                client = builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(list.get(i).getIP(), list.get(i).getPort()))).build();
            }

            Request html = new Request.Builder()
                    .url(URL)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0 nicovrc.net/2.0")
                    .build();
            Response response = client.newCall(html).execute();
            if (response.body() != null && response.body().contentType().toString().startsWith("image")) {
                response.close();
                return new Image();
            } else if (response.body() != null && response.body().contentType().toString().startsWith("video")) {
                response.close();
                return new Video();
            }
            response.close();
        } catch (Exception e){
            // e.printStackTrace();
        }

        if (matcher_GimyURL.find()){
            return new Gimy();
        }

        return null;

    }

    /**
     * @return ログデータのキューの件数
     */
    public int getLogDataListCount() {
        return LogDataList.size();
    }

    /**
     * キューを強制的に書き出す
     */
    public void ForceLogDataWrite() {
        File file = new File("./log-write-lock.txt");
        if (file.exists()){
            return;
        }
        try {
            file.createNewFile();
        } catch (Exception e){
            //e.printStackTrace();
        }

        HashMap<String, LogData> map = new HashMap<>(LogDataList);
        LogDataList.clear();

        if (map.isEmpty()){
            file.delete();
            return;
        }

        boolean isRedis1;
        try {
            YamlMapping input = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();
            isRedis1 = input.string("LogToRedis").toLowerCase(Locale.ROOT).equals("true");
        } catch (IOException e) {
            // e.printStackTrace();
            isRedis1 = false;
        }

        final boolean isRedis = isRedis1;
        final File fiFile = file;

        new Thread(()->{
            System.out.println("[Info] Log Write Start (Count : " + map.size() + ")");
            map.forEach((id, content)->{
                try {
                    if (isRedis){
                        RedisWrite(content);
                    } else {
                        FileWrite(content);
                    }
                } catch (Exception e){
                    LogDataList.put(id, content);
                }
            });
            System.out.println("[Info] Log Write End ("+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) +")");
            map.clear();
            fiFile.delete();
        }).start();

    }

    /**
     * @param URL 処理したいURL
     * @return サービスの名前
     */
    private String getServiceName(String URL){
        Matcher matcher_YoutubeURL = Pattern.compile("(youtu\\.be|youtube\\.com)").matcher(URL);
        Matcher matcher_TVerURL = Pattern.compile("tver\\.jp").matcher(URL);

        if (matcher_YoutubeURL.find()){
            return "Youtube";
        }
        if (matcher_TVerURL.find()){
            return "TVer";
        }

        return null;
    }

    /**
     *
     * @return 対応サービスURLリスト
     */
    public List<String> getServiceURLList() {
        return ServiceURLList;
    }

    private void RedisWrite(LogData logData) throws Exception {
        YamlMapping input = Yaml.createYamlInput(new File("./config.yml")).readYamlMapping();

        JedisPool jedisPool = new JedisPool(input.string("RedisServer"), input.integer("RedisPort"));
        Jedis jedis = jedisPool.getResource();
        if (!input.string("RedisPass").isEmpty()){
            jedis.auth(input.string("RedisPass"));
        }

        boolean isFound = jedis.get(logData.getLogID()) != null;
        while (isFound){
            logData.setLogID(UUID.randomUUID() + "-" + new Date().getTime());

            isFound = jedis.get(logData.getLogID()) != null;
            try {
                Thread.sleep(500L);
            } catch (Exception e){
                isFound = false;
            }
        }

        jedis.set("nico-proxy:ExecuteLog:"+logData.getLogID(), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(logData));

        jedis.close();
        jedisPool.close();
    }

    private void FileWrite(LogData logData) throws Exception {
        File logFolder = new File("./log");
        if (!logFolder.exists()){
            if (!new File("./log").mkdir()){
                throw new Exception("Folder Not Created");
            }
        }
        if (logFolder.isFile()){
            if (!logFolder.delete() || !logFolder.mkdir()){
                throw new Exception("Folder Not Created");
            }
        }

        File file = new File("./log/" + logData.getLogID() + ".json");
        boolean isFound = file.exists();
        while (isFound){
            logData.setLogID(UUID.randomUUID() + "-" + new Date().getTime());

            file = new File("./log/" + logData.getLogID() + ".json");
            isFound = file.exists();
            try {
                Thread.sleep(500L);
            } catch (Exception e){
                isFound = false;
            }
        }

        if (!file.createNewFile()){
            throw new Exception("File Not Created");
        }
        PrintWriter writer = new PrintWriter(file);
        writer.print(new Gson().toJson(logData));
        writer.close();

    }
}
