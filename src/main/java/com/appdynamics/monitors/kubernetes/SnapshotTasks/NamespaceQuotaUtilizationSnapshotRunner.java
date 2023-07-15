package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_RECS_BATCH_SIZE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_NAMESPACE_QUOTA_UTILIZATION;
import static com.appdynamics.monitors.kubernetes.Constants.K8S_VERSION;
import static com.appdynamics.monitors.kubernetes.Constants.OPENSHIFT_VERSION;
import static com.appdynamics.monitors.kubernetes.Utilities.ALL;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddFloat;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;

public class NamespaceQuotaUtilizationSnapshotRunner  extends SnapshotRunnerBase {


	@Override
    protected SummaryObj initDefaultSummaryObject(Map<String, String> config){
        return initNamespaceQuotaUtilizationSummaryObject(config, ALL,ALL);
    }
	
	public NamespaceQuotaUtilizationSnapshotRunner(){

    }

    public NamespaceQuotaUtilizationSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
    }

    @SuppressWarnings("unchecked")
	@Override
	public void run() {
	    AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
	    generateNamespaceQuotaUtilizationSnapshot();
    }

	
	private SummaryObj initNamespaceQuotaUtilizationSummaryObject(Map<String, String> config, String namespace,String  node) {
	    ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();
	    summary.put("namespace", namespace);

	    
	    summary.put("CPURequestUsed", 0);
	    summary.put("CPURequestTotal", 0);
	    summary.put("CPULimitsUsed", 0);
	    summary.put("CPULimitsTotal", 0);
	    summary.put("MemoryRequestUsed", 0);
	    summary.put("MemoryRequestTotal", 0);
	    summary.put("MemoryLimitsUsed", 0);
	    summary.put("MemoryLimitsTotal", 0);
	    
	    ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace,node);

	    String path = Utilities.getMetricsPath(config, namespace, node);

	    return new SummaryObj(summary, metricsList, path);
	}
	
	
	private SummaryObj updateNamespaceQuotaUtilizationSummaryObject(Map<String, String> config, String namespace,String  node, Map<String, Float> metrics) {
	    ObjectMapper mapper = new ObjectMapper();
	    ObjectNode summary = mapper.createObjectNode();
	    summary.put("namespace", namespace);
	    
	    summary.put("CPURequestUsed", metrics.get("CpuRequestUsed").floatValue());
	    summary.put("CPURequestTotal", metrics.get("CpuRequestTotal").floatValue());
	    summary.put("CPULimitsUsed",metrics.get("CpuLimitsUsed").floatValue());
	    summary.put("CPULimitsTotal",metrics.get("CpuLimitsTotal").floatValue());
	    summary.put("MemoryRequestUsed", metrics.get("MemoryRequestUsed").floatValue());
	    summary.put("MemoryRequestTotal", metrics.get("MemoryRequestTotal").floatValue());
	    summary.put("MemoryLimitsUsed",metrics.get("MemoryLimitsUsed").floatValue());
	    summary.put("MemoryLimitsTotal",metrics.get("MemoryLimitsTotal").floatValue());
	  
	    ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace,node);

	    String path = Utilities.getMetricsPath(config, namespace, node);

	    return new SummaryObj(summary, metricsList, path);
	}
	
	private void generateNamespaceQuotaUtilizationSnapshot() {
	    logger.info("Proceeding to capture Namespace Quota Utilization snapshot...");

	    Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
	    if (config != null) {
	        String apiKey = Utilities.getEventsAPIKey(config);
	        String accountName = Utilities.getGlobalAccountName(config);
	        URL publishUrl = Utilities.ensureSchema(config, apiKey, accountName, CONFIG_SCHEMA_NAME_NAMESPACE_QUOTA_UTILIZATION, CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION);

	        try {
	            V1NamespaceList namespaces = getNamespacesFromKubernetes(config);

	            createPayload(namespaces, config, publishUrl, accountName, apiKey);
	            List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);

	            logger.info("About to send {} Namespace Quota Utilization metrics", metricList.size());
	            UploadMetricsTask metricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
	            getConfiguration().getExecutorService().execute("UploadNamespaceQuotaUtilizationMetricsTask", metricsTask);
	        } catch (IOException e) {
	            countDownLatch.countDown();
	            logger.error("Failed to push Namespace Quota Utilization data", e);
	        } catch (Exception e) {
	            countDownLatch.countDown();
	            logger.error("Failed to push Namespace Quota Utilization data", e);
	        }
	    }
	}
	


	public V1NamespaceList getNamespacesFromKubernetes(Map<String, String> config) throws Exception {
	   

	   try { 
		   ApiClient client = KubernetesClientSingleton.getInstance(config);
			CoreV1Api api =KubernetesClientSingleton.getCoreV1ApiClient(config);
		    this.setAPIServerTimeout(KubernetesClientSingleton.getInstance(config), K8S_API_TIMEOUT);
           Configuration.setDefaultApiClient(client);
           this.setCoreAPIServerTimeout(api, K8S_API_TIMEOUT);
	    

           return api.listNamespace(null, null, null, null, null, null, null, null, null, null);
	   }
	    catch (Exception ex){
	        throw new Exception("Unable to connect to Kubernetes API server because it may be unavailable or the cluster credentials are invalid", ex);
	    }
	    
	}
	
	public ArrayNode createPayload(V1NamespaceList namespaces, Map<String, String> config, URL publishUrl, String accountName, String apiKey) throws Exception {
	    ObjectMapper mapper = new ObjectMapper();
	    ArrayNode arrayNode = mapper.createArrayNode();
	    long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));
	   
	    
	    for (V1Namespace namespaceObj : namespaces.getItems()) {
	    	String namespace=namespaceObj.getMetadata().getName();
	        ObjectNode objectNode = mapper.createObjectNode();
	        
	        Map<String, Float> metrics= collectNamespaceMetrics( namespace);
	        if(!OPENSHIFT_VERSION.isEmpty()) {
	        	objectNode = checkAddObject(objectNode,OPENSHIFT_VERSION, "openshiftVersion");	        	
	        }
	        
	        if(!K8S_VERSION.isEmpty()) {
	        	objectNode = checkAddObject(objectNode,K8S_VERSION, "kubernetesVersion");	        	
	        }
	        
	        objectNode = checkAddObject(objectNode,namespaceObj.getMetadata().getName(), "namespace");
	        objectNode = checkAddFloat(objectNode,metrics.get("CpuRequestUsed"), "cpuRequestUsed");
	        objectNode = checkAddFloat(objectNode,metrics.get("CpuRequestTotal"), "cpuRequestTotal");
	        objectNode = checkAddFloat(objectNode,metrics.get("CpuLimitsUsed"), "cpuLimitsUsed");
	        objectNode = checkAddFloat(objectNode,metrics.get("CpuLimitsTotal"), "cpuLimitsTotal");
	        objectNode = checkAddFloat(objectNode,metrics.get("MemoryRequestUsed"), "memoryRequestUsed");
	        objectNode = checkAddFloat(objectNode,metrics.get("MemoryRequestTotal"), "memoryRequestTotal");
	        objectNode = checkAddFloat(objectNode,metrics.get("MemoryLimitsTotal"), "memoryLimitsTotal");
	        objectNode = checkAddFloat(objectNode,metrics.get("MemoryLimitsUsed"), "memoryLimitsUsed");
		     
            ObjectNode labelsObject = Utilities.getResourceLabels(config,mapper, namespaceObj);
            objectNode=checkAddObject(objectNode, labelsObject, "customLabels") ; 
	        String clusterName = Utilities.ensureClusterName(config, namespaceObj.getMetadata().getClusterName());
	        SummaryObj summary = getSummaryMap().get(ALL);
            if (summary == null) {
                summary = updateNamespaceQuotaUtilizationSummaryObject(config, ALL, ALL,metrics);
                getSummaryMap().put(ALL, summary);
            }

            SummaryObj summaryNamespace = getSummaryMap().get(namespace);
            if (Utilities.shouldCollectMetricsForNamespace(getConfiguration(), namespaceObj.getMetadata().getName())) {
                if (summaryNamespace == null) {
                    summaryNamespace = updateNamespaceQuotaUtilizationSummaryObject(config, namespace, ALL,metrics);
                    getSummaryMap().put(namespace, summaryNamespace);
                }
            }
           
	        objectNode = checkAddObject(objectNode, clusterName, "clusterName");
	        arrayNode.add(objectNode);

	        if (arrayNode.size() >= batchSize) {
	            logger.info("Sending batch of {} Namespace Quota Utilization records", arrayNode.size());
	            String payload = arrayNode.toString();
	            arrayNode = arrayNode.removeAll();
	            if (!payload.equals("[]")) {
	            	  UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
	                    getConfiguration().getExecutorService().execute("UploadNamespaceQuotaUtilizationData", uploadEventsTask);
	            }
	        }
	    }

	    if (arrayNode.size() > 0) {
	        logger.info("Sending last batch of {} Namespace Quota Utilization records", arrayNode.size());
	        String payload = arrayNode.toString();
	        arrayNode = arrayNode.removeAll();
	        if (!payload.equals("[]")) {
	        	 UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                 getConfiguration().getExecutorService().execute("UploadNamespaceQuotaUtilizationData", uploadEventsTask);
	        }
	    }

	    return arrayNode;
	}
	
	
	public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String namespace,String node) {
	    if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
	        return new ArrayList<>();
	    }

	    String clusterName = Utilities.ClusterName;
	    String parentSchema = config.get(CONFIG_SCHEMA_NAME_NAMESPACE_QUOTA_UTILIZATION);
	    String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|", Utilities.getClusterTierName(config));
	    ArrayList<AppDMetricObj> metricsList = new ArrayList<>();

	    String nodeCondition = "";
        String namespacesCondition="";
		if(namespace != null && !namespace.equals(ALL)){
            namespacesCondition = String.format("and namespace = \"%s\"", namespace);
        }


        String filter = namespacesCondition.isEmpty() ? nodeCondition : namespacesCondition;


	   
	        metricsList.add(new AppDMetricObj("CPURequestUsed", parentSchema, CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION,
	                String.format("SELECT CPURequestUsed FROM %s WHERE clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, node,null));
	        metricsList.add(new AppDMetricObj("CPURequestTotal", parentSchema, CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION,
	                String.format("SELECT CPURequestTotal FROM %s WHERE clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, node,null));
	        metricsList.add(new AppDMetricObj("CPULimitsUsed", parentSchema, CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION,
	                String.format("SELECT CPULimitsUsed FROM %s WHERE clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, node,null));
	        metricsList.add(new AppDMetricObj("CPULimitsTotal", parentSchema, CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION,
	                String.format("SELECT CPULimitsTotal FROM %s WHERE clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, node,null));
	        metricsList.add(new AppDMetricObj("MemoryRequestUsed", parentSchema, CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION,
	                String.format("SELECT MemoryRequestUsed FROM %s WHERE clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, node,null));
	        metricsList.add(new AppDMetricObj("MemoryRequestTotal", parentSchema, CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION,
	                String.format("SELECT MemoryRequestTotal FROM %s WHERE clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, node,null));
	        metricsList.add(new AppDMetricObj("MemoryLimitsUsed", parentSchema, CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION,
	                String.format("SELECT MemoryLimitsUsed FROM %s WHERE clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, node,null));
	        metricsList.add(new AppDMetricObj("MemoryLimitsTotal", parentSchema,CONFIG_SCHEMA_DEF_NAMESPACE_QUOTA_UTILIZATION,
	                String.format("SELECT MemoryLimitsTotal FROM %s WHERE clusterName = \"%s\" %s", parentSchema, clusterName, filter), rootPath, namespace, node,null));



	    return metricsList;
	}



	private static Map<String, Float> collectNamespaceMetrics( String namespace) {
		try (KubernetesClient client = new DefaultKubernetesClient()) {
			Map<String, Float> metrics = new HashMap<String, Float>();

			
			float cpuRequestUsedSum= 0,cpuLimitsUsedSum= 0,memoryRequestUsedSum= 0,memoryLimitsUsedSum=0;

			float cpuRequestTotalSum = 0,cpuLimitsTotalSum= 0,memoryRequestTotalSum= 0,memoryLimitsTotalSum=0;
			
			PodList podList = client.pods().inNamespace(namespace).list();
			for (Pod pod : podList.getItems()) {
			    List<Container> containers = pod.getSpec().getContainers();
			        
			    for (Container container : containers) {
			        Quantity cpuRequest = container.getResources().getRequests().get("cpu");
			        if (cpuRequest != null) {
			            Float cpuRequestUsed = convertCpuQuantityToCores(cpuRequest);
			            cpuRequestTotalSum+=cpuRequestUsed;
			        }

			        Quantity cpuLimit = container.getResources().getLimits().get("cpu");
			        if (cpuLimit != null) {
			            Float cpuLimitValue = convertCpuQuantityToCores(cpuLimit);
			            
			            cpuLimitsTotalSum+=cpuLimitValue;
			        }

			        Quantity memoryRequest = container.getResources().getRequests().get("memory");
			        if (memoryRequest != null) {
			            Float memoryRequestUsed = convertMemoryQuantityToMegabytes(memoryRequest);
			          
			            memoryRequestTotalSum+=memoryRequestUsed;
			        }

			        Quantity memoryLimit = container.getResources().getLimits().get("memory");
			        if (memoryLimit != null) {
			            Float memoryLimitValue = convertMemoryQuantityToMegabytes(memoryLimit);
			            
			            memoryLimitsTotalSum+=memoryLimitValue;
			        }
			    }
			}
			

			
			PodMetricsList podMetricList = client.top().pods().metrics();
			for(PodMetrics podmetrics: podMetricList.getItems())
			{
			    for(ContainerMetrics containerMetric : podmetrics.getContainers())
			    {
			        Quantity cpu = containerMetric.getUsage().get("cpu");
			        Float cpuUsed = convertCpuQuantityToCores(cpu);
			        cpuLimitsUsedSum+=cpuUsed;
			        cpuRequestUsedSum=cpuLimitsUsedSum;
			        
			        Quantity memory = containerMetric.getUsage().get("memory");
			        
			        Float memoryUsed = convertMemoryQuantityToMegabytes(memory);
			        memoryLimitsUsedSum+=memoryUsed;
			        memoryRequestUsedSum=memoryLimitsUsedSum;
			    }
			}
 
			metrics.put("CpuRequestUsed", cpuRequestUsedSum);
			metrics.put("CpuRequestTotal", cpuRequestTotalSum);
			metrics.put("CpuLimitsUsed", cpuLimitsUsedSum);
			metrics.put("CpuLimitsTotal", cpuLimitsTotalSum);
			metrics.put("MemoryRequestUsed", memoryRequestUsedSum);
			metrics.put("MemoryRequestTotal", memoryRequestTotalSum);
			metrics.put("MemoryLimitsTotal", memoryLimitsTotalSum);
			metrics.put("MemoryLimitsUsed", memoryLimitsUsedSum);
			

			return metrics;
		}
	}
	

	public static float convertCpuQuantityToCores(Quantity cpuLimit) {
 	    BigDecimal numericalAmount = cpuLimit.getNumericalAmount();
	    return numericalAmount.floatValue() * 1000;
	   
	}
	
	public static float convertMemoryQuantityToMegabytes(Quantity memoryLimit) {
		 BigDecimal bytes = Quantity.getAmountInBytes(memoryLimit);
         BigDecimal mebibytes = bytes.divide(new BigDecimal(1024 * 1024), 2, RoundingMode.HALF_UP);
	    return mebibytes.floatValue();
	}
}
