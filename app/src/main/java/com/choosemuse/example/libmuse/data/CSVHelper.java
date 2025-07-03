package com.choosemuse.example.libmuse.data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class CSVHelper {

    private static final String secretKey = "MuseApp";
    private static final String urlStr = "https://script.google.com/macros/s/AKfycbzAkKXy-gJSh4FfTW4MPBqCZTK-1c9m05CDnmFB0D3oaIWsMShhqtknO0vAWkcOOuWo8g/exec?secret=" + secretKey;

    public static void sendCsvToGoogle(String csvContent, CsvUploadCallback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/csv");

                OutputStream os = conn.getOutputStream();
                os.write(csvContent.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.d("UPLOAD", "Response code: " + responseCode);

                InputStream responseStream = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                Log.d("CSV UPLOAD", "Response: " + response.toString());
                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onUploadSuccess(response.toString());
                });

            } catch (Exception e) {
                Log.e("CSV UPLOAD", "Error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    callback.onUploadError(e);
                });
            }
        }).start();
    }

    private static double parseOrDefault(String input, double result) {
        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException e) {
            return result;
        }
    }

    public static String eegFormat(String text) {
        return String.format(Locale.US, "%.1f", CSVHelper.parseOrDefault(text, 0.0));
    }

}
