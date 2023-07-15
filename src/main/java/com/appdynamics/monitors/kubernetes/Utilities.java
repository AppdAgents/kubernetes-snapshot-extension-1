package com.appdynamics.monitors.kubernetes;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_APP_NAME;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_APP_TIER_NAME;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_CONTROLLER_URL;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_CUSTOM_TAGS;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_ENTITY_TYPE;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_EVENTS_API_KEY;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_EVENTS_URL;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_GLOBAL_ACCOUNT_NAME;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_NODE_NAMESPACES;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_NODE_NODES;
import static com.appdynamics.monitors.kubernetes.Constants.DEFAULT_METRIC_PREFIX_NAME;
import static com.appdynamics.monitors.kubernetes.Constants.METRIC_PATH_MICRO_SERVICES;
import static com.appdynamics.monitors.kubernetes.Constants.METRIC_PATH_NAMESPACES;
import static com.appdynamics.monitors.kubernetes.Constants.METRIC_PATH_NODES;
import static com.appdynamics.monitors.kubernetes.Constants.METRIC_SEPARATOR;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.appdynamics.extensions.conf.MonitorConfiguration;
import com.appdynamics.monitors.kubernetes.Models.AdqlSearchObj;
import com.appdynamics.monitors.kubernetes.Models.SummaryObj;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.VersionInfo;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.CoreV1Event;
import io.kubernetes.client.openapi.models.V1DaemonSet;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1ReplicaSet;
import io.kubernetes.client.util.Config;

public class Utilities {
    private static final Logger logger = LoggerFactory.getLogger(Utilities.class);
    public static final String ALL = "all";
    public static int tierID = 0;
    public static String ClusterName = "";
    public static ArrayList<AdqlSearchObj> savedSearches = new ArrayList<AdqlSearchObj>();
    public static int FIELD_LENGTH_LIMIT = 4000;
    protected static int K8S_API_TIMEOUT = 240;
    public static URL getUrl(String input){
        URL url = null;
        try {
            url = new URL(input);
        } catch (MalformedURLException e) {
            logger.error("Error forming URL from String {}", input, e);
        }
        return url;
    }


    public static Map<String, String> getEntityConfig(List<Map<String, String>> config, String entityType){
        Map<String, String>  entityConfig = null;
        logger.info("Checking section {}", entityType);
        for(Map<String, String> map : config){
            if (entityType.equals(map.get(CONFIG_ENTITY_TYPE))){
                entityConfig = map;
                break;
            }
        }
        return entityConfig;
    }

    public static boolean shouldCollectMetricsForNode(MonitorConfiguration configuration, String node){
        boolean should = false;
        try {
            List<Map<String, String>> nodes = (List<Map<String, String>>) configuration.getConfigYml().get(CONFIG_NODE_NODES);
            if (nodes != null) {
                for (Map<String, String> map : nodes) {
                    if (map.get("name").equals(node) || map.get("name").equals(ALL)) {
                        should = true;
                        break;
                    }
                }
            }
        }
        catch (Exception ex){
            logger.error("Issues when parsing nodes config", ex);
        }
        return should;
    }

    public static boolean shouldCollectMetricsForNamespace(MonitorConfiguration configuration, String ns){
        boolean should = false;
        try {
            List<Map<String, String>> namespaces = (List<Map<String, String>>) configuration.getConfigYml().get(CONFIG_NODE_NAMESPACES);
            if (namespaces != null) {
                for (Map<String, String> map : namespaces) {
                    if (map.get("name").equals(ns) || map.get("name").equals(ALL)) {
                        should = true;
                        break;
                    }
                }
            }
        }
        catch (Exception ex){
            logger.error("Issues when parsing namespace config", ex);
        }
        return should;
    }
	

	public static void main(String[] args) throws IOException {
		System.out.println(getKubernetesVersion());
	}
	

	private static String addAdditionalVersionFields(String oldSchemaDefinition) {
		
		try {
		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode jsonNode = objectMapper.readTree(oldSchemaDefinition);
		JsonNode schemaJson =jsonNode.get("schema");
		ObjectNode schemaJsonObject = (ObjectNode) schemaJson;
		if(Constants.OPENSHIFT_VERSION!=null && !Constants.OPENSHIFT_VERSION.isEmpty())
        {
			((ObjectNode) schemaJsonObject).put("openshiftVersion", "string");
        }
		if(Constants.K8S_VERSION!=null && !Constants.K8S_VERSION.isEmpty())
        {	
			((ObjectNode) schemaJsonObject).put("kubernetesVersion", "string");
        }
		
			return  objectMapper.writeValueAsString(jsonNode);
		}catch(IOException e)
		{
			logger.error("error while adding the openShiftVersion version field to schema, returning default schema");
			return oldSchemaDefinition;
		}
	}

