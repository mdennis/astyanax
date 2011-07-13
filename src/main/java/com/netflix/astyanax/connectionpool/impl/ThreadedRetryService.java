package com.netflix.astyanax.connectionpool.impl;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;
import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.cliffc.high_scale_lib.NonBlockingHashSet;

import com.netflix.astyanax.connectionpool.ConnectionFactory;
import com.netflix.astyanax.connectionpool.Host;
import com.netflix.astyanax.connectionpool.HostRetryService;
import com.netflix.astyanax.connectionpool.RetryBackoffStrategy;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.connectionpool.exceptions.OperationException;

/**
 * Retry hosts with one thread per host.  The retry service 
 * 
 * @author elandau
 *
 * @param <CL>
 */
public class ThreadedRetryService<CL> implements HostRetryService {

	private static Logger  logger = Logger.getLogger(ThreadedRetryService.class);
	
	private final ExecutorService executor;
	private final ConnectionFactory<CL> factory;
	private final RetryBackoffStrategy strategy;
	
	private final int resumeTimeWindow = 10000;
	
	private final NonBlockingHashMap<Host, Long> lastResumeTime;
	private final NonBlockingHashMap<Host, RetryBackoffStrategy.Instance> backoffs;
	private final NonBlockingHashSet<Host> hosts;
	
	public ThreadedRetryService(RetryBackoffStrategy strategy, ConnectionFactory<CL> factory) {
		this.factory = factory;
		this.executor = Executors.newCachedThreadPool();
		this.strategy = strategy;
		this.hosts = new NonBlockingHashSet<Host>();
		this.lastResumeTime = new NonBlockingHashMap<Host, Long>();
		this.backoffs = new NonBlockingHashMap<Host, RetryBackoffStrategy.Instance>();
	}
	
	@Override
	public void addHost(final Host host, final ReconnectCallback callback) {
		// Prevent duplicates
		if (!this.hosts.add(host)) {
			return;
		}
		
		// Keep the old backoff strategy state if failed within the window
		Long lastTime = lastResumeTime.get(host);
		RetryBackoffStrategy.Instance backoff = strategy.createInstance();
		if (lastTime == null || (System.currentTimeMillis() - lastTime) > resumeTimeWindow) {
			backoffs.put(host, backoff);
		}
		else {
			if (null != backoffs.putIfAbsent(host, backoff)) {
				logger.info(String.format("Suspending host %s", host));
				backoff = backoffs.get(host);
				backoff.suspend();
			}
		}
		
		this.executor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					RetryBackoffStrategy.Instance backoff = backoffs.get(host);
					while (true) {
						Thread.sleep(backoff.nextDelay());
						try {
							factory.createConnection(null);
							
							// Created a new connection successfully.  
							lastResumeTime.put(host, System.currentTimeMillis());
							hosts.remove(host);
							
							callback.onReconnected(host);
							return;
						} catch (ConnectionException e) {
						}
					}
				} catch (InterruptedException e) {
				}
			}
		});
	}

	@Override
	public void removeHost(Host host) {
	}

	@Override
	public void shutdown() {
		this.executor.shutdown();
	}
}