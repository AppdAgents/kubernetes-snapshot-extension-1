package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_RECS_BATCH_SIZE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_POD_CRASH_STATUS;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_POD_CRASH_STATUS;
import static com.appdynamics.monitors.kubernetes.Constants.K8S_VERSION;
import static com.appdynamics.monitors.kubernetes.Constants.OPENSHIFT_VERSION;
import static com.appdynamics.monitors.kubernetes.Utilities.ALL;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddInt;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddObject;
import static com.appdynamics.monitors.kubernetes.Utilities.ensureSchema;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.appdynamics.extensions.TasksExecutionServiceProvider;
import com.appdynamics.extensions.metrics.Metric;
import com.appdynamics.extensions.util.AssertUtils;
import com.appdynamics.monitors.kubernetes.Globals;
import com.appdynamics.monitors.kubernetes.Utilities;
import com.appdynamics.monitors.kubernetes.Metrics.UploadMetricsTask;
import com.appdynamics.monitors.kubernetes.Models.AppDMetricObj;
import com.appdynamics.monitors.kubernetes.Models.SummaryObj;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kubernetes.client.openapi.models.V1ContainerStateTerminated;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodList;


public class PodCrashStatusSnapshotRunner extends SnapshotRunnerBase {

	@Override
    protected SummaryObj initDefaultSummaryObject(Map<String, String> config){
        return initPodCrashStatusSummaryObject(config, ALL,ALL);
    }

	public PodCrashStatusSnapshotRunner(){

    }

