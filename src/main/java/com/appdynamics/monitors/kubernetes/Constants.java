package com.appdynamics.monitors.kubernetes;

public class Constants {
    public static final String CONFIG_EVENTS_API_KEY  = "eventsApiKey";
    public static final String CONFIG_GLOBAL_ACCOUNT_NAME  ="accountName";
    public static final String METRIC_SEPARATOR  = "|";
    public static final String DEFAULT_METRIC_PREFIX_NAME  = "metricPrefix";
    public static final String METRIC_PATH_NODES  = "Nodes";
    public static final String METRIC_PATH_NAMESPACES  = "Namespaces";
    public static final String METRIC_PATH_MICRO_SERVICES  = "Microservices";
    public static final String DEFAULT_METRIC_PREFIX = "Server|Component:%s|Custom Metrics|Cluster Stats|";
    public static final String CONFIG_DASH_TEMPLATE_PATH = "dashboardTemplatePath";
    public static final String CONFIG_DASH_NAME_SUFFIX = "dashboardNameSuffix";
    public static final String CONFIG_DASH_CHECK_INTERVAL = "dashboardCheckInterval";
    public static final String CONFIG_APP_NAME = "appName";
    public static final String CONFIG_APP_TIER_NAME = "appTierName";
    public static final String CONFIG_NODE_ENTITIES = "entities";
    public static final String CONFIG_NODE_NODES = "nodes";
    public static final String CONFIG_NODE_NAMESPACES = "namespaces";
    public static final String CONFIG_EVENTS_URL = "eventsUrl";
    public static final String CONFIG_CONTROLLER_URL = "controllerUrl";
    public static final String CONFIG_CONTROLLER_API_USER = "controllerAPIUser";
    
    public static final String CONFIG_CUSTOM_TAGS = "customTags";

    public static final String CONFIG_ENTITY_TYPE = "type";
    public static final String CONFIG_ENTITY_TYPE_POD = "pod";
    public static final String CONFIG_ENTITY_TYPE_NODE = "node";
    public static final String CONFIG_ENTITY_TYPE_EVENT = "event";
    public static final String CONFIG_ENTITY_TYPE_DEPLOYMENT = "deployment";
    public static final String CONFIG_ENTITY_TYPE_DAEMON = "daemon";
    public static final String CONFIG_ENTITY_TYPE_REPLICA = "replica";
    public static final String CONFIG_ENTITY_TYPE_ENDPOINT = "endpoint";
    
    
    public static final String CONFIG_ENTITY_TYPE_POD_STATUS_MONITOR = "podStatusMonitor";
    public static final String CONFIG_ENTITY_TYPE_POD_RESOURCE_QUOTA = "podResourceQuota";
    public static final String CONFIG_ENTITY_TYPE_POD_CRASH_STATUS = "podCrashStatus";
    public static final String CONFIG_ENTITY_TYPE_NAMESPACE_QUOTA_UTILIZATION = "namespaceQuotaUtilization";
    public static final String CONFIG_ENTITY_TYPE_NOT_RUNNING_PODS_PER_NODE = "notRunningPodsPerNode";
    

    

    public static final String CONFIG_SCHEMA_DEF_POD = "podsSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_POD = "podsSchemaName";

    public static final String CONFIG_SCHEMA_DEF_NODE = "nodeSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_NODE = "nodeSchemaName";

    public static final String CONFIG_SCHEMA_DEF_EVENT = "eventsSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_EVENT = "eventsSchemaName";

    public static final String CONFIG_SCHEMA_DEF_RS = "rsSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_RS = "rsSchemaName";

    public static final String CONFIG_SCHEMA_DEF_DAEMON = "daemonSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_DAEMON = "daemonSchemaName";

    public static final String CONFIG_SCHEMA_DEF_DEPLOY = "deploySchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_DEPLOY = "deploySchemaName";

    public static final String CONFIG_SCHEMA_DEF_EP = "endpointSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_EP = "endpointSchemaName";
    
    
    public static final String CONFIG_SCHEMA_DEF_POD_CRASH_STATUS = "podsCrashStatusSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_POD_CRASH_STATUS = "podsCrashStatusSchemaName";
    
    public static final String CONFIG_SCHEMA_DEF_NOT_RUNNING_POD_COUNT = "notRunningPodCountPerNodeSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_NOT_RUNNING_POD_COUNT = "notRunningPodCountPerNodeSchemaName";
 
    public static final String CONFIG_SCHEMA_DEF_POD_RESOURCE_QUOTA = "podResourceQuotaSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_POD_RESOURCE_QUOTA = "podResourceQuotaSchemaName";
    
    
    public static final String CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION= "namespaceQuotaUtilizationSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_NAMESPACE_QUOTA_UTILIZATION = "namespaceQuotaUtilizationSchemaName";
    
    
    
    public static final String CONFIG_SCHEMA_DEF_POD_STATUS_MONITOR = "podStatusMonitorSchemaDefinition";
    public static final String CONFIG_SCHEMA_NAME_POD_STATUS_MONITOR  = "podStatusMonitorSchemaName";
    
    public static final String CONFIG_RECS_BATCH_SIZE = "batchSize";
    
    public static final String WORKER_NODE = "Worker";
    public static final String MASTER_NODE = "Master";
    public static String OPENSHIFT_VERSION="";
	public static String K8S_VERSION="";
	public static final String CLUSTER_NAME = "clusterName";
    

}
