package com.appdynamics.monitors.kubernetes;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_DASH_CHECK_INTERVAL;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_DAEMON;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_DEPLOYMENT;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_ENDPOINT;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_EVENT;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_NAMESPACE_QUOTA_UTILIZATION;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_NODE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_NOT_RUNNING_PODS_PER_NODE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_POD;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_POD_CRASH_STATUS;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_POD_RESOURCE_QUOTA;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_POD_STATUS_MONITOR;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_REPLICA;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE_POD_METRICS;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_NODE_ENTITIES;
import static com.appdynamics.monitors.kubernetes.Constants.DEFAULT_METRIC_PREFIX;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.appdynamics.extensions.ABaseMonitor;
import com.appdynamics.extensions.TasksExecutionServiceProvider;
import com.appdynamics.extensions.util.AssertUtils;
import com.appdynamics.monitors.kubernetes.Dashboard.ClusterDashboardGenerator;
import com.appdynamics.monitors.kubernetes.Models.AppDMetricObj;
import com.appdynamics.monitors.kubernetes.Models.SummaryObj;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.DaemonSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.DeploymentSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.EndpointSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.EventSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.NamespacePodMetricsSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.NamespaceQuotaUtilizationSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.NodeSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.NodeWiseNotRunningPodsSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.PodCrashStatusSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.PodResourceQuotaSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.PodSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.PodStatusMonitorSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.ReplicaSnapshotRunner;
import com.appdynamics.monitors.kubernetes.SnapshotTasks.SnapshotRunnerBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;




@SuppressWarnings("WeakerAccess")
public class KubernetesSnapshotExtension extends ABaseMonitor {


    private static final Logger logger = LoggerFactory.getLogger(KubernetesSnapshotExtension.class);
    private static final String [] TASKS = new String[]{
            CONFIG_ENTITY_TYPE_POD,
            CONFIG_ENTITY_TYPE_NODE,
            CONFIG_ENTITY_TYPE_EVENT,
            CONFIG_ENTITY_TYPE_DEPLOYMENT,
            CONFIG_ENTITY_TYPE_DAEMON,
            CONFIG_ENTITY_TYPE_ENDPOINT,
            CONFIG_ENTITY_TYPE_REPLICA,
            CONFIG_ENTITY_TYPE_POD_STATUS_MONITOR,
            CONFIG_ENTITY_TYPE_POD_RESOURCE_QUOTA,
            CONFIG_ENTITY_TYPE_POD_CRASH_STATUS,
            CONFIG_ENTITY_TYPE_NAMESPACE_QUOTA_UTILIZATION,
            CONFIG_ENTITY_TYPE_NOT_RUNNING_PODS_PER_NODE,
    		CONFIG_ENTITY_TYPE_POD_METRICS
            
    };

    private CountDownLatch latch;
    public KubernetesSnapshotExtension() { logger.info(String.format("Using Kubernetes Snapshot Extension Version [%s]", getImplementationVersion())); }


    @Override
    protected String getDefaultMetricPrefix() {
        return DEFAULT_METRIC_PREFIX;
    }

    @Override
    public String getMonitorName() {
        return "Kubernetes State Monitor";
    }

    @Override
    protected void doRun(TasksExecutionServiceProvider tasksExecutionServiceProvider) {
        try{
            long start = new Date().getTime();
            logger.info("Taking cluster snapshot");
            Constants.OPENSHIFT_VERSION= Utilities.getOpenShiftVersion();
            Constants.K8S_VERSION= Utilities.getKubernetesVersion();
            Map<String, String> config = (Map<String, String>)configuration.getConfigYml();
            Globals.K8S_POD_LIST=Utilities.getPodsFromKubernetes(config);
            //populate Tier ID and cache of searched
            if (initClusterMonitoring(config)) {
                ArrayList<SnapshotRunnerBase> tasks = new ArrayList<SnapshotRunnerBase>();
                List<Map<String, String>> entities = (List<Map<String, String>>) configuration.getConfigYml().get(CONFIG_NODE_ENTITIES);
                if (entities != null) {
                    logger.info("Requested entities: {}", entities.toString());
                    int count = entities.size();
                    latch = new CountDownLatch(count);

                    for (String taskName : TASKS) {
                        Map<String, String> taskConfig = Utilities.getEntityConfig(entities, taskName);
                        if (taskConfig != null) {
                            tasks.add(initTask(tasksExecutionServiceProvider, taskConfig, taskName));
                        }
                    }

                    for (SnapshotRunnerBase task : tasks) {
                        executeSnapshotTask(tasksExecutionServiceProvider, task);
                    }

                    try {
                        logger.info("Waiting for tasks to complete");
                        latch.await();
                    } catch (InterruptedException ex) {
                        logger.error("Snapshot execution is interrupted", ex.toString());
                    }


                    long finish = new Date().getTime();
                    long duration = finish - start;
                    logger.info("All tasks complete {} millisec. Checking the dashboard", duration);
                    //check dashboard
                    //if does not exist, create from template
                    if (shoudldBuildDashboard(config)) {
                        Globals.lastDashboardCheck = finish;
                        ArrayList<AppDMetricObj> metrics = new ArrayList<AppDMetricObj>();
                        for (SnapshotRunnerBase t : tasks) {
                            for (SummaryObj summaryObj : t.getMetricsBundle()) {
                                metrics.addAll(summaryObj.getMetricsMetadata());
                            }
                        }
                        logger.info("Starting dashboard build with collected {} metric metadata", metrics.size());
                        buildDashboard(tasksExecutionServiceProvider, config, metrics);
                    } else {
                        logger.info("No action necessary. Done");
                    }
                }
            }
            else{
                logger.error("Initialization failed. Aborting...");
            }
            Globals.K8S_POD_LIST=null;
        }
        catch(Exception e) {
            logger.error("Failed to execute the Kubernetes Snapshot Extension task", e);
        }

    }