    public static URL ensureSchema(Map<String, String> config, String apiKey, String accountName, String schemaName, String schemaDefinition){
        URL publishUrl = Utilities.getUrl(getEventsAPIUrl(config) + "/events/publish/" + config.get(schemaName));
        URL schemaUrl = Utilities.getUrl(getEventsAPIUrl(config) + "/events/schema/" + config.get(schemaName));
        String requestBody = addAdditionalVersionFields(config.get(schemaDefinition));
        
        
        JsonNode serverSchema = RestClient.doRequest(schemaUrl, config,accountName, apiKey, "", "GET");
        logger.info("serverSchema{} ,  Schema Url {}", serverSchema, schemaUrl);
        int statusCode=0;
        String code="";
        if (serverSchema.has("statusCode")) {
      		statusCode = serverSchema.get("statusCode").asInt();
               code = serverSchema.get("code").asText();
        }
       
        if(statusCode == 404 && code.equals("Missing.EventType")){

            logger.debug("Schema Url {} does not exists. creating {}", schemaUrl, requestBody);

            RestClient.doRequest(schemaUrl, config,accountName, apiKey, requestBody, "POST");
        }
        else {
            logger.info("Schema exists");
        }
        return publishUrl;
    }


    public static ObjectNode checkAddObject(ObjectNode objectNode, Object object, String fieldName){
        if(object != null && object.toString() != null){
            String objString = object.toString();
            byte[] bytes = objString.getBytes(Charset.forName("UTF-8"));
            if (bytes.length >= FIELD_LENGTH_LIMIT){
                logger.info("Field {} is greater than the allowed size of 4K. Skipping....", fieldName);
                objectNode.put(fieldName, "");
            }
            else{
                objectNode.put(fieldName, objString);
            }
        }
        return objectNode;
    }

    public static ObjectNode checkAddInt(ObjectNode objectNode, Integer val, String fieldName){
        if (val == null){
            val = 0;
        }
        objectNode.put(fieldName, val);

        return objectNode;
    }

    public static ObjectNode checkAddLong(ObjectNode objectNode, Long val, String fieldName){
        if (val == null){
            val = 0L;
        }
        objectNode.put(fieldName, val);

        return objectNode;
    }

    public static ObjectNode checkAddFloat(ObjectNode objectNode, Float val, String fieldName){
        if (val == null){
            val = new Float(0);
        }
        objectNode.put(fieldName, val);

        return objectNode;
    }


    public static ObjectNode checkAddDecimal(ObjectNode objectNode, BigDecimal val, String fieldName){
        if (val == null){
            val = new BigDecimal(0);
        }
        objectNode.put(fieldName, val);

        return objectNode;
    }

    public static ObjectNode checkAddBoolean(ObjectNode objectNode, Boolean val, String fieldName){
        if (val == null){
            val = false;
        }
        objectNode.put(fieldName, val);

        return objectNode;
    }


    public static ObjectNode incrementField(SummaryObj summaryObj, String fieldName){
        if (summaryObj == null){
            return null;
        }
        ObjectNode obj = summaryObj.getData();
        if(obj != null && obj.has(fieldName)) {
            int val = obj.get(fieldName).asInt() + 1;
            obj.put(fieldName, val);
        }

        return obj;
    }
    
    public static SummaryObj checkAddObject(SummaryObj summaryObj, String object, String fieldName){
    	 if (summaryObj == null){
             return null;
         }
    	 
    	 ObjectNode obj = summaryObj.getData();
         if(obj != null && obj.has(fieldName)) {
            
             obj.put(fieldName,  object);
         }
    	
        return summaryObj;
    }
    
    public static SummaryObj checkAndInt(SummaryObj summaryObj, String object, String fieldName){
   	 if (summaryObj == null){
            return null;
        }
   	 
   	 ObjectNode obj = summaryObj.getData();
        if(obj != null && obj.has(fieldName)) {
           
            obj.put(fieldName,  object);
        }
   	
       return summaryObj;
   }

    public static ObjectNode incrementField(SummaryObj summaryObj, String fieldName, int increment){
        if (summaryObj == null){
            return null;
        }
        ObjectNode obj = summaryObj.getData();
        if(obj != null && obj.has(fieldName)) {
            int val = obj.get(fieldName).asInt();
            obj.put(fieldName, val+increment);
        }

        return obj;
    }

