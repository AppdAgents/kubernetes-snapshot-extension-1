package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_RECS_BATCH_SIZE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_NODE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_NODE;
import static com.appdynamics.monitors.kubernetes.Constants.K8S_VERSION;
import static com.appdynamics.monitors.kubernetes.Constants.OPENSHIFT_VERSION;
import static com.appdynamics.monitors.kubernetes.Utilities.ALL;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddFloat;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddInt;
import static com.appdynamics.monitors.kubernetes.Utilities.checkAddObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1AttachedVolume;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeAddress;
import io.kubernetes.client.openapi.models.V1NodeCondition;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Taint;

public class NodeSnapshotRunner extends SnapshotRunnerBase {
	
//	private Map<String,String> Node_Role_Map = new HashMap<String, String>();
	
    public NodeSnapshotRunner(){

    }

    public NodeSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch){
        super(serviceProvider, config, countDownLatch);
//        initMetrics(config);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
        generateNodeSnapshot();
    }

    private void generateNodeSnapshot(){
        logger.info("Proceeding to Node update...");
        Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();
        if (config != null) {
            String apiKey = Utilities.getEventsAPIKey(config);
            String accountName = Utilities.getGlobalAccountName(config);
            URL publishUrl = Utilities.ensureSchema(config, apiKey, accountName, CONFIG_SCHEMA_NAME_NODE, CONFIG_SCHEMA_DEF_NODE);

            try {
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
                            false);
                }
                catch (Exception ex){
                    throw new Exception("Unable to connect to Kubernetes API server because it may be unavailable or the cluster credentials are invalid", ex);
                }

                createNodePayload(nodeList, config, publishUrl, accountName, apiKey);

                //build and update metrics
                List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);
                logger.info("About to send {} node metrics", metricList.size());
                UploadMetricsTask metricsTask = new UploadMetricsTask(getConfiguration(), getServiceProvider().getMetricWriteHelper(), metricList, countDownLatch);
                getConfiguration().getExecutorService().execute("UploadNodeMetricsTask", metricsTask);

                //check searches
            } catch (IOException e) {
                logger.error("Failed to push Node data", e);
                countDownLatch.countDown();
            } catch (Exception e) {
                countDownLatch.countDown();
                logger.error("Failed to push Node data", e);
            }
        }
    }

     ArrayNode createNodePayload(V1NodeList nodeList, Map<String, String> config, URL publishUrl, String accountName, String apiKey) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();
       
			
		
        long batchSize = Long.parseLong(config.get(CONFIG_RECS_BATCH_SIZE));
        for(V1Node nodeObj : nodeList.getItems()) {
            ObjectNode nodeObject = mapper.createObjectNode();
            String nodeName = nodeObj.getMetadata().getName();
            
            nodeObject = checkAddObject(nodeObject, nodeName, "nodeName");
            String clusterName = Utilities.ensureClusterName(config, nodeObj.getMetadata().getClusterName());

            SummaryObj summary = getSummaryMap().get(ALL);
            if (summary == null) {
                summary = initNodeSummaryObject(config, ALL,null);
                getSummaryMap().put(ALL, summary);
            }
            
            ObjectNode labelsObject = Utilities.getResourceLabels(config,mapper, nodeObj);
            nodeObject=checkAddObject(nodeObject, labelsObject, "customLabels") ; 
            
            boolean isMaster = false;
            int masters = 0;
            int workers = 0;
            if (nodeObj.getMetadata().getLabels() != null) {
                String labels = "";
                Iterator it = nodeObj.getMetadata().getLabels().entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry pair = (Map.Entry) it.next();
                    if (!isMaster && pair.getKey().equals("node-role.kubernetes.io/master")) {
                        isMaster = true;
                    }
                    labels += String.format("%s:%s;", pair.getKey(), pair.getValue());
                    it.remove();
                }
                nodeObject = checkAddObject(nodeObject, labels, "labels");
            }

            SummaryObj summaryNode = getSummaryMap().get(nodeName);
            if(Utilities.shouldCollectMetricsForNode(getConfiguration(), nodeName)) {
                if (summaryNode == null) {
                    summaryNode = initNodeSummaryObject(config, nodeName, isMaster ? Constants.MASTER_NODE : Constants.WORKER_NODE);
                    getSummaryMap().put(nodeName, summaryNode);
                    Globals.NODE_ROLE_MAP.put(nodeName, isMaster ? Constants.MASTER_NODE : Constants.WORKER_NODE);
                }
            }
       
            	nodeObject = checkAddInt(nodeObject,getNotRunningPodCount(nodeName),"notRunningPodCount");
			
            
            if(!OPENSHIFT_VERSION.isEmpty()) {
            	nodeObject = checkAddObject(nodeObject,OPENSHIFT_VERSION,"openshiftVersion");
            }
            if(!K8S_VERSION.isEmpty()) {
	        	nodeObject = checkAddObject(nodeObject,K8S_VERSION, "kubernetesVersion");	        	
	        }
            
