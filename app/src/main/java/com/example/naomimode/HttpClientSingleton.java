package com.example.naomimode;

import okhttp3.OkHttpClient;

public class HttpClientSingleton {
    private static OkHttpClient instance;

    public static OkHttpClient getInstance() {
        if (instance == null) {
            synchronized (HttpClientSingleton.class) {
                if (instance == null) {
                    instance = new OkHttpClient.Builder()
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return instance;
    }
}
