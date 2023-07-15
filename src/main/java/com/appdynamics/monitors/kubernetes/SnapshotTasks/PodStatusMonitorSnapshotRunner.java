package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_RECS_BATCH_SIZE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_POD_STATUS_MONITOR;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_POD_STATUS_MONITOR;
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
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1ReplicaSet;


public class PodStatusMonitorSnapshotRunner extends SnapshotRunnerBase {

	@Override
    protected SummaryObj initDefaultSummaryObject(Map<String, String> config){
        return initPodStatusMonitorSummaryObject(config, ALL,ALL);
    }
	

	public PodStatusMonitorSnapshotRunner(){

    }

    public PodStatusMonitorSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
	@Override
	public void run() {
	    AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
	    generatePodStatusMonitorSnapshot();
	}

    
	private void generatePodStatusMonitorSnapshot() {
	    logger.info("Proceeding to capture POD Status Monitor snapshot...");

	    Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
	    if (config != null) {
	        String apiKey = Utilities.getEventsAPIKey(config);
	        String accountName = Utilities.getGlobalAccountName(config);
	        URL publishUrl = ensureSchema(config, apiKey, accountName,CONFIG_SCHEMA_NAME_POD_STATUS_MONITOR, CONFIG_SCHEMA_DEF_POD_STATUS_MONITOR);

	        try {
	         

	            List<V1Pod> pods = getPods();
	            createPayload(pods, config, publishUrl, accountName, apiKey);
	            List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);

	            logger.info("About to send {} POD Status Monitor metrics", metricList.size());
	            UploadMetricsTask metricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
	            getConfiguration().getExecutorService().execute("UploadPodStatusMonitorMetricsTask", metricsTask);
	        } catch (IOException e) {
	            countDownLatch.countDown();
	            logger.error("Failed to push POD Status Monitor data", e);
	        } catch (Exception e) {
	            countDownLatch.countDown();
	            logger.error("Failed to push POD Status Monitor data", e);
	        }
	    }
	}
	


	public ArrayNode createPayload(List<V1Pod> pods, Map<String, String> config, URL publishUrl, String accountName, String apiKey) throws Exception {
	    ObjectMapper mapper = new ObjectMapper();
	    ArrayNode arrayNode = mapper.createArrayNode();
	    long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));

      
    
        for (V1Pod pod : pods) {
        	ObjectNode objectNode = mapper.createObjectNode();
  	      	if(!OPENSHIFT_VERSION.isEmpty()) {
              	  objectNode = checkAddObject(objectNode,OPENSHIFT_VERSION, "openshiftVersion");
               }
  	       if(!K8S_VERSION.isEmpty()) {
        	  objectNode = checkAddObject(objectNode,K8S_VERSION, "kubernetesVersion");	        	
	        }
             
           objectNode = checkAddObject(objectNode,pod.getSpec().getNodeName(), "nodeName");
           
           objectNode = checkAddObject(objectNode, pod.getMetadata().getNamespace(), "namespace");
            
            objectNode = checkAddObject(objectNode, getContainerName(pod), "containerName");
            objectNode = checkAddObject(objectNode, getDeploymentName(pod,config), "deploymentName");
            objectNode = checkAddObject(objectNode, pod.getMetadata().getName(), "podName");
            objectNode = checkAddObject(objectNode, getErrorReason(pod), "errorReason");
            objectNode = checkAddObject(objectNode, getContainerPhase(pod), "containerPhase");
            String clusterName = Utilities.ensureClusterName(config, pod.getMetadata().getClusterName());
	        objectNode = checkAddObject(objectNode, clusterName, "clusterName");
	      
	        SummaryObj summary = getSummaryMap().get(ALL);


           ObjectNode labelsObject = Utilities.getResourceLabels(config,mapper, pod);
          
           objectNode=checkAddObject(objectNode, labelsObject, "customLabels") ;  
            if (summary == null) {
                summary = initPodStatusMonitorSummaryObject(config, ALL, ALL);
                getSummaryMap().put(ALL, summary);
            }

           
            String namespace = pod.getMetadata().getNamespace();
            String nodeName = pod.getSpec().getNodeName();
          
	            SummaryObj summaryNamespace = getSummaryMap().get(namespace);
	            if (Utilities.shouldCollectMetricsForNamespace(getConfiguration(), namespace)) {
	                if (summaryNamespace == null) {
	                    summaryNamespace = initPodStatusMonitorSummaryObject(config, namespace, ALL);
	                    getSummaryMap().put(namespace, summaryNamespace);
	                }
	            }
	            SummaryObj summaryNode = getSummaryMap().get(nodeName);
	            boolean isMaster = false;
	            int masters = 0;
	            int workers = 0;
	         // Retrieve the Node object
	            if(nodeName!=null) {
		           V1Node nodeObj = getNode(config, nodeName);
		            // Extract the labels from the Node object
		            Map<String, String> labels = nodeObj.getMetadata().getLabels();
		            if (nodeObj.getMetadata().getLabels() != null) {
		                Iterator it = nodeObj.getMetadata().getLabels().entrySet().iterator();
		                while (it.hasNext()) {
		                    Map.Entry pair = (Map.Entry) it.next();
		                    if (!isMaster && pair.getKey().equals("node-role.kubernetes.io/master")) {
		                        isMaster = true;
		                    }
		                    it.remove();
		                }
		            }
		           
		            if(Utilities.shouldCollectMetricsForNode(getConfiguration(), nodeName)) {
		                if (summaryNode == null) {
		                    summaryNode = initPodStatusMonitorSummaryObjectNode(config, nodeName, isMaster ? Constants.MASTER_NODE : Constants.WORKER_NODE);
		                    getSummaryMap().put(nodeName, summaryNode);
		                    Globals.NODE_ROLE_MAP.put(nodeName, isMaster ? Constants.MASTER_NODE : Constants.WORKER_NODE);
		                }
		            }
	            }
            
            int notRunningCount=0;
            if (!"Running".equalsIgnoreCase(pod.getStatus().getPhase())) {
            	objectNode = checkAddInt(objectNode, notRunningCount, "NotRunningPodCount");
	        	notRunningCount++;
	        	Utilities.incrementField(summary, "NotRunningPodCount");
	            Utilities.incrementField(summaryNamespace, "NotRunningPodCount");
	            Utilities.incrementField(summaryNode, "NotRunningPodCount");
		    }
           
            objectNode = checkAddInt(objectNode, notRunningCount, "notRunningPodCount");
            int podRestarts = 0;

        
            if (pod.getStatus().getContainerStatuses() != null){
                for(V1ContainerStatus status : pod.getStatus().getContainerStatuses()){
                    int restarts = status.getRestartCount();
                    podRestarts += restarts;
                }
            }
            
            objectNode = checkAddInt(objectNode, podRestarts, "restartCount");
            Utilities.incrementField(summary, "RestartCount", podRestarts);
            Utilities.incrementField(summaryNamespace, "RestartCount", podRestarts);
            Utilities.incrementField(summaryNode, "RestartCount", podRestarts);
            arrayNode.add(objectNode);

            if (arrayNode.size() >= batchSize) {
                logger.info("Sending batch of {} POD Status Monitor records", arrayNode.size());
                String payload = arrayNode.toString();
                arrayNode = arrayNode.removeAll();
                if (!payload.equals("[]")) {
                    UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                    getConfiguration().getExecutorService().execute("UploadPodStatusMonitorData", uploadEventsTask);
                }
            }
        }
        
	      
	   

	    if (arrayNode.size() > 0) {
	        logger.info("Sending last batch of {} POD Status Monitor records", arrayNode.size());
	        String payload = arrayNode.toString();
	        arrayNode = arrayNode.removeAll();
	        if (!payload.equals("[]")) {
	            UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
	            getConfiguration().getExecutorService().execute("UploadPodStatusMonitorData", uploadEventsTask);
	        }
	    }

	    return arrayNode;
	}

	



	private String getContainerName(V1Pod pod) {
		String containerNames="";
		boolean flag=false;
		for(V1Container container : pod.getSpec().getContainers()){
			containerNames+=container.getName()+", ";
			flag=true;
		}
	    if(flag)
	    	containerNames= containerNames.substring(0,containerNames.length()-2);
	    return containerNames;
	}

	private String getDeploymentName(V1Pod pod, Map<String, String> config) throws Exception {
		 // Get the metadata of the pod
        V1ObjectMeta metadata = pod.getMetadata();

        // Get the owner references from the pod's metadata
        List<V1OwnerReference> ownerReferences = metadata.getOwnerReferences();
        
		ApiClient client = KubernetesClientSingleton.getInstance(config);
		AppsV1Api api =KubernetesClientSingleton.getAppsV1ApiClient(config);
	    this.setAPIServerTimeout(KubernetesClientSingleton.getInstance(config), K8S_API_TIMEOUT);
        Configuration.setDefaultApiClient(client);
       
        
        // Find the owner reference with the "ReplicaSet" kind
        V1OwnerReference replicaSetReference = null;
        if (ownerReferences !=null) {
            for (V1OwnerReference ref : ownerReferences) {
                if (ref.getKind().equals("ReplicaSet")) {
                    replicaSetReference = ref;
                    break;
                }
            }

            if (replicaSetReference != null) {
                String replicaSetName = replicaSetReference.getName();

                // Get the ReplicaSet object
                V1ReplicaSet replicaSet = api.readNamespacedReplicaSet(replicaSetName,pod.getMetadata().getNamespace(), null);

                // Get the owner references from the ReplicaSet's metadata
                List<V1OwnerReference> rsOwnerReferences = replicaSet.getMetadata().getOwnerReferences();

                // Find the owner reference with the "Deployment" kind
                V1OwnerReference deploymentReference = null;
                if(rsOwnerReferences!=null) {
	                for (V1OwnerReference ref : rsOwnerReferences) {
	                    if (ref.getKind().equals("Deployment")) {
	                        deploymentReference = ref;
	                        break;
	                    }
	                }
                }
                else {
                	
                	logger.info("rsOwnerReferences is null for {} and {}",replicaSetName , replicaSet.getMetadata().getName() );
                }

                if (deploymentReference != null) {
                    String deploymentName = deploymentReference.getName();
                   return deploymentName;
                }
            } 
        }
		return "";
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

	private String getContainerPhase(V1Pod pod) {
	    if (pod.getStatus() != null) {
	        String phase = pod.getStatus().getPhase();
	        // Retrieve the container phase from the pod's status phase
	        return phase;
	    }
	    return "";
	}



	
	private List<V1Pod> getPods()  {
		return Globals.K8S_POD_LIST.getItems();
	}


	private V1Node getNode(Map<String, String> config,String nodeName) throws Exception {

		ApiClient client = KubernetesClientSingleton.getInstance(config);
		CoreV1Api api =KubernetesClientSingleton.getCoreV1ApiClient(config);
	    this.setAPIServerTimeout(KubernetesClientSingleton.getInstance(config), K8S_API_TIMEOUT);
        Configuration.setDefaultApiClient(client);
        this.setCoreAPIServerTimeout(api, K8S_API_TIMEOUT);
         V1Node node = api.readNode(nodeName, null);
         
	    return node;
	}

	
	


	
	public static SummaryObj initPodStatusMonitorSummaryObject(Map<String, String> config, String namespace,String node) {
	    ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();
        summary.put("namespace", namespace);
        summary.put("nodename", node);
	    summary.put("NotRunningPodCount",0);

	    summary.put("RestartCount", 0);

	    ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace,node);

	    String path = Utilities.getMetricsPath(config, namespace, node);

	    return new SummaryObj(summary, metricsList, path);
	}
	
	private SummaryObj initPodStatusMonitorSummaryObjectNode(Map<String, String> config, String node,
			String role) {
       
        ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();
        summary.put("namespace", ALL);
        summary.put("nodename", node);
	    summary.put("NotRunningPodCount",0);

	    summary.put("RestartCount", 0);
	    ArrayList<AppDMetricObj> metricsList = initMetrics(config, node);
        String path = Utilities.getMetricsPath(config, ALL, node,role);
		return new SummaryObj(summary, metricsList, path);
	}


	 public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String node){


	        
	        if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
		        return new ArrayList<AppDMetricObj>();
		    }

		    String clusterName = Utilities.ClusterName;
		    String parentSchema = config.get(CONFIG_SCHEMA_NAME_POD_STATUS_MONITOR);
		    String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats", Utilities.getClusterTierName(config));
		    ArrayList<AppDMetricObj> metricsList = new ArrayList<>();

		    String namespacesCondition = "";
	        String nodeCondition = "";
		  
	        if(node != null && !node.equals(ALL)){
	            nodeCondition = String.format("and nodeName = \"%s\"", node);
	        }

	        String filter = namespacesCondition.isEmpty() ? nodeCondition : namespacesCondition;
	   	    metricsList.add(new AppDMetricObj("NotRunningPodCount", parentSchema,CONFIG_SCHEMA_DEF_POD_STATUS_MONITOR,
	                String.format("select * from %s where clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, ALL, node,null));

	   	    metricsList.add(new AppDMetricObj("RestartCount", parentSchema,CONFIG_SCHEMA_DEF_POD_STATUS_MONITOR,
	                String.format("select * from %s where clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, ALL, node,null));

		    
		    return metricsList;
	
	    }
	public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String namespace,String node) {
	    if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
	        return new ArrayList<AppDMetricObj>();
	    }

	    String clusterName = Utilities.ClusterName;
	    String parentSchema = config.get(CONFIG_SCHEMA_NAME_POD_STATUS_MONITOR);
	    String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats", Utilities.getClusterTierName(config));
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
   	    metricsList.add(new AppDMetricObj("NotRunningPodCount", parentSchema,CONFIG_SCHEMA_DEF_POD_STATUS_MONITOR,
                String.format("select * from %s where clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node,null));

   	    metricsList.add(new AppDMetricObj("RestartCount", parentSchema,CONFIG_SCHEMA_DEF_POD_STATUS_MONITOR,
                String.format("select * from %s where clusterName = \"%s\" %s ORDER BY creationTimestamp DESC", parentSchema, clusterName, filter), rootPath, namespace, node,null));

	    
	    return metricsList;
	}


	

}