    private boolean shoudldBuildDashboard(Map<String, String> config){
        long now = new Date().getTime();
        long interval = Long.parseLong(config.get(CONFIG_DASH_CHECK_INTERVAL));
        if (Globals.lastDashboardCheck == 0){
            logger.info("Skipping dashboard creation till the next cycle");
            Globals.lastDashboardCheck = now;
            return false;
        }
        return (now - Globals.lastDashboardCheck) > interval * 1000;
    }

    public void buildDashboard(TasksExecutionServiceProvider tasksExecutionServiceProvider, Map<String, String> config, ArrayList<AppDMetricObj> metrics){
          ClusterDashboardGenerator dashboardGenerator = new ClusterDashboardGenerator(config, metrics);
        try {
            tasksExecutionServiceProvider.submit("DashboardTask", dashboardGenerator);
        }
        catch (Exception ex){
            logger.error("Dashboard task was interrupted.", ex);
        }

    }

    private SnapshotRunnerBase initTask(TasksExecutionServiceProvider tasksExecutionServiceProvider, Map<String, String> config, String taskName){
        SnapshotRunnerBase task = null;
        switch (taskName){
            case CONFIG_ENTITY_TYPE_POD:
                task = new PodSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_NODE:
                task = new NodeSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_DEPLOYMENT:
                task = new DeploymentSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_DAEMON:
                task = new DaemonSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_ENDPOINT:
                task = new EndpointSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_REPLICA:
                task = new ReplicaSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_EVENT:
                task = new EventSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_POD_STATUS_MONITOR:
                task = new PodStatusMonitorSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_POD_RESOURCE_QUOTA:
                task = new PodResourceQuotaSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_POD_CRASH_STATUS:
                task = new PodCrashStatusSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_NOT_RUNNING_PODS_PER_NODE:
                task = new NodeWiseNotRunningPodsSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;
            case CONFIG_ENTITY_TYPE_NAMESPACE_QUOTA_UTILIZATION:
                task = new NamespaceQuotaUtilizationSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;              
            case CONFIG_ENTITY_TYPE_POD_METRICS:
                task = new NamespacePodMetricsSnapshotRunner(tasksExecutionServiceProvider, config, latch);
                break;  
                
        }
        return task;
    }

    private void executeSnapshotTask(TasksExecutionServiceProvider tasksExecutionServiceProvider, SnapshotRunnerBase task){
        try {
            tasksExecutionServiceProvider.submit(task.getTaskName(), task);
        }catch (Exception ex){
            logger.error(task.getTaskName() + " task was interrupted", ex);
        }
    }

    @Override
    protected int getTaskCount() {
        List<Map<String,String>> entities = (List<Map<String,String>>)configuration.getConfigYml().get(CONFIG_NODE_ENTITIES);
        AssertUtils.assertNotNull(entities, "The 'entities' section in config.yml must have values");
        return entities.size();
    }

    public boolean initClusterMonitoring(Map<String, String> config){
        boolean init = false;
        try {
            String clusterName = Utilities.getClusterApplicationName(config);
            if (clusterName == null || clusterName.isEmpty()){
                logger.error("Application name cannot be empty. Set appName value in config.yml or via APPLICATION_NAME environmental variable");
                return false;
            }
            //check if tier name is already in the metricsPath
            String path = Utilities.getMetricsPath(config);
            if (path.contains(Utilities.getClusterTierName(config))){
                logger.info("Tier name {} is already configured in the metricPath. Validation complete",Utilities.getClusterTierName(config));
                return true;
            }

            logger.info("Initializing Monitoring. Cluster {}", clusterName);
            //does the app exist?
            if (Utilities.tierID == 0) {
                JsonNode appObj = findClusterApp(config, clusterName);
                if (appObj == null || appObj.get("id") == null) {
                    logger.info("Creating Application for cluster metrics...");
                    //create if it doesn't
                    appObj = createClusterApp(config, clusterName);
                    if (appObj != null && appObj.get("id") != null) {
                        int appID = appObj.get("id").asInt();
                        checkAppTier(config, appID);
                    }
                }
                else if (appObj != null && appObj.get("id") != null) {
                    int appID = appObj.get("id").asInt();
                    logger.info("Application {} exists", appID);
                    checkAppTier(config, appID);
                }
            }
            if (Utilities.tierID > 0) {
                logger.info("Application and App Tier for cluster monitoring validated");
            }
            else{
                logger.info("App Tier identifier is missing. Metrics will not be properly saved in the controller");
            }
            init = true;
        }
        catch (Exception ex){
            logger.error("Unable to initialize cluster monitoring");
        }
        return init;
    }

