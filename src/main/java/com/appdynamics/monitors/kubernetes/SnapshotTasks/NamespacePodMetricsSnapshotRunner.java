package com.appdynamics.monitors.kubernetes.SnapshotTasks;

import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_DEF_POD_METRICS;
import static com.appdynamics.monitors.kubernetes.Constants.CONFIG_SCHEMA_NAME_POD_METRICS;
import static com.appdynamics.monitors.kubernetes.Utilities.ALL;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.appdynamics.extensions.TasksExecutionServiceProvider;
import com.appdynamics.extensions.metrics.Metric;
import com.appdynamics.extensions.util.AssertUtils;
import com.appdynamics.monitors.kubernetes.KubernetesClientSingleton;
import com.appdynamics.monitors.kubernetes.Utilities;
import com.appdynamics.monitors.kubernetes.Metrics.UploadMetricsTask;
import com.appdynamics.monitors.kubernetes.Models.AppDMetricObj;
import com.appdynamics.monitors.kubernetes.Models.SummaryObj;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;

@SuppressWarnings("deprecation")
public class NamespacePodMetricsSnapshotRunner extends SnapshotRunnerBase {

    private static final Logger logger = LoggerFactory.getLogger(NamespacePodMetricsSnapshotRunner.class);

    public NamespacePodMetricsSnapshotRunner() {}

    public NamespacePodMetricsSnapshotRunner(TasksExecutionServiceProvider serviceProvider, Map<String, String> config, CountDownLatch countDownLatch) {
        super(serviceProvider, config, countDownLatch);
    }

    @Override
    protected SummaryObj initDefaultSummaryObject(Map<String, String> config) {
        return initPodDefaultMetricsSummaryObject(config, ALL);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        AssertUtils.assertNotNull(getConfiguration(), "The job configuration cannot be empty");
        generatePodMetricsSnapshot();
    }

    private void generatePodMetricsSnapshot() {
        logger.info("Starting Pod Metrics Snapshot...");
        Map<String, String> config = (Map<String, String>) getConfiguration().getConfigYml();

        if (config != null) {
            try (KubernetesClient client = new DefaultKubernetesClient()) {
                V1NamespaceList namespaces = getNamespacesFromKubernetes(config);

                for (V1Namespace namespaceObj : namespaces.getItems()) {
                    String namespace = namespaceObj.getMetadata().getName();
                    PodList podList = client.pods().inNamespace(namespace).list();

                    logger.info("Namespace: {}, Pods Found: {}", namespace, podList.getItems().size());

                    for (Pod pod : podList.getItems()) {
                        String podName = pod.getMetadata().getName();

                        SummaryObj podSummary = initPodMetricsSummaryObject(config, namespace, podName, pod);
                        getSummaryMap().put(podName, podSummary);

                        List<Container> containers = pod.getSpec().getContainers();
                        for (Container container : containers) {
                            String containerName = container.getName();
                            SummaryObj containerSummary = initContainerMetricsSummaryObject(config, namespace, podName, containerName, pod);
                            getSummaryMap().put(podName + "|" + containerName, containerSummary);
                        }
                    }
                }

                List<Metric> metricList = getMetricsFromSummary(getSummaryMap(), config);
                logger.info("About to send {} Pod metrics", metricList.size());

                UploadMetricsTask metricsTask = new UploadMetricsTask(
                    getConfiguration(),
                    getServiceProvider().getMetricWriteHelper(),
                    metricList,
                    countDownLatch
                );
                getConfiguration().getExecutorService().execute("UploadPodMetricsTask", metricsTask);

            } catch (KubernetesClientException e) {
                countDownLatch.countDown();
                logger.error("Failed to collect Pod Metrics", e);
            } catch (Exception e) {
                countDownLatch.countDown();
                logger.error("Unexpected error during Pod Metrics Snapshot", e);
            }
        }
    }

