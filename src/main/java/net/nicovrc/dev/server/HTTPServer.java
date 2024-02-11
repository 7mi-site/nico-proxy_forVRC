package net.nicovrc.dev.server;

import com.google.gson.Gson;
import net.nicovrc.dev.api.*;
import net.nicovrc.dev.data.LogData;
import net.nicovrc.dev.data.ServerData;
import net.nicovrc.dev.data.UDPPacket;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HTTPServer extends Thread {
    private final int Port;

    private final CacheAPI CacheAPI;
    private final ProxyAPI ProxyAPI;
    private final ServerAPI ServerAPI;
    private final JinnnaiSystemURL_API JinnnaiAPI;
    private final ConversionAPI ConversionAPI;

    private final OkHttpClient HttpClient;

    private final Gson gson = new Gson();


    public HTTPServer(CacheAPI cacheAPI, ProxyAPI proxyAPI, ServerAPI serverAPI, JinnnaiSystemURL_API jinnnaiAPI, OkHttpClient client, int Port){
        this.Port = Port;

        this.CacheAPI = cacheAPI;
        this.ProxyAPI = proxyAPI;
        this.ServerAPI = serverAPI;
        this.JinnnaiAPI = jinnnaiAPI;
        this.ConversionAPI = new ConversionAPI(ProxyAPI);

        this.HttpClient = client;
    }

    @Override
    public void run() {
        try {
            ServerSocket svSock = new ServerSocket(Port);
            System.out.println("[Info] TCP Port "+Port+"で 処理受付用HTTPサーバー待機開始");

            final boolean[] temp = {true};
            while (temp[0]) {
                try {
                    System.gc();
                    Socket sock = svSock.accept();
                    new Thread(() -> {
                        try {
                            final InputStream in = sock.getInputStream();
                            final OutputStream out = sock.getOutputStream();

                            // リクエスト来てるなら取ってくる
                            final String httpRequest = getHttpRequest(in);
                            if (httpRequest == null){
                                in.close();
                                out.close();
                                sock.close();
                                return;
                            }

                            // HTTPバージョンがちゃんと取れているか
                            final String httpVersion = getHttpVersion(httpRequest);
                            if (httpVersion.equals("unknown")){
                                SendResult(out, "HTTP/1.1 502 Bad Gateway\nContent-Type: text/plain; charset=utf-8\n\nbad gateway");
                                in.close();
                                out.close();
                                sock.close();
                                return;
                            }


                            // 指定のURLじゃない場合はBad Requestを返す
                            final String RequestURL = getRequestURL(httpRequest);
                            if (RequestURL == null){
                                SendResult(out, "HTTP/" + httpVersion + " 400 Bad Request\nContent-Type: text/plain; charset=utf-8\n\nbad request");
                                out.close();
                                in.close();
                                sock.close();
                                return;
                            }


                            // どれだけ溜まっているかのチェック用 (check_queueは過去のverの互換用)
                            if (RequestURL.equals("check_queue") || RequestURL.equals("check_cache")) {
                                SendResult(out, "HTTP/" + httpVersion + " 200 OK\nContent-Type: application/json; charset=utf-8\n\n" + gson.toJson(CacheAPI.getList()));
                                out.close();
                                in.close();
                                sock.close();
                                return;
                            }

                            // 生死確認
                            if (RequestURL.equals("check_health")) {
                                String Result = "HTTP/" + httpVersion + " 200 OK\nContent-Type: text/plain; charset=utf-8\n\nへるすちぇっくー！ (Ver "+ ConversionAPI.getVer() +")\n\n"+ProxyAPI.getListCount()+"\n\n" + ServerAPI.getListCount() + "\n\n" + CacheAPI.getListCount();

                                out.write(Result.getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                out.close();
                                in.close();
                                sock.close();
                                return;
                            }

                            // 生死確認その2
                            if (RequestURL.startsWith("check_server=")) {
                                Matcher matcher = Pattern.compile("check_server=(.+)").matcher(RequestURL);
                                final String Result;
                                if (matcher.find()){
                                    boolean check = ServerAPI.isCheck(matcher.group(1));
                                    Result = "HTTP/" + httpVersion + " 200 OK\nContent-Type: application/json; charset=utf-8\n\n" + (check ? "OK" : "NG");
                                } else {
                                    Result = "HTTP/" + httpVersion + " 404 Not Found\nContent-Type: text/plain; charset=utf-8\n\n404";
                                }
                                SendResult(out, Result);
                                out.close();
                                in.close();
                                sock.close();
                                return;
                            }

                            // 加工用
                            //System.out.println(RequestURL);
                            String TempURL = JinnnaiAPI.replace(RequestURL);
                            //System.out.println(TempURL);

                            // 先頭がsm/nm/so/lv/数字のみの場合は先頭に「https://nico.ms/」を追加する
                            if (TempURL.startsWith("sm") || TempURL.startsWith("nm") || TempURL.startsWith("so") || TempURL.startsWith("lv") || Pattern.compile("^\\d+").matcher(TempURL).find()){
                                TempURL = "https://nico.ms/"+TempURL;
                            }
                            //System.out.println(TempURL);

                            // リダイレクト先のURLを渡す
                            final Matcher redirectUrl = Pattern.compile("(api\\.nicoad\\.nicovideo\\.jp|b23\\.tv|nico\\.ms)").matcher(TempURL);
                            if (redirectUrl.find()){
                                try {
                                    Request request = new Request.Builder()
                                            .url(TempURL)
                                            .build();
                                    Response response = HttpClient.newCall(request).execute();
                                    if (response.body() != null) {
                                        TempURL = response.request().url().toString();
                                    }
                                    response.close();
                                } catch (Exception e){
                                    e.printStackTrace();
                                }
                            }
                            //System.out.println(TempURL);

                            // 置き換え用
                            final Matcher replaceUrl = Pattern.compile("(ext|commons)\\.nicovideo\\.jp").matcher(TempURL);
                            if (replaceUrl.find()){
                                TempURL = TempURL.replaceAll("ext", "www").replaceAll("commons", "www").replaceAll("thumb", "watch").replaceAll("works", "watch");
                            }

                            // Videoモードの場合の誘導
                            if (Pattern.compile("Chrome/91").matcher(httpRequest).find()){
                                TempURL = "https://r2.7mi.site/vrc/nico/v2.mp4";
                            }
                            //System.out.println(TempURL);

                            // 処理鯖に投げるための事前準備
                            final Matcher CacheCheck = Pattern.compile("(nicovideo\\.jp|bilibili|tver\\.jp)").matcher(TempURL);
                            final boolean isCache = CacheCheck.find();

                            Matcher getTitle = Pattern.compile("(x-nicovrc-titleget: yes|user-agent: unityplayer/)").matcher(httpRequest.toLowerCase(Locale.ROOT));
                            boolean isTitleGet = getTitle.find();

                            if (isCache && !isTitleGet){
                                // キャッシュ対象の場合はキャッシュチェック
                                String cacheUrl = CacheAPI.getCache(TempURL);
                                if (cacheUrl != null && cacheUrl.equals("pre")){
                                    // キャッシュにはあるが処理中の場合 一旦待機してから内容を返す
                                    while (cacheUrl.equals("pre")){
                                        cacheUrl = CacheAPI.getCache(TempURL);
                                    }
                                    System.out.println("リクエスト (キャッシュ) : " + RequestURL + " ---> " + cacheUrl);

                                    SendResult(out,  "HTTP/" + httpVersion + " 302 Found\nLocation: " + cacheUrl + "\nDate: " + new Date() + "\n\n");
                                    out.close();
                                    in.close();
                                    sock.close();

                                    final String finalCacheUrl = cacheUrl;
                                    new Thread(()-> ConversionAPI.LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), httpRequest, "Cache", RequestURL, finalCacheUrl, null))).start();
                                    return;
                                } else if (cacheUrl != null && (cacheUrl.startsWith("http://") || cacheUrl.startsWith("https://"))){
                                    // 処理中ではなくURLが入っている場合はその結果を返す
                                    System.out.println("リクエスト (キャッシュ) : " + RequestURL + " ---> " + cacheUrl);

                                    SendResult(out, "HTTP/" + httpVersion + " 302 Found\nLocation: " + cacheUrl + "\nDate: " + new Date() + "\n\n");
                                    out.close();
                                    in.close();
                                    sock.close();

                                    final String finalCacheUrl = cacheUrl;
                                    new Thread(() -> ConversionAPI.LogWrite(new LogData(UUID.randomUUID() + "-" + new Date().getTime(), new Date(), httpRequest, "Cache", RequestURL, finalCacheUrl, null))).start();
                                    return;
                                }

                                // キャッシュない場合は処理中を表す「pre」をキャッシュリストに入れる
                                CacheAPI.setCache(TempURL, "pre", -1L);
                            }

                            HashMap<String, ServerData> list = ServerAPI.getList();
                            if (list.isEmpty()){
                                // 処理鯖が設定されていない場合は内部で処理する
                                try {
                                    //System.out.println(TempURL);
                                    //System.out.println(isTitleGet);
                                    String ResultURL = ConversionAPI.get(httpRequest, RequestURL, TempURL, isTitleGet);
                                    if (ResultURL == null){
                                        throw new Exception("Not Found");
                                    }

                                    if (!isTitleGet){
                                        System.out.println("リクエスト : " + RequestURL + " ---> " + ResultURL);
                                        SendResult(out, "HTTP/" + httpVersion + " 302 Found\nLocation: "+ ResultURL +"\nDate: " + new Date() + "\n\n");
                                    } else {
                                        System.out.println("リクエスト (タイトル取得) : " + RequestURL + " ---> " + ResultURL);
                                        SendResult(out, "HTTP/" + httpVersion + " 200 OK\nContent-Type: text/plain; charset=utf-8\n\n"+ResultURL);
                                    }
                                } catch (Exception e){
                                    SendResult(out, "HTTP/" + httpVersion + " 302 Found\nLocation: https://i2v.nicovrc.net/?url=https://nicovrc.net/php/mojimg.php?msg="+e.getMessage()+"\nDate: " + new Date() + "\n\n");

                                    if (isCache){
                                        CacheAPI.removeCache(TempURL);
                                    }
                                    System.out.println("リクエスト (エラー) : " + RequestURL + " ---> " + e.getMessage());
                                }
                            } else {
                                // 処理鯖が設定されている場合は処理鯖へ投げてその結果を返す
                                UDPPacket packet = new UDPPacket(TempURL, httpRequest, isTitleGet);
                                UDPPacket result = ServerAPI.SendServer(packet);
                                if (result.getResultURL() != null){
                                    if (isTitleGet){
                                        System.out.println("リクエスト (タイトル取得) : " + RequestURL + " ---> " + result.getResultURL());
                                        SendResult(out, "HTTP/" + httpVersion + " 200 OK\nContent-Type: text/plain; charset=utf-8\n\n"+result.getResultURL());
                                    } else {
                                        System.out.println("リクエスト : " + RequestURL + " ---> " + result.getResultURL());
                                        SendResult(out, "HTTP/" + httpVersion + " 302 Found\nLocation: "+result.getResultURL()+"\nDate: " + new Date() + "\n\n");
                                    }
                                    if (isCache){
                                        CacheAPI.setCache(TempURL, result.getResultURL(), (result.getResultURL().startsWith("https://n.nicovrc.net") || result.getResultURL().startsWith("https://b.nicovrc.net")) ? new Date().getTime() + 86400000 : -1);
                                    }
                                } else {
                                    if (isTitleGet){
                                        System.out.println("リクエスト (タイトル取得) : " + RequestURL + " ---> (null)");
                                    } else {
                                        System.out.println("リクエスト : " + RequestURL + " ---> (null)");
                                    }
                                    // 処理鯖がすべて応答を返さなかった場合
                                    SendResult(out, "HTTP/" + httpVersion + " 302 Found\nLocation: https://i2v.nicovrc.net/?url=https://nicovrc.net/php/mojimg.php?msg=Not%20Found\nDate: " + new Date() + "\n\n");
                                    if (isCache){
                                        CacheAPI.removeCache(TempURL);
                                    }
                                }
                            }

                            in.close();
                            out.close();
                            sock.close();
                        } catch (Exception e){
                            e.printStackTrace();
                            temp[0] = false;
                        }
                    }).start();
                } catch (Exception e){
                    e.printStackTrace();
                    temp[0] = false;
                }
            }
            svSock.close();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    private String getHttpRequest(InputStream in){
        try {
            byte[] data = new byte[1000000];
            int readSize = in.read(data);
            if (readSize <= 0) {
                data = null;
                return null;
            }
            data = Arrays.copyOf(data, readSize);

            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e){
            return null;
        }
    }

    public String getHttpVersion(String HttpRequest){
        Matcher matcher1 = Pattern.compile("HTTP/1\\.(\\d)").matcher(HttpRequest);
        Matcher matcher2 = Pattern.compile("HTTP/2\\.(\\d)").matcher(HttpRequest);
        if (matcher1.find()){
            return "1."+matcher1.group(1);
        }
        if (matcher2.find()){
            return "2."+matcher1.group(1);
        }

        return "unknown";
    }

    public String getRequestURL(String HttpRequest){
        Matcher requestMatch = Pattern.compile("(GET|HEAD) /\\?vi=(.*) HTTP").matcher(HttpRequest);
        if (requestMatch.find()){
            return requestMatch.group(2);
        }
        return null;
    }

    public void SendResult(OutputStream out, String Result){
        try {
            out.write(Result.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e){
            // e.printStackTrace();
        }
    }
}
