package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_RECS_BATCH_SIZE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_DAEMON;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_DAEMON;
import static com.appdynamics.monitors.kubernetes.Constants.K8S_VERSION;
import static com.appdynamics.monitors.kubernetes.Constants.OPENSHIFT_VERSION;
import static com.appdynamics.monitors.kubernetes.Utilities.ALL;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddInt;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddObject;
import static com.appdynamics.monitors.kubernetes.Utilities.ensureSchema;
import static com.appdynamics.monitors.kubernetes.Utilities.incrementField;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.appdynamics.extensions.TasksExecutionServiceProvider;
import com.appdynamics.extensions.metrics.Metric;
import com.appdynamics.extensions.util.AssertUtils;
import com.appdynamics.monitors.kubernetes.KubernetesClientSingleton;
import com.appdynamics.monitors.kubernetes.Utilities;
import com.appdynamics.monitors.kubernetes.Metrics.UploadMetricsTask;
import com.appdynamics.monitors.kubernetes.Models.AppDMetricObj;
import com.appdynamics.monitors.kubernetes.Models.SummaryObj;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1DaemonSet;
import io.kubernetes.client.openapi.models.V1DaemonSetList;

public class DaemonSnapshotRunner extends SnapshotRunnerBase{

    public DaemonSnapshotRunner(){

    }

