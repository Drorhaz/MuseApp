package com.choosemuse.example.libmuse.data;

public interface CsvUploadCallback {
    void onUploadSuccess(String response);
    void onUploadError(Exception e);
}