    private void checkAppTier(Map<String, String> config, int appID){
        //build monitoring tier
        JsonNode tierObj = findAppTier(config, appID);
        if (tierObj != null && tierObj.get("id") != null){
            Utilities.tierID = tierObj.get("id").asInt();
            logger.info("App tier {} discovered.", Utilities.tierID);
        }
        else {
            logger.info("Creating Application Tier for cluster metrics...");
            tierObj = createAppTier(config, appID);
            if (tierObj != null && tierObj.get("id") != null) {
                Utilities.tierID = tierObj.get("id").asInt();
                logger.info("Tier ID = {}", Utilities.tierID);
            }
            else{
                logger.info("App tier is not discovered. It may be necessary to add tier name to the configured path value 'metricPrefix'");
            }
        }
    }

    private JsonNode findAppTier(Map<String, String> config, int appID){
        JsonNode theTier = null;
        String tierName = Utilities.getClusterTierName(config);
        logger.info("Looking for tier {}", tierName);
        String path = "restui/tiers/list/health";
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode obj = mapper.createObjectNode();
        ArrayNode sorts = obj.putArray("columnSorts");
        ObjectNode columnObj = mapper.createObjectNode();
        columnObj.put("column", "TIER_NAME");
        columnObj.put("direction", "ASC");
        sorts.add(columnObj);
        obj.put("limit", -1);
        obj.put("offset", 0);
        ObjectNode requestFilter = obj.putObject("requestFilter");
        ObjectNode params = requestFilter.putObject("queryParams");
        params.put("applicationId", appID);
        requestFilter.putArray("filters");
        ArrayNode results = obj.putArray("resultColumns");
        results.add("TIER_NAME");

        ArrayNode searchList = obj.putArray("searchFilters");
        ObjectNode searchObj = mapper.createObjectNode();
        ArrayNode cols = searchObj.putArray("columns");
        cols.add("TIER_NAME");
        searchObj.put("query", tierName);
        searchList.add(searchObj);
        String requestBody = obj.toString();

        JsonNode tierObj = RestClient.callControllerAPI(path, config, requestBody, "POST");
        if (tierObj != null && tierObj.get("data") != null){
            JsonNode list = tierObj.get("data");
            for(JsonNode tier : list){
                if (tier.get("name") != null && tier.get("name").asText().equals(tierName) ){
                    theTier = tier;
                    break;
                }
            }
        }
        return theTier;
    }

    private JsonNode findClusterApp(Map<String, String> config, String appName){
        String path = String.format("restui/applicationManagerUiBean/applicationByName?applicationName=%s", appName);
        JsonNode appObj = RestClient.callControllerAPI(path, config, "", "GET");
        return appObj;
    }

    private JsonNode createClusterApp(Map<String, String> config, String clusterName){
        try {
            String path = "restui/allApplications/createApplication?applicationType=APM";
            String requestBody = buildAppObj(clusterName).toString();
            JsonNode appObj = RestClient.callControllerAPI(path, config, requestBody, "POST");
            return appObj;
        }
        catch (Exception ex){
            logger.error("Unable create Application for cluster monitoring");
            return null;
        }
    }

    private JsonNode createAppTier(Map<String, String> config , int appID){
        String path = "restui/components/createComponent";
        String tierName = Utilities.getClusterTierName(config);
        String requestBody = buildTierObj(appID, tierName).toString();
        JsonNode tierObj = RestClient.callControllerAPI(path, config, requestBody, "POST");
        return tierObj;
    }

    private ObjectNode buildAppObj(String appName){
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode obj = mapper.createObjectNode();
        obj.put("name", appName);
        obj.put("description", String.format("Data Repository for cluster %s", appName));
        return obj;
    }

    private ObjectNode buildTierObj(int appID, String tierName){
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode obj = mapper.createObjectNode();
        obj.put("applicationId", appID);
        ObjectNode componentType = obj.putObject("componentType");
        componentType.put("agentType", "APP_AGENT");
        componentType.put("id", 4);
        componentType.put("name", "Application Server");
        componentType.put("nameUnique", true);
        componentType.put("version", 0);
        obj.put("name", tierName);
        obj.put("description","Monitoring tier for the cluster monitoring application");
        return obj;
    }
}