    public DaemonSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
        generateDaemonsetSnapshot();
    }

    private void generateDaemonsetSnapshot(){
        logger.info("Proceeding to Daemonsets update...");
        Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
        if (config != null) {
            String apiKey = Utilities.getEventsAPIKey(config);
            String accountName = Utilities.getGlobalAccountName(config);
            URL publishUrl = ensureSchema(config, apiKey, accountName,CONFIG_SCHEMA_NAME_DAEMON, CONFIG_SCHEMA_DEF_DAEMON);

            try {
                V1DaemonSetList dsList;
                try {
                    ApiClient client = KubernetesClientSingleton.getInstance(config);
                    this.setAPIServerTimeout(client, K8S_API_TIMEOUT);
                    Configuration.setDefaultApiClient(client);
                    AppsV1Api api = KubernetesClientSingleton.getAppsV1ApiClient(config);
                    
                    dsList = api.listDaemonSetForAllNamespaces(
                    		false, //allow Watch bookmarks
                    		null,  //_continue - relevant for pagination
                    		null,  //fieldSelector
                    		null,  //labelSelector
                    		null,  //limit the number of records
                    		null,  //pretty output
                    		null,  //resourseVersion
                    		null,  //resourceVersionMatch
                    		K8S_API_TIMEOUT, //timeout in sec
                    		false // isWatch
                    		);
                }
                catch (Exception ex){
                    throw new Exception("Unable to connect to Kubernetes API server because it may be unavailable or the cluster credentials are invalid", ex);
                }

                createDaemonsetPayload(dsList, config, publishUrl, accountName, apiKey);

                List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);
                logger.info("About to send {} Daemon set metrics", metricList.size());
                UploadMetricsTask podMetricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
                getConfiguration().getExecutorService().execute("UploadDaemonMetricsTask", podMetricsTask);

            } catch (IOException e) {
                countDownLatch.countDown();
                logger.error("Failed to push Daemonsets data", e);
            } catch (Exception e) {
                countDownLatch.countDown();
                logger.error("Failed to push Daemonsets data", e);
            }
        }
    }

    private ArrayNode createDaemonsetPayload(V1DaemonSetList dsList, Map<String, String> config, URL publishUrl, String accountName, String apiKey){
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();
        long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));

        for(V1DaemonSet deployItem : dsList.getItems()) {
            ObjectNode deployObject = mapper.createObjectNode();

            String namespace = deployItem.getMetadata().getNamespace();
            String clusterName = Utilities.ensureClusterName(config, deployItem.getMetadata().getClusterName());

            SummaryObj summary = getSummaryMap().get(ALL);
            if (summary == null) {
                summary = initDaemonSummaryObject(config, ALL);
                getSummaryMap().put(ALL, summary);
            }

            SummaryObj summaryNamespace = getSummaryMap().get(namespace);
            if (Utilities.shouldCollectMetricsForNamespace(getConfiguration(), namespace)) {
                if (summaryNamespace == null) {
                    summaryNamespace = initDaemonSummaryObject(config, namespace);
                    getSummaryMap().put(namespace, summaryNamespace);
                }
            }
            if(!OPENSHIFT_VERSION.isEmpty()) {
	        	deployObject = checkAddObject(deployObject,OPENSHIFT_VERSION, "openshiftVersion");
	        }
	        if(!K8S_VERSION.isEmpty()) {
	        	deployObject = checkAddObject(deployObject,K8S_VERSION, "kubernetesVersion");	        	
	        }
            ObjectNode labelsObject = Utilities.getResourceLabels(config,mapper, deployItem);
            deployObject=checkAddObject(deployObject, labelsObject, "customLabels") ;  

            incrementField(summary, "DaemonSets");
            incrementField(summaryNamespace, "DaemonSets");

            deployObject = checkAddObject(deployObject, deployItem.getMetadata().getUid(), "object_uid");
            deployObject = checkAddObject(deployObject, clusterName, "clusterName");
            deployObject = checkAddObject(deployObject, deployItem.getMetadata().getCreationTimestamp(), "creationTimestamp");
            deployObject = checkAddObject(deployObject, deployItem.getMetadata().getDeletionTimestamp(), "deletionTimestamp");
            deployObject = checkAddObject(deployObject, deployItem.getMetadata().getName(), "name");
            deployObject = checkAddObject(deployObject, namespace, "namespace");

            deployObject = checkAddInt(deployObject, deployItem.getSpec().getMinReadySeconds(), "minReadySecs");


            deployObject = checkAddInt(deployObject, deployItem.getSpec().getRevisionHistoryLimit(), "revisionHistoryLimits");




            deployObject = checkAddInt(deployObject, deployItem.getStatus().getNumberAvailable(), "replicasAvailable");
            deployObject = checkAddInt(deployObject, deployItem.getStatus().getNumberUnavailable(), "replicasUnAvailable");
            deployObject = checkAddInt(deployObject, deployItem.getStatus().getCollisionCount(), "collisionCount");
            deployObject = checkAddInt(deployObject, deployItem.getStatus().getNumberReady(), "replicasReady");

            deployObject = checkAddInt(deployObject, deployItem.getStatus().getCurrentNumberScheduled(), "numberScheduled");
            deployObject = checkAddInt(deployObject, deployItem.getStatus().getDesiredNumberScheduled(), "desiredNumber");
            deployObject = checkAddInt(deployObject, deployItem.getStatus().getNumberMisscheduled(), "missScheduled");

            deployObject = checkAddInt(deployObject, deployItem.getStatus().getUpdatedNumberScheduled(), "updatedNumberScheduled");

            if (deployItem.getStatus().getNumberAvailable() != null) {
                incrementField(summary, "DaemonReplicasAvailable", deployItem.getStatus().getNumberAvailable());
                incrementField(summaryNamespace, "DaemonReplicasAvailable", deployItem.getStatus().getNumberAvailable());
            }

            if (deployItem.getStatus().getNumberUnavailable() != null) {
                incrementField(summary, "DaemonReplicasUnAvailable", deployItem.getStatus().getNumberUnavailable());
                incrementField(summaryNamespace, "DaemonReplicasUnAvailable", deployItem.getStatus().getNumberUnavailable());
            }

            if (deployItem.getStatus().getCollisionCount() != null) {
                incrementField(summary, "DaemonCollisionCount", deployItem.getStatus().getCollisionCount());
                incrementField(summaryNamespace, "DaemonCollisionCount", deployItem.getStatus().getCollisionCount());
            }

            if (deployItem.getStatus().getNumberReady() != null) {
                incrementField(summary, "DaemonReplicasReady", deployItem.getStatus().getNumberReady());
                incrementField(summaryNamespace, "DaemonReplicasReady", deployItem.getStatus().getNumberReady());
            }

            if (deployItem.getStatus().getCurrentNumberScheduled() != null) {
                incrementField(summary, "DaemonNumberScheduled", deployItem.getStatus().getCurrentNumberScheduled());
                incrementField(summaryNamespace, "DaemonNumberScheduled", deployItem.getStatus().getCurrentNumberScheduled());
            }


            if (deployItem.getStatus().getNumberMisscheduled() != null) {
                incrementField(summary, "DaemonMissScheduled", deployItem.getStatus().getNumberMisscheduled());
                incrementField(summaryNamespace, "DaemonMissScheduled", deployItem.getStatus().getNumberMisscheduled());
            }

            arrayNode.add(deployObject);

            if (arrayNode.size() >= batchSize){
                logger.info("Sending batch of {} Daemon Set records", arrayNode.size());
                String payload = arrayNode.toString();
                arrayNode = arrayNode.removeAll();
                if(!payload.equals("[]")){
                    UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                    getConfiguration().getExecutorService().execute("UploadDaemonData", uploadEventsTask);
                }
            }
        }
        if (arrayNode.size() > 0){
            logger.info("Sending last batch of {} Daemon Set records", arrayNode.size());
            String payload = arrayNode.toString();
            arrayNode = arrayNode.removeAll();
            if(!payload.equals("[]")){
                UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                getConfiguration().getExecutorService().execute("UploadDaemonData", uploadEventsTask);
            }
        }


        return arrayNode;
    }

    protected SummaryObj initDefaultSummaryObject(Map<String, String> config){
        return initDaemonSummaryObject(config, ALL);
    }


    public  static SummaryObj initDaemonSummaryObject(Map<String, String> config, String namespace){
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode summary = mapper.createObjectNode();
        summary.put("namespace", namespace);

        summary.put("DaemonSets", 0);
        summary.put("DaemonReplicasAvailable", 0);
        summary.put("DaemonReplicasUnAvailable", 0);
        summary.put("DaemonMissScheduled", 0);
        summary.put("DaemonCollisionCount", 0);


        ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace);

        String path = Utilities.getMetricsPath(config, namespace, ALL);

        return new SummaryObj(summary, metricsList, path);
    }

    public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String namespace) {
        if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
            return new ArrayList<AppDMetricObj>();
        }
        String clusterName = Utilities.ClusterName;
        String parentSchema = config.get(CONFIG_SCHEMA_NAME_DAEMON);
        String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|", Utilities.getClusterTierName(config));
        ArrayList<AppDMetricObj> metricsList = new ArrayList<AppDMetricObj>();

        String filter = "";
        if(!namespace.equals(ALL)){
            filter = String.format("and namespace = \"%s\"", namespace);
        }

        if(namespace.equals(ALL)) {
            metricsList.add(new AppDMetricObj("DaemonSets", parentSchema, CONFIG_SCHEMA_DEF_DAEMON,
                    String.format("select * from %s where clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, ALL,null));

            metricsList.add(new AppDMetricObj("DaemonReplicasAvailable", parentSchema, CONFIG_SCHEMA_DEF_DAEMON,
                    String.format("select * from %s where replicasAvailable > 0 and clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace,  ALL,null));

            metricsList.add(new AppDMetricObj("DaemonReplicasUnAvailable", parentSchema, CONFIG_SCHEMA_DEF_DAEMON,
                    String.format("select * from %s where replicasUnAvailable > 0 and clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, ALL,null));

            metricsList.add(new AppDMetricObj("DaemonMissScheduled", parentSchema, CONFIG_SCHEMA_DEF_DAEMON,
                    String.format("select * from %s where missScheduled > 0 and clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace,  ALL,null));

            metricsList.add(new AppDMetricObj("DaemonCollisionCount", parentSchema, CONFIG_SCHEMA_DEF_DAEMON,
                    String.format("select * from %s where collisionCount > 0 and clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, ALL,null));
        }

        return metricsList;
    }
}