    public PodCrashStatusSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
	@Override
	public void run() {
	    AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
	    generatePodCrashStatusSnapshot();
	}

	
	private void generatePodCrashStatusSnapshot() {
	    logger.info("Proceeding to capture Pod Crash Status...");

	    Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
	    if (config != null) {
	        String apiKey = Utilities.getEventsAPIKey(config);
	        String accountName = Utilities.getGlobalAccountName(config);
	        URL publishUrl = ensureSchema(config, apiKey, accountName, CONFIG_SCHEMA_NAME_POD_CRASH_STATUS, CONFIG_SCHEMA_DEF_POD_CRASH_STATUS);

	        try {
	           

	        	V1PodList pods = getPodsFromKubernetes(); 

	        	createPodCrashStatusPayload( pods, config, publishUrl,  accountName,  apiKey);

	        	List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);
  

	            logger.info("About to send {} Pod Crash Status metrics", metricList.size());
	
	            UploadMetricsTask podMetricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
	            getConfiguration().getExecutorService().execute("UploadPodCrashStatusTask", podMetricsTask);
 
	        } catch (Exception e) {
                countDownLatch.countDown();
                logger.error("Failed to push End Points data", e);
            } 
        }
    }
	
	private Integer getCrashCount(V1Pod pod) {
	    Integer crashCount = 0;

	    // Retrieve the crash count from the container statuses
	    if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
	        List<V1ContainerStatus> containerStatuses = pod.getStatus().getContainerStatuses();
	        for (V1ContainerStatus containerStatus : containerStatuses) {
	            if (containerStatus.getState() != null && containerStatus.getState().getTerminated() != null) {
	                // Check if the container has terminated due to an error or crash
	                V1ContainerStateTerminated terminatedState = containerStatus.getState().getTerminated();
	                if (terminatedState.getExitCode() != 0) {
	                    crashCount++;
	                }
	            }
	        }
	    }

	    return crashCount;
	}


	private Map<String, String> getContainerNameAndStatus(V1Pod pod) {
	    Map<String, String> containerNameAndStatus = new HashMap<>();

	    // Retrieve the container name and status from the Pod status
	    if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
	        List<V1ContainerStatus> containerStatuses = pod.getStatus().getContainerStatuses();
	        for (V1ContainerStatus containerStatus : containerStatuses) {
	            String containerName = containerStatus.getName();
	            String containerStatusString ="";
	            if(containerStatus.getState().getRunning()!=null)
	            {
	            	containerStatusString="Running";
	            }else  if(containerStatus.getState().getTerminated()!=null)
	            {
	            	containerStatusString="Terminated";
	            }else  if(containerStatus.getState().getWaiting()!=null)
	            {
	            	containerStatusString="Waiting";
	            }
	            containerNameAndStatus.put(containerName, containerStatusString);
	        }
	    }

	    return containerNameAndStatus;
	}


	private String getErrorReason(V1Pod pod) {
		String reasons="";
	    if (pod.getStatus() != null && pod.getStatus().getConditions() != null) {
	        List<V1PodCondition> conditions = pod.getStatus().getConditions();
	        for (V1PodCondition condition : conditions) {
	            if ( condition.getStatus().equalsIgnoreCase("False")) {
	                // Retrieve the error reason from the pod's condition
	            	reasons += String.format("%s;", condition.getReason()+", Message: "+condition.getMessage());
	            }
	        }
	    }
	    return reasons;
	}


	private String getNodeName(V1Pod pod) {
		String nodeName = "";

	    // Retrieve the node name from the Pod
	    if (pod.getSpec() != null && pod.getSpec().getNodeName() != null) {
	        nodeName = pod.getSpec().getNodeName();
	    }

	    return nodeName;
	}


	private V1PodList getPodsFromKubernetes() {
		  
        return Globals.K8S_POD_LIST;
	
	}


	ArrayNode createPodCrashStatusPayload(V1PodList  podList, Map<String, String> config, URL publishUrl, String accountName, String apiKey) {
	    ObjectMapper mapper = new ObjectMapper();
	    ArrayNode arrayNode = mapper.createArrayNode();
	    long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));

	    
	    
		
	    for (V1Pod pod  : podList.getItems()) {
	        ObjectNode objectNode = mapper.createObjectNode();
	        objectNode = checkAddObject(objectNode, pod.getMetadata().getName(), "podName");

	        String namespace = pod.getMetadata().getNamespace();
	        objectNode = checkAddObject(objectNode, namespace, "namespace");

		  if(!OPENSHIFT_VERSION.isEmpty()) {
				objectNode = checkAddObject(objectNode, OPENSHIFT_VERSION, "openshiftVersion");
		  }
		  if(!K8S_VERSION.isEmpty()) {
		    	objectNode = checkAddObject(objectNode,K8S_VERSION, "kubernetesVersion");	        	
		  }
	        objectNode = checkAddObject(objectNode, getNodeName(pod), "nodeName");

            String clusterName = Utilities.ensureClusterName(config, pod.getMetadata().getClusterName());
	        objectNode = checkAddObject(objectNode, clusterName, "clusterName");


	        String errorReason = getErrorReason(pod);
	        objectNode = checkAddObject(objectNode, errorReason, "errorReason");

	        Map<String, String> containerName = getContainerNameAndStatus(pod);
	        ObjectNode containerNode = mapper.createObjectNode();
            for (Map.Entry<String, String> entry : containerName.entrySet()) {
                containerNode.put(entry.getKey(), entry.getValue());
            }
            objectNode=checkAddObject(objectNode, containerNode,"containers");

            objectNode = checkAddInt(objectNode,getCrashCount(pod) , "crashCount");
            objectNode = checkAddInt(objectNode,getRestartCount(pod) , "restartCount");
            
	        ObjectNode labelsObject = Utilities.getResourceLabels(config,mapper, pod);
	        objectNode=checkAddObject(objectNode,labelsObject,"customLabels");
	        objectNode=checkAddObject(objectNode,getContainerPhase(pod),"containerPhase");
            
            SummaryObj summary = getSummaryMap().get(ALL);
            if (summary == null) {
                summary = initPodCrashStatusSummaryObject(config, ALL, ALL);
                getSummaryMap().put(ALL, summary);
            }

            SummaryObj summaryNamespace = getSummaryMap().get(namespace);
            if (Utilities.shouldCollectMetricsForNamespace(getConfiguration(), namespace)) {
                if (summaryNamespace == null) {
                    summaryNamespace = initPodCrashStatusSummaryObject(config, namespace, ALL);
                    getSummaryMap().put(namespace, summaryNamespace);
                }
            }
          
            String phase = pod.getStatus().getPhase();
  
            if (phase.equals("Pending")) {
                Utilities.incrementField(summary, "PendingPods");
                Utilities.incrementField(summaryNamespace, "PendingPods");
            }else if (phase.equals("Running")) {
                Utilities.incrementField(summary, "RunningPods");
                Utilities.incrementField(summaryNamespace, "RunningPods");
            } else{
                Utilities.incrementField(summary, "CrashedPods");
                Utilities.incrementField(summaryNamespace, "CrashedPods");
            }

            
	        arrayNode.add(objectNode);

	        if (arrayNode.size() >= batchSize) {
	            logger.info("Sending batch of {} PodCrashStatus records", arrayNode.size());
	            String payload = arrayNode.toString();
	            arrayNode = arrayNode.removeAll();
	            if (!payload.equals("[]")) {
	                UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
	                getConfiguration().getExecutorService().execute("UploadPodCrashStatusData", uploadEventsTask);
	            }
	        }
	    }

	    if (arrayNode.size() > 0) {
	        logger.info("Sending last batch of {} PodCrashStatus records", arrayNode.size());
	        String payload = arrayNode.toString();
	        arrayNode = arrayNode.removeAll();
	        if (!payload.equals("[]")) {
	            UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
	            getConfiguration().getExecutorService().execute("UploadPodCrashStatusData", uploadEventsTask);
	        }
	    }

	    return arrayNode;
	}
	private String getContainerPhase(V1Pod pod) {
	    if (pod.getStatus() != null) {
	        String phase = pod.getStatus().getPhase();
	        // Retrieve the container phase from the pod's status phase
	        return phase;
	    }
	    return "";
	}
    private int getRestartCount(V1Pod podItem) {
    	 int podRestarts = 0;

         if (podItem.getStatus().getContainerStatuses() != null){
             for(V1ContainerStatus status : podItem.getStatus().getContainerStatuses()){
                 int restarts = status.getRestartCount();
                 podRestarts += restarts;
              }
         }
         return podRestarts;
    }

	public static SummaryObj initPodCrashStatusSummaryObject(Map<String, String> config, String namespace,String node) {
	    ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();
	    summary.put("namespace", namespace);
	    summary.put("CrashedPods", 0);
	    summary.put("RunningPods", 0);
	    summary.put("PendingPods", 0);

	    ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace,node);

	    String path = Utilities.getMetricsPath(config, namespace,node); 
	    return new SummaryObj(summary, metricsList, path);
	}
	
	public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String namespace,String node) {
	    if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
	        return new ArrayList<AppDMetricObj>();
	    }

	    String clusterName = Utilities.ClusterName;
	    String parentSchema = config.get(CONFIG_SCHEMA_NAME_POD_CRASH_STATUS);
	    String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|", Utilities.getClusterTierName(config));
	    ArrayList<AppDMetricObj> metricsList = new ArrayList<>();

	    String namespacesCondition = "";
        String nodeCondition = "";
        if(namespace != null && !namespace.equals(ALL)){
            namespacesCondition = String.format("and namespace = \"%s\"", namespace);
        }

        if(node != null && !node.equals(ALL)){
            nodeCondition = String.format("and nodeName = \"%s\"", node);
        }

        String filter = namespacesCondition.isEmpty() ? nodeCondition : namespacesCondition;

	    if (namespace.equals(ALL)) {
	        metricsList.add(new AppDMetricObj("CrashedPods", parentSchema, CONFIG_SCHEMA_DEF_POD_CRASH_STATUS,
	                String.format("select * from %s where phase = \"CrashLoopBackOff\" and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node,null));

	        metricsList.add(new AppDMetricObj("RunningPods", parentSchema, CONFIG_SCHEMA_DEF_POD_CRASH_STATUS,
	                String.format("select * from %s where phase = \"Running\" and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node,null));

	        metricsList.add(new AppDMetricObj("PendingPods", parentSchema, CONFIG_SCHEMA_DEF_POD_CRASH_STATUS,
	                String.format("select * from %s where phase = \"Pending\" and clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node,null));
	    }

	    return metricsList;
	}

	

}
