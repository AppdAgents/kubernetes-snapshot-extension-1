package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import java.net.URL;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.appdynamics.monitors.kubernetes.RestClient;

public class UploadEventsTask implements Runnable{
    private URL publishUrl;
    private String payload;
    private String accountName;
    private String apiKey;
    private String taskName;
    private Map<String, String> config;

    private static final Logger logger = LoggerFactory.getLogger(UploadEventsTask.class);
    public UploadEventsTask(String taskName, Map<String, String> config, URL url, String accountName, String apiKey, String requestBody) {
        this.publishUrl = url;
        this.payload = requestBody;
        this.accountName = accountName;
        this.apiKey = apiKey;
        this.taskName = taskName;
        this.config = config;
    }

    @Override
    public void run() {
        try {

            if(!payload.equals("[]")){
                logger.info("Task {}. Sending data to AppD events API", this.taskName);
                logger.debug("Upload task: about to push Events API: {}", payload);
                RestClient.doRequest(publishUrl, config, accountName, apiKey, payload, "POST");
            }
        }
        catch(Exception e){
            logger.error("Event upload task error", e.getMessage());
        }
    }
}
