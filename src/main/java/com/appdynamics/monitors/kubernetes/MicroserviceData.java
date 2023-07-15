package com.appdynamics.monitors.kubernetes;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kubernetes.client.openapi.models.V1Node;

public  class MicroserviceData {
    private ObjectNode labels;
	private String serviceName;
    private String clusterName;
    private int podCount;
    private float cpuRequestTotal;
    private float cpuLimitTotal;
    private float memoryRequestsTotal;
    private float memoryLimitsTotal;
	private String namespace;
	private V1Node nodeObject;

  

	public MicroserviceData(String serviceName,String namespace) {
        this.serviceName = serviceName;
        this.namespace=namespace;
    }

	
	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}
	
    public String getServiceName() {
        return serviceName;
    }

    public int getPodCount() {
        return podCount;
    }

    public void incrementPodCount() {
        podCount++;
    }

    public void addCPURequest(float cpuRequest) {
        cpuRequestTotal += cpuRequest;
    }

    public void addCPULimit(float cpuLimit) {
        cpuLimitTotal += cpuLimit;
    }

    public void addMemoryRequests(float memoryRequests) {
        memoryRequestsTotal += memoryRequests;
    }

    public void addMemoryLimits(float memoryLimits) {
        memoryLimitsTotal += memoryLimits;
    }

    public float getAverageCPURequest() {
        return podCount > 0 ? cpuRequestTotal / podCount : 0;
    }

    public float getAverageCPULimit() {
        return podCount > 0 ? cpuLimitTotal / podCount : 0;
    }

    public float getAverageMemoryRequests() {
        return podCount > 0 ? memoryRequestsTotal / podCount : 0;
    }

    public float getAverageMemoryLimits() {
        return podCount > 0 ? memoryLimitsTotal / podCount : 0;
    }

	public String getClusterName() {
		return clusterName;
	}

	public void setClusterName(String clusterName) {
		this.clusterName = clusterName;
	}


	public void setNodeObject(V1Node nodeObject) {
		this.nodeObject=nodeObject;
		
	}

	public V1Node getNodeObject() {
		return nodeObject;
		
	}

	public ObjectNode getLabels() {
		return labels;
	}


	public void setLabels(ObjectNode labels) {
		this.labels = labels;
	}
}