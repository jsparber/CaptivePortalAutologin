/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.juliansparber.captiveportallogin;

import android.app.Activity;
//import android.app.LoadedApk;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.http.SslError;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.lang.InterruptedException;
import java.lang.reflect.Method;
import java.util.Random;

public class CaptivePortalLoginActivity extends Activity {
    private static final String TAG = "CaptivePortalLogin";
    private static final int SOCKET_TIMEOUT_MS = 10000;
    private static SharedPreferences sharedPref;
    private static String wifiName;
    private WebView myWebView;

    private enum Result { DISMISSED, UNWANTED, WANTED_AS_IS, REMOVE_LOGINS };

    private URL mURL;
    private Network mNetwork;
    private CaptivePortal mCaptivePortal;
    private NetworkCallback mNetworkCallback;
    private ConnectivityManager mCm;
    private boolean mLaunchBrowser = false;
    private MyWebViewClient mWebViewClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        mCm = ConnectivityManagerFrom(this);
        String url = getIntent().getStringExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL);
        //if (url == null) url = mCm.getCaptivePortalServerUrl();
        if (url == null) url = "http://connectivitycheck.gstatic.com/generate_204";
        try {
            mURL = new URL(url);
        } catch (MalformedURLException e) {
            // System misconfigured, bail out in a way that at least provides network access.
            Log.e(TAG, "Invalid captive portal URL, url=" + url);
            done(Result.WANTED_AS_IS);
        }
        mNetwork = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
        mCaptivePortal = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);

        // Also initializes proxy system properties.
        mCm.bindProcessToNetwork(mNetwork);

        // Proxy system properties must be initialized before setContentView is called because
        // setContentView initializes the WebView logic which in turn reads the system properties.
        setContentView(R.layout.activity_captive_portal_login);

        getActionBar().setDisplayShowHomeEnabled(false);

        // Exit app if Network disappears.
        final NetworkCapabilities networkCapabilities = mCm.getNetworkCapabilities(mNetwork);



        if (networkCapabilities == null) {
            //finish();
            findViewById(R.id.loading_panel).setVisibility(View.GONE);
            return;
        }

        NetworkInfo wifiInfo = mCm.getNetworkInfo(mNetwork);
        if (wifiInfo.getTypeName().equals("WIFI"))
            wifiName = wifiInfo.getExtraInfo();
        else
            wifiName = null;

        try {
            new HttpServer();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network lostNetwork) {
                if (mNetwork.equals(lostNetwork)) done(Result.UNWANTED);
            }
        };

        final NetworkRequest.Builder builder = new NetworkRequest.Builder();
        for (int transportType : getTransportTypes(networkCapabilities)) {
            builder.addTransportType(transportType);
        }

        //builder.addTransportType(networkCapabilities.TRANSPORT_WIFI);
        mCm.registerNetworkCallback(builder.build(), mNetworkCallback);


        createWebView();
        if (getSavedLogin() != null) {
            autologinRunner();
        }
        else {
            findViewById(R.id.loading_panel).setVisibility(View.GONE);
            findViewById(R.id.webview_panel).setVisibility(View.VISIBLE);
            myWebView.loadData("", "text/html", null);
        }

    }
    private void createWebView() {
        myWebView = (WebView) findViewById(R.id.webview);
        myWebView.clearCache(true);
        WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        mWebViewClient = new MyWebViewClient();
        myWebView.setWebViewClient(mWebViewClient);
        myWebView.setWebChromeClient(new MyWebChromeClient());
        // Start initial page load so WebView finishes loading proxy settings.
        // Actual load of mUrl is initiated by MyWebViewClient.
    }

    private void autologinRunner() {
        String[] pair = getSavedLogin().split(", ");
        new doRequestInBackground().execute(pair[0], pair[1]);
    }

    private class doRequestInBackground extends AsyncTask<String, Integer, Integer> {
        protected Integer doInBackground(String... url) {
            try {
                doPostRequest(url[0], url[1]);
                testForCaptivePortal(false);
                Thread.sleep(1000);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 0;
        }

        protected void onPostExecute(Integer result) {
            if (mNetworkCallback != null) {
                findViewById(R.id.loading_panel).setVisibility(View.GONE);
                findViewById(R.id.webview_panel).setVisibility(View.VISIBLE);
                myWebView.loadData("", "text/html", null);
            }
        }
    }


    private void doPostRequest(String destination, String parms) throws IOException {
        URL url = new URL(destination);
        String response = "";
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setReadTimeout(10000);
        conn.setConnectTimeout(15000);
        conn.setRequestMethod("POST");
        conn.setDoInput(true);
        conn.setDoOutput(true);

        OutputStream os = conn.getOutputStream();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(os, "UTF-8"));
        writer.write(parms);
        writer.flush();
        writer.close();
        os.close();

        conn.connect();
        int responseCode = conn.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            String line;
            BufferedReader br=new BufferedReader(new InputStreamReader(conn.getInputStream()));
            while ((line=br.readLine()) != null) {
                response+=line;
            }
        }
        else {
            response="";

        }
    }

    // Find WebView's proxy BroadcastReceiver and prompt it to read proxy system properties.
    private void setWebViewProxy() {
        /*LoadedApk loadedApk = getApplication().mLoadedApk;
        try {
            Field receiversField = LoadedApk.class.getDeclaredField("mReceivers");
            receiversField.setAccessible(true);
            ArrayMap receivers = (ArrayMap) receiversField.get(loadedApk);
            for (Object receiverMap : receivers.values()) {
                for (Object rec : ((ArrayMap) receiverMap).keySet()) {
                    Class clazz = rec.getClass();
                    if (clazz.getName().contains("ProxyChangeListener")) {
                        Method onReceiveMethod = clazz.getDeclaredMethod("onReceive", Context.class,
                                Intent.class);
                        Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
                        onReceiveMethod.invoke(rec, getApplicationContext(), intent);
                        Log.v(TAG, "Prompting WebView proxy reload.");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while setting WebView proxy: " + e);
        }
        */
    }

    private void done(Result result) {
        if (mNetworkCallback != null) {
            mCm.unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
        }
        switch (result) {
            case DISMISSED:
                if (mCaptivePortal != null)
                    mCaptivePortal.reportCaptivePortalDismissed();
                break;
            case UNWANTED:
                if (mCaptivePortal != null)
                    mCaptivePortal.ignoreNetwork();
                break;
            case WANTED_AS_IS:
                //mCaptivePortal.useNetwork();
                if (mCaptivePortal != null)
                    useNetwork();
                break;
            case REMOVE_LOGINS:
                clearSharedPref();
                break;
        }
        finish();
    }

    private static ConnectivityManager ConnectivityManagerFrom(Context context) {
        Method method = null;
        ConnectivityManager res = null;
        try {
            //method = mCaptivePortal.getClass().getMethod("useNetwork", CaptivePortal.class, boolean.class);
            method = ConnectivityManager.class.getMethod("from", Context.class);
            res = (ConnectivityManager) method.invoke(null, context);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return res;
    }

    private void useNetwork() {
        Method method = null;
        try {
            method = mCaptivePortal.getClass().getMethod("useNetwork", null);
            method.invoke(mCaptivePortal);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }
    private int[] getTransportTypes(NetworkCapabilities networkCapabilities ) {
        Method method = null;
        int res[] = new int[0];
        try {
            method = networkCapabilities.getClass().getMethod("getTransportTypes", null);
            res = (int[]) method.invoke(networkCapabilities);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return res;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.captive_portal_login, menu);
        return true;
    }

    @Override
    public void onBackPressed() {
        WebView myWebView = (WebView) findViewById(R.id.webview);
        if (myWebView.canGoBack() && mWebViewClient.allowBack()) {
            myWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_use_network) {
            done(Result.WANTED_AS_IS);
            return true;
        }
        if (id == R.id.action_do_not_use_network) {
            done(Result.UNWANTED);
            return true;
        }
        if (id == R.id.action_remove_saved_logins) {
            done(Result.REMOVE_LOGINS);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mNetworkCallback != null) {
            mCm.unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
        }
        if (mLaunchBrowser) {
            // Give time for this network to become default. After 500ms just proceed.
            for (int i = 0; i < 5; i++) {
                // TODO: This misses when mNetwork underlies a VPN.
                if (mNetwork.equals(mCm.getActiveNetwork())) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mURL.toString())));
        }
    }

    private void testForCaptivePortal(final boolean sleep) {
        new Thread(new Runnable() {
            public void run() {
                // Give time for captive portal to open.
                if (sleep) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }
                HttpURLConnection urlConnection = null;
                int httpResponseCode = 500;
                try {
                    urlConnection = (HttpURLConnection) mURL.openConnection();
                    urlConnection.setInstanceFollowRedirects(false);
                    urlConnection.setConnectTimeout(SOCKET_TIMEOUT_MS);
                    urlConnection.setReadTimeout(SOCKET_TIMEOUT_MS);
                    urlConnection.setUseCaches(false);
                    urlConnection.getInputStream();
                    httpResponseCode = urlConnection.getResponseCode();
                } catch (IOException e) {
                } finally {
                    if (urlConnection != null) urlConnection.disconnect();
                }
                if (httpResponseCode == 204) {
                    done(Result.DISMISSED);
                }
            }
        }).start();
    }

    private class MyWebViewClient extends WebViewClient {
        private static final String INTERNAL_ASSETS = "file:///android_asset/";
        private final String mBrowserBailOutToken = Long.toString(new Random().nextLong());
        // How many Android device-independent-pixels per scaled-pixel
        // dp/sp = (px/sp) / (px/dp) = (1/sp) / (1/dp)
        private final float mDpPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1,
                getResources().getDisplayMetrics()) /
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                        getResources().getDisplayMetrics());
        private int mPagesLoaded;

        // If we haven't finished cleaning up the history, don't allow going back.
        public boolean allowBack() {
            return mPagesLoaded > 1;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (url.contains(mBrowserBailOutToken)) {
                mLaunchBrowser = true;
                done(Result.WANTED_AS_IS);
                return;
            }
            // The first page load is used only to cause the WebView to
            // fetch the proxy settings.  Don't update the URL bar, and
            // don't check if the captive portal is still there.
            if (mPagesLoaded == 0) return;
            // For internally generated pages, leave URL bar listing prior URL as this is the URL
            // the page refers to.
            if (!url.startsWith(INTERNAL_ASSETS) && !url.startsWith("http://127.0.0.1"))  {
                final TextView myUrlBar = (TextView) findViewById(R.id.url_bar);
                myUrlBar.setText(url);
            }
            testForCaptivePortal(true);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mPagesLoaded++;
            if (mPagesLoaded == 1) {
                // Now that WebView has loaded at least one page we know it has read in the proxy
                // settings.  Now prompt the WebView read the Network-specific proxy settings.
                setWebViewProxy();
                // Load the real page.
                view.loadUrl(mURL.toString());
                return;
            } else if (mPagesLoaded == 2) {
                // Prevent going back to empty first page.
                view.clearHistory();
            }
            String code = "var form = document.getElementsByTagName('form');" +
                    //"alert(document.baseURI);" +
                        "if (form.length === 0) { }" +
                        "else {" +
                        "for (var i = 0; i < form.length; i++) {" +
                        "var action = escape(form[i].action);" +
                        "form[i].action = 'http://127.0.0.1:8765?android-original-action='+action;" +
                        "}}";
                //Add my password and username
                /*String code = "var form = document.getElementsByTagName('form')[0];" +
                "form.user.value = 'u.name';" +
                "form.auth_user.value = 'u.name@stud'; " +
                "form.Realm.value = 'stud';" +
                "form.auth_pass.value = 'password';";
                */
                view.loadUrl("javascript:(function() {" + code +  "})()");
            testForCaptivePortal(true);
        }

        // Convert Android device-independent-pixels (dp) to HTML size.
        private String dp(int dp) {
            // HTML px's are scaled just like dp's, so just add "px" suffix.
            return Integer.toString(dp) + "px";
        }

        // Convert Android scaled-pixels (sp) to HTML size.
        private String sp(int sp) {
            // Convert sp to dp's.
            float dp = sp * mDpPerSp;
            // Apply a scale factor to make things look right.
            dp *= 1.3;
            // Convert dp's to HTML size.
            return dp((int)dp);
        }

        // A web page consisting of a large broken lock icon to indicate SSL failure.
        private final String SSL_ERROR_HTML = "<html><head><style>" +
                "body { margin-left:" + dp(48) + "; margin-right:" + dp(48) + "; " +
                "margin-top:" + dp(96) + "; background-color:#fafafa; }" +
                "img { width:" + dp(48) + "; height:" + dp(48) + "; }" +
                "div.warn { font-size:" + sp(16) + "; margin-top:" + dp(16) + "; " +
                "           opacity:0.87; line-height:1.28; }" +
                "div.example { font-size:" + sp(14) + "; margin-top:" + dp(16) + "; " +
                "              opacity:0.54; line-height:1.21905; }" +
                "a { font-size:" + sp(14) + "; text-decoration:none; text-transform:uppercase; " +
                "    margin-top:" + dp(24) + "; display:inline-block; color:#4285F4; " +
                "    height:" + dp(48) + "; font-weight:bold; }" +
                "</style></head><body><p><img src=quantum_ic_warning_amber_96.png><br>" +
                "<div class=warn>%s</div>" +
                "<div class=example>%s</div>" +
                "<a href=%s>%s</a></body></html>";

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.w(TAG, "SSL error (error: " + error.getPrimaryError() + " host: " +
                    // Only show host to avoid leaking private info.
                    Uri.parse(error.getUrl()).getHost() + " certificate: " +
                    error.getCertificate() + "); displaying SSL warning.");
            final String html = String.format(SSL_ERROR_HTML, getString(R.string.ssl_error_warning),
                    getString(R.string.ssl_error_example), mBrowserBailOutToken,
                    getString(R.string.ssl_error_continue));
            view.loadDataWithBaseURL(INTERNAL_ASSETS, html, "text/HTML", "UTF-8", null);
        }

        @Override
        public boolean shouldOverrideUrlLoading (WebView view, String url) {
            if (url.startsWith("tel:")) {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(url)));
                return true;
            }
            return false;
        }
    }

    private class MyWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            final ProgressBar myProgressBar = (ProgressBar) findViewById(R.id.progress_bar);
            myProgressBar.setProgress(newProgress);
        }
    }

    private static String getSavedLogin(){
        if (wifiName != null)
            return sharedPref.getString(wifiName, null);
        else
            return null;
    }

    public static void saveSavedLogin(String info) {
        if (wifiName != null) {
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putString(wifiName, info);
            editor.commit();
        }
    }
    public void clearSharedPref() {
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.clear();
        editor.commit();
    }
}