//            nodeObject = checkAddObject(nodeObject, isMaster ? Constants.MASTER_NODE : Constants.WORKER_NODE + Constants.METRIC_SEPARATOR + nodeName, "nodeName");
            nodeObject = checkAddObject(nodeObject, clusterName, "clusterName");
            nodeObject = checkAddObject(nodeObject, nodeObj.getSpec().getPodCIDR(), "podCIDR");
            String taints = "";

            if (nodeObj.getSpec().getTaints() != null) {
                for (V1Taint t : nodeObj.getSpec().getTaints()) {
                    taints += String.format("%s:", t.toString());
                }
            }
            nodeObject = checkAddObject(nodeObject, taints, "taints");
            Utilities.incrementField(summary, "TaintsTotal");

            nodeObject = checkAddObject(nodeObject, nodeObj.getStatus().getPhase(), "phase");
            String addresses = "";
            for (V1NodeAddress add : nodeObj.getStatus().getAddresses()) {
                addresses += add.getAddress();
            }
            nodeObject = checkAddObject(nodeObject, addresses, "addresses");

            //labels
          
            if (isMaster) {
                nodeObject = checkAddObject(nodeObject, "master", "role");
                masters++;
            } else {
                nodeObject = checkAddObject(nodeObject, "worker", "role");
                workers++;
            }

            Utilities.incrementField(summary, "Masters", masters);
            Utilities.incrementField(summary, "Workers", workers);

            if (nodeObj.getStatus().getCapacity() != null) {
                Set<Map.Entry<String, Quantity>> set = nodeObj.getStatus().getCapacity().entrySet();
                for (Map.Entry<String, Quantity> s : set) {
                    if (s.getKey().equals("memory")) {
                        float val = s.getValue().getNumber().divide(new BigDecimal(1000000)).floatValue(); //MB
                        nodeObject = checkAddFloat(nodeObject, val, "memCapacity");
                        Utilities.incrementField(summaryNode, "CapacityMemory", val);
                    }
                    if (s.getKey().equals("cpu")) {
                        nodeObject = checkAddFloat(nodeObject, s.getValue().getNumber().floatValue(), "cpuCapacity");
                        Utilities.incrementField(summaryNode, "CapacityCpu", s.getValue().getNumber().floatValue());
                    }
                    if (s.getKey().equals("pods")) {
                        nodeObject = checkAddInt(nodeObject, s.getValue().getNumber().intValueExact(), "podCapacity");
                        Utilities.incrementField(summaryNode, "CapacityPods", s.getValue().getNumber().intValueExact());
                    }
                }
            }

            if (nodeObj.getStatus().getAllocatable() != null) {
                Set<Map.Entry<String, Quantity>> setAll = nodeObj.getStatus().getAllocatable().entrySet();
                for (Map.Entry<String, Quantity> s : setAll) {
                    if (s.getKey().equals("memory")) {
                        float val = s.getValue().getNumber().divide(new BigDecimal(1000000)).floatValue(); //MB
                        nodeObject = checkAddFloat(nodeObject, val, "memAllocations");
                        Utilities.incrementField(summaryNode, "AllocationsMemory", val);
                    }
                    if (s.getKey().equals("cpu")) {
                        nodeObject = checkAddFloat(nodeObject, s.getValue().getNumber().floatValue(), "cpuAllocations");
                        Utilities.incrementField(summaryNode, "AllocationsCpu", s.getValue().getNumber().floatValue());
                    }
                    if (s.getKey().equals("pods")) {
                        nodeObject = checkAddInt(nodeObject, s.getValue().getNumber().intValueExact(), "podAllocations");
                    }
                }
            }

            nodeObject = checkAddInt(nodeObject, nodeObj.getStatus().getDaemonEndpoints().getKubeletEndpoint().getPort(), "kubeletPort");

            nodeObject = checkAddObject(nodeObject, nodeObj.getStatus().getNodeInfo().getArchitecture(), "osArch");
            nodeObject = checkAddObject(nodeObject, nodeObj.getStatus().getNodeInfo().getKubeletVersion(), "kubeletVersion");
            nodeObject = checkAddObject(nodeObject, nodeObj.getStatus().getNodeInfo().getContainerRuntimeVersion(), "runtimeVersion");
            nodeObject = checkAddObject(nodeObject, nodeObj.getStatus().getNodeInfo().getMachineID(), "machineID");
            nodeObject = checkAddObject(nodeObject, nodeObj.getStatus().getNodeInfo().getOperatingSystem(), "osName");

            if (nodeObj.getStatus().getVolumesAttached() != null){
                String attachedValumes = "";
                for (V1AttachedVolume v : nodeObj.getStatus().getVolumesAttached()) {
                    attachedValumes += String.format("%s:%s;", v.getName(), v.getDevicePath());
                }
                nodeObject = checkAddObject(nodeObject, attachedValumes, "attachedVolumes");
            }

            if (nodeObj.getStatus().getVolumesInUse() != null) {
                String volumesInUse = "";
                for (String v : nodeObj.getStatus().getVolumesInUse()) {
                    volumesInUse += String.format("%s:", v);
                }
                nodeObject = checkAddObject(nodeObject, volumesInUse, "volumesInUse");
            }

            //conditions
            if (nodeObj.getStatus().getConditions() != null) {
                for (V1NodeCondition condition : nodeObj.getStatus().getConditions()) {
                    if (condition.getType().equals("Ready")) {
                        String status = condition.getStatus();
                        nodeObject = checkAddObject(nodeObject, status, "ready");
                        if (status.toLowerCase().equals("true")) {
                            Utilities.incrementField(summary, "ReadyNodes");
                        }
                    }
                    if (condition.getType().equals("OutOfDisk")) {
                        String status = condition.getStatus();
                        nodeObject = checkAddObject(nodeObject, status, "outOfDisk");
                        if (status.toLowerCase().equals("true")) {
                            Utilities.incrementField(summary, "OutOfDiskNodes");
                        }
                    }

                    if (condition.getType().equals("MemoryPressure")) {
                        String status = condition.getStatus();
                        nodeObject = checkAddObject(nodeObject, status, "memoryPressure");
                        if (status.toLowerCase().equals("true")) {
                            Utilities.incrementField(summary, "MemoryPressureNodes");
                        }
                    }

                    if (condition.getType().equals("DiskPressure")) {
                        String status = condition.getStatus();
                        nodeObject = checkAddObject(nodeObject, status, "diskPressure");
                        if (status.toLowerCase().equals("true")) {
                            Utilities.incrementField(summary, "DiskPressureNodes");
                        }
                    }
                }
            }

            arrayNode.add(nodeObject);
            if (arrayNode.size() >= batchSize){
                logger.info("Sending batch of {} Node records", arrayNode.size());
                String payload = arrayNode.toString();
                arrayNode = arrayNode.removeAll();
                if(!payload.equals("[]")){
                    UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                    getConfiguration().getExecutorService().execute("UploadNodeData", uploadEventsTask);
                }
            }
        }

         if (arrayNode.size() > 0){
             logger.info("Sending last batch of {} Node records", arrayNode.size());
             String payload = arrayNode.toString();
             arrayNode = arrayNode.removeAll();
             if(!payload.equals("[]")){
                 UploadEventsTask uploadEventsTask = new UploadEventsTask(getTaskName(), config, publishUrl, accountName, apiKey, payload);
                 getConfiguration().getExecutorService().execute("UploadNodeData", uploadEventsTask);
             }
         }

        return arrayNode;
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

   
 	protected SummaryObj initDefaultSummaryObject(Map<String, String> config){
        return initNodeSummaryObject(config, ALL,null);
    }

    public  static SummaryObj initNodeSummaryObject(Map<String, String> config, String node,String role){
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode summary = mapper.createObjectNode();
        summary.put("nodename", node);

        if (node.equals(ALL)) {
            summary.put("ReadyNodes", 0);
            summary.put("OutOfDiskNodes", 0);
            summary.put("MemoryPressureNodes", 0);
            summary.put("DiskPressureNodes", 0);
            summary.put("TaintsTotal", 0);
            summary.put("Masters", 0);
            summary.put("Workers", 0);
            summary.put("NotRunningPodCount", 0);
        }
        else{
            summary.put("CapacityMemory", 0);
            summary.put("CapacityCpu", 0);
            summary.put("CapacityPods", 0);
            summary.put("AllocationsMemory", 0);
            summary.put("AllocationsCpu", 0);
            summary.put("NotRunningPodCount", 0);
        }

        ArrayList<AppDMetricObj> metricsList = initMetrics(config, node);

        String path = Utilities.getMetricsPath(config, ALL, node,role);

        return new SummaryObj(summary, metricsList, path);
    }

    public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String nodeName){
        if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()){
            return new ArrayList<AppDMetricObj>();
        }
        String clusterName = Utilities.ClusterName;
        String parentSchema = config.get(CONFIG_SCHEMA_NAME_NODE);
        String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|", Utilities.getClusterTierName(config));
        ArrayList<AppDMetricObj> metricsList = new ArrayList<AppDMetricObj>();
        if (nodeName.equals(ALL)) {
            //global
            metricsList.add(new AppDMetricObj("ReadyNodes", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where ready = \"True\" and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("OutOfDiskNodes", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where outOfDisk = \"True\" and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("MemoryPressureNodes", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where memoryPressure = \"True\" and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("DiskPressureNodes", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where diskPressure = \"True\" and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("TaintsTotal", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where taints IS NOT NULL and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("CapacityMemory", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where memCapacity > 0 and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("CapacityCpu", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where cpuCapacity > 0 and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("AllocationsMemory", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where memAllocations > 0 and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("AllocationsCpu", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where cpuAllocations > 0 and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            metricsList.add(new AppDMetricObj("NotRunningPodCount", parentSchema, CONFIG_SCHEMA_DEF_NODE,
                    String.format("select * from %s where notRunningPodCount > 0 and clusterName = \"%s\"", parentSchema, clusterName), rootPath, ALL, nodeName,null));
            
        }

        return metricsList;
    }
}
