package br.com.cas10.oraman.service

import java.util.concurrent.TimeUnit

import javax.annotation.PostConstruct

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.PeriodicTrigger
import org.springframework.stereotype.Service

import br.com.cas10.oraman.agent.Agent

@Service
class AgentsService {

	@Autowired
	private Agent[] agents
	@Autowired
	private TaskScheduler taskScheduler

	@PostConstruct
	void init() {
		// concurrent execution of agents is undesired; the delays minimize potential problems
		int delay = TimeUnit.SECONDS.toMillis(1) / agents.length
		int currentDelay = 0
		for (Agent agent in agents) {
			PeriodicTrigger trigger = new PeriodicTrigger(agent.interval)
			trigger.fixedRate = true
			trigger.initialDelay = currentDelay
			taskScheduler.schedule(agent, trigger);
			currentDelay += delay
		}
	}
}
