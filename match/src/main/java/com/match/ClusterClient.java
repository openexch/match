package com.match;

import com.match.infrastructure.http.HttpController;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

public class ClusterClient {
    public static void main(String[] args) throws Exception {

        HttpController agent = new HttpController();

        final AgentRunner clusterInteractionAgentRunner = new AgentRunner(new SleepingMillisIdleStrategy(), Throwable::printStackTrace,
                null, agent);
        AgentRunner.startOnThread(clusterInteractionAgentRunner);
    }
} 