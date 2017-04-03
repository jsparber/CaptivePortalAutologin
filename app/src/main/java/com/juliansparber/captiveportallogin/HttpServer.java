package com.juliansparber.captiveportallogin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import fi.iki.elonen.NanoHTTPD;

/**
 * Created by jSparber on 3/29/17.
 */

public class HttpServer extends NanoHTTPD {
    private static final int PORT = 8765;

    public HttpServer() throws IOException {
        super(PORT);
        start();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String msg = "<html><body><h1>Hello server</h1>\n";
        Map<String, List<String>> parms = session.getParameters();
        Map<String, String> files = new HashMap<String, String>();
        String destURL = null;
        try {
            session.parseBody(files);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ResponseException e) {
            e.printStackTrace();
        }
        try {
            destURL = getDestination(parms.get("android-original-action"));
        } catch (NullPointerException e) {
            destURL = "";

        }

        if (destURL.equals("")) {
            try {
                destURL = (splitQuery(session.getHeaders().get("referer")).get("android-original-action")) + session.getUri();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }

        if (session.getMethod() == Method.GET) {
            try {
                return doGetRequest(destURL, parms);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        else if (session.getMethod() == Method.POST) {
            try {
                return doPostRequest(destURL, parms);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return newFixedLengthResponse("<html><head></head></html>\n");
    }

    private static Map<String, String> splitQuery(String urlString) throws UnsupportedEncodingException, MalformedURLException {
        Map<String, String> query_pairs = new LinkedHashMap<String, String>();

        String query = new URL(urlString).getQuery();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            query_pairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"), URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
        }
        return query_pairs;
    }

    private String getDestination(List<String> parms) {
        return parms.get(0);
    }

    private Response doPostRequest(String destination, Map<String, List<String>> parms) throws IOException {
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
        writer.write(getQuery(parms));
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

        String pair = destination + ", " + getQuery(parms);
        CaptivePortalLoginActivity.saveSavedLogin(pair);
        Response.IStatus status = Response.Status.lookup(conn.getResponseCode());
        return newFixedLengthResponse(status, conn.getHeaderField("content-type"), response);
    }

    private Response doGetRequest(String destination, Map<String, List<String>> parms) throws IOException {
        String response = "";
        URL url = null;
        HttpURLConnection conn = null;
        try {
            url = new URL(destination);

            conn = (HttpURLConnection) url
                    .openConnection();


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
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        String path = url.getFile().substring(0, url.getFile().lastIndexOf('/'));
        String base = url.getProtocol() + "://" + url.getHost() + path;

        response = absPath(response, base);
        Response.IStatus status = Response.Status.lookup(conn.getResponseCode());
        return newFixedLengthResponse(status, conn.getHeaderField("content-type"), response);
    }

    private String absPath(String input, String baseUrl) {
        Document doc = Jsoup.parse(input);
        doc.getAllElements();
        Elements link = doc.select("link");
        link.attr("href", baseUrl + "/" + link.attr("href"));

        return doc.toString();
    }

    private String getQuery(Map<String, List<String>> params) throws UnsupportedEncodingException {
        StringBuilder result = new StringBuilder();
        boolean first = true;

        for (Entry<String, List<String>> entry : params.entrySet()) {
            {
                for (String element : entry.getValue()) {
                    if (first)
                        first = false;
                    else
                        result.append("&");

                    result.append(URLEncoder.encode((String) entry.getKey(), "UTF-8"));
                    result.append("=");
                    result.append(URLEncoder.encode((String) element, "UTF-8"));
                }
            }
        }
        return result.toString();
    }
}
