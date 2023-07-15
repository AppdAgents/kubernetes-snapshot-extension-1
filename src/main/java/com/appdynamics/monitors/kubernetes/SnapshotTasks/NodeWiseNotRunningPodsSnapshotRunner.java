package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_RECS_BATCH_SIZE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_NODE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_NOT_RUNNING_POD_COUNT;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_NOT_RUNNING_POD_COUNT;
import static com.appdynamics.monitors.kubernetes.Constants.K8S_VERSION;
import static com.appdynamics.monitors.kubernetes.Constants.OPENSHIFT_VERSION;
import static com.appdynamics.monitors.kubernetes.Utilities.ALL;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddInt;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddObject;
import static com.appdynamics.monitors.kubernetes.Utilities.ensureSchema;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import com.appdynamics.extensions.TasksExecutionServiceProvider;
import com.appdynamics.extensions.metrics.Metric;
import com.appdynamics.extensions.util.AssertUtils;
import com.appdynamics.monitors.kubernetes.Constants;
import com.appdynamics.monitors.kubernetes.Globals;
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
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;

public class NodeWiseNotRunningPodsSnapshotRunner extends SnapshotRunnerBase {

	@Override
    protected SummaryObj initDefaultSummaryObject(Map<String, String> config){
        return initNodeWiseNotRunningPodSummaryObject(config, ALL,null);
    }
	
	public NodeWiseNotRunningPodsSnapshotRunner(){

    }

    public NodeWiseNotRunningPodsSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
	@Override
	public void run() {
	    AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
	    generateNotRunningPodCountPerNodeSnapshot();
    }


