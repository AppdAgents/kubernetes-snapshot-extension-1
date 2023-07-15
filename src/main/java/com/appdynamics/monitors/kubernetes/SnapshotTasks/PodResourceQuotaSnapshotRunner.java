package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_RECS_BATCH_SIZE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_POD_RESOURCE_QUOTA;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_POD_RESOURCE_QUOTA;
import static com.appdynamics.monitors.kubernetes.Constants.K8S_VERSION;
import static com.appdynamics.monitors.kubernetes.Constants.OPENSHIFT_VERSION;
import static com.appdynamics.monitors.kubernetes.Utilities.ALL;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddFloat;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddInt;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
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
import com.appdynamics.monitors.kubernetes.MicroserviceData;
import com.appdynamics.monitors.kubernetes.Utilities;
import com.appdynamics.monitors.kubernetes.Metrics.UploadMetricsTask;
import com.appdynamics.monitors.kubernetes.Models.AppDMetricObj;
import com.appdynamics.monitors.kubernetes.Models.SummaryObj;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1ReplicaSet;

public class PodResourceQuotaSnapshotRunner  extends SnapshotRunnerBase {

	@Override
    protected SummaryObj initDefaultSummaryObject(Map<String, String> config){
        return initPODResourceQuotaSummaryObject(config, ALL,ALL,"");
    }

	

	public PodResourceQuotaSnapshotRunner(){

    }

    public PodResourceQuotaSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
	@Override
	public void run() {
	    AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
	    generatePodResourceQuotaSnapshot();
	}

    
	
