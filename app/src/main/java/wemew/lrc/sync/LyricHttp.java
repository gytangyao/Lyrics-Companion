package wemew.lrc.sync;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class LyricHttp {
    private LyricHttp() {}

    static String get(String address, String referer) throws Exception {
        return request("GET", address, referer, null, null);
    }

    /** GET that also reports the server's Date header (epoch ms, 0 when absent). */
    static String get(String address, String referer, long[] dateOutMs) throws Exception {
        return request("GET", address, referer, null, dateOutMs);
    }

    static String request(String method, String address, String referer, String body)
            throws Exception {
        return request(method, address, referer, body, null);
    }

    static String request(String method, String address, String referer, String body,
                          long[] dateOutMs) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpURLConnection connection = HttpCompat.open(address);
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(7_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Lyrics-Companion/1.0");
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            if (referer != null && !referer.isEmpty()) {
                connection.setRequestProperty("Referer", referer);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            String response = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status + " from " + address);
            }
            if (dateOutMs != null && dateOutMs.length > 0) {
                dateOutMs[0] = connection.getDate();
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            return response;
        } finally {
            connection.disconnect();
        }
    }

    static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString();
    }
}