    private SummaryObj initContainerMetricsSummaryObject(Map<String, String> config, String namespace, String podName, String containerName, Pod pod) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode summary = mapper.createObjectNode();
        summary.put("namespace", namespace);
        summary.put("podName", podName);
        summary.put("containerName", containerName);

        Map<String, Float> metrics = collectContainerMetricsData(pod, containerName);

        summary.put("cpuRequestTotal", metrics.get("cpuRequestTotal"));
        summary.put("cpuUsage", metrics.get("cpuUsage"));
        summary.put("cpuLimitsTotal", metrics.get("cpuLimitsTotal"));
        summary.put("memoryRequestTotal", metrics.get("memoryRequestTotal"));
        summary.put("memoryUsage", metrics.get("memoryUsage"));
        summary.put("memoryLimitsTotal", metrics.get("memoryLimitsTotal"));

        String path = Utilities.getPodsMetricsPath(config, namespace, podName) + "|" + containerName;
        ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace, podName + "|" + containerName);
        return new SummaryObj(summary, metricsList, path);
    }

    private Map<String, Float> collectContainerMetricsData(Pod pod, String containerName) {
        Map<String, Float> metrics = new HashMap<>();

        List<Container> containers = pod.getSpec().getContainers();
        for (Container container : containers) {
            if (container.getName().equals(containerName)) {
                if (container.getResources() != null) {
                    Map<String, Quantity> requests = container.getResources().getRequests();
                    Map<String, Quantity> limits = container.getResources().getLimits();

                    metrics.put("cpuRequestTotal", requests != null && requests.containsKey("cpu") 
                        ? convertCpuQuantityToCores(requests.get("cpu")) : 0f);
                    metrics.put("cpuLimitsTotal", limits != null && limits.containsKey("cpu") 
                        ? convertCpuQuantityToCores(limits.get("cpu")) : 0f);

                    metrics.put("memoryRequestTotal", requests != null && requests.containsKey("memory") 
                        ? convertMemoryQuantityToMegabytes(requests.get("memory")) : 0f);
                    metrics.put("memoryLimitsTotal", limits != null && limits.containsKey("memory") 
                        ? convertMemoryQuantityToMegabytes(limits.get("memory")) : 0f);
                }
                
                break;
            }
        }

        float cpuUsage = 0f;
        float memoryUsage = 0f;

        try (KubernetesClient client = new DefaultKubernetesClient()) {
            PodMetricsList podMetricsList = client.top().pods().metrics();
            for (PodMetrics podMetrics : podMetricsList.getItems()) {
                if (podMetrics.getMetadata().getName().equals(pod.getMetadata().getName())) {
                    for (ContainerMetrics containerMetric : podMetrics.getContainers()) {
                        if (containerMetric.getName().equals(containerName)) {
                            logger.info("Raw CPU usage for {}: {}", containerName, containerMetric.getUsage().get("cpu"));
                            logger.info("Raw Memory usage for {}: {}", containerName, containerMetric.getUsage().get("memory"));

                            Quantity cpuQuantity = containerMetric.getUsage().get("cpu");
                            cpuUsage = convertCpuQuantityToCores(cpuQuantity);

                            Quantity memoryQuantity = containerMetric.getUsage().get("memory");
                            memoryUsage = convertMemoryQuantityToMegabytes(memoryQuantity);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching usage metrics for container: {}", containerName, e);
        }

        metrics.put("cpuUsage", cpuUsage); 
        metrics.put("memoryUsage", memoryUsage); 

        return metrics;
    }

    public V1NamespaceList getNamespacesFromKubernetes(Map<String, String> config) throws Exception {
        try { 
            ApiClient client = KubernetesClientSingleton.getInstance(config);
            CoreV1Api api = KubernetesClientSingleton.getCoreV1ApiClient(config);
            this.setAPIServerTimeout(KubernetesClientSingleton.getInstance(config), K8S_API_TIMEOUT);
            Configuration.setDefaultApiClient(client);
            this.setCoreAPIServerTimeout(api, K8S_API_TIMEOUT);

            return api.listNamespace(null, null, null, null, null, null, null, null, null, null);
        } catch (Exception ex) {
            throw new Exception("Unable to connect to Kubernetes API server because it may be unavailable or the cluster credentials are invalid", ex);
        }
    }

    private SummaryObj initPodDefaultMetricsSummaryObject(Map<String, String> config, String namespace) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode summary = mapper.createObjectNode();
        summary.put("namespace", namespace);

        summary.put("cpuRequestTotal", 0f);
        summary.put("cpuUsage", 0f);
        summary.put("cpuLimitsTotal", 0f);
        summary.put("memoryUsage", 0f);
        summary.put("memoryRequestTotal", 0f);
        summary.put("memoryLimitsTotal", 0f);

        String path = Utilities.getPodsMetricsPath(config, namespace);
        ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace, ALL);
        return new SummaryObj(summary, metricsList, path);
    }

    private SummaryObj initPodMetricsSummaryObject(Map<String, String> config, String namespace, String podName, Pod pod) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode summary = mapper.createObjectNode();
        summary.put("namespace", namespace);
        summary.put("podName", podName);

        Map<String, Float> metrics = collectPodMetricsData(pod);

        summary.put("cpuRequestTotal", metrics.get("cpuRequestTotal"));
        summary.put("cpuUsage", metrics.get("cpuUsage"));
        summary.put("cpuLimitsTotal", metrics.get("cpuLimitsTotal"));
        summary.put("memoryUsage", metrics.get("memoryUsage"));
        summary.put("memoryRequestTotal", metrics.get("memoryRequestTotal"));
        summary.put("memoryLimitsTotal", metrics.get("memoryLimitsTotal"));

        String path = Utilities.getPodsMetricsPath(config, namespace, podName);
        ArrayList<AppDMetricObj> metricsList = initMetrics(config, namespace, podName);
        return new SummaryObj(summary, metricsList, path);
    }

    private Map<String, Float> collectPodMetricsData(Pod pod) {
        Map<String, Float> metrics = new HashMap<>();

        float cpuRequestTotal = 0, cpuLimitsTotal = 0, memoryRequestTotal = 0, memoryLimitsTotal = 0;
        float cpuUsage = 0, memoryUsage = 0;

        List<Container> containers = pod.getSpec().getContainers();
        for (Container container : containers) {
            if (container.getResources() != null) {
                Map<String, Quantity> requests = container.getResources().getRequests();
                Map<String, Quantity> limits = container.getResources().getLimits();

                if (requests != null) {
                    cpuRequestTotal += requests.containsKey("cpu") ? convertCpuQuantityToCores(requests.get("cpu")) : 0;
                    memoryRequestTotal += requests.containsKey("memory") ? convertMemoryQuantityToMegabytes(requests.get("memory")) : 0;
                }

                if (limits != null) {
                    cpuLimitsTotal += limits.containsKey("cpu") ? convertCpuQuantityToCores(limits.get("cpu")) : 0;
                    memoryLimitsTotal += limits.containsKey("memory") ? convertMemoryQuantityToMegabytes(limits.get("memory")) : 0;
                }
            }
        }

        try (KubernetesClient client = new DefaultKubernetesClient()) {
            PodMetricsList podMetricsList = client.top().pods().metrics();
            for (PodMetrics podMetrics : podMetricsList.getItems()) {
                if (podMetrics.getMetadata().getName().equals(pod.getMetadata().getName())) {
                    for (ContainerMetrics containerMetric : podMetrics.getContainers()) {
                        Quantity cpuQuantity = containerMetric.getUsage().get("cpu");
                        cpuUsage += convertCpuQuantityToCores(cpuQuantity);

                        Quantity memoryQuantity = containerMetric.getUsage().get("memory");
                        memoryUsage += convertMemoryQuantityToMegabytes(memoryQuantity);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching usage metrics for pod: {}", pod.getMetadata().getName(), e);
        }

        metrics.put("cpuRequestTotal", cpuRequestTotal);
        metrics.put("cpuLimitsTotal", cpuLimitsTotal);
        metrics.put("memoryRequestTotal", memoryRequestTotal);
        metrics.put("memoryLimitsTotal", memoryLimitsTotal);

        metrics.put("cpuUsage", cpuUsage);  
        metrics.put("memoryUsage", memoryUsage);  

        return metrics;
    }

    public static float convertCpuQuantityToCores(Quantity cpuLimit) {
        BigDecimal numericalAmount = cpuLimit.getNumericalAmount();
        return numericalAmount.floatValue() * 1000;  // Convert millicores (m) to cores
    }

    public static float convertMemoryQuantityToMegabytes(Quantity memoryLimit) {
        BigDecimal bytes = Quantity.getAmountInBytes(memoryLimit);
        BigDecimal mebibytes = bytes.divide(new BigDecimal(1024 * 1024), 2, RoundingMode.HALF_UP);
        return mebibytes.floatValue();
    }

    public static ArrayList<AppDMetricObj> initMetrics(Map<String, String> config, String namespace, String podName) {
        if (Utilities.ClusterName == null || Utilities.ClusterName.isEmpty()) {
            return new ArrayList<>();
        }

        String clusterName = Utilities.ClusterName;
        String parentSchema = config.get(CONFIG_SCHEMA_NAME_POD_METRICS);
        String rootPath = String.format("Application Infrastructure Performance|%s|Custom Metrics|Cluster Stats|Namespaces|%s|%s", clusterName, namespace, podName);

        ArrayList<AppDMetricObj> metricsList = new ArrayList<>();

        metricsList.add(new AppDMetricObj("cpuUsage", parentSchema, CONFIG_SCHEMA_DEF_POD_METRICS,
            String.format("SELECT cpuUsage FROM %s WHERE clusterName = \"%s\" AND namespace = \"%s\" AND podName = \"%s\"",
                    parentSchema, clusterName, namespace, podName), rootPath, namespace, podName, null));

        metricsList.add(new AppDMetricObj("cpuRequestTotal", parentSchema, CONFIG_SCHEMA_DEF_POD_METRICS,
            String.format("SELECT cpuRequestTotal FROM %s WHERE clusterName = \"%s\" AND namespace = \"%s\" AND podName = \"%s\"",
                    parentSchema, clusterName, namespace, podName), rootPath, namespace, podName, null));

        metricsList.add(new AppDMetricObj("cpuLimitsTotal", parentSchema, CONFIG_SCHEMA_DEF_POD_METRICS,
            String.format("SELECT cpuLimitsTotal FROM %s WHERE clusterName = \"%s\" AND namespace = \"%s\" AND podName = \"%s\"",
                    parentSchema, clusterName, namespace, podName), rootPath, namespace, podName, null));

        metricsList.add(new AppDMetricObj("memoryUsage", parentSchema, CONFIG_SCHEMA_DEF_POD_METRICS,
            String.format("SELECT memoryUsage FROM %s WHERE clusterName = \"%s\" AND namespace = \"%s\" AND podName = \"%s\"",
                    parentSchema, clusterName, namespace, podName), rootPath, namespace, podName, null));

        metricsList.add(new AppDMetricObj("memoryRequestTotal", parentSchema, CONFIG_SCHEMA_DEF_POD_METRICS,
            String.format("SELECT memoryRequestTotal FROM %s WHERE clusterName = \"%s\" AND namespace = \"%s\" AND podName = \"%s\"",
                    parentSchema, clusterName, namespace, podName), rootPath, namespace, podName, null));

        metricsList.add(new AppDMetricObj("memoryLimitsTotal", parentSchema, CONFIG_SCHEMA_DEF_POD_METRICS,
            String.format("SELECT memoryLimitsTotal FROM %s WHERE clusterName = \"%s\" AND namespace = \"%s\" AND podName = \"%s\"",
                    parentSchema, clusterName, namespace, podName), rootPath, namespace, podName, null));

        return metricsList;
    }
}
