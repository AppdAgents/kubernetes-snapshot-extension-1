package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_RECS_BATCH_SIZE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_RS;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_RS;
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
import io.kubernetes.client.openapi.models.V1ReplicaSet;
import io.kubernetes.client.openapi.models.V1ReplicaSetList;

public class ReplicaSnapshotRunner extends SnapshotRunnerBase {
    public ReplicaSnapshotRunner(){

    }

    public ReplicaSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
        generateReplicasetSnapshot();
    }

    private void generateReplicasetSnapshot(){
        logger.info("Proceeding to ReplicaSet update...");
        Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
        if (config != null) {
            String apiKey = Utilities.getEventsAPIKey(config);
            String accountName = Utilities.getGlobalAccountName(config);
            URL publishUrl = ensureSchema(config, apiKey, accountName, CONFIG_SCHEMA_NAME_RS, CONFIG_SCHEMA_DEF_RS);

            try {
                V1ReplicaSetList rsList;
                try {
        			ApiClient client = KubernetesClientSingleton.getInstance(config);
        			AppsV1Api api =KubernetesClientSingleton.getAppsV1ApiClient(config);
        		    this.setAPIServerTimeout(KubernetesClientSingleton.getInstance(config), K8S_API_TIMEOUT);
                    Configuration.setDefaultApiClient(client);
                 
                    
                    rsList = api.listReplicaSetForAllNamespaces(
                    		false, 
                    		null, 
                    		null, 
                    		null, 
                    		null, 
                    		null, 
                    		null, 
                    		null, 
                    		K8S_API_TIMEOUT, false);
                }
                catch (Exception ex){
                    throw new Exception("Unable to connect to Kubernetes API server because it may be unavailable or the cluster credentials are invalid", ex);
                }

                createReplicasetPayload(rsList, config, publishUrl, accountName, apiKey);


                //build and update metrics
                List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);
                logger.info("About to send {} replica set metrics", metricList.size());
                UploadMetricsTask metricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
                getConfiguration().getExecutorService().execute("UploadRSMetricsTask", metricsTask);

            } catch (IOException e) {
                countDownLatch.countDown();
                logger.error("Failed to push ReplicaSet data", e);
            } catch (Exception e) {
                countDownLatch.countDown();
                logger.error("Failed to push ReplicaSet data", e);
            }
        }
    }

     ArrayNode createReplicasetPayload(V1ReplicaSetList rsList, Map<String, String> config, URL publishUrl, String accountName, String apiKey) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();

        long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));

        for (V1ReplicaSet deployItem : rsList.getItems()) {
            ObjectNode deployObject = mapper.createObjectNode();

            String namespace = deployItem.getMetadata().getNamespace();
            String clusterName = Utilities.ensureClusterName(config, deployItem.getMetadata().getClusterName());
           
            SummaryObj summary = getSummaryMap().get(ALL);
            if (summary == null) {
                summary = initRSSummaryObject(config, ALL);
                getSummaryMap().put(ALL, summary);
            }

            SummaryObj summaryNamespace = getSummaryMap().get(namespace);
            if (Utilities.shouldCollectMetricsForNamespace(getConfiguration(), namespace)) {
                if (summaryNamespace == null) {
                    summaryNamespace = initRSSummaryObject(config, namespace);
                    getSummaryMap().put(namespace, summaryNamespace);
                }
            }

            incrementField(summary, "ReplicaSets");
            incrementField(summaryNamespace, "ReplicaSets");

            if(!OPENSHIFT_VERSION.isEmpty()) {
	        	deployObject = checkAddObject(deployObject,OPENSHIFT_VERSION, "openshiftVersion");
	        }
	        if(!K8S_VERSION.isEmpty()) {
	        	deployObject = checkAddObject(deployObject,K8S_VERSION, "kubernetesVersion");	        	
	        }
           ObjectNode labelsObject = Utilities.getResourceLabels(config,mapper, deployItem);
           deployObject=checkAddObject(deployObject, labelsObject, "customLabels") ;  
            
            deployObject = checkAddObject(deployObject, deployItem.getMetadata().getUid(), "object_uid");
            deployObject = checkAddObject(deployObject, clusterName, "clusterName");
            deployObject = checkAddObject(deployObject, deployItem.getMetadata().getCreationTimestamp(), "creationTimestamp");
            deployObject = checkAddObject(deployObject, deployItem.getMetadata().getDeletionTimestamp(), "deletionTimestamp");
            deployObject = checkAddObject(deployObject, deployItem.getMetadata().getName(), "name");
            deployObject = checkAddObject(deployObject, namespace, "namespace");

            deployObject = checkAddInt(deployObject, deployItem.getSpec().getMinReadySeconds(), "minReadySecs");

            int replicas = deployItem.getSpec().getReplicas();
            deployObject = checkAddInt(deployObject, deployItem.getSpec().getReplicas(), "replicas");

            incrementField(summary, "RsReplicas", replicas);
            incrementField(summaryNamespace, "RsReplicas", replicas);


            deployObject = checkAddInt(deployObject, deployItem.getStatus().getAvailableReplicas(), "rsReplicasAvailable");
            deployObject = checkAddInt(deployObject, deployItem.getStatus().getFullyLabeledReplicas(), "replicasLabeled");
            deployObject = checkAddInt(deployObject, deployItem.getStatus().getReadyReplicas(), "replicasReady");

            Integer availableReplicas = deployItem.getStatus().getAvailableReplicas();
            if (availableReplicas != null) {
                incrementField(summary, "RsReplicasAvailable", deployItem.getStatus().getAvailableReplicas());
                incrementField(summaryNamespace, "RsReplicasAvailable", deployItem.getStatus().getFullyLabeledReplicas());
                int unavailable = replicas - availableReplicas;
                deployObject = checkAddInt(deployObject, unavailable, "rsReplicasUnAvailable");
                incrementField(summary, "RsReplicasUnAvailable", unavailable);
                incrementField(summaryNamespace, "RsReplicasUnAvailable", unavailable);
            }


            arrayNode.add(deployObject);
            if (arrayNode.size() >= batchSize){
                logger.info("Sending batch of {} Replica Set records", arrayNode.size());
                String payload = arrayNode.toString();
                arrayNode = arrayNode.removeAll();
                if(!payload.equals("[]")){
                    UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                    getConfiguration().getExecutorService().execute("UploadReplicaData", uploadEventsTask);
                }
            }
        }

         if (arrayNode.size() > 0){
             logger.info("Sending last batch of {} Replica Set records", arrayNode.size());
             String payload = arrayNode.toString();
             arrayNode = arrayNode.removeAll();
             if(!payload.equals("[]")){
                 UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                 getConfiguration().getExecutorService().execute("UploadReplicaData", uploadEventsTask);
             }
         }

        return arrayNode;
    }

    protected SummaryObj initDefaultSummaryObject(Map<String, String> config){
        return initRSSummaryObject(config, ALL);
    }

    public  static SummaryObj initRSSummaryObject(Map<String, String> config, String namespace){
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode summary = mapper.createObjectNode();
        summary.put("namespace", namespace);
        summary.put("ReplicaSets", 0);
        summary.put("RsReplicas", 0);
        summary.put("RsReplicasAvailable", 0);
        summary.put("RsReplicasUnAvailable", 0);



        ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace);

        String path = Utilities.getMetricsPath(config, namespace, ALL);

        return new SummaryObj(summary, metricsList, path);
    }

    public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String namespace) {
        if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
            return new ArrayList<AppDMetricObj>();
        }
        String clusterName = Utilities.ClusterName;
        String parentSchema = config.get(CONFIG_SCHEMA_NAME_RS);
        String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|", Utilities.getClusterTierName(config));
        ArrayList<AppDMetricObj> metricsList = new ArrayList<AppDMetricObj>();

        String filter = "";
        if(!namespace.equals(ALL)){
            filter = String.format("and namespace = \"%s\"", namespace);
        }

        if (namespace.equals(ALL)) {
            metricsList.add(new AppDMetricObj("ReplicaSets", parentSchema, CONFIG_SCHEMA_DEF_RS,
                    String.format("select * from %s where clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, ALL,null));

            metricsList.add(new AppDMetricObj("RsReplicas", parentSchema, CONFIG_SCHEMA_DEF_RS,
                    String.format("select * from %s where replicas > 0 and clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, ALL,null));

            metricsList.add(new AppDMetricObj("RsReplicasAvailable", parentSchema, CONFIG_SCHEMA_DEF_RS,
                    String.format("select * from %s where rsReplicasAvailable > 0 and clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, ALL,null));

            metricsList.add(new AppDMetricObj("RsReplicasUnAvailable", parentSchema, CONFIG_SCHEMA_DEF_RS,
                    String.format("select * from %s where rsReplicasAvailable = 0 and clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, ALL,null));
        }

        return metricsList;
    }
}
