package com.appdynamics.monitors.kubernetes;

import java.util.Map;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;

public class KubernetesClientSingleton {

	private static ApiClient instance;
    private static CoreV1Api coreV1Api;
    private static AppsV1Api appsV1Api;

    private KubernetesClientSingleton() {
        // Private constructor to prevent instantiation
    }

    public static synchronized ApiClient getInstance(Map<String, String> config) throws Exception {
        if (instance == null) {
            // Create the client instance
        	instance = Utilities.initClient(config);
        	 Configuration.setDefaultApiClient(instance);
        }
        return instance;
    }

    public static synchronized CoreV1Api getCoreV1ApiClient(Map<String, String> config) throws Exception {
        if (coreV1Api == null) {
            // Create the CoreV1Api client using the existing ApiClient instance
            coreV1Api = new CoreV1Api(getInstance(config));
        }
        return coreV1Api;
    }
    
    public static synchronized AppsV1Api getAppsV1ApiClient(Map<String, String> config) throws Exception {
        if (appsV1Api == null) {
            // Create the CoreV1Api client using the existing ApiClient instance
        	appsV1Api = new AppsV1Api(getInstance(config));
        }
        return appsV1Api;
    }
}
