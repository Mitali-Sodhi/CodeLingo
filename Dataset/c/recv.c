/*
 * ZMap Copyright 2013 Regents of the University of Michigan 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

#include <pcap.h>
#include <pcap/pcap.h>

#include <netinet/tcp.h>
#include <netinet/ip_icmp.h>
#include <netinet/udp.h>
#include <netinet/ip.h>
#include <netinet/in.h>

#include <linux/if_ether.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if.h>

#include "../lib/logger.h"

#include "state.h"
#include "validate.h"
#include "probe_modules/probe_modules.h"
#include "output_modules/output_modules.h"

#define PCAP_PROMISC 1
#define PCAP_TIMEOUT 1000

static uint32_t num_src_ports;
static pcap_t *pc = NULL;

// bitmap of observed IP addresses
static uint64_t *ip_seen = NULL;
static const int IP_SEEN_SIZE = 0x4000000; // == 2^32/64

// check if we've received a response from this address previously
static inline int check_ip(uint32_t ip)
{
	return (ip_seen[ip >> 6] >> (ip & 0x3F)) & 1;
}

// set that we've received a response from the address
static inline void set_ip(uint32_t ip)
{
	ip_seen[ip >> 6] |= (uint64_t)1 << (ip & 0x3F);
}

void packet_cb(u_char __attribute__((__unused__)) *user,
		const struct pcap_pkthdr *p, const u_char *bytes)
{
	if (!p) {
		return;
	}
	if (zrecv.success_unique >= zconf.max_results) {
		// Libpcap can process multiple packets per pcap_dispatch;
		// we need to throw out results once we've
		// gotten our --max-results worth.
		return;
	}
	// length of entire packet captured by libpcap
	uint32_t buflen = (uint32_t) p->caplen;

	if ((sizeof(struct iphdr) + sizeof(struct ethhdr)) > buflen) {
		// buffer not large enough to contain ethernet
		// and ip headers. further action would overrun buf
		return;
	}
	struct iphdr *ip_hdr = (struct iphdr *)&bytes[sizeof(struct ethhdr)];
	
	uint32_t src_ip = ip_hdr->saddr;

	uint32_t validation[VALIDATE_BYTES/sizeof(uint8_t)];
	// TODO: for TTL exceeded messages, ip_hdr->saddr is going to be different
	// and we must calculate off potential payload message instead
	validate_gen(ip_hdr->daddr, ip_hdr->saddr, (uint8_t *)validation);

	if (!zconf.probe_module->validate_packet(ip_hdr, buflen - sizeof(struct ethhdr),
				&src_ip, validation)) {
		return;
	}

	int is_repeat = check_ip(src_ip);
	response_type_t *r = zconf.probe_module->classify_packet(bytes, buflen);

	if (r->is_success) {
		zrecv.success_total++;
		if (!is_repeat) {
			zrecv.success_unique++;
			set_ip(src_ip);
		}
		if (zsend.complete) { 
			zrecv.cooldown_total++;
			if (!is_repeat) {
				zrecv.cooldown_unique++;
			}
		}
		if (zconf.output_module && zconf.output_module->success_ip) {
			zconf.output_module->success_ip(
					ip_hdr->saddr, ip_hdr->daddr,
					r->name, is_repeat, zsend.complete, bytes, buflen);
		}
	} else {
		zrecv.failure_total++;
		if (zconf.output_module && zconf.output_module->other_ip) {
			zconf.output_module->other_ip(
					ip_hdr->saddr, ip_hdr->daddr,
					r->name, is_repeat,	zsend.complete, bytes, buflen);
		}
	}

	if (zconf.output_module && zconf.output_module->update
			&& !(zrecv.success_unique % zconf.output_module->update_interval)) {
		zconf.output_module->update(&zconf, &zsend, &zrecv);
	}
}

int recv_update_pcap_stats(void)
{
	if (!pc) {
		return EXIT_FAILURE;
	}
	struct pcap_stat pcst;
	if (pcap_stats(pc, &pcst)) {
		log_error("recv", "unable to retrieve pcap statistics: %s",
				pcap_geterr(pc));
		return EXIT_FAILURE;
	} else {
		zrecv.pcap_recv = pcst.ps_recv;
		zrecv.pcap_drop = pcst.ps_drop;
		zrecv.pcap_ifdrop = pcst.ps_ifdrop;
	}
	return EXIT_SUCCESS;
}

int recv_run(pthread_mutex_t *recv_ready_mutex)
{
	log_debug("recv", "thread started");
	num_src_ports = zconf.source_port_last - zconf.source_port_first + 1;
	ip_seen = calloc(IP_SEEN_SIZE, sizeof(uint64_t));
	if (!ip_seen) {
		log_fatal("recv", "couldn't allocate address bitmap");
	}
	log_debug("recv", "using dev %s", zconf.iface);
	char errbuf[PCAP_ERRBUF_SIZE];
	pc = pcap_open_live(zconf.iface, zconf.probe_module->pcap_snaplen,
					PCAP_PROMISC, PCAP_TIMEOUT, errbuf);
	if (pc == NULL) {
		log_fatal("recv", "couldn't open device %s: %s",
						zconf.iface, errbuf);
	}
	struct bpf_program bpf;
	if (pcap_compile(pc, &bpf, zconf.probe_module->pcap_filter, 1, 0) < 0) {
		log_fatal("recv", "couldn't compile filter");
	}
	if (pcap_setfilter(pc, &bpf) < 0) {
		log_fatal("recv", "couldn't install filter");
	}
	log_debug("recv", "receiver ready");
	pthread_mutex_lock(recv_ready_mutex);
	zconf.recv_ready = 1;
	pthread_mutex_unlock(recv_ready_mutex);
	zrecv.start = now();
	if (zconf.max_results == 0) {
		zconf.max_results = -1;
	}
	do {
		if (pcap_dispatch(pc, 0, packet_cb, NULL) == -1) {
			log_fatal("recv", "pcap_dispatch error");
		}
		if (zconf.max_results && zrecv.success_unique >= zconf.max_results) {
			zsend.complete = 1;
			break;
		}
	} while (!(zsend.complete && (now()-zsend.finish > zconf.cooldown_secs)));
	zrecv.finish = now();
	// get final pcap statistics before closing
	recv_update_pcap_stats();
	pcap_close(pc);
	zrecv.complete = 1;
	log_debug("recv", "thread finished");
	return 0;
}

