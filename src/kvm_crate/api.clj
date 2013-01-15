(ns kvm-crate.api
  (:require
   [pallet.algo.fsmop :as fsmop]
   [pallet.node :as node]
   [pallet.configure :as configure]
   [pallet.compute :as compute]
   [pallet.api :as api]
   [pallet-nodelist-helpers :as helpers]
   [kvm-crate.crate.server :as server-crate]))

(defn host-is-kvm-server?
  "Check if a host is a KVM server (as understood by the kvm-create)"
  [hostname]
  (helpers/host-has-phase? hostname :configure-kvm-server))

(defn update-dhcp-hosts-file
  "Update the dhcp hosts file on DHCP server for private LAN."
  [dhcp-server]
  (helpers/ensure-nodelist-bindings)
  (let [dhcp-ips (doall (filter (fn [[host config]]
                                  (server-crate/is-dhcp-ip? config))
                                helpers/*nodelist-hosts-config*))
        dhcp-hosts-content (reduce str ""
                                   (map (fn [[host config]]
                                          (format "%s,%s,%s,infinite\n"
                                                  (:mac config)
                                                  (:ip config)
                                                  host))
                                        dhcp-ips))
        result (helpers/run-one-plan-fn dhcp-server
                                        server-crate/update-dhcp-hosts-file
                                        {:dhcp-hosts-content dhcp-hosts-content})]
    (when (fsmop/failed? result)
      (throw (IllegalStateException. (format "Failed to update dhcp hots file for %s!" dhcp-server))))
    result))

(defn install-dnsmasq-upstart-job
  "Install the dnsmasq upstart job on DHCP server"
  [hostname]
  (helpers/ensure-nodelist-bindings)
  (let [result (helpers/run-one-plan-fn hostname server-crate/install-dnsmasq-upstart-job)]
    (when (fsmop/failed? result)
      (throw (IllegalStateException. "Failed to install dnsmasq upstart job!")))
    result))

(defn configure-kvm-server
  "Set up a machine to act as a KVM server"
  [hostname]
  (helpers/ensure-nodelist-bindings)
  (when-not (host-is-kvm-server? hostname)
    (throw (IllegalArgumentException. (format "%s is not a kvm-server!" hostname))))
  (when (get-in helpers/*nodelist-hosts-config* [hostname :dhcp-server])
    (install-dnsmasq-upstart-job hostname)
    (update-dhcp-hosts-file hostname))
  (let [result (helpers/lift-one-node-and-phase hostname :configure-kvm-server)]
    (when (fsmop/failed? result)
      (throw (IllegalStateException. "Failed to configure KVM server!")))
    result))

(defn- add-gre-port
  "Add a single GRE port for a KVM server"
  [hostname gre-config]
  (let [result (helpers/run-one-plan-fn hostname server-crate/add-gre-port {:gre-config gre-config})]
    (when (fsmop/failed? result)
      (throw (IllegalStateException. (format "Failed to add GRE port %s to KVM server %s!"
                                             (:iface gre-config)
                                             hostname))))
    result))

(defn- add-gre-ports
  "Add the relevant GRE ports for a KVM server."
  [[hostname config]]
  ;; note: we need the doall since map is lazy and the outer
  ;; binding will be lost if we wait to kick it off
  (doall (map (partial add-gre-port hostname) (:gre-connections config))))

(defn connect-kvm-servers
  "Connect the OVS of KVM servers using GRE+IPSec"
  []
  (helpers/ensure-nodelist-bindings)
  ;; note: we need the doall since map is lazy and the outer
  ;; binding will be lost if we wait to kick it off
  (doall (map add-gre-ports helpers/*nodelist-hosts-config*)))

(defn- update-hosts-file
  [static-ips [hostname config]]
  (let [result (helpers/run-one-plan-fn hostname server-crate/update-hosts-file {:static-ips static-ips})]
    (when (fsmop/failed? result)
      (throw (IllegalStateException. (format "Failed to update /etc/hosts for %s!" hostname))))
    result))

(defn update-hosts-with-static-ips
  "Update /etc/hosts to include all non-DHCP IPs (on all hosts)"
  []
  (helpers/ensure-nodelist-bindings)
  (let [static-ips (filter (fn [[host config]]
                             (server-crate/is-static-ip? config))
                           helpers/*nodelist-hosts-config*)]
    (doall (map (partial update-hosts-file static-ips) helpers/*nodelist-hosts-config*))))