    public static ObjectNode incrementField(SummaryObj summaryObj, String fieldName, float increment){
        if (summaryObj == null){
            return null;
        }
        ObjectNode obj = summaryObj.getData();
        if(obj != null && obj.has(fieldName)) {
            int val = obj.get(fieldName).asInt();
            obj.put(fieldName, val+increment);
        }

        return obj;
    }

    public static ObjectNode incrementField(SummaryObj summaryObj,  String fieldName, BigDecimal increment){
        if (summaryObj == null){
            return null;
        }
        ObjectNode obj = summaryObj.getData();
        if(obj != null && obj.has(fieldName)) {
            BigDecimal val = new BigDecimal(obj.get(fieldName).asDouble());
            val = val.add(increment);
            obj.put(fieldName, val);
        }

        return obj;
    }

    public  static ArrayList getSummaryDataList(HashMap<String, SummaryObj> summaryMap){
        ArrayList list = new ArrayList();
        Iterator it = summaryMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            SummaryObj summaryObj = (SummaryObj)pair.getValue();
            list.add(summaryObj);
//            it.remove();
        }
        return list;
    }

    public  static ArrayNode getSummaryData(HashMap<String, SummaryObj> summaryMap){
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode list = mapper.createArrayNode();
        Iterator it = summaryMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry)it.next();
            SummaryObj summaryObj = (SummaryObj)pair.getValue();
            list.add(summaryObj.getData());