	private void generateNotRunningPodCountPerNodeSnapshot() {
	    logger.info("Proceeding to capture Not Running Pod Count per Node...");

	    Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
	    if (config != null) {
	        String apiKey = Utilities.getEventsAPIKey(config);
	        String accountName = Utilities.getGlobalAccountName(config);
	        URL publishUrl = ensureSchema(config, apiKey, accountName, CONFIG_SCHEMA_NAME_NOT_RUNNING_POD_COUNT, CONFIG_SCHEMA_DEF_NOT_RUNNING_POD_COUNT);

	        try {
	            V1NodeList nodes = getNodesFromKubernetes(config);

	            
	            createPayload( nodes,config, publishUrl,  accountName,  apiKey);
	            List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);
	            
	            logger.info("About to send {} Not Running Pod Count per Node metrics", metricList.size());
                UploadMetricsTask metricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
	            getConfiguration().getExecutorService().execute("UploadNotRunningPodCountTask", metricsTask);
	        } catch (IOException e) {
	            countDownLatch.countDown();
	            logger.error("Failed to push Not Running Pod Count per Node data", e);
	        } catch (Exception e) {
	            countDownLatch.countDown();
	            logger.error("Failed to push Not Running Pod Count per Node data", e);
	        }
	    }
	}
	
 	public int getNotRunningPodCount(String nodeName)  {
		int count = 0;
		String podPhase = "Running";
		V1PodList podList = Globals.K8S_POD_LIST;
		for (V1Pod pod : podList.getItems()) {
			if(pod.getSpec().getNodeName().equals(nodeName) &&  (!pod.getStatus().getPhase().equalsIgnoreCase(podPhase))) {
				count++;     
			}
		}

		return count;   
	}


	public ArrayNode createPayload(V1NodeList nodeList, Map<String, String> config, URL publishUrl, String accountName, String apiKey) throws Exception {
	    ObjectMapper mapper = new ObjectMapper();
	    ArrayNode arrayNode = mapper.createArrayNode();
	    long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));
	   
	    for (V1Node node : nodeList.getItems()) {
	        ObjectNode objectNode = mapper.createObjectNode();
	        if(!OPENSHIFT_VERSION.isEmpty()) {
	        	objectNode = checkAddObject(objectNode,OPENSHIFT_VERSION, "openshiftVersion");
	        }
	        if(!K8S_VERSION.isEmpty()) {
	        	objectNode = checkAddObject(objectNode,K8S_VERSION, "kubernetesVersion");	        	
	        }
	        objectNode = checkAddObject(objectNode, node.getMetadata().getName(), "nodeName");
	        objectNode = checkAddObject(objectNode, getContainerStatus(node), "containerStatus");
	        int notRunningPodCount = getNotRunningPodCount(node.getMetadata().getName());
			objectNode=checkAddInt(objectNode, notRunningPodCount, "notRunningPodCount");

	        String clusterName = Utilities.ensureClusterName(config, node.getMetadata().getClusterName());
	        objectNode = checkAddObject(objectNode, clusterName, "clusterName");
	        arrayNode.add(objectNode);

	        ObjectNode labelsObject = Utilities.getResourceLabels(config,mapper, node);
            objectNode=checkAddObject(objectNode, labelsObject, "customLabels") ; 

	        
	        boolean isMaster = false;
            if (node.getMetadata().getLabels() != null) {
                String labels = "";
                Iterator it = node.getMetadata().getLabels().entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry pair = (Map.Entry) it.next();
                    if (!isMaster && pair.getKey().equals("node-role.kubernetes.io/master")) {
                        isMaster = true;
                    }
                    labels += String.format("%s:%s;", pair.getKey(), pair.getValue());
                    it.remove();
                }
                objectNode = checkAddObject(objectNode, labels, "labels");
            }
	        
	        
	        String nodeName=node.getMetadata().getName();
	        SummaryObj summaryNode = getSummaryMap().get(nodeName);
	        
	       // objectNode = checkAddObject(objectNode,getNotRunningPodCount(nodeName, config),"notRunningPodCount");
            if(Utilities.shouldCollectMetricsForNode(getConfiguration(), nodeName)) {
                if (summaryNode == null) {
                    summaryNode = updateNodeWiseNotRunningPodSummaryObject(config, nodeName, isMaster ? Constants.MASTER_NODE : Constants.WORKER_NODE,notRunningPodCount);
                    getSummaryMap().put(nodeName, summaryNode);
                    Globals.NODE_ROLE_MAP.put(nodeName, isMaster ? Constants.MASTER_NODE : Constants.WORKER_NODE);
                }
            }
 	        
	        if (arrayNode.size() >= batchSize) {
	            logger.info("Sending batch of {} notRunningPodCountPerNode records", arrayNode.size());
	            String payload = arrayNode.toString();
	            arrayNode = arrayNode.removeAll();
	            if (!payload.equals("[]")) {
	                UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
	                getConfiguration().getExecutorService().execute("UploadNotRunningPodCountPerNodeData", uploadEventsTask);
	            }
	        }
	    }

	    if (arrayNode.size() > 0) {
	        logger.info("Sending last batch of {} notRunningPodCountPerNode records", arrayNode.size());
	        String payload = arrayNode.toString();
	        arrayNode = arrayNode.removeAll();
	        if (!payload.equals("[]")) {
	            UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
	            getConfiguration().getExecutorService().execute("UploadNotRunningPodCountPerNodeData", uploadEventsTask);
	        }
	    }

	    return arrayNode;
	}
	
	public String getContainerStatus(V1Node node) throws Exception {
	    List<V1Pod> pods = getPodsFromNode(node);

	    StringBuilder containerStatusBuilder = new StringBuilder();

	    for (V1Pod pod : pods) {
	        if (!"Running".equals(pod.getStatus().getPhase())) {
	            List<V1ContainerStatus> containerStatuses = pod.getStatus().getContainerStatuses();
	            if(containerStatuses!=null) {
		            for (V1ContainerStatus containerStatus : containerStatuses) {
		                String containerName = containerStatus.getName();
		                String containerState = getContainerState(containerStatus.getState());
		                containerStatusBuilder.append(containerName).append(": ").append(containerState).append(", ");
		            }
	            }
	        }
	    }

	    // Remove the trailing comma and whitespace
	    if (containerStatusBuilder.length() > 0) {
	        containerStatusBuilder.setLength(containerStatusBuilder.length() - 2);
	    }

	    return containerStatusBuilder.toString();
	}

	private String getContainerState(V1ContainerState containerState) {
	    if (containerState.getWaiting() != null) {
	        return "Waiting: " + containerState.getWaiting().getReason();
	    } else if (containerState.getTerminated() != null) {
	        return "Terminated: " + containerState.getTerminated().getReason();
	    } else if (containerState.getRunning() != null) {
	        return "Running";
	    } else {
	        return "Unknown";
	    }
	}

	private List<V1Pod> getPodsFromNode(V1Node node) {
	    List<V1Pod> pods = new ArrayList<>();
	
	    String nodeName = node.getMetadata().getName();
        // Filter pods based on node name across all namespaces
        V1PodList podList = Globals.K8S_POD_LIST;
        for (V1Pod pod :podList.getItems()) {
			if(pod.getSpec().getNodeName().equalsIgnoreCase(nodeName)) {
				pods.add(pod);
			}				
		}	   
	    return pods;
	}



	private V1NodeList getNodesFromKubernetes(Map<String, String> config) throws Exception {
		  V1NodeList nodeList;
		try {
			ApiClient client = KubernetesClientSingleton.getInstance(config);
			CoreV1Api api =KubernetesClientSingleton.getCoreV1ApiClient(config);
		    this.setAPIServerTimeout(KubernetesClientSingleton.getInstance(config), K8S_API_TIMEOUT);
            Configuration.setDefaultApiClient(client);
            this.setCoreAPIServerTimeout(api, K8S_API_TIMEOUT);
            nodeList = api.listNode(null,
                false,
                null,
                null,
                null, 500,
                null,
                null,
                K8S_API_TIMEOUT,
	                false);    }
	    catch (Exception ex){
	        throw new Exception("Unable to connect to Kubernetes API server because it may be unavailable or the cluster credentials are invalid", ex);
	    }
	        return nodeList;
	
	}


	


	private static SummaryObj initNodeWiseNotRunningPodSummaryObject(Map<String, String> config, String node,String role) {
	    ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();

        summary.put("nodename", node);
	    summary.put("NotRunningPodCount", 0); // Add the new metric field with an initial value of 0

	    ArrayList<AppDMetricObj> metricsList = initMetrics(config, node);

	    String path = Utilities.getMetricsPath(config, ALL, node,role);
	    return new SummaryObj(summary, metricsList, path);
	}
	
	

	
	public static SummaryObj updateNodeWiseNotRunningPodSummaryObject(Map<String, String> config, String node,String role,int notRunningPodCountPerNode) {
	    ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();

        summary.put("nodename", node);
	    summary.put("NotRunningPodCount", notRunningPodCountPerNode); // Add the new metric field with an initial value of 0

	    ArrayList<AppDMetricObj> metricsList = initMetrics(config, node);

	    String path = Utilities.getMetricsPath(config, ALL, node,role);
	    return new SummaryObj(summary, metricsList, path);
	}
	
	public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String node) {
	    if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
	        return new ArrayList<AppDMetricObj>();
	    }

	    String clusterName = Utilities.ClusterName;
	    String parentSchema = config.get(CONFIG_SCHEMA_NAME_NOT_RUNNING_POD_COUNT);
	    String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|", Utilities.getClusterTierName(config));
	    ArrayList<AppDMetricObj> metricsList = new ArrayList<>();

	        
	   
	    metricsList.add(new AppDMetricObj("NotRunningPodCount", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                String.format("select * from %s where notRunningPodCount > 0 and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, node,null));
	    

	    return metricsList;
	}

	
	

}
