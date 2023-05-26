package xyz.n7mn.lib;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlSequence;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static xyz.n7mn.lib.Redis.LogRedisWrite;

public class Bilibili {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public static String getVideo(String url, String AccessCode, String host){

        System.gc();

        // Proxy読み込み
        List<String> ProxyList_video = new ArrayList<>();

        File config = new File("./config-proxy.yml");
        YamlMapping ConfigYaml = null;
        try {
            if (config.exists()){
                ConfigYaml = Yaml.createYamlInput(config).readYamlMapping();
            } else {
                System.out.println("ProxyList is Empty!!!");
                return null;
            }

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        YamlSequence list = ConfigYaml.yamlSequence("VideoProxy");
        for (int i = 0; i < list.size(); i++){
            ProxyList_video.add(list.string(i));
        }

        if (ProxyList_video.size() == 0){
            list = null;

            System.gc();

            System.out.println("ProxyList is Empty!!!");
            return null;
        }

        String VideoURL = null;

        // https://www.bilibili.com/video/BV1H24y1L7m6/

        Matcher com = Pattern.compile("bilibili\\.com").matcher(url);
        Matcher tv = Pattern.compile("bilibili\\.tv").matcher(url);
        if (com.find()){
            // IDだけにする
            String s = url.split("\\?")[0];
            String[] strings = s.split("/");
            String id = strings[strings.length - 1];
            if (id.length() == 0){
                id = strings[strings.length - 2];
            }

            //System.out.println("debug id : "+id);

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            String[] split = ProxyList_video.get(new SecureRandom().nextInt(0, ProxyList_video.size())).split(":");

            String ProxyIP = split[0];
            int ProxyPort = Integer.parseInt(split[1]);


            final OkHttpClient client = builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ProxyIP, ProxyPort))).build();
            final String HtmlText;
            Request request_html = new Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/view?bvid="+id)
                    .build();

            try {
                Response response = client.newCall(request_html).execute();
                HtmlText = response.body().string();
            } catch (IOException e) {
                e.printStackTrace();
                LogRedisWrite(AccessCode, "getURL:error","api.bilibili.com (get cid)");
                return VideoURL;
            }

            //
            Matcher matcher = Pattern.compile("\"cid\":(\\d+),").matcher(HtmlText);
            if (!matcher.find()){
                LogRedisWrite(AccessCode, "getURL:error","api.bilibili.com (not found cid)");
                return VideoURL;
            }

            String cid = matcher.group(1);

            //System.out.println(cid);

            final String ResultText;
            Request request_api = new Request.Builder()
                    .url("https://api.bilibili.com/x/player/playurl?bvid="+id+"&cid="+cid)
                    .build();

            try {
                Response response2 = client.newCall(request_api).execute();
                ResultText = response2.body().string();
            } catch (IOException e) {
                e.printStackTrace();
                LogRedisWrite(AccessCode, "getURL:error","api.bilibili.com (get videoURL)");
                return VideoURL;
            }

            Matcher matcher2 = Pattern.compile("\"url\":\"(.*)\",\"backup_url\"").matcher(ResultText);
            if (matcher2.find()){
                VideoURL = matcher2.group(1).replaceAll("\\\\u0026","&");
            }
        }
/*
        if (tv.find()){
            // https://www.bilibili.tv/en/video/4786094886751232
            String s = url.split("\\?")[0];
            String[] strings = s.split("/");
            String id = strings[strings.length - 1];
            if (id.length() == 0){
                id = strings[strings.length - 2];
            }

            if (new File("./temp/"+id+".m3u8").exists()){
               return "http:///?vi="+id+".m3u8";
            }

            //
            final String ResultText;
            Request api = new Request.Builder()
                    .url("https://api.bilibili.tv/intl/gateway/web/playurl?s_locale=en_US&platform=web&aid="+id)
                    .build();


            VideoURL = "./";

        }
*/
        //System.out.println(VideoURL);
        return VideoURL;

    }

}