//            it.remove();
        }
        return list;
    }

    public static String getMetricsPath(Map<String, String> config){
        return String.format(config.get(DEFAULT_METRIC_PREFIX_NAME), tierID);
    }

    public static String getMetricsPath(Map<String, String> config, String namespace, String node){
    	if(node==null || node.isEmpty()) {
    		node=ALL;
    		logger.error("node is empty setting it to "+node);
    	}
    	if(namespace==null || namespace.isEmpty()) {
    		namespace=ALL;
    		logger.error("namespace is empty setting it to "+namespace);
    	}
        if(!node.equals(ALL)){
        	
        	if(Globals.NODE_ROLE_MAP.containsKey(node)) {
        		return String.format("%s%s%s%s%s%s%s", Utilities.getMetricsPath(config), METRIC_SEPARATOR, METRIC_PATH_NODES, METRIC_SEPARATOR,Globals.NODE_ROLE_MAP.get(node),METRIC_SEPARATOR, node);
        	}else {
        		return String.format("%s%s%s%s%s", Utilities.getMetricsPath(config), METRIC_SEPARATOR, METRIC_PATH_NODES, METRIC_SEPARATOR, node);
        	}
            
        }
        else if (!namespace.equals(ALL)){
            return String.format("%s%s%s%s%s", Utilities.getMetricsPath(config), METRIC_SEPARATOR, METRIC_PATH_NAMESPACES, METRIC_SEPARATOR, namespace);
        }

        return getMetricsPath(config);
    }
    
    
    public static String getMetricsPath(Map<String, String> config, String microService){

        if (microService.isEmpty()){
        	 return getMetricsPath(config);
            
        }
        String str=String.format("%s%s%s%s%s", Utilities.getMetricsPath(config), METRIC_SEPARATOR, METRIC_PATH_MICRO_SERVICES, METRIC_SEPARATOR, microService);
        return str;
       
    }
    
    public static String getMetricsPath(Map<String, String> config, String namespace,MicroserviceData microService){

        
        String str=String.format("%s%s%s%s%s%s%s%s%s", Utilities.getMetricsPath(config),METRIC_SEPARATOR,METRIC_PATH_NAMESPACES,METRIC_SEPARATOR,namespace, METRIC_SEPARATOR, METRIC_PATH_MICRO_SERVICES,METRIC_SEPARATOR,microService.getServiceName());
        return str;
       
    }
    
    
    public static String getMetricsPath(Map<String, String> config, String namespace, String node,String role){
        if(!node.equals(ALL)){
            return String.format("%s%s%s%s%s%s%s", Utilities.getMetricsPath(config), METRIC_SEPARATOR, METRIC_PATH_NODES, METRIC_SEPARATOR,role,METRIC_SEPARATOR, node);
        }
        
       

        return getMetricsPath(config);
    }
    

	public static String getMetricsPath(Map<String, String> config, String namespace, String node,
			MicroserviceData microserviceData, String role) {
		if(!node.equals(ALL)){
            return String.format("%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s", Utilities.getMetricsPath(config), METRIC_SEPARATOR, METRIC_PATH_NODES, METRIC_SEPARATOR,role,METRIC_SEPARATOR, node,
            		METRIC_SEPARATOR,METRIC_PATH_NAMESPACES,METRIC_SEPARATOR,namespace,METRIC_SEPARATOR,METRIC_PATH_MICRO_SERVICES,METRIC_SEPARATOR,microserviceData.getServiceName());
        }
        else if (!namespace.equals(ALL)){
            return String.format("%s%s%s%s%s", Utilities.getMetricsPath(config), METRIC_SEPARATOR, METRIC_PATH_NODES, METRIC_SEPARATOR, METRIC_PATH_NODES);
        }
		return getMetricsPath(config);
	}


    public static String ensureClusterName(Map<String, String> config, String clusterName){
        if (clusterName == null || clusterName.isEmpty()){

            if (Utilities.ClusterName != null &&  !Utilities.ClusterName.isEmpty()) {
                clusterName = Utilities.ClusterName;
            }
            else {
                clusterName = Utilities.getClusterApplicationName(config);
            }
        }
        if (Utilities.ClusterName == null | Utilities.ClusterName.isEmpty()){
            Utilities.ClusterName = clusterName; //need this to build queries;
        }
        return clusterName;
    }

    public static String getClusterApplicationName(Map<String, String> config){
        String appName = System.getenv("APPLICATION_NAME");
        if (StringUtils.isNotEmpty(appName) == false){
            appName = config.get(CONFIG_APP_NAME);
        }
        return  appName;
    }

    public static String getClusterTierName(Map<String, String> config){
        String appName = System.getenv("TIER_NAME");
        if (StringUtils.isNotEmpty(appName) == false){
            appName = config.get(CONFIG_APP_TIER_NAME);
        }
        return  appName;
    }

    public static String getProxyHost(Map<String, String> config){
        String proxyHost = System.getenv("APPD_PROXY_HOST");
        if (StringUtils.isNotEmpty(proxyHost) == false){
            proxyHost = config.get("proxyHost");
        }

        return  proxyHost;
    }

    public static String getProxyPort(Map<String, String> config){
        String proxyPort = System.getenv("APPD_PROXY_PORT");
        if (StringUtils.isNotEmpty(proxyPort) == false){
            proxyPort = config.get("proxyPort");
        }
        return  proxyPort;
    }

    public static String getProxyUser(Map<String, String> config){
        String proxyUser = System.getenv("APPD_PROXY_USER");
        if (StringUtils.isNotEmpty(proxyUser) == false){
            proxyUser = config.get("proxyUser");
        }
        return  proxyUser;
    }

    public static String getProxyPass(Map<String, String> config){
        String proxyPass = System.getenv("APPD_PROXY_PASS");
        if (StringUtils.isNotEmpty(proxyPass) == false){
            proxyPass = config.get("proxyPass");
        }
        return  proxyPass;
    }


    public static String getEventsAPIKey(Map<String, String> config){
        String key = System.getenv("EVENT_ACCESS_KEY");
        if (StringUtils.isNotEmpty(key) == false){
            key = config.get(CONFIG_EVENTS_API_KEY);
        }
        return  key;
    }

    public static String getGlobalAccountName(Map<String, String> config){
        String key = System.getenv("GLOBAL_ACCOUNT_NAME");
        if (StringUtils.isNotEmpty(key) == false){
            key = config.get(CONFIG_GLOBAL_ACCOUNT_NAME);
        }
        return  key;
    }

    public static AdqlSearchObj getSavedSearch(String name){
        AdqlSearchObj theObj = null;
        for(AdqlSearchObj s : savedSearches){
            if(s.getName().equals(name)){
                theObj = s;
                break;
            }
        }
        return theObj;
    }

    public static io.kubernetes.client.openapi.ApiClient initClient(Map<String, String> config) throws Exception{
        io.kubernetes.client.openapi.ApiClient client;
        String apiMode = System.getenv("K8S_API_MODE");
        if (StringUtils.isNotEmpty(apiMode) == false){
            apiMode = config.get("apiMode");
        }

        if (apiMode.equals("server")) {
            try {
                client = Config.fromConfig(config.get("kubeClientConfig"));
            }
            catch (Exception ex){
                logger.info("K8s API client cannot be initialized form the config file {}. Reason {}. Trying cluster creds", config.get("kubeClientConfig"), ex.getMessage());
                client = Config.fromCluster();
            }
        }
        else if (apiMode.equals("cluster")){
            client = Config.fromCluster();
        }
        else{
            throw new Exception(String.format("apiMode %s not supported. Must be server or cluster", apiMode));
        }
        if (client == null){
            throw new Exception("Kubernetes API client is not initialized. Aborting...");
        }
        return client;
    }

    public static String getRootDirectory(){
        File file = new File(".");
        return String.format("%s/monitors/KubernetesSnapshotExtension", file.getAbsolutePath());
    }

    public static String getControllerUrl(Map<String, String> config){
        String url = System.getenv("REST_API_URL");
        if (StringUtils.isNotEmpty(url) == false){
            url = config.get(CONFIG_CONTROLLER_URL);
        }
        return  url;
    }

    public static String getEventsAPIUrl(Map<String, String> config){
        String url = System.getenv("EVENTS_API_URL");
        if (StringUtils.isNotEmpty(url) == false){
            url = config.get(CONFIG_EVENTS_URL);
        }
        return  url;
    }


    public static String getOpenShiftVersion() {
    	String version=""; 
    	try (OpenShiftClient client = new DefaultOpenShiftClient()) {
             // Retrieve the version information
             version = client.getOpenShiftV4Version();           
              logger.info("OpenShift Version is {}",version);    
         } catch (Exception e) {
             logger.error("Exception encountered while retrieving the OpenShift version. Please disregard this message if you are not working with OpenShift or a shiftcluster. Error message:{}", e.getMessage());
         }
		return version;
    }

    private static io.fabric8.kubernetes.client.Config buildConfig() {
        return new ConfigBuilder().build();
    }
   
    public static String getKubernetesVersion() {
    	   String kubernetesVersion="";
		 try (KubernetesClient client = new DefaultKubernetesClient(buildConfig())) {
		     VersionInfo versionInfo = client.getVersion();
		
		     kubernetesVersion= versionInfo.getGitVersion();
		    
		 }
		return kubernetesVersion;
		          
    }
    
    
    public static List<String>  getCustomTags(Map<String, String> config) {
	
    	String customTags = config.get(CONFIG_CUSTOM_TAGS);
    	if(customTags!=null && !customTags.isEmpty()){
			String []customTagArray =customTags.split(",");
			 
			return  Arrays.asList(customTagArray);
    	}
    	return null;
    }

    public static ObjectNode getResourceLabels(Map<String, String> config,ObjectMapper mapper, Object resource) {
    	List<String> customTags=getCustomTags(config);
    	ObjectNode labelsObject = mapper.createObjectNode();
    	if(customTags!=null && customTags.size()>0) {
	    	
	        Map<String, String> labels = new HashMap<>();
	        
	        if (resource instanceof V1Pod) {
	            labels = ((V1Pod) resource).getMetadata().getLabels();           
	        } else if (resource instanceof V1Namespace) {
	        	 labels = ((V1Namespace) resource).getMetadata().getLabels();
	        } else if (resource instanceof V1DaemonSet) {
	        	 labels = ((V1DaemonSet) resource).getMetadata().getLabels();
	        } else if (resource instanceof V1Deployment) {
	        	 labels = ((V1Deployment) resource).getMetadata().getLabels();
	        } else if (resource instanceof V1Node) {
	        	 labels = ((V1Node) resource).getMetadata().getLabels();
	        } else if (resource instanceof V1ReplicaSet) {
	        	 labels = ((V1ReplicaSet) resource).getMetadata().getLabels();
	        } else if (resource instanceof CoreV1Event) {
	        	 labels = ((CoreV1Event) resource).getMetadata().getLabels();
	        }
	        
	        if(labels!=null) {
		        for (Map.Entry<String, String> entry : labels.entrySet()) {
		            String key = entry.getKey();
		            if(customTags.contains(key)) {
			            String value = entry.getValue();
			            labelsObject.put(key, value);
		            }
		        }
        
	        }
	       
	     }
    	return labelsObject;
    }
    
	public static V1PodList getPodsFromKubernetes(Map<String, String> config) throws Exception {
		
		V1PodList podList;
		try {
			ApiClient client = KubernetesClientSingleton.getInstance(config);
			CoreV1Api api =KubernetesClientSingleton.getCoreV1ApiClient(config);
		   
            Configuration.setDefaultApiClient(client);
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
    
}