	private void generatePodResourceQuotaSnapshot() {
	    logger.info("Proceeding to capture POD Resource Quota snapshot...");

	    Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
	    if (config != null) {
	        String apiKey = Utilities.getEventsAPIKey(config);
	        String accountName = Utilities.getGlobalAccountName(config);
	        URL publishUrl = Utilities.ensureSchema(config, apiKey, accountName, CONFIG_SCHEMA_NAME_POD_RESOURCE_QUOTA, CONFIG_SCHEMA_DEF_POD_RESOURCE_QUOTA);

	        try {
	        	V1PodList pods =  Globals.K8S_POD_LIST;

	            createPayload(pods, config, publishUrl, accountName, apiKey);
	            List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);

	            logger.info("About to send {} POD Resource Quota metrics", metricList.size());
	            UploadMetricsTask metricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
	            getConfiguration().getExecutorService().execute("UploadPodResourceQuotaMetricsTask", metricsTask);
	        } catch (Exception  e) {
	            countDownLatch.countDown();
	            logger.error("Failed to push POD Resource Quota data", e);
	        } 
	    }
	}

	private V1PodList getPodsFromKubernetes(Map<String, String> config) throws Exception {
		
		V1PodList podList;
		try {
			ApiClient client = KubernetesClientSingleton.getInstance(config);
			CoreV1Api api =KubernetesClientSingleton.getCoreV1ApiClient(config);
		    this.setAPIServerTimeout(KubernetesClientSingleton.getInstance(config), K8S_API_TIMEOUT);
            Configuration.setDefaultApiClient(client);
            this.setCoreAPIServerTimeout(api, K8S_API_TIMEOUT);
            podList = api.listPodForAllNamespaces(null,
	          null,
	          null,
	          null,
	          null,
	          null,
	          null,
	          null,
	          null, null);
		}
	  catch (Exception ex){
	      throw new Exception("Unable to connect to Kubernetes API server because it may be unavailable or the cluster credentials are invalid", ex);
	  }
	  
		return podList;
	
	}
	
	private SummaryObj initPODResourceQuotaSummaryObject(Map<String, String> config, String namespace, String node, String msName) {
	    ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();
	    summary.put("MsServiceName", msName);
	    summary.put("namespace", namespace);
	    summary.put("PodCount", 0);
	    summary.put("CpuRequest", 0);
	    summary.put("CpuLimit", 0);
	    summary.put("MemoryLimits", 0);
	    summary.put("MemoryRequests", 0);
	    

	    ArrayList<AppDMetricObj> metricsList = initMetrics(config,namespace, node,msName);

	    String path = Utilities.getMetricsPath(config,msName); 

	    return new SummaryObj(summary, metricsList, path);
	}

	
	private SummaryObj updatePODResourceQuotaSummaryObject(Map<String, String> config, String namespace, String node,MicroserviceData microserviceData) {
	    ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();
	    summary.put("namespace", namespace);
	    summary.put("MsServiceName", microserviceData.getServiceName());
	    summary.put("PodCount", microserviceData.getPodCount());
	    summary.put("CpuRequest",microserviceData.getAverageCPURequest());
	    summary.put("CpuLimit", microserviceData.getAverageCPULimit());
	    summary.put("MemoryLimits", microserviceData.getAverageMemoryLimits());
	    summary.put("MemoryRequests", microserviceData.getAverageMemoryRequests());
	    

	    ArrayList<AppDMetricObj> metricsList = initMetrics(config,namespace, node,microserviceData.getServiceName());
        String path="";
	    if(namespace.equals(ALL)) {
        	path = Utilities.getMetricsPath(config, namespace, microserviceData ); 
        }
        else
        {
        	path = Utilities.getMetricsPath(config,microserviceData.getServiceName()); 
        }
	    

	    return new SummaryObj(summary, metricsList, path);
	}
	
	private SummaryObj updatePODResourceQuotaSummaryObject(Map<String, String> config, MicroserviceData microserviceData,String node,
			String masterWorker) {
		 ObjectMapper mapper = new ObjectMapper();
		    ObjectNode summary = mapper.createObjectNode();
		    String namespace=microserviceData.getNamespace();
		
		    summary.put("namespace",namespace);
		    summary.put("MsServiceName", microserviceData.getServiceName());
		    summary.put("PodCount", microserviceData.getPodCount());
		    summary.put("CpuRequest",microserviceData.getAverageCPURequest());
		    summary.put("CpuLimit", microserviceData.getAverageCPULimit());
		    summary.put("MemoryLimits", microserviceData.getAverageMemoryLimits());
		    summary.put("MemoryRequests", microserviceData.getAverageMemoryRequests());
		    

		    ArrayList<AppDMetricObj> metricsList = initMetrics(config,namespace, node,microserviceData.getServiceName());
	       String path = Utilities.getMetricsPath(config, namespace, node,microserviceData ,masterWorker); 
	        

		return  new SummaryObj(summary, metricsList, path);
	}

	
	private ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String namespace, String node,String msServiceName) {
	    if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
	        return new ArrayList<AppDMetricObj>();
	    }

	    String clusterName = Utilities.ClusterName;
	    String parentSchema = config.get(CONFIG_SCHEMA_NAME_POD_RESOURCE_QUOTA);
	    String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|", Utilities.getClusterTierName(config));
	    
	    
        String namespacesCondition = "";
        String nodeCondition = "";

        if(msServiceName != null && !msServiceName.equals(ALL)){
            nodeCondition = String.format("and msServiceName = \"%s\"", msServiceName);
        }else
        if( !namespace.equals(ALL)){
        	namespacesCondition = String.format("and msServiceName = \"%s\"", msServiceName);
        }

        String filter = namespacesCondition.isEmpty() ? nodeCondition : namespacesCondition;
	    ArrayList<AppDMetricObj> metricsList = new ArrayList<>();

	
	    metricsList.add(new AppDMetricObj("PodCount", parentSchema, CONFIG_SCHEMA_DEF_POD_RESOURCE_QUOTA,
	            String.format("select PodCount from %s where  clusterName = \"%s\" ", parentSchema, clusterName,filter), rootPath, namespace,node, msServiceName));

	  	    metricsList.add(new AppDMetricObj("CpuRequest", parentSchema, CONFIG_SCHEMA_DEF_POD_RESOURCE_QUOTA,
	  	            String.format("select CpuRequest  from %s where   clusterName = \"%s\" ", parentSchema, clusterName,filter), rootPath, namespace,node, msServiceName));

	  	    metricsList.add(new AppDMetricObj("CpuLimit", parentSchema, CONFIG_SCHEMA_DEF_POD_RESOURCE_QUOTA,
	  	            String.format("select CpuLimit from %s where namespace and clusterName = \"%s\" ", parentSchema, clusterName,filter), rootPath,  namespace,node, msServiceName));

	  	    metricsList.add(new AppDMetricObj("MemoryRequests", parentSchema, CONFIG_SCHEMA_DEF_POD_RESOURCE_QUOTA,
	  	            String.format("select MemoryRequests  from %s where  and clusterName = \"%s\" ", parentSchema, clusterName,filter), rootPath,  namespace,node, msServiceName));

	  	    metricsList.add(new AppDMetricObj("MemoryRequests", parentSchema, CONFIG_SCHEMA_DEF_POD_RESOURCE_QUOTA,
	  	            String.format("select MemoryLimits as average from %s where  and clusterName = \"%s\" e", parentSchema, clusterName,filter), rootPath, namespace,node, msServiceName));

	  	    
	    return metricsList;
	}
	
	public ArrayNode createPayload(V1PodList pods, Map<String, String> config,URL publishUrl, String accountName, String apiKey) {
	    ObjectMapper mapper = new ObjectMapper();
	    ArrayNode arrayNode = mapper.createArrayNode();
	    long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));

	    Map<String, MicroserviceData> microserviceDataMap = new HashMap<String, MicroserviceData>();
	    Map<String, V1Node> nodeObjectMap = new HashMap<String, V1Node>();

	    for (V1Pod pod : pods.getItems()) {
	        String serviceName = getDeploymentName(pod);
	        V1Node nodeObject=null;
	        if(!serviceName.isEmpty()) {
		        String namespace = pod.getMetadata().getNamespace();
		        MicroserviceData microserviceData = microserviceDataMap.get(serviceName);
		        String nodeName=pod.getSpec().getNodeName();
		        if(nodeObjectMap.containsKey(apiKey))  {
		        	nodeObject=nodeObjectMap.get(nodeName);
		        }
		        else{
		        	nodeObject=getNodeforPod(config,pod.getSpec().getNodeName());
		        	nodeObjectMap.put(nodeName, nodeObject);
		        }
		        if (microserviceData == null) {
		            microserviceData = new MicroserviceData(serviceName,namespace);
		            microserviceDataMap.put(serviceName, microserviceData);
		        }
		        ObjectNode labelsObject = Utilities.getResourceLabels(config, mapper, pod);
		     
		        microserviceData.incrementPodCount();
		        microserviceData.addCPURequest(getCPURequest(pod));
		        microserviceData.addCPULimit(getCPULimit(pod));
		        microserviceData.addMemoryRequests(getMemoryRequests(pod));
		        microserviceData.addMemoryLimits(getMemoryLimits(pod));
		        String clusterName = Utilities.ensureClusterName(config, pod.getMetadata().getClusterName());
		        microserviceData.setClusterName(clusterName);
		        microserviceData.setLabels(labelsObject);
		        microserviceData.setNodeObject(nodeObject);
		    }
	    }
	   

		    for (Map.Entry<String, MicroserviceData> entry : microserviceDataMap.entrySet()) {
		       
		        MicroserviceData microserviceData = entry.getValue();
		        ObjectNode objectNode = mapper.createObjectNode();
		        String namespace=microserviceData.getNamespace();
		        objectNode= checkAddInt(objectNode, microserviceData.getPodCount(), "podCount");
		        objectNode=checkAddObject(objectNode, microserviceData.getServiceName(), "msServiceName");
		        objectNode=checkAddFloat(objectNode, microserviceData.getAverageCPURequest(), "cpuRequest");
		        objectNode=checkAddFloat(objectNode, microserviceData.getAverageCPULimit(), "cpuLimit");
		        objectNode=checkAddFloat(objectNode, microserviceData.getAverageMemoryRequests(), "memoryRequests");
		        objectNode=checkAddFloat(objectNode, microserviceData.getAverageMemoryLimits(), "memoryLimits");
		        
		      
		        objectNode=checkAddObject(objectNode, microserviceData.getLabels(), "customLabels") ; 
		        SummaryObj summary = getSummaryMap().get(microserviceData.getServiceName());
	            if (summary == null) {
	                summary = updatePODResourceQuotaSummaryObject(config, ALL, ALL,microserviceData);
	                getSummaryMap().put(microserviceData.getServiceName(), summary);
	            }
	
	            SummaryObj summaryNamespace = getSummaryMap().get(namespace+"-"+microserviceData.getServiceName());
	            if(Utilities.shouldCollectMetricsForNamespace(getConfiguration(),namespace)) {
		                if (summaryNamespace == null) {
		                    summaryNamespace = updatePODResourceQuotaSummaryObject(config, namespace, ALL,microserviceData);
		                    getSummaryMap().put(namespace+"-"+microserviceData.getServiceName(), summaryNamespace);
		                }
	            }
		        
		        
	            V1Node nodeObj = microserviceData.getNodeObject();
	            logger.debug("createPayload microserviceData getNodeObject:"+nodeObj);
	            if(nodeObj!=null && (nodeObj.getMetadata()!=null)) {
			            String nodeNameKey=microserviceData.getServiceName()+"-"+nodeObj.getMetadata().getName();
			            boolean isMaster = false;
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
		
			            SummaryObj summaryNode = getSummaryMap().get(nodeNameKey);
			            if(Utilities.shouldCollectMetricsForNode(getConfiguration(), nodeObj.getMetadata().getName())) {
			                if (summaryNode == null) {
			                    summaryNode = updatePODResourceQuotaSummaryObject(config, microserviceData,nodeObj.getMetadata().getName(), isMaster ? Constants.MASTER_NODE : Constants.WORKER_NODE);
			                    getSummaryMap().put(nodeNameKey, summaryNode);
			                }
			            }
	            	
	            }
	
	
		       if(!OPENSHIFT_VERSION.isEmpty()) {
		    	   objectNode = checkAddObject(objectNode, OPENSHIFT_VERSION, "openshiftVersion");				
		       }
		       if(!K8S_VERSION.isEmpty()) {
		        	objectNode = checkAddObject(objectNode,K8S_VERSION, "kubernetesVersion");	        	
		        }
		        objectNode = checkAddObject(objectNode, microserviceData.getClusterName(), Constants.CLUSTER_NAME);
		        arrayNode.add(objectNode);
	
		        if (arrayNode.size() >= batchSize) {
		            logger.info("Sending batch of {} Openshift POD Resource Quota records", arrayNode.size());
		            String payload = arrayNode.toString();
		            arrayNode = arrayNode.removeAll();
		            if (!payload.equals("[]")) {
		                UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
		                getConfiguration().getExecutorService().execute("UploadPodResourceQuotaData", uploadEventsTask);
		            }
		        }
		    }
	    

	    if (arrayNode.size() > 0) {
	        logger.info("Sending last batch of {} Openshift POD Resource Quota records", arrayNode.size());
	        String payload = arrayNode.toString();
	        arrayNode = arrayNode.removeAll();
	        if (!payload.equals("[]")) {
	        	 UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
	                getConfiguration().getExecutorService().execute("UploadPodResourceQuotaData", uploadEventsTask);
	        }
	    }

	    return arrayNode;
	}

	private V1Node getNodeforPod(Map<String, String> config, String nodeName) {
		
		try {
			ApiClient client = KubernetesClientSingleton.getInstance(config);
			CoreV1Api api =KubernetesClientSingleton.getCoreV1ApiClient(config);
		    this.setAPIServerTimeout(KubernetesClientSingleton.getInstance(config), K8S_API_TIMEOUT);
            Configuration.setDefaultApiClient(client);
            this.setCoreAPIServerTimeout(api, K8S_API_TIMEOUT);
			return api.readNode(nodeName, null);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}



	 private static String  getDeploymentName(V1Pod pod)  {
	 // Get the metadata of the pod
		try {
		        V1ObjectMeta metadata = pod.getMetadata();
	
	        // Get the owner references from the pod's metadata
	        List<V1OwnerReference> ownerReferences = metadata.getOwnerReferences();
	        
	
	
	        AppsV1Api appsApi = new AppsV1Api();
	        
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
	                V1ReplicaSet replicaSet;
			
	                replicaSet = appsApi.readNamespacedReplicaSet(replicaSetName,pod.getMetadata().getNamespace(), null);
		
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
		} catch (ApiException e) {
			logger.info("Error on getting deployment name for pod {}",pod.getMetadata().getName() );
		}
		return "";
	}

	private float getCPURequest(V1Pod pod) {
	    if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
	        float cpuRequest = 0;
	        for (V1Container container : pod.getSpec().getContainers()) {
	            if (container.getResources() != null && container.getResources().getRequests() != null) {
	                Quantity quantity = container.getResources().getRequests().get("cpu");
	                if (quantity != null) {
	                    cpuRequest += convertCPUQuantityToMilliCores(quantity);
	                }
	            }
	        }
	        return cpuRequest;
	    }
	    return 0;
	}

	private float getCPULimit(V1Pod pod) {
	    if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
	        float cpuLimit = 0;
	        for (V1Container container : pod.getSpec().getContainers()) {
	            if (container.getResources() != null && container.getResources().getLimits() != null) {
	                Quantity quantity = container.getResources().getLimits().get("cpu");
	                if (quantity != null) {
	                    cpuLimit += convertCPUQuantityToMilliCores(quantity);
	                }
	            }
	        }
	        return cpuLimit;
	    }
	    return 0;
	}

	private float getMemoryRequests(V1Pod pod) {
	    if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
	        float memoryRequests = 0;
	        for (V1Container container : pod.getSpec().getContainers()) {
	            if (container.getResources() != null && container.getResources().getRequests() != null) {
	                Quantity quantity = container.getResources().getRequests().get("memory");
	                if (quantity != null) {
	                    memoryRequests += convertMemoryQuantityToMegabytes(quantity);
	                }
	            }
	        }
	        return memoryRequests;
	    }
	    return 0;
	}

	private float getMemoryLimits(V1Pod pod) {
	    if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
	        float memoryLimits = 0;
	        for (V1Container container : pod.getSpec().getContainers()) {
	            if (container.getResources() != null && container.getResources().getLimits() != null) {
	                Quantity quantity = container.getResources().getLimits().get("memory");
	                if (quantity != null) {
	                    memoryLimits += convertMemoryQuantityToMegabytes(quantity);
	                }
	            }
	        }
	        return memoryLimits;
	    }
	    return 0;
	}

	private float convertCPUQuantityToMilliCores(Quantity cpuQuantity) {
	    float value = cpuQuantity.getNumber().floatValue();
	  
	    
	        return value * 1000;
	   
	}

	private float convertMemoryQuantityToMegabytes(Quantity memoryQuantity) {
		    	   
	    return memoryQuantity.getNumber().divide(BigDecimal.valueOf(1024 * 1024), 2, RoundingMode.HALF_UP).floatValue();
	}
	

	
}


