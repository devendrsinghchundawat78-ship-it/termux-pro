package com.termux.pro.shared.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minimal HTTP client (built on {@link HttpURLConnection}) used to talk to AI providers.
 * No external dependencies are needed and every failure is turned into a friendly,
 * human readable {@link AiException}.
 */
public final class AiHttp {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024; // 2 MB safety cap

    private AiHttp() {}

    /** A simple HTTP response. */
    public static final class Response {
        public final int statusCode;
        public final String body;

        public Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public boolean isOk() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /**
     * Perform an HTTP request.
     *
     * @param method "GET" or "POST"
     * @param headers optional request headers
     * @param body optional request body (POST only)
     */
    public static Response request(@NonNull String method, @NonNull String url,
                                   @Nullable Map<String, String> headers,
                                   @Nullable String body) throws AiException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(AiConstants.HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(AiConstants.HTTP_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = connection.getResponseCode();

            InputStream stream = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

            String responseBody = "";
            if (stream != null) {
                try (InputStream in = stream) {
                    responseBody = readFully(in);
                }
            }

            return new Response(statusCode, responseBody);

        } catch (SocketTimeoutException e) {
            throw new AiException("The server took too long to respond. Please try again in a moment.");
        } catch (UnknownHostException | java.net.ConnectException e) {
            throw new AiException("Internet connection problem. Check your WiFi/mobile data and try again.");
        } catch (IOException e) {
            throw new AiException("Network error: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) break;
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
