package xyz.n7mn.data;

public class NicoVideoInputData {
    private String VideoURL;
    private String AudioURL;
    private String Cookie;
    private String Proxy;

    public String getVideoURL() {
        return VideoURL;
    }

    public void setVideoURL(String videoURL) {
        VideoURL = videoURL;
    }

    public String getAudioURL() {
        return AudioURL;
    }

    public void setAudioURL(String audioURL) {
        AudioURL = audioURL;
    }

    public String getCookie() {
        return Cookie;
    }

    public void setCookie(String cookie) {
        Cookie = cookie;
    }

    public String getProxy() {
        return Proxy;
    }

    public void setProxy(String proxy) {
        Proxy = proxy;
    }
}