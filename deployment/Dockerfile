FROM eclipse-temurin:11-jre-alpine

RUN apk add --no-cache bash gawk sed grep bc coreutils udev

RUN mkdir -p /opt/appdynamics/MachineAgent

COPY artifacts /opt/appdynamics/MachineAgent

ENV MACHINE_AGENT_HOME /opt/appdynamics/MachineAgent


COPY updateAnalyticsAgent.sh /updateAnalyticsAgent.sh
RUN chmod +x /updateAnalyticsAgent.sh

COPY startup.sh /startup.sh
RUN chmod +x /startup.sh


RUN chgrp -R 0 /opt && \
    chmod -R g=u /opt

EXPOSE 9090

WORKDIR ${MACHINE_AGENT_HOME}
CMD "/startup.sh"
