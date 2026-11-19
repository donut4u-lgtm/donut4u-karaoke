
private void playYouTube(String videoId) {

    if (videoId == null || videoId.trim().isEmpty()) {
        return;
    }

    String safeVideoId = videoId.trim();

    if (!safeVideoId.matches("[A-Za-z0-9_-]{6,20}")) {
        return;
    }

    if (currentStage == 1) {
        playStage1YouTube(safeVideoId);
    } else {
        playYouTubeLegacy(safeVideoId);
    }
}

private void playStage1YouTube(String videoId) {

    currentVideoId = videoId;
    currentMediaUrl = "";
    videoLoaded = false;

    destroyVideoView();

    videoView = new WebView(this);
    videoView.setBackgroundColor(BLACK);

    WebSettings settings = videoView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setSupportZoom(false);
    settings.setBuiltInZoomControls(false);
    settings.setDisplayZoomControls(false);

    videoView.setWebChromeClient(new WebChromeClient());
    videoView.setWebViewClient(new WebViewClient());

    videoContainer.addView(
            videoView,
            new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            )
    );

    String html =
            "<!doctype html>"
            + "<html><head>"
            + "<meta name='viewport' content='width=device-width,"
            + "initial-scale=1,maximum-scale=1'>"
            + "<meta name='referrer' "
            + "content='strict-origin-when-cross-origin'>"
            + "<style>"
            + "html,body{margin:0;padding:0;background:#000;"
            + "width:100%;height:100%;overflow:hidden;}"
            + "#player{position:absolute;inset:0;width:100%;"
            + "height:100%;}"
            + "</style>"
            + "</head><body>"
            + "<div id='player'></div>"
            + "<script>"
            + "var donutPlayer;"
            + "function onYouTubeIframeAPIReady(){"
            + "donutPlayer=new YT.Player('player',{"
            + "width:'100%',"
            + "height:'100%',"
            + "videoId:'" + videoId + "',"
            + "playerVars:{"
            + "playsinline:1,"
            + "autoplay:1,"
            + "controls:1,"
            + "rel:0,"
            + "modestbranding:1,"
            + "enablejsapi:1,"
            + "origin:'" + YOUTUBE_APP_ORIGIN + "'"
            + "},"
            + "events:{"
            + "onReady:function(event){"
            + "event.target.playVideo();"
            + "},"
            + "onError:function(event){"
            + "console.error('Stage 1 YouTube error: '+event.data);"
            + "}"
            + "}"
            + "});"
            + "}"
            + "</script>"
            + "<script src='https://www.youtube.com/iframe_api'></script>"
            + "</body></html>";

    videoView.loadDataWithBaseURL(
            YOUTUBE_APP_BASE_URL,
            html,
            "text/html",
            "UTF-8",
            null
    );

    videoLoaded = true;
}

private void playYouTubeLegacy(String videoId) {

    currentVideoId = videoId;
    currentMediaUrl = "";
    videoLoaded = false;

    destroyVideoView();

    videoView = new WebView(this);
    videoView.setBackgroundColor(BLACK);

    WebSettings settings = videoView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setSupportZoom(false);
    settings.setBuiltInZoomControls(false);
    settings.setDisplayZoomControls(false);

    videoView.setWebChromeClient(new WebChromeClient());
    videoView.setWebViewClient(new WebViewClient());

    videoContainer.addView(
            videoView,
            new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            )
    );

    String html =
            "<!doctype html><html><head>"
            + "<meta name='viewport' content='width=device-width,"
            + "initial-scale=1,maximum-scale=1'>"
            + "<meta name='referrer' "
            + "content='strict-origin-when-cross-origin'>"
            + "<style>"
            + "html,body{margin:0;padding:0;background:#000;"
            + "width:100%;height:100%;overflow:hidden;}"
            + "iframe{position:absolute;inset:0;width:100%;"
            + "height:100%;border:0;}"
            + "</style></head><body>"
            + "<iframe id='player' "
            + "src='https://www.youtube.com/embed/"
            + videoId
            + "?enablejsapi=1&playsinline=1&controls=1"
            + "&rel=0&autoplay=1"
            + "&origin=https://com.donut4u.karaoke' "
            + "referrerpolicy='strict-origin' "
            + "allow='autoplay; encrypted-media; picture-in-picture' "
            + "allowfullscreen></iframe>"
            + "</body></html>";

    videoView.loadDataWithBaseURL(
            YOUTUBE_APP_BASE_URL,
            html,
            "text/html",
            "UTF-8",
            null
    );

    videoLoaded = true;
}
