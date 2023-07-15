package com.appdynamics.monitors.kubernetes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1PodList;

public class Globals {

    public static OffsetDateTime lastElementTimestamp = null;
    public static OffsetDateTime previousRunTimestamp = null;
    public static String lastElementSelfLink = "";
    public static String previousRunSelfLink = "";

    public static long lastDashboardCheck = 0;
    
    public static Map<String, String> NODE_ROLE_MAP = new HashMap<String, String>();
    public static V1PodList K8S_POD_LIST=null;

    
}
